package com.codex.indown

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RecentLinkStore(context: Context) {
    private val preferences = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun load(): List<RecentLink> {
        val raw = preferences.getString(KeyLinks, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").trim()
                if (url.isBlank()) return@mapNotNull null
                RecentLink(
                    url = url,
                    savedAtMillis = item.optLong("savedAtMillis", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun remember(url: String): List<RecentLink> {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return load()

        val next = buildList {
            add(RecentLink(normalizedUrl, System.currentTimeMillis()))
            addAll(load().filterNot { it.url == normalizedUrl })
        }.take(MaxLinks)

        save(next)
        return next
    }

    fun clear(): List<RecentLink> {
        preferences.edit().remove(KeyLinks).apply()
        return emptyList()
    }

    private fun save(links: List<RecentLink>) {
        val array = JSONArray()
        links.forEach { link ->
            array.put(
                JSONObject()
                    .put("url", link.url)
                    .put("savedAtMillis", link.savedAtMillis),
            )
        }
        preferences.edit().putString(KeyLinks, array.toString()).apply()
    }

    private companion object {
        const val PrefsName = "recent_links"
        const val KeyLinks = "links"
        const val MaxLinks = 30
    }
}
