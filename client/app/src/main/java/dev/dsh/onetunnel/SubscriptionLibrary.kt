// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SubscriptionEntry(val id: String, val name: String, val updatedAt: Long)

/** Independent encrypted snapshots, so adding a second subscription does not erase the first. */
class SubscriptionLibrary(private val context: Context) {
    private val index get() = ConfigStore(context, "subscriptions.enc")
    fun list(): List<SubscriptionEntry> {
        val text = index.load()
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val id = item.getString("id")
            require(id.matches(Regex("[0-9a-f]{32}")))
            SubscriptionEntry(id, item.getString("name"), item.getLong("updatedAt"))
        }
    }
    fun save(preview: ClashPreview, replaceId: String? = null): List<SubscriptionEntry> {
        val source = requireNotNull(preview.source)
        val existing = list() // A damaged index is never silently overwritten.
        val id = id(source.url)
        val snapshot = ClashImport.apply("", preview, false)
        ConfigStore(context, "subscription-$id.enc").save(snapshot)
        val entries = existing.filterNot { it.id == id || it.id == replaceId } + SubscriptionEntry(id, source.name, source.updatedAt)
        index.save(JSONArray().apply { entries.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("updatedAt", it.updatedAt)) } }.toString())
        return entries
    }
    fun load(id: String): ClashPreview {
        require(id.matches(Regex("[0-9a-f]{32}")))
        val text = ConfigStore(context, "subscription-$id.enc").load()
        return ClashImport.prepare(text, requireNotNull(ClashImport.subscription(text)))
    }
    fun remove(id: String): List<SubscriptionEntry> {
        require(id.matches(Regex("[0-9a-f]{32}")))
        val entries = list().filterNot { it.id == id }
        index.save(JSONArray().apply { entries.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("updatedAt", it.updatedAt)) } }.toString())
        ConfigStore(context, "subscription-$id.enc").delete()
        return entries
    }
    companion object {
        fun id(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
