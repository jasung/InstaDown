package com.codex.indown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale

class InstagramMediaResolver(
    private val client: OkHttpClient = InDownHttp.client,
) {
    suspend fun resolve(input: String): ResolvedMedia = withContext(Dispatchers.IO) {
        val pageUrl = extractFirstUrl(input)
            ?: throw IllegalArgumentException("링크를 찾지 못했습니다.")
        val shortcode = shortcodeFrom(pageUrl)

        val selected = LinkedHashMap<String, MediaItem>()
        var postInfo = PostInfo()
        var lastFailure: Throwable? = null
        attemptUrls(pageUrl).forEach { url ->
            requestProfiles.forEach { profile ->
                val resolved = runCatching { resolveOnce(url, profile) }
                    .onFailure { error -> lastFailure = error }
                    .getOrDefault(ResolvedMedia(emptyList()))
                postInfo = mergePostInfo(postInfo, resolved.postInfo)
                resolved.items.forEach { item ->
                    putPreferredMedia(selected, item)
                }
            }
        }
        if (selected.values.none { it.kind == MediaKind.Video } && shortcode != null) {
            val mirrorResolved = runCatching { resolveMirrorVideo(shortcode) }
                .onFailure { error -> lastFailure = error }
                .getOrDefault(ResolvedMedia(emptyList()))
            mirrorResolved.items.forEach { item ->
                putPreferredMedia(selected, item)
            }
        }

        if (selected.isEmpty() && !postInfo.hasContent) {
            lastFailure?.let { throw it }
        }

        val items = removeCrossAttemptMetaImages(selected.values.toList())
        ResolvedMedia(
            items = preferMirrorVideo(items),
            postInfo = postInfo,
        )
    }

    private fun resolveOnce(
        pageUrl: String,
        profile: RequestProfile,
    ): ResolvedMedia {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", profile.userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Instagram 응답에 실패했습니다: HTTP ${response.code}")
            }

            val responseUrl = response.request.url.toString()
            val contentType = response.header("Content-Type").orEmpty()
            val body = response.body ?: throw IOException("응답 본문이 비어 있습니다.")
            if (isLoginPage(responseUrl)) {
                throw IOException("로그인이 필요하거나 공개 게시물 정보가 막혀 있어.")
            }

            if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
                val kind = kindFrom(contentType, responseUrl)
                return@use ResolvedMedia(
                    items = listOf(
                        MediaItem(
                            url = responseUrl,
                            kind = kind,
                            source = "direct:${profile.name}",
                            previewUrl = if (kind == MediaKind.Image) responseUrl else null,
                        ),
                    ),
                    postInfo = PostInfo(siteName = mediaHostLabel(responseUrl)),
                )
            }

            val html = body.string()
            parseHtml(
                html = html,
                shortcode = shortcodeFrom(pageUrl) ?: shortcodeFrom(responseUrl),
            )
        }
    }

    private fun parseHtml(
        html: String,
        shortcode: String?,
    ): ResolvedMedia {
        val sidecar = LinkedHashMap<String, MediaItem>()
        val keyed = LinkedHashMap<String, MediaItem>()
        val meta = LinkedHashMap<String, MediaItem>()
        val json = LinkedHashMap<String, MediaItem>()
        val postInfo = collectPostInfo(html)

        collectSidecarUrls(html, sidecar)
        shortcode?.let { collectCurrentMediaUrls(html, it, keyed) }
        collectMeta(html, meta)
        collectLdJson(html, json)
        if (sidecar.isEmpty() && json.isEmpty() && keyed.isEmpty() && meta.isEmpty()) {
            collectKeyedUrls(html, keyed)
        }

        val selected = LinkedHashMap<String, MediaItem>()
        val structuredItems = if (sidecar.isNotEmpty()) {
            sidecar.values
        } else {
            json.values + keyed.values + meta.values
        }
        structuredItems.forEach { item ->
            putPreferredMedia(selected, item)
        }

        if (selected.isEmpty()) {
            val loose = LinkedHashMap<String, MediaItem>()
            collectLooseCdnUrls(html, loose)
            selectLooseFallbackItems(loose.values).forEach { item ->
                putPreferredMedia(selected, item)
            }
        }

        val previewUrl = meta.values.firstOrNull { it.kind == MediaKind.Image }?.url
        val hasVideo = selected.values.any { it.kind == MediaKind.Video }
        val hasStructuredImages = selected.values.any {
            it.kind == MediaKind.Image && it.source != "meta"
        }

        val filteredItems = filterCroppedEmbeddedImages(selected.values.toList())
        val items = filteredItems
            .filterNot { item ->
                hasVideo && hasStructuredImages && item.source == "meta" && item.kind == MediaKind.Image
            }
            .filter { item -> item.kind == MediaKind.Image || item.kind == MediaKind.Video }
            .map { item ->
                when {
                    item.previewUrl != null -> item
                    item.kind == MediaKind.Image -> item.copy(previewUrl = item.url)
                    item.kind == MediaKind.Video -> item.copy(previewUrl = previewUrl)
                    else -> item
                }
            }
            .toList()

        return ResolvedMedia(
            items = items,
            postInfo = postInfo,
        )
    }

    private fun resolveMirrorVideo(shortcode: String): ResolvedMedia {
        val request = Request.Builder()
            .url("https://www.vxinstagram.com/reel/$shortcode/")
            .header("User-Agent", InstagramUserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("보조 미디어 응답에 실패했습니다: HTTP ${response.code}")
            }
            val html = response.body?.string() ?: throw IOException("보조 미디어 응답 본문이 비어 있습니다.")
            parseMirrorHtml(html)
        }
    }

    private fun parseMirrorHtml(html: String): ResolvedMedia {
        val output = LinkedHashMap<String, MediaItem>()
        metaTagRegex.findAll(html).forEach { match ->
            val tag = match.value
            val key = attr(tag, "property") ?: attr(tag, "name") ?: return@forEach
            if (key.lowercase(Locale.US) !in mirrorVideoMetaKeys) return@forEach
            val content = attr(tag, "content") ?: return@forEach
            addMedia(content, "mirror", output)
        }
        return ResolvedMedia(output.values.filter { item -> item.kind == MediaKind.Video })
    }

    private fun collectMeta(
        html: String,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        metaTagRegex.findAll(html).forEach { match ->
            val tag = match.value
            val key = attr(tag, "property") ?: attr(tag, "name") ?: return@forEach
            val content = attr(tag, "content") ?: return@forEach
            val normalizedKey = key.lowercase(Locale.US)
            if (normalizedKey in metaMediaKeys) {
                addMedia(content, "meta", output)
            }
        }
    }

    private fun collectSidecarUrls(
        html: String,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        val decoded = decodeEmbeddedMediaText(html)
        var searchStart = 0
        while (searchStart < decoded.length) {
            val markerIndex = decoded.indexOf(sidecarMarker, searchStart)
            if (markerIndex < 0) break

            val objectStart = decoded.indexOf(
                '{',
                startIndex = decoded.indexOf(':', markerIndex + sidecarMarker.length).coerceAtLeast(markerIndex),
            )
            if (objectStart < 0) break

            val objectEnd = findJsonObjectEnd(decoded, objectStart)
            if (objectEnd < 0) {
                searchStart = markerIndex + sidecarMarker.length
                continue
            }

            runCatching {
                collectSidecarObject(JSONObject(decoded.substring(objectStart, objectEnd + 1)), output)
            }
            searchStart = objectEnd + 1
        }
    }

    private fun collectSidecarObject(
        sidecar: JSONObject,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        val edges = sidecar.optJSONArray("edges") ?: return
        for (index in 0 until edges.length()) {
            val node = edges.optJSONObject(index)?.optJSONObject("node") ?: continue
            collectSidecarNode(node, output)
        }
    }

    private fun collectSidecarNode(
        node: JSONObject,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        val type = node.stringOrNull("__typename").orEmpty()
        val videoUrl = node.stringOrNull("video_url")
        if (type == "GraphVideo" && videoUrl != null) {
            addMedia(videoUrl, "sidecar", output, previewOverride = bestSidecarImageUrl(node))
            return
        }

        node.stringOrNull("display_url")?.let { url ->
            addMedia(url, "sidecar", output)
        }
        addSidecarResourceUrls(node.optJSONArray("display_resources"), output)
        addSidecarResourceUrls(node.optJSONArray("thumbnail_resources"), output)
    }

    private fun collectCurrentMediaUrls(
        html: String,
        shortcode: String,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        val decoded = decodeEmbeddedMediaText(html)
        val marker = "\"shortcode\":\"$shortcode\""
        var searchStart = 0
        while (searchStart < decoded.length) {
            val markerIndex = decoded.indexOf(marker, searchStart)
            if (markerIndex < 0) break

            val objectStart = findJsonObjectStartContaining(decoded, markerIndex)
            if (objectStart < 0) {
                searchStart = markerIndex + marker.length
                continue
            }

            val objectEnd = findJsonObjectEnd(decoded, objectStart)
            if (objectEnd < 0) {
                searchStart = markerIndex + marker.length
                continue
            }

            runCatching {
                collectCurrentMediaObject(JSONObject(decoded.substring(objectStart, objectEnd + 1)), output)
            }
            searchStart = objectEnd + 1
        }
    }

    private fun collectCurrentMediaObject(
        media: JSONObject,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        val type = media.stringOrNull("__typename").orEmpty()
        val videoUrl = media.stringOrNull("video_url")
        if (type == "GraphVideo" && videoUrl != null) {
            addMedia(videoUrl, "embedded", output, previewOverride = bestSidecarImageUrl(media))
        }

        media.stringOrNull("display_url")?.let { url ->
            addMedia(url, "embedded", output)
        }
        addSidecarResourceUrls(media.optJSONArray("display_resources"), output)
        addSidecarResourceUrls(media.optJSONArray("thumbnail_resources"), output)
    }

    private fun addSidecarResourceUrls(
        resources: JSONArray?,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        if (resources == null) return
        for (index in 0 until resources.length()) {
            resources.optJSONObject(index)?.stringOrNull("src")?.let { url ->
                addMedia(url, "sidecar", output)
            }
        }
    }

    private fun collectLdJson(
        html: String,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        ldJsonRegex.findAll(html).forEach { match ->
            val raw = match.groupValues[1].trim()
            runCatching {
                if (raw.startsWith("[")) {
                    collectJson(JSONArray(raw), null, output)
                } else {
                    collectJson(JSONObject(raw), null, output)
                }
            }
        }
    }

    private fun collectJson(
        value: Any?,
        key: String?,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        when (value) {
            is JSONObject -> value.keys().forEach { childKey ->
                collectJson(value.opt(childKey), childKey, output)
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectJson(value.opt(index), key, output)
                }
            }

            is String -> {
                val normalizedKey = key.orEmpty().lowercase(Locale.US)
                if (normalizedKey in structuredMediaKeys) {
                    addMedia(value, "json", output)
                }
            }
        }
    }

    private fun collectKeyedUrls(
        html: String,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        keyedUrlRegex.findAll(decodeEmbeddedMediaText(html)).forEach { match ->
            addMedia(match.groupValues[1], "embedded", output)
        }
    }

    private fun collectLooseCdnUrls(
        html: String,
        output: LinkedHashMap<String, MediaItem>,
    ) {
        looseUrlRegex.findAll(html).forEach { match ->
            addMedia(match.value, "embedded", output)
        }
    }

    private fun collectPostInfo(html: String): PostInfo {
        val values = LinkedHashMap<String, String>()
        metaTagRegex.findAll(html).forEach { match ->
            val tag = match.value
            val key = attr(tag, "property") ?: attr(tag, "name") ?: return@forEach
            val content = rawAttr(tag, "content")?.let(::cleanText) ?: return@forEach
            values.putIfAbsent(key.lowercase(Locale.US), content)
        }

        val title = firstUseful(
            values["og:title"],
            values["twitter:title"],
            titleTagRegex.find(html)?.groupValues?.getOrNull(1)?.let(::cleanText),
        )
        val description = firstUseful(
            values["og:description"],
            values["twitter:description"],
            values["description"],
        )
        val siteName = firstUseful(
            values["og:site_name"],
            values["twitter:site"],
        )

        return PostInfo(
            title = title,
            description = description,
            siteName = siteName,
        )
    }

    private fun addMedia(
        raw: String,
        source: String,
        output: LinkedHashMap<String, MediaItem>,
        previewOverride: String? = null,
    ) {
        val cleaned = cleanUrl(raw)
        if (!isLikelyMediaUrl(cleaned)) return
        val kind = kindFrom(null, cleaned)
        if (kind == MediaKind.Unknown) return
        val previewUrl = previewOverride
            ?.let(::cleanUrl)
            ?.takeIf { url -> isLikelyMediaUrl(url) && kindFrom(null, url) == MediaKind.Image }
        putPreferredMedia(
            output,
            MediaItem(
                url = cleaned,
                kind = kind,
                source = source,
                previewUrl = previewUrl ?: if (kind == MediaKind.Image) cleaned else null,
            ),
        )
    }

    private fun putPreferredMedia(
        output: LinkedHashMap<String, MediaItem>,
        item: MediaItem,
    ) {
        val key = canonicalKey(item.url)
        val existing = output[key]
        if (existing == null || mediaPriority(item) > mediaPriority(existing)) {
            output[key] = item
        }
    }

    private fun extractFirstUrl(input: String): String? =
        plainUrlRegex.find(input)?.value
            ?.trimEnd('.', ',', ')', ']', '"', '\'')
            ?: input.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun attemptUrls(url: String): List<String> {
        val normalized = url.replace("/reels/", "/reel/")
        return listOfNotNull(
            url,
            normalized,
            embedUrl(url),
            embedUrl(normalized),
        ).distinct()
    }

    private fun embedUrl(url: String): String? {
        val pathSegments = pathSegments(url)
        val typeIndex = pathSegments.indexOfFirst { segment ->
            segment == "p" || segment == "reel" || segment == "tv"
        }
        if (typeIndex < 0 || typeIndex + 1 >= pathSegments.size) return null

        val type = pathSegments[typeIndex]
        val shortcode = pathSegments[typeIndex + 1]
        return "https://www.instagram.com/$type/$shortcode/embed/"
    }

    private fun shortcodeFrom(url: String): String? {
        val pathSegments = pathSegments(url)
        val typeIndex = pathSegments.indexOfFirst { segment ->
            segment == "p" || segment == "reel" || segment == "tv"
        }
        return pathSegments.getOrNull(typeIndex + 1)
    }

    private fun pathSegments(url: String): List<String> =
        runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

    private fun attr(tag: String, name: String): String? =
        rawAttr(tag, name)?.let(::cleanUrl)

    private fun rawAttr(tag: String, name: String): String? {
        val regex = Regex("""\b$name\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE)
        return regex.find(tag)?.groupValues?.get(2)
    }

    private fun cleanUrl(value: String): String =
        value.trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\u0025", "%")
            .replace("&amp;", "&")
            .trimEnd('\\', ',', ';')

    private fun cleanText(value: String): String =
        value.trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun decodeEmbeddedMediaText(value: String): String {
        var decoded = value
        repeat(5) {
            decoded = escapedSlashRegex.replace(decoded, "/")
                .replace(escapedQuoteRegex, "\"")
                .replace(escapedAmpersandRegex, "&")
                .replace(escapedColonRegex, ":")
                .replace(escapedEqualsRegex, "=")
                .replace(escapedQuestionRegex, "?")
                .replace(escapedPercentRegex, "%")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
        }
        return decoded
    }

    private fun findJsonObjectEnd(
        text: String,
        startIndex: Int,
    ): Int {
        var depth = 0
        var inString = false
        var escaping = false
        for (index in startIndex until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaping -> escaping = false
                    char == '\\' -> escaping = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun findJsonObjectStartContaining(
        text: String,
        index: Int,
    ): Int {
        var searchBefore = index
        while (searchBefore >= 0) {
            val objectStart = text.lastIndexOf('{', searchBefore)
            if (objectStart < 0) return -1
            val objectEnd = findJsonObjectEnd(text, objectStart)
            if (objectEnd >= index) return objectStart
            searchBefore = objectStart - 1
        }
        return -1
    }

    private fun bestSidecarImageUrl(node: JSONObject): String? {
        var bestUrl = node.stringOrNull("display_url")
        var bestScore = bestUrl?.let(::mediaUrlQualityScore) ?: Int.MIN_VALUE
        val resources = node.optJSONArray("display_resources") ?: node.optJSONArray("thumbnail_resources")
        if (resources != null) {
            for (index in 0 until resources.length()) {
                val url = resources.optJSONObject(index)?.stringOrNull("src") ?: continue
                val score = mediaUrlQualityScore(url)
                if (score > bestScore) {
                    bestUrl = url
                    bestScore = score
                }
            }
        }
        return bestUrl
    }

    private fun firstUseful(vararg values: String?): String? =
        values.firstOrNull { value ->
            val cleaned = value?.trim().orEmpty()
            cleaned.isNotBlank() && isUsefulPostText(cleaned)
        }?.trim()

    private fun isUsefulPostText(value: String): Boolean =
        !value.equals("Instagram", ignoreCase = true) &&
            !value.startsWith("Create an account or log in to Instagram", ignoreCase = true)

    private fun mergePostInfo(
        current: PostInfo,
        next: PostInfo,
    ): PostInfo =
        PostInfo(
            title = current.title ?: next.title,
            description = current.description ?: next.description,
            siteName = current.siteName ?: next.siteName,
        )

    private fun removeCrossAttemptMetaImages(items: List<MediaItem>): List<MediaItem> {
        val hasStructuredMedia = items.any { item -> item.source != "meta" }
        if (!hasStructuredMedia) return items
        return items.filterNot { item ->
            item.source == "meta" && item.kind == MediaKind.Image
        }
    }

    private fun preferMirrorVideo(items: List<MediaItem>): List<MediaItem> {
        val hasMirrorVideo = items.any { item -> item.source == "mirror" && item.kind == MediaKind.Video }
        if (!hasMirrorVideo) return items
        return items.filter { item ->
            item.kind == MediaKind.Video || item.source != "meta"
        }
    }

    private fun filterCroppedEmbeddedImages(items: List<MediaItem>): List<MediaItem> {
        val hasNonCroppedImage = items.any { item ->
            item.kind == MediaKind.Image && !isCroppedInstagramImageUrl(item.url)
        }
        if (!hasNonCroppedImage) return items

        return items.filterNot { item ->
            item.kind == MediaKind.Image &&
                item.source != "sidecar" &&
                isCroppedInstagramImageUrl(item.url)
        }
    }

    private fun selectLooseFallbackItems(items: Collection<MediaItem>): List<MediaItem> {
        if (items.size <= 1) return items.toList()
        val reliableItems = items.filter { item ->
            item.kind == MediaKind.Video ||
                (item.kind == MediaKind.Image && !isCroppedInstagramImageUrl(item.url))
        }
        return if (reliableItems.size == 1) reliableItems else emptyList()
    }

    private fun canonicalKey(url: String): String =
        url.substringBefore("?").lowercase(Locale.US)

    private fun isLikelyMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        val isHttp = lower.startsWith("https://") || lower.startsWith("http://")
        val mediaExtension = mediaExtensionRegex.containsMatchIn(lower)
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        val mediaHost = (host.endsWith("cdninstagram.com") && host != "static.cdninstagram.com") ||
            host.endsWith("fbcdn.net") ||
            host == "www.vxinstagram.com" ||
            host == "vxinstagram.com" ||
            host == "d.rapidcdn.app"
        val appResource = lower.contains("/rsrc.php/")
        return isHttp && mediaExtension && mediaHost && !appResource && !isLikelyProfileImageUrl(lower)
    }

    private fun kindFrom(
        contentType: String?,
        url: String,
    ): MediaKind {
        val lowerType = contentType.orEmpty().lowercase(Locale.US)
        val lowerUrl = url.substringBefore("?").lowercase(Locale.US)
        return when {
            lowerType.startsWith("video/") || lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mov") -> MediaKind.Video
            lowerType.startsWith("image/") ||
                lowerUrl.endsWith(".jpg") ||
                lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".png") ||
                lowerUrl.endsWith(".webp") -> MediaKind.Image
            else -> MediaKind.Unknown
        }
    }

    private fun isLoginPage(url: String): Boolean =
        runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .contains("/accounts/login")

    private fun isLikelyProfileImageUrl(lowerUrl: String): Boolean =
        lowerUrl.contains("profile_pic") || profileImagePathRegex.containsMatchIn(lowerUrl)

    private fun mediaPriority(item: MediaItem): Int {
        var score = when (item.source) {
            "sidecar" -> 30
            "json", "embedded" -> 20
            "meta" -> 0
            else -> 10
        }
        if (item.kind == MediaKind.Video) score += 3
        score += mediaUrlQualityScore(item.url)
        return score
    }

    private fun isCroppedInstagramImageUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        if (!host.endsWith("cdninstagram.com")) return false
        val stp = queryParam(url, "stp") ?: return false
        return croppedStpRegex.containsMatchIn(stp)
    }

    private fun mediaUrlQualityScore(url: String): Int {
        val stp = queryParam(url, "stp").orEmpty()
        var score = 0
        if (queryParam(url, "se") == "8") score += 8
        if (stp.contains("e35")) score += 3
        if (stp.contains("e15")) score += 1
        stpSizeRegex.findAll(stp).forEach { match ->
            val width = match.groupValues[1].toIntOrNull() ?: 0
            val height = match.groupValues[2].toIntOrNull() ?: 0
            score += (maxOf(width, height) / 100).coerceAtMost(12)
        }
        if (isCroppedInstagramImageUrl(url)) score -= 20
        return score
    }

    private fun queryParam(
        url: String,
        name: String,
    ): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        return query.split("&")
            .firstNotNullOfOrNull { part ->
                val separator = part.indexOf("=")
                val key = if (separator >= 0) part.substring(0, separator) else part
                if (key == name) {
                    if (separator >= 0) part.substring(separator + 1) else ""
                } else {
                    null
                }
            }
    }

    private fun mediaHostLabel(url: String): String? =
        runCatching { URI(url).host }
            .getOrNull()
            ?.removePrefix("www.")

    private companion object {
        data class RequestProfile(
            val name: String,
            val userAgent: String,
        )

        val requestProfiles = listOf(
            RequestProfile(
                name = "android",
                userAgent = InstagramUserAgent,
            ),
            RequestProfile(
                name = "iphone",
                userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            ),
        )
        val plainUrlRegex = Regex("""https?://\S+""")
        val metaTagRegex = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        val ldJsonRegex = Regex(
            """<script[^>]+type\s*=\s*['"]application/ld\+json['"][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val titleTagRegex = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val keyedUrlRegex = Regex(
            """"(?:video_url|display_url|thumbnail_src|contentUrl|thumbnailUrl|playbackUrl)"\s*:\s*"((?:https?:\\?/\\?/)[^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        val looseUrlRegex = Regex("""https?:\\?/\\?/[^"'<>\s)]+""")
        val mediaExtensionRegex = Regex("""\.(jpg|jpeg|png|webp|mp4|mov)(\?|&|$)""")
        val profileImagePathRegex = Regex("""/t51\.[^/]*-19/""")
        val croppedStpRegex = Regex("""(^|_)c\d+(?:\.\d+){3}a(?:_|$)""")
        val stpSizeRegex = Regex("""(?:^|_)[ps](\d+)x(\d+)(?:_|$)""")
        val escapedSlashRegex = Regex("""\\+/""")
        val escapedQuoteRegex = Regex("""\\+"""")
        val escapedAmpersandRegex = Regex("""\\+u0026""", RegexOption.IGNORE_CASE)
        val escapedColonRegex = Regex("""\\+u003a""", RegexOption.IGNORE_CASE)
        val escapedEqualsRegex = Regex("""\\+u003d""", RegexOption.IGNORE_CASE)
        val escapedQuestionRegex = Regex("""\\+u003f""", RegexOption.IGNORE_CASE)
        val escapedPercentRegex = Regex("""\\+u0025""", RegexOption.IGNORE_CASE)
        val sidecarMarker = """"edge_sidecar_to_children""""
        val metaMediaKeys = setOf(
            "og:image",
            "og:video",
            "og:video:secure_url",
            "twitter:image",
            "twitter:player:stream",
        )
        val structuredMediaKeys = setOf(
            "contenturl",
            "thumbnailurl",
            "videourl",
            "video_url",
            "display_url",
            "thumbnail_src",
            "playbackurl",
        )
        val mirrorVideoMetaKeys = setOf(
            "og:video",
            "og:video:secure_url",
            "twitter:player:stream",
        )
    }
}

private fun JSONObject.stringOrNull(name: String): String? =
    optString(name)
        .takeIf { value -> value.isNotBlank() && value != "null" }
