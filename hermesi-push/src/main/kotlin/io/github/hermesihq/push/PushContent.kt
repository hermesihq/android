package io.github.hermesihq.push

import java.net.URI

/**
 * What a Hermesi push notification says, read out of the message Firebase delivered.
 *
 * Hermesi sends the title, body and image in the message's notification block and everything else in its data.
 * The link a tap should open travels in the data under [ACTION_URL_KEY], where it works on every platform.
 */
public class PushContent(
    public val title: String?,
    public val body: String?,
    /** Every data entry the template set, plus [ACTION_URL_KEY] when there is a link. */
    public val data: Map<String, String>,
) {
    /**
     * The link to open on a tap, or null. A link comes from a template, not from the app, so only the schemes the
     * app listed are opened: `http` and `https` always, and the app's own deep link schemes through
     * [HermesiPushOptions.deepLinkSchemes]. Anything else (`file`, `content`, `intent`, `javascript`) is dropped.
     */
    public fun actionUrl(deepLinkSchemes: Set<String> = emptySet()): String? {
        val raw = data[ACTION_URL_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val scheme = try {
            URI(raw).scheme?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return null
        val allowed = ALWAYS_ALLOWED_SCHEMES + deepLinkSchemes.map { it.lowercase() }
        return raw.takeIf { scheme in allowed }
    }

    public companion object {
        /** The data key Hermesi puts the link under. */
        public const val ACTION_URL_KEY: String = "action_url"

        private val ALWAYS_ALLOWED_SCHEMES = setOf("https", "http")
    }
}
