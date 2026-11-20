package dev.octaneui.app.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.octaneui.app.R
import dev.octaneui.app.data.AppFont

/**
 * App font families. The files are variable fonts (weight axis), so for each weight
 * Compose gets its own [Font] instance with variation settings: one file covers the whole 300-700 range.
 *  - Inter: rsms/inter (SIL OFL 1.1), axes wght 100-900 and opsz;
 *  - Google Sans Flex: google/fonts (SIL OFL 1.1), axes wght 1-1000, wdth, opsz, ROND, slnt;
 *  - JetBrains Mono: JetBrains/JetBrainsMono (SIL OFL 1.1), axis wght 100-800.
 */
@OptIn(ExperimentalTextApi::class)
object AppFonts {
    private val weights = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    private fun variable(resId: Int) = FontFamily(
        weights.map { w ->
            Font(
                resId = resId,
                weight = w,
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(w, FontStyle.Normal)
            )
        }
    )

    val inter: FontFamily = variable(R.font.inter_variable)
    val googleSansFlex: FontFamily = variable(R.font.google_sans_flex)
    val jetBrainsMono: FontFamily = variable(R.font.jetbrains_mono)

    fun family(f: AppFont): FontFamily = when (f) {
        AppFont.INTER -> inter
        AppFont.GOOGLE_SANS_FLEX -> googleSansFlex
        AppFont.JETBRAINS_MONO -> jetBrainsMono
    }
}

/** Font for the terminal and logs: technical lines, commands, paths, console. Set by the theme for the whole app. */
val LocalMonoFont = compositionLocalOf<FontFamily> { FontFamily.Monospace }

/** Applies the family to ALL Material 3 styles so that it works in every component, not on a single screen. */
fun Typography.withFont(ff: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = ff), displayMedium = displayMedium.copy(fontFamily = ff),
    displaySmall = displaySmall.copy(fontFamily = ff),
    headlineLarge = headlineLarge.copy(fontFamily = ff), headlineMedium = headlineMedium.copy(fontFamily = ff),
    headlineSmall = headlineSmall.copy(fontFamily = ff),
    titleLarge = titleLarge.copy(fontFamily = ff), titleMedium = titleMedium.copy(fontFamily = ff),
    titleSmall = titleSmall.copy(fontFamily = ff),
    bodyLarge = bodyLarge.copy(fontFamily = ff), bodyMedium = bodyMedium.copy(fontFamily = ff),
    bodySmall = bodySmall.copy(fontFamily = ff),
    labelLarge = labelLarge.copy(fontFamily = ff), labelMedium = labelMedium.copy(fontFamily = ff),
    labelSmall = labelSmall.copy(fontFamily = ff),
)
