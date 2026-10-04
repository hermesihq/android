package io.github.hermesihq.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A notification's link comes from a template, so what a tap is allowed to open is decided here, not by whoever
 * wrote the template.
 */
class PushContentTest {

    private fun link(url: String?, schemes: Set<String> = emptySet()): String? =
        PushContent("t", "b", if (url == null) emptyMap() else mapOf(PushContent.ACTION_URL_KEY to url)).actionUrl(schemes)

    @Test
    fun opensWebLinks() {
        assertEquals("https://app.example.test/orders/1", link("https://app.example.test/orders/1"))
        assertEquals("http://app.example.test/x", link("http://app.example.test/x"))
    }

    @Test
    fun readsTheSchemeWithoutRegardToCase() {
        assertEquals("HTTPS://app.example.test/x", link("HTTPS://app.example.test/x"))
    }

    @Test
    fun opensTheAppsOwnDeepLinksOnlyWhenTheAppListedTheScheme() {
        assertNull(link("myapp://orders/1"))
        assertEquals("myapp://orders/1", link("myapp://orders/1", setOf("myapp")))
        assertEquals("myapp://orders/1", link("myapp://orders/1", setOf("MyApp")))
    }

    @Test
    fun refusesSchemesThatAreNotLinks() {
        for (url in listOf(
            "javascript:alert(1)",
            "file:///data/data/app/secret",
            "content://com.example.provider/secret",
            "intent://scan/#Intent;scheme=zxing;end",
            "data:text/html,<script>1</script>",
            "blob:https://x/1",
            "ftp://x/y",
            "tel:+237670000001",
        )) {
            assertNull("$url must not open", link(url, setOf("myapp")))
        }
    }

    @Test
    fun refusesWhatIsNotAUrlAtAll() {
        assertNull(link(null))
        assertNull(link(""))
        assertNull(link("   "))
        assertNull(link("/orders/1"))
        assertNull(link("orders/1"))
        assertNull(link("https://exa mple.test/with space"))
    }

    @Test
    fun keepsTheLinkInTheDataSoOneCodePathReadsBothKindsOfNotification() {
        val content = PushContent("t", "b", mapOf(PushContent.ACTION_URL_KEY to "https://a.test/", "order" to "1"))

        assertEquals("1", content.data["order"])
        assertEquals("https://a.test/", content.data[PushContent.ACTION_URL_KEY])
    }
}
