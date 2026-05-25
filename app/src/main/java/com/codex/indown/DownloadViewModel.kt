package com.codex.indown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = InstagramMediaResolver()
    private val downloader = MediaDownloader(application.applicationContext)
    private val recentLinkStore = RecentLinkStore(application.applicationContext)
    private val _state = MutableStateFlow(
        DownloadUiState(recentLinks = recentLinkStore.load()),
    )
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    fun setLink(value: String) {
        _state.update {
            if (it.link == value) {
                it
            } else {
                DownloadUiState(
                    link = value,
                    recentLinks = it.recentLinks,
                )
            }
        }
    }

    fun setLinkAndPreview(value: String) {
        setLink(value)
        previewLink()
    }

    fun clear() {
        _state.update {
            DownloadUiState(recentLinks = it.recentLinks)
        }
    }

    fun clearRecentLinks() {
        _state.update {
            it.copy(recentLinks = recentLinkStore.clear())
        }
    }

    fun storagePermissionDenied() {
        _state.update { it.copy(status = "저장 권한이 필요해.") }
    }

    fun previewLink() {
        val input = _state.value.link.trim()
        if (input.isBlank() || _state.value.isWorking) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isWorking = true,
                    status = "미리보기 확인 중",
                    foundCount = 0,
                    postInfo = PostInfo(),
                    checkedMedia = false,
                    previewItems = emptyList(),
                    selectedUrls = emptySet(),
                    results = emptyList(),
                )
            }

            runCatching {
                val resolved = resolver.resolve(input)
                val items = resolved.items
                if (items.isEmpty()) {
                    error("이미지나 영상 파일을 찾지 못했어.")
                }
                val recentLinks = recentLinkStore.remember(input)

                _state.update {
                    it.copy(
                        isWorking = false,
                        status = if (items.isEmpty()) "게시물 정보 확인" else "${items.size}개 확인",
                        foundCount = items.size,
                        postInfo = resolved.postInfo,
                        checkedMedia = true,
                        previewItems = items,
                        selectedUrls = items.map { item -> item.url }.toSet(),
                        recentLinks = recentLinks,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isWorking = false,
                        status = error.message ?: "실패했어.",
                    )
                }
            }
        }
    }

    fun toggleSelection(url: String) {
        if (_state.value.isWorking) return
        _state.update { state ->
            val nextSelection = if (url in state.selectedUrls) {
                state.selectedUrls - url
            } else {
                state.selectedUrls + url
            }
            state.copy(selectedUrls = nextSelection)
        }
    }

    fun selectAll() {
        if (_state.value.isWorking) return
        _state.update { state ->
            state.copy(selectedUrls = state.previewItems.map { it.url }.toSet())
        }
    }

    fun clearSelection() {
        if (_state.value.isWorking) return
        _state.update { state ->
            state.copy(selectedUrls = emptySet())
        }
    }

    fun downloadSelected() {
        val current = _state.value
        if (current.isWorking) return
        val items = current.previewItems.filter { it.url in current.selectedUrls }
        if (items.isEmpty()) {
            _state.update { it.copy(status = "선택한 파일이 없어.") }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isWorking = true,
                    status = "다운로드 준비 중",
                    results = emptyList(),
                )
            }

            runCatching {
                val results = downloader.downloadAll(items) { currentIndex, total ->
                    _state.update {
                        it.copy(status = "다운로드 중 $currentIndex/$total")
                    }
                }

                val successCount = results.count { it.isSuccess }
                _state.update {
                    it.copy(
                        isWorking = false,
                        status = "완료 $successCount/${results.size}",
                        results = results,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isWorking = false,
                        status = error.message ?: "실패했어.",
                    )
                }
            }
        }
    }
}
