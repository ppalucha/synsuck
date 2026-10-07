package pl.synsuck.librus

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Base URLs plus the rule deciding which hosts the client may talk to.
 * Every request and every redirect target is checked against [allows].
 */
public class LibrusEndpoints internal constructor(
    internal val api: HttpUrl,
    internal val synergia: HttpUrl,
    internal val messages: HttpUrl,
    private val hostAllowed: (String) -> Boolean,
    private val httpsOnly: Boolean,
) {
    internal fun allows(url: HttpUrl): Boolean =
        hostAllowed(url.host) && (!httpsOnly || url.isHttps)

    /**
     * Resolves a `Location` header against [base]. Plain-http links to allowed hosts are
     * upgraded to https (Librus redirects `/nowy` to `http://`); anything else off-list is rejected.
     */
    internal fun resolveRedirect(base: HttpUrl, location: String): HttpUrl {
        var target = base.resolve(location)
            ?: throw LibrusException.UnexpectedResponse("Malformed redirect")
        if (httpsOnly && target.scheme == "http" && hostAllowed(target.host)) {
            target = target.newBuilder().scheme("https").port(443).build()
        }
        if (!allows(target)) {
            throw LibrusException.UnexpectedResponse("Redirect to disallowed host: ${target.host}")
        }
        return target
    }

    public companion object {
        public val PRODUCTION: LibrusEndpoints = LibrusEndpoints(
            api = "https://api.librus.pl/".toHttpUrl(),
            synergia = "https://synergia.librus.pl/".toHttpUrl(),
            messages = "https://wiadomosci.librus.pl/".toHttpUrl(),
            hostAllowed = { host -> host == "librus.pl" || host.endsWith(".librus.pl") },
            httpsOnly = true,
        )

        /** All three "domains" served by one local server; used by tests. */
        internal fun local(base: HttpUrl): LibrusEndpoints = LibrusEndpoints(
            api = base, synergia = base, messages = base,
            hostAllowed = { it == base.host },
            httpsOnly = false,
        )
    }
}
