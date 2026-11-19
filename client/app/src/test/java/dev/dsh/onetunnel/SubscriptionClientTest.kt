package dev.dsh.onetunnel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class SubscriptionClientTest {
    private class Fake(url: URL, private val status: Int, private val body: ByteArray = byteArrayOf(), private val location: String? = null, private val gzip: Boolean = false) : HttpURLConnection(url) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentEncoding() = if (gzip) "gzip" else null
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }
    @Test fun validatesSubscriptionUrlWithoutEchoingToken() {
        listOf("file:///secret", "https://user:secret@example.com/sub", "https://example.com/#secret").forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { SubscriptionClient.validate(it) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        assertEquals("https://example.com/sub?token=secret", SubscriptionClient.validate(" https://example.com/sub?token=secret "))
        assertEquals("http://example.com/sub", SubscriptionClient.validate("http://example.com/sub"))
    }
    @Test fun followsHttpsRedirectAndClosesConnections() {
        val requests = mutableListOf<Fake>()
        val body = SubscriptionClient.fetch("https://example.com/old") { url ->
            Fake(url, if (requests.isEmpty()) 302 else 200, "proxies: []".toByteArray(), "/new").also { requests.add(it) }
        }
        assertEquals("proxies: []", body)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.closed })
        assertEquals("ClashMeta/OneTunnel", requests.last().getRequestProperty("User-Agent"))
    }
    @Test fun rejectsDowngradeAndRedactsTransportFailures() {
        assertThrows(IllegalArgumentException::class.java) { SubscriptionClient.fetch("https://example.com/") { Fake(it, 302, location = "http://example.com/secret") } }
        val error = assertThrows(IllegalArgumentException::class.java) { SubscriptionClient.fetch("https://example.com/?token=secret") { throw IllegalArgumentException("transport secret") } }
        assertFalse(error.message.orEmpty().contains("secret"))
    }
    @Test fun limitsDecompressedBody() {
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(ConfigStore.MAX_BYTES + 1) { 65 }) } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { SubscriptionClient.fetch("https://example.com/") { Fake(it, 200, compressed, gzip = true) } }
    }
}
