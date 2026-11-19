// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

object SubscriptionClient {
    private class DownloadFailure(message: String) : IllegalArgumentException(message)
    fun validate(url: String): String {
        val value = url.trim()
        val uri = runCatching { URI(value) }.getOrNull()
        require(value.length in 1..4096 && uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "请输入有效 HTTP / HTTPS 订阅链接，不支持用户名密码或片段。"
        }
        return value
    }
    fun fetch(url: String): String = fetch(url) { it.openConnection() as HttpURLConnection }
    internal fun fetch(url: String, open: (URL) -> HttpURLConnection): String {
        var next = validate(url)
        val deadline = System.nanoTime() + 60_000_000_000L
        try {
            repeat(6) { attempt ->
                if (System.nanoTime() > deadline) throw DownloadFailure("订阅下载超时。")
                val connection = open(URL(next))
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", "ClashMeta/OneTunnel")
                    connection.setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*")
                    connection.setRequestProperty("Accept-Encoding", "gzip")
                    val code = connection.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        if (attempt >= 5) throw DownloadFailure("订阅跳转次数过多。")
                        val location = connection.getHeaderField("Location") ?: throw DownloadFailure("订阅跳转缺少地址。")
                        val redirected = try { validate(URL(URL(next), location).toExternalForm()) }
                            catch (_: IllegalArgumentException) { throw DownloadFailure("订阅跳转地址无效。") }
                        if (URI(next).scheme.equals("https", true) && !URI(redirected).scheme.equals("https", true)) throw DownloadFailure("订阅不能从 HTTPS 降级跳转至 HTTP。")
                        next = redirected
                    } else {
                        if (code != 200) throw DownloadFailure("订阅下载失败（HTTP $code）。")
                        val body = connection.inputStream.let { if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it }
                        return body.use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (System.nanoTime() > deadline) throw DownloadFailure("订阅下载超时。")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (out.size() + count > ConfigStore.MAX_BYTES) throw DownloadFailure("解压后的订阅超过 1 MiB。")
                                out.write(buffer, 0, count)
                            }
                            out.toString("UTF-8").removePrefix("\uFEFF")
                        }
                    }
                } finally { connection.disconnect() }
            }
        } catch (error: DownloadFailure) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("下载失败，请检查网络、订阅链接与服务端证书。") }
        throw IllegalArgumentException("订阅下载失败。")
    }
}
