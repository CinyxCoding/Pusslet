package se.pusslet.core.medicine

/**
 * Where the medicine view points for what it does not say itself (ADR-061): the package leaflet on FASS and 1177's advice.
 * Only pages the parent opens in the browser and a number the parent calls; the app fetches nothing and works out no doses.
 */
object HealthLinks {
    /** 1177's page on fever in children. */
    const val FEVER_IN_CHILDREN = "https://www.1177.se/sjukdomar--besvar/infektioner/feber/feber-hos-barn/"

    /** Sjukvårdsrådgivningen: 1177 by phone. */
    const val PHONE_1177 = "1177"

    private const val FASS = "https://fass.se"
    private const val HEX = "0123456789ABCDEF"

    /** FASS's search for the public with [medicine] as written, or FASS's start page when it is blank. */
    fun fass(medicine: String): String {
        val query = medicine.trim()
        return if (query.isEmpty()) FASS else "$FASS/search?query=${encode(query)}"
    }

    /** Percent-encodes [text] as UTF-8, keeping only the unreserved characters of RFC 3986. */
    private fun encode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
                append(c)
            } else {
                append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
    }
}
