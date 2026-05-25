package com.codex.indown

enum class MediaKind {
    Image,
    Video,
    Unknown,
}

data class MediaItem(
    val url: String,
    val kind: MediaKind,
    val source: String,
    val previewUrl: String? = null,
)

data class PostInfo(
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
) {
    val hasContent: Boolean =
        !title.isNullOrBlank() || !description.isNullOrBlank() || !siteName.isNullOrBlank()
}

data class ResolvedMedia(
    val items: List<MediaItem>,
    val postInfo: PostInfo = PostInfo(),
)

data class DownloadResult(
    val item: MediaItem,
    val fileName: String? = null,
    val uri: String? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean = error == null
}

data class RecentLink(
    val url: String,
    val savedAtMillis: Long,
)

data class DownloadUiState(
    val link: String = "",
    val isWorking: Boolean = false,
    val status: String = "대기 중입니다.",
    val foundCount: Int = 0,
    val postInfo: PostInfo = PostInfo(),
    val checkedMedia: Boolean = false,
    val previewItems: List<MediaItem> = emptyList(),
    val selectedUrls: Set<String> = emptySet(),
    val results: List<DownloadResult> = emptyList(),
    val recentLinks: List<RecentLink> = emptyList(),
) {
    val selectedCount: Int = selectedUrls.size
}
