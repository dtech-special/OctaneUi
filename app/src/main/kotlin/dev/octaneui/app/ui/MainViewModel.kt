package dev.octaneui.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.octaneui.app.system.PreflightCollector
import dev.octaneui.app.system.ResourceIntegrity
import dev.octaneui.container.DryRunInputs
import dev.octaneui.container.DryRunReport
import dev.octaneui.container.HotSwitchDryRun
import dev.octaneui.container.PreflightReport
import dev.octaneui.root.KernelNode
import dev.octaneui.root.PrivilegedOp
import dev.octaneui.app.BuildConfig
import dev.octaneui.app.data.AppSettings
import dev.octaneui.app.data.DisplayMode
import dev.octaneui.app.data.SettingsRepository
import dev.octaneui.app.data.SystemCatalog
import dev.octaneui.app.data.SystemEntry
import dev.octaneui.app.data.ThemeMode
import dev.octaneui.container.BackendChoice
import dev.octaneui.container.BackendKind
import dev.octaneui.container.BackendManager
import dev.octaneui.container.BackendSelector
import dev.octaneui.container.ContainerStatus
import dev.octaneui.core.OctaneLog
import dev.octaneui.core.OctaneResult
import dev.octaneui.core.ContainerState
import dev.octaneui.core.ErrorCode
import dev.octaneui.core.ErrorInfo
import dev.octaneui.core.toErrorInfo
import dev.octaneui.detection.HostDetector
import dev.octaneui.detection.HostInfo
import dev.octaneui.image.BootstrapPlan
import dev.octaneui.image.Bootstrapper
import dev.octaneui.image.DownloadDecision
import dev.octaneui.image.InstallState
import dev.octaneui.image.MobileDataPolicy
import dev.octaneui.image.NetworkPolicy
import dev.octaneui.image.NetworkTypeDetector
import dev.octaneui.image.Stage
import dev.octaneui.root.ManagerDetector
import dev.octaneui.root.PrivilegeLevel
import dev.octaneui.root.ProcessShellRunner
import dev.octaneui.root.RootBridge
import dev.octaneui.root.RootStatus
import dev.octaneui.root.privilegeLevel
import dev.octaneui.storage.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installation wizard: a confirmation is required at every step. */
sealed interface InstallFlow {
    data object Idle : InstallFlow
    data class VersionWarning(val entry: SystemEntry) : InstallFlow
    data class Loading(val entry: SystemEntry) : InstallFlow
    data class Confirm(val entry: SystemEntry, val plan: BootstrapPlan) : InstallFlow
    data class MobileWarning(val entry: SystemEntry, val plan: BootstrapPlan) : InstallFlow
    data class Error(val entry: SystemEntry, val info: ErrorInfo) : InstallFlow
}

/** Directory size: computing, ready and failed are three different states; 0 never means "still calculating". */
sealed interface SizeState {
    data object Computing : SizeState
    data class Ready(val bytes: Long) : SizeState
    data object Failed : SizeState
}

/** A start refused by the preflight; [report] is null when no report could be produced (still a BLOCK). */
data class PreflightBlock(val report: PreflightReport?)

