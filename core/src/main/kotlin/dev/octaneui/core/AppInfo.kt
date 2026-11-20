package dev.octaneui.core

/**
 * Single place for the author and project data.
 * Change the values here; they appear on the About page.
 */
object AppInfo {
    const val APP_NAME = "OctaneUi"
    const val PACKAGE_NAME = "dev.octaneui.app"
    const val LICENSE = "Apache License 2.0"

    // ---- Author ----
    const val AUTHOR_TIKTOK = "@techdelta"
    const val AUTHOR_GITHUB = "@dtech-special"

    const val TIKTOK_URL = "https://www.tiktok.com/$AUTHOR_TIKTOK"
    const val GITHUB_PROFILE_URL = "https://github.com/dtech-special"
    const val GITHUB_REPO_URL = "https://github.com/dtech-special/OctaneUi"

    // ---- Storage and default image ----
    const val STORAGE_ROOT = "/data/adb/octaneui"
    const val DEFAULT_IMAGE = "redroid/redroid:16.0.0_64only-latest"
}
