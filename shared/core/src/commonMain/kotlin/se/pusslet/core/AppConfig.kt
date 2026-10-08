package se.pusslet.core

import kotlinx.datetime.TimeZone

/**
 * Product-wide constants. The app name lives here and in the `app_name` string resource
 * (generated from `pusslet.appName` in gradle.properties) so a rebrand is a two-line change.
 */
object AppConfig {
    const val APP_NAME: String = "Pusslet"

    /**
     * Public contact (website or e-mail) included in the User-Agent sent to open data sources.
     * Open Food Facts asks every client to identify itself. Set before launch, see docs/SETUP.md.
     */
    const val CONTACT: String = ""

    /** Default zone for a Swedish family. All domain logic takes a [TimeZone] parameter, this is only the default. */
    const val DEFAULT_TIME_ZONE_ID: String = "Europe/Stockholm"

    val defaultTimeZone: TimeZone get() = TimeZone.of(DEFAULT_TIME_ZONE_ID)

    fun userAgent(versionName: String, platform: String = "Android"): String {
        val contact = if (CONTACT.isBlank()) "" else " - $CONTACT"
        return "$APP_NAME - $platform - $versionName$contact"
    }
}