/** Removing a custom SOURCE from the list. Files are never deleted by the app. */
data class RemoveSourceRequest(val entry: SystemEntry)
enum class UpdateResult { NONE_INSTALLED, UP_TO_DATE, FOUND, ERROR }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Working directory. /data/adb/octaneui needs root, so for now the staging area is in the app's internal storage. */
    val baseDir = File(app.filesDir, "octaneui")
    private val storage = StorageManager(baseDir)
    private val bootstrapper = Bootstrapper(app, baseDir)
    private val detector = HostDetector(app)
    private val rootBridge = RootBridge(ProcessShellRunner(), ManagerDetector.forContext(app))
    private val collector = PreflightCollector(app)
    private val manager = BackendManager(
        detector, rootBridge, baseDir,
        appInputs = collector::appInputs, appVersion = BuildConfig.VERSION_NAME, bootId = collector::bootId,
    )
    private val network = NetworkTypeDetector(app)

    /** The image list intro animation plays once per process lifetime. */
    var imagesIntroPlayed = false

    // ---------- device / root / backend ----------
    private val _host = MutableStateFlow<HostInfo?>(null)
    val host: StateFlow<HostInfo?> = _host.asStateFlow()

    private val _root = MutableStateFlow<RootStatus?>(null)
    val rootStatus: StateFlow<RootStatus?> = _root.asStateFlow()
    val privilege: StateFlow<PrivilegeLevel?> = _root.map { it.privilegeLevel() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _snapshot = MutableStateFlow<BackendManager.Snapshot?>(null)
    val snapshot: StateFlow<BackendManager.Snapshot?> = _snapshot.asStateFlow()

    /** Backend that will be used: the manual choice or the automatically selected one. */
    val effectiveBackend: StateFlow<BackendKind> = combine(settings, _snapshot) { s, snap ->
        BackendSelector.resolve(s.backendChoice, snap?.capabilities ?: emptyMap())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BackendKind.SHARED_PID)

    /** Recommended runtime for THIS device: null until capabilities are checked. The criterion is the real checks, not the phone model. */
    val recommendedBackend: StateFlow<BackendKind?> = _snapshot.map { snap ->
        snap?.capabilities?.let { caps -> BackendSelector.resolve(BackendChoice.AUTO, caps).takeIf { caps[it]?.canStart == true } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Latest preflight report (Level 1 when root is unknown, Level 2 otherwise). */
    val preflight: StateFlow<PreflightReport?> = _snapshot.map { it?.preflight }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _preflightBlock = MutableStateFlow<PreflightBlock?>(null)
    /** Non-null while the "start blocked by preflight" dialog should be shown (the report may be missing = unknown). */
    val preflightBlock: StateFlow<PreflightBlock?> = _preflightBlock.asStateFlow()
    fun dismissPreflightBlock() { _preflightBlock.value = null }

    private val _issueKind = MutableStateFlow<BackendKind?>(null)
    /** If not null, a dialog with the reasons why this backend is unavailable is shown. */
    val issueDialog: StateFlow<BackendKind?> = _issueKind.asStateFlow()
    fun showIssues(kind: BackendKind? = null) { _issueKind.value = kind ?: effectiveBackend.value }
    fun dismissIssues() { _issueKind.value = null }

    private val _guest = MutableStateFlow<ContainerStatus?>(null)
    val guestStatus: StateFlow<ContainerStatus?> = _guest.asStateFlow()
    private val _runningKind = MutableStateFlow<BackendKind?>(null)
    val runningKind: StateFlow<BackendKind?> = _runningKind.asStateFlow()
    private val _guestLog = MutableStateFlow<List<String>>(emptyList())
    val guestLog: StateFlow<List<String>> = _guestLog.asStateFlow()
    private var monitor: Job? = null

    // ---------- images ----------
    val entries: StateFlow<List<SystemEntry>> = settings
        .map { s -> SystemCatalog.builtIn + s.customSources.map(SystemCatalog::custom) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SystemCatalog.builtIn)

    val installStates: StateFlow<Map<String, InstallState>> = entries.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyMap())
        else combine(list.map { e -> bootstrapper.observe(e.id).map { s -> e.id to s } }) { it.toMap() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _failedState = MutableStateFlow(false)
    private val stoppingFlow = MutableStateFlow(false)

    /** Real state for the UI: from the downloader, then from the guest session, then from the check result. */
    val containerState: StateFlow<ContainerState> = combine(installStates, _guest, _failedState, stoppingFlow) { installs, guest, failed, stopping ->
        val running = installs.values.filterIsInstance<InstallState.Running>().firstOrNull()
        val queued = installs.values.any { it is InstallState.Queued }
        val installed = installs.values.any { it is InstallState.Installed }
        when {
            running != null -> when (running.progress?.stage) {
                Stage.EXTRACTING -> ContainerState.UNPACKING
                Stage.SAVING -> ContainerState.PREPARING
                else -> ContainerState.DOWNLOADING
            }
            queued -> ContainerState.DOWNLOADING
            !installed -> ContainerState.NOT_INSTALLED
            stopping -> ContainerState.STOPPING
            guest?.errorCode != null -> ContainerState.ERROR
            guest?.running == true -> ContainerState.RUNNING
            failed -> ContainerState.UNSUPPORTED
            else -> ContainerState.READY
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContainerState.NOT_INSTALLED)

    // ---------- storage ----------
    private val _storageUsed = MutableStateFlow<Long?>(null)
    /** null = still being calculated. */
    val storageUsed: StateFlow<Long?> = _storageUsed.asStateFlow()
    private val _cacheBytes = MutableStateFlow<Long?>(null)
    val cacheBytes: StateFlow<Long?> = _cacheBytes.asStateFlow()
    private val _sizes = MutableStateFlow<Map<String, SizeState>>(emptyMap())
    val installedSizes: StateFlow<Map<String, SizeState>> = _sizes.asStateFlow()

    private val _error = MutableStateFlow<OctaneResult.Fail?>(null)
    val error: StateFlow<OctaneResult.Fail?> = _error.asStateFlow()
    fun consumeError() { _error.value = null }
    private fun report(r: OctaneResult<*>) { if (r is OctaneResult.Fail) { OctaneLog.e("Storage", "error_detail", r.message); _error.value = r } }

    private val _flow = MutableStateFlow<InstallFlow>(InstallFlow.Idle)
    val installFlow: StateFlow<InstallFlow> = _flow.asStateFlow()
    private val _removeSource = MutableStateFlow<RemoveSourceRequest?>(null)
    val removeSourceRequest: StateFlow<RemoveSourceRequest?> = _removeSource.asStateFlow()

    private val _updates = MutableStateFlow<Set<String>>(emptySet())
    val updates: StateFlow<Set<String>> = _updates.asStateFlow()
    private val _updatesBusy = MutableStateFlow(false)
    val updatesBusy: StateFlow<Boolean> = _updatesBusy.asStateFlow()
    private val _updateResult = MutableStateFlow<UpdateResult?>(null)
    val updateResult: StateFlow<UpdateResult?> = _updateResult.asStateFlow()

    val hasActiveDownload: StateFlow<Boolean> = installStates
        .map { m -> m.values.any { it is InstallState.Running || it is InstallState.Queued } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        OctaneLog.i("App", "app_started", BuildConfig.VERSION_NAME, baseDir.path)
        viewModelScope.launch { _host.value = withContext(Dispatchers.IO) { detector.detect() } }
        viewModelScope.launch { report(storage.ensureLayout()) }
        viewModelScope.launch {
            // Image state changed (installed/removed): recompute sizes and capabilities (without asking for root).
            installStates.map { m -> m.mapValues { it.value::class } }.distinctUntilChanged().collect {
                refreshStorage()
                refreshCapabilities(askRoot = false)
            }
        }
        viewModelScope.launch { restoreGuestSession() }
        viewModelScope.launch { settings.map { it.verboseLogging }.distinctUntilChanged().collect { OctaneLog.verbose = it } }
    }

    // ---------- sizes ----------
    private suspend fun refreshStorage() {
        val installed = entries.value.filter { installStates.value[it.id] is InstallState.Installed }
        // Mark as computing only if there is no ready value yet (no flicker on recomputation).
        _sizes.value = installed.associate { e -> e.id to (_sizes.value[e.id] ?: SizeState.Computing) }
        withContext(Dispatchers.IO) {
            runCatching { storage.usedBytes() }.onSuccess { _storageUsed.value = it }
            runCatching { storage.usedBytes("cache") }.onSuccess { _cacheBytes.value = it }
            for (e in installed) {
                val r = runCatching { bootstrapper.installedSize(e.id) }
                _sizes.value = _sizes.value + (e.id to r.fold({ SizeState.Ready(it) }, { SizeState.Failed }))
            }
        }
    }

    // ---------- backend capabilities ----------
    private fun currentImage(): SystemEntry? = entries.value.firstOrNull { installStates.value[it.id] is InstallState.Installed }

    private suspend fun refreshCapabilities(askRoot: Boolean) {
        val img = currentImage()
        val digest = img?.let { bootstrapper.readMetadata(it.id)?.manifestDigest }
        val snap = manager.refresh(img?.id, img?.androidMajor, askRoot, digest)
        _snapshot.value = snap
        _host.value = snap.host
        _root.value = snap.root
    }

    fun checkRoot() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { refreshCapabilities(askRoot = true) } finally { _busy.value = false }
        }
    }

    fun runCompat() = checkRoot()
    fun diagRoot() { OctaneLog.i("console", "raw", "$ root-check"); checkRoot() }
    fun diagCompat() { OctaneLog.i("console", "raw", "$ compat-check"); checkRoot() }
    fun diagDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            OctaneLog.i("console", "raw", "$ system-info")
            val h = detector.detect()
            _host.value = h
            OctaneLog.i("system", "raw", "${h.deviceModel}, SoC=${h.soc ?: "n/a"}, CPU cores=${h.cpuCores}, RAM=${h.totalRamBytes ?: "n/a"}")
            OctaneLog.i("system", "raw", "ABIs=${h.supportedAbis.joinToString()}, SELinux=${h.selinux ?: "n/a"}, AVF=${h.avfFeature}")
            OctaneLog.i("system", "raw", "binder=${h.binderDeviceVisible}, binderfs=${h.binderFsVisible}, kvm=${h.kvmVisible}, ns=${h.namespaces?.joinToString(" ") ?: "n/a"}")
        }
    }

    // ---------- start / stop ----------
    fun startEnvironment() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                refreshCapabilities(askRoot = true)
                val snap = _snapshot.value ?: return@launch
                // Fail-closed gate: Level 1 + Level 2 are re-run right now; any BLOCK (or a missing root-side check) forbids the start.
                val pf = snap.preflight
                if (pf == null || pf.level < 2 || !pf.allowed) {
                    OctaneLog.w("App", "start_blocked_preflight", pf?.blockers?.joinToString { it.id.name } ?: "no report")
                    _preflightBlock.value = PreflightBlock(pf)
                    return@launch
                }
                val kind = BackendSelector.resolve(settings.value.backendChoice, snap.capabilities)
                val cap = snap.capabilities.getValue(kind)
                _failedState.value = cap.availability == dev.octaneui.container.Availability.UNSUPPORTED
                if (!cap.canStart) {
                    OctaneLog.w("App", "start_blocked", kind.name, cap.availability.name)
                    _issueKind.value = kind // the concrete reason is shown in the dialog
                    return@launch
                }
                val img = currentImage() ?: return@launch
                val r = manager.backend(kind).start(img.id)
                refreshGuest(kind)
                if (r is OctaneResult.Fail) {
                    OctaneLog.w("App", "start_failed", r.message)
                    _error.value = r
                    appendGuestLogToConsole()
                } else startMonitor(kind)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                OctaneLog.ex("App", t)
                _error.value = OctaneResult.Fail(t.message ?: t.javaClass.simpleName, t)
            } finally { _busy.value = false }
        }
    }

    fun stopEnvironment() {
        val kind = _runningKind.value ?: return
        viewModelScope.launch {
            stoppingFlow.value = true
            try {
                monitor?.cancel()
                val r = manager.backend(kind).stop()
                if (r is OctaneResult.Fail && r.code != ErrorCode.NOT_RUNNING) _error.value = r
                refreshGuest(kind)
            } finally { stoppingFlow.value = false }
        }
    }

    fun restartEnvironment() {
        val kind = _runningKind.value ?: return
        val img = currentImage() ?: return
        viewModelScope.launch {
            stoppingFlow.value = true
            try {
                monitor?.cancel()
                val r = manager.backend(kind).restart(img.id)
                refreshGuest(kind)
                if (r is OctaneResult.Fail) _error.value = r else startMonitor(kind)
            } finally { stoppingFlow.value = false }
        }
    }

    private suspend fun refreshGuest(kind: BackendKind) {
        val st = manager.backend(kind).status()
        _guest.value = st
        _runningKind.value = if (st.running) kind else null
        _guestLog.value = manager.backend(kind).logs()
    }

    private fun appendGuestLogToConsole() {
        _guestLog.value.takeLast(8).forEach { OctaneLog.w("guest", "raw", it) }
    }

    /** Recovery after an app restart: only if traces of a previous session remain. */
    private suspend fun restoreGuestSession() {
        for (k in listOf(BackendKind.SHARED_PID, BackendKind.NATIVE_CONTAINER)) {
            val b = manager.backend(k)
            if (b.hasSession()) {
                refreshGuest(k)
                if (_runningKind.value != null) { startMonitor(k); return }
            }
        }
    }

    /** Infrequent polling (every 10 s, only while the guest runs); root loss is detected by su becoming unavailable. */
    private fun startMonitor(kind: BackendKind) {
        monitor?.cancel()
        monitor = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val st = manager.backend(kind).status()
                _guest.value = st
                if (st.errorCode == ErrorCode.ROOT_LOST) {
                    OctaneLog.e("App", "root_lost")
                    _root.value = RootStatus.Denied("Root access lost while the guest was running")
                    _runningKind.value = null
                    break
                }
                if (!st.running) { _runningKind.value = null; _guestLog.value = manager.backend(kind).logs(); break }
            }
        }
    }

    // ---------- installation ----------
    fun beginInstall(e: SystemEntry) {
        val hostMajor = _host.value?.androidMajor
        if (e.androidMajor != null && hostMajor != null && e.androidMajor != hostMajor) _flow.value = InstallFlow.VersionWarning(e)
        else loadPlan(e)
    }

    fun continueAfterWarning(e: SystemEntry) = loadPlan(e)

    private fun loadPlan(e: SystemEntry) {
        _flow.value = InstallFlow.Loading(e)
        viewModelScope.launch {
            try {
                val plan = bootstrapper.plan(e.id, e.ref, settings.value.mobilePolicy, network.current())
                _flow.value = InstallFlow.Confirm(e, plan)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val info = t.toErrorInfo()
                OctaneLog.e("Bootstrapper", "error_detail", info.message)
                _flow.value = InstallFlow.Error(e, info)
            }
        }
    }

    fun confirmInstall(e: SystemEntry, plan: BootstrapPlan) {
        // The network may have changed while the confirmation sheet was open, so the decision is made again.
        when (NetworkPolicy.decide(settings.value.mobilePolicy, network.current())) {
            DownloadDecision.Proceed -> startNow(plan)
            DownloadDecision.NeedMobileConfirmation -> _flow.value = InstallFlow.MobileWarning(e, plan)
            else -> _flow.value = InstallFlow.Idle
        }
    }

    fun confirmMobile(plan: BootstrapPlan) = startNow(plan)

    private fun startNow(plan: BootstrapPlan) {
        report(storage.ensureLayout())
        bootstrapper.start(plan, unmeteredOnly = settings.value.mobilePolicy == MobileDataPolicy.WIFI_ONLY)
        _flow.value = InstallFlow.Idle
    }

    fun dismissFlow() { _flow.value = InstallFlow.Idle }
    fun pause(e: SystemEntry) = bootstrapper.pause(e.id)
    fun cancel(e: SystemEntry) { bootstrapper.cancel(e.id) }

    // ---------- source removal (no file deletion) ----------
    fun requestRemoveSource(e: SystemEntry) {
        // Only a custom source that is NOT installed can be removed: its files could not be deleted by this app.
        if (!e.custom || installStates.value[e.id] is InstallState.Installed) return
        _removeSource.value = RemoveSourceRequest(e)
    }

    fun dismissRemoveSource() { _removeSource.value = null }

    fun confirmRemoveSource() {
        val req = _removeSource.value ?: return
        _removeSource.value = null
        viewModelScope.launch {
            settingsRepo.removeSource(req.entry.ref)
            OctaneLog.w("App", "source_removed", req.entry.ref)
        }
    }

    // ---------- updates ----------
    fun checkUpdates() {
        if (_updatesBusy.value) return
        viewModelScope.launch {
            _updatesBusy.value = true
            try {
                val installed = entries.value.filter { installStates.value[it.id] is InstallState.Installed }
                if (installed.isEmpty()) { _updateResult.value = UpdateResult.NONE_INSTALLED; return@launch }
                val newer = mutableSetOf<String>()
                for (e in installed) {
                    val have = bootstrapper.readMetadata(e.id)?.manifestDigest
                    val latest = bootstrapper.latestDigest(e.ref)
                    if (have != null && have != latest) newer += e.id
                }
                _updates.value = newer
                _updateResult.value = if (newer.isEmpty()) UpdateResult.UP_TO_DATE else UpdateResult.FOUND
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                OctaneLog.e("Updates", "error_detail", t.message ?: ""); _updateResult.value = UpdateResult.ERROR
            } finally { _updatesBusy.value = false }
        }
    }

    fun consumeUpdateResult() { _updateResult.value = null }

    // ---------- settings ----------
    fun setTheme(m: ThemeMode) { viewModelScope.launch { settingsRepo.setTheme(m) } }
    fun setDynamic(v: Boolean) { viewModelScope.launch { settingsRepo.setDynamic(v) } }
    fun setMobilePolicy(p: MobileDataPolicy) { viewModelScope.launch { settingsRepo.setMobilePolicy(p) } }
    fun setBackend(c: BackendChoice) { viewModelScope.launch { settingsRepo.setBackend(c) } }
    fun setDisplayMode(m: DisplayMode) { viewModelScope.launch { settingsRepo.setDisplay(m) } }
    fun setUiFont(f: dev.octaneui.app.data.AppFont) { viewModelScope.launch { settingsRepo.setUiFont(f) } }
    fun setTerminalFont(f: dev.octaneui.app.data.AppFont) { viewModelScope.launch { settingsRepo.setTerminalFont(f) } }
    fun resetFonts() { viewModelScope.launch { settingsRepo.resetFonts() } }
    fun setNavStyle(s: dev.octaneui.app.data.NavBarStyle) { viewModelScope.launch { settingsRepo.setNavStyle(s) } }
    fun setNavBlur(v: Int) { viewModelScope.launch { settingsRepo.setNavBlur(v) } }
    fun addSource(ref: String) { viewModelScope.launch { settingsRepo.addSource(ref) } }

    // ---------- console ----------
    fun logText(): String {
        val f = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val res = getApplication<Application>().resources
        val pkg = getApplication<Application>().packageName
        return OctaneLog.entries.value.joinToString("\n") { "[${f.format(Date(it.timeMs))}] ${it.level} ${it.tag}: ${it.localized(res, pkg)}" }
    }

    // ---------- Developer Settings (diagnostics only; none of this can relax a safety check) ----------
    fun setVerbose(v: Boolean) { viewModelScope.launch { settingsRepo.setVerbose(v) } }

    private val _devInfo = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val devInfo: StateFlow<List<Pair<String, String>>> = _devInfo.asStateFlow()
    private val _dryRun = MutableStateFlow<DryRunReport?>(null)
    val dryRun: StateFlow<DryRunReport?> = _dryRun.asStateFlow()
    private val _integrity = MutableStateFlow<List<ResourceIntegrity.Entry>?>(null)
    val integrity: StateFlow<List<ResourceIntegrity.Entry>?> = _integrity.asStateFlow()
    private val _emergency = MutableStateFlow<Boolean?>(null)
    /** null = not run, true = all guests confirmed stopped, false = something is still alive. */
    val emergencyResult: StateFlow<Boolean?> = _emergency.asStateFlow()

    /** Read-only root diagnostics: host services, SELinux, namespaces, binder, sessions. Nothing is changed. */
    fun loadDevDiagnostics() {
        viewModelScope.launch {
            val rows = mutableListOf<Pair<String, String>>()
            suspend fun prop(n: String) = rootBridge.execute(PrivilegedOp.GetProp(n)).let { if (it.ok) it.stdout.trim().ifEmpty { "-" } else "n/a" }
            suspend fun pid(n: String) = rootBridge.execute(PrivilegedOp.Pidof(n)).let { if (it.ok) it.stdout.trim().ifEmpty { "-" } else "-" }
            val rs = rootBridge.detect()
            _root.value = rs
            rows += "Root" to (rs.privilegeLevel()?.name ?: "unknown")
            if (rs is RootStatus.Granted) {
                rows += "SELinux enforce" to (rootBridge.execute(PrivilegedOp.CatNode(KernelNode.SELINUX_ENFORCE)).takeIf { it.ok }?.stdout?.trim() ?: "n/a")
                rows += "init.svc.zygote" to prop("init.svc.zygote")
                rows += "init.svc.surfaceflinger" to prop("init.svc.surfaceflinger")
                rows += "zygote64 pid" to pid("zygote64")
                rows += "system_server pid" to pid("system_server")
                rows += "ro.build.fingerprint" to prop("ro.build.fingerprint")
            }
            val h = _host.value
            rows += "Namespaces" to (h?.namespaces?.joinToString(" ") ?: "n/a")
            rows += "binder / binderfs / kvm" to "${h?.binderDeviceVisible} / ${h?.binderFsVisible} / ${h?.kvmVisible}"
            rows += "Loop images used by OctaneUi" to "none (guest userdata images are not implemented)"
            BackendKind.entries.filter { it != BackendKind.VIRTUAL_MACHINE }.forEach { k ->
                val b = manager.backend(k)
                rows += "${k.name} session" to (if (b.hasSession()) "active, image=${b.currentImage()}" else "none")
            }
            _devInfo.value = rows
        }
    }

    /** Dry run of the hot-switch prerequisites. Starts nothing, stops nothing, mounts nothing. */
    fun runDryRun() {
        viewModelScope.launch {
            refreshCapabilities(askRoot = false)
            val snap = _snapshot.value
            val img = currentImage()
            val sdk = img?.let { runCatching { dev.octaneui.container.ImageInspector.inspect(java.io.File(baseDir, "images/${it.id}/rootfs")).apiLevel }.getOrNull() }
            _dryRun.value = HotSwitchDryRun.evaluate(
                DryRunInputs(snap?.preflight, snap?.host?.fingerprint ?: "", sdk, img?.let { bootstrapper.readMetadata(it.id)?.manifestDigest }, guestStorageIsolated = false)
            )
        }
    }

    fun checkIntegrity() {
        viewModelScope.launch { _integrity.value = withContext(Dispatchers.IO) { ResourceIntegrity.verify(getApplication()) } }
    }

    /** Idempotent: stops every OctaneUi guest (controlled kill of guest processes only) and verifies the result. */
    fun emergencyReturn() {
        viewModelScope.launch {
            monitor?.cancel()
            var alive = false
            for (k in listOf(BackendKind.SHARED_PID, BackendKind.NATIVE_CONTAINER)) {
                val b = manager.backend(k)
                if (!b.hasSession()) continue
                val r = b.stop()
                if (r is OctaneResult.Fail && r.code != ErrorCode.NOT_RUNNING) _error.value = r
                refreshGuest(k)
                if (b.status().running) alive = true
            }
            _runningKind.value = null
            _emergency.value = !alive
            OctaneLog.w("App", "emergency_return", if (alive) "guest still alive" else "ok")
        }
    }
}
