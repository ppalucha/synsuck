package pl.synsuck.librus

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointsTest {
    private val prod = LibrusEndpoints.PRODUCTION
    private val api = "https://api.librus.pl/OAuth/Authorization?client_id=46".toHttpUrl()

    @Test
    fun `relative redirects resolve against current url`() {
        assertEquals(
            "https://api.librus.pl/OAuth/Authorization/2FA?client_id=46",
            prod.resolveRedirect(api, "/OAuth/Authorization/2FA?client_id=46").toString(),
        )
    }

    @Test
    fun `cross-subdomain https redirects are allowed`() {
        val target = prod.resolveRedirect(api, "https://synergia.librus.pl/loguj/portalRodzina?code=x")
        assertEquals("synergia.librus.pl", target.host)
    }

    @Test
    fun `plain http on librus is upgraded to https`() {
        val target = prod.resolveRedirect(
            "https://wiadomosci.librus.pl/nowy".toHttpUrl(),
            "http://wiadomosci.librus.pl/nowy/",
        )
        assertEquals("https://wiadomosci.librus.pl/nowy/", target.toString())
    }

    @Test
    fun `foreign hosts are rejected`() {
        for (evil in listOf(
            "https://evil.example/",
            "https://librus.pl.evil.example/",
            "https://evillibrus.pl/",
            "http://evil.example/",
        )) {
            assertFailsWith<LibrusException.UnexpectedResponse>(evil) { prod.resolveRedirect(api, evil) }
        }
    }

    @Test
    fun `allows requires https in production`() {
        assertTrue(prod.allows("https://sandbox.librus.pl/GetFile/abc".toHttpUrl()))
        assertFalse(prod.allows("http://sandbox.librus.pl/GetFile/abc".toHttpUrl()))
    }
}
