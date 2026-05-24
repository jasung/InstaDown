package com.codex.indown

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri as AndroidUri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.util.Locale
import android.widget.VideoView

private const val UPDATE_INFO_API_URL =
    "https://api.github.com/repos/jasung/InstaDown/contents/instadownversion.json?ref=main"
private const val UPDATE_INFO_RAW_URL =
    "https://raw.githubusercontent.com/jasung/InstaDown/main/instadownversion.json"
private const val DEFAULT_UPDATE_APK_URL =
    "https://github.com/jasung/InstaDown/raw/main/release/InstaDown-latest.apk"

class MainActivity : ComponentActivity() {
    private val viewModel: DownloadViewModel by viewModels()
    private val updateNotice = mutableStateOf<AppUpdateInfo?>(null)
    private var updateCheckJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val clipboardManager = LocalClipboardManager.current
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    viewModel.downloadSelected()
                } else {
                    viewModel.storagePermissionDenied()
                }
            }

            InDownTheme {
                InDownScreen(
                    state = state,
                    onLinkChanged = viewModel::setLink,
                    onClear = viewModel::clear,
                    onPaste = {
                        clipboardManager.getText()?.text
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(viewModel::setLinkAndPreview)
                    },
                    onPreview = viewModel::previewLink,
                    onToggleMedia = viewModel::toggleSelection,
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                    onDownload = {
                        if (needsLegacyStoragePermission(context)) {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            viewModel.downloadSelected()
                        }
                    },
                )

                updateNotice.value?.let { notice ->
                    UpdateNoticeDialog(
                        updateInfo = notice,
                        onDismiss = ::dismissUpdateNotice,
                        onOpen = ::openUpdateNotice,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        checkForAppUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val link = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.let(::extractFirstSharedUrl)
            ?: return
        viewModel.setLinkAndPreview(link)
    }

    private fun extractFirstSharedUrl(text: String): String? =
        sharedUrlRegex.find(text)
            ?.value
            ?.trimEnd('.', ',', ')', ']', '"', '\'')
            ?: text.trim().takeIf { value ->
                value.startsWith("http://") || value.startsWith("https://")
            }

    private fun checkForAppUpdate() {
        if (updateNotice.value != null || updateCheckJob?.isActive == true) return
        updateCheckJob = lifecycleScope.launch {
            val currentCode = currentVersionCode()
            val info = withContext(Dispatchers.IO) { fetchUpdateInfo() } ?: return@launch
            if (info.versionCode > currentCode) {
                updateNotice.value = info
            }
        }
    }

    private fun currentVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun fetchUpdateInfo(): AppUpdateInfo? =
        runCatching {
            val jsonText = listOf(UPDATE_INFO_API_URL, UPDATE_INFO_RAW_URL)
                .firstNotNullOfOrNull { url ->
                    runCatching { fetchUpdateJsonText(url) }.getOrNull()
                }
                ?: return@runCatching null
            val json = org.json.JSONObject(jsonText)
            val versionCode = json.optLong("versionCode", -1L)
            if (versionCode <= 0L) return@runCatching null

            AppUpdateInfo(
                versionName = json.optString("versionName").ifBlank { "새 버전" },
                versionCode = versionCode,
                apkUrl = json.optString("apkUrl").ifBlank { DEFAULT_UPDATE_APK_URL },
                message = json.optString("message").ifBlank { "새 버전이 있습니다." },
                releaseNotes = parseReleaseNotes(json),
                required = json.optBoolean("required", false),
            )
        }.getOrNull()

    private fun fetchUpdateJsonText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "Accept",
                if (url.contains("api.github.com")) {
                    "application/vnd.github.raw"
                } else {
                    "application/json"
                },
            )
            .header("Cache-Control", "no-cache")
            .build()

        return InDownHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }

    private fun parseReleaseNotes(json: org.json.JSONObject): List<String> {
        val array = json.optJSONArray("releaseNotes") ?: json.optJSONArray("installedReleaseNotes")
        if (array != null) {
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotBlank() }
            }
        }
        return json.optString("releaseNotes")
            .ifBlank { json.optString("installedReleaseNotes") }
            .lines()
            .map { line -> line.trim().trimStart('-', '•').trim() }
            .filter { it.isNotBlank() }
    }

    private fun openUpdateNotice(info: AppUpdateInfo) {
        val uri = runCatching { AndroidUri.parse(info.apkUrl) }.getOrNull() ?: return
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.isSuccess
        if (opened && !info.required) {
            updateNotice.value = null
        }
    }

    private fun dismissUpdateNotice() {
        val info = updateNotice.value ?: return
        if (!info.required) {
            updateNotice.value = null
        }
    }

    private companion object {
        val sharedUrlRegex = Regex("""https?://\S+""")
    }
}

private data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val message: String,
    val releaseNotes: List<String>,
    val required: Boolean,
)

private object UrlPreviewTransformation : VisualTransformation {
    private const val MaxVisibleChars = 42

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length <= MaxVisibleChars) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val visible = raw.take(MaxVisibleChars).trimEnd() + "..."
        return TransformedText(
            AnnotatedString(visible),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    if (offset >= MaxVisibleChars) visible.length else offset

                override fun transformedToOriginal(offset: Int): Int =
                    if (offset >= MaxVisibleChars) raw.length else offset
            },
        )
    }
}

@Composable
private fun UpdateNoticeDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit,
    onOpen: (AppUpdateInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!updateInfo.required) onDismiss() },
        title = { Text("새 버전이 있습니다") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(updateInfo.message)
                Text("최신 버전: ${updateInfo.versionName}")
                if (updateInfo.releaseNotes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("업데이트 내용", fontWeight = FontWeight.SemiBold)
                        updateInfo.releaseNotes.forEach { note ->
                            Text("• $note", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onOpen(updateInfo) }) {
                Text("업데이트")
            }
        },
        dismissButton = {
            if (!updateInfo.required) {
                TextButton(onClick = onDismiss) {
                    Text("나중에")
                }
            }
        },
    )
}

@Composable
private fun InDownTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFE4405F),
            onPrimary = Color.White,
            secondary = Color(0xFFF58529),
            tertiary = Color(0xFF833AB4),
            background = Color(0xFFFFFBF7),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFFFE5EC),
            error = Color(0xFFB3261E),
        ),
        content = content,
    )
}

@Composable
private fun InDownScreen(
    state: DownloadUiState,
    onLinkChanged: (String) -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
    onPreview: () -> Unit,
    onToggleMedia: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(status = state.status)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.link,
                    onValueChange = onLinkChanged,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isWorking,
                    singleLine = true,
                    label = { Text("Instagram URL") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Link, contentDescription = "링크")
                    },
                    trailingIcon = {
                        if (state.link.isNotBlank() && !state.isWorking) {
                            IconButton(onClick = onClear) {
                                Icon(Icons.Outlined.Close, contentDescription = "지우기")
                            }
                        }
                    },
                    visualTransformation = UrlPreviewTransformation,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onPreview() }),
                    shape = RoundedCornerShape(8.dp),
                )

                OutlinedButton(
                    onClick = onPaste,
                    modifier = Modifier.size(56.dp),
                    enabled = !state.isWorking,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = "붙여넣기")
                }
            }

            Button(
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = state.selectedCount > 0 && !state.isWorking,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("선택 다운로드")
            }

            if (state.isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            MediaList(
                state = state,
                onToggleMedia = onToggleMedia,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Header(status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "InstaDown",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF7A3B21),
            )
        }
    }
}

@Composable
private fun MediaList(
    state: DownloadUiState,
    onToggleMedia: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (state.previewItems.isEmpty() && state.results.isEmpty()) {
            Text(
                text = if (state.checkedMedia) "저장할 이미지나 영상은 못 찾았어." else "기록 없음",
                color = Color(0xFF7A5A63),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.previewItems.isNotEmpty()) {
                    item {
                        PreviewHeader(
                            selectedCount = state.selectedCount,
                            totalCount = state.previewItems.size,
                            onSelectAll = onSelectAll,
                            onClearSelection = onClearSelection,
                        )
                    }

                    items(
                        items = state.previewItems,
                        key = { item -> item.url },
                    ) { item ->
                        PreviewRow(
                            item = item,
                            selected = item.url in state.selectedUrls,
                            onToggle = { onToggleMedia(item.url) },
                        )
                    }
                }

                if (state.results.isNotEmpty()) {
                    item {
                        Text(
                            text = "저장 결과",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF7A3B21),
                        )
                    }
                }

                items(state.results) { result ->
                    ResultRow(result)
                }
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "미리보기 $selectedCount/$totalCount",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF7A3B21),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSelectAll) {
                Text("전체 선택")
            }
            TextButton(onClick = onClearSelection) {
                Text("해제")
            }
        }
    }
}

@Composable
private fun PreviewRow(
    item: MediaItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreviewMedia(
                item = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${kindLabel(item.kind)} · ${extensionLabel(item.url)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = mediaHost(item.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7A5A63),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sourceLabel(item.source),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7A5A63),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewMedia(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .background(Color(0xFF1C1B1F), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (item.kind == MediaKind.Video) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            start()
                        }
                        setOnErrorListener { _, _, _ -> true }
                    }
                },
                update = { videoView ->
                    videoView.setVideoURI(AndroidUri.parse(item.url))
                    videoView.start()
                },
            )
            Icon(
                imageVector = Icons.Outlined.Videocam,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        } else {
            NetworkImage(
                url = item.previewUrl ?: item.url,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun NetworkImage(
    url: String,
    modifier: Modifier = Modifier,
) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", InstagramUserAgent)
                    .header("Accept", "image/*,*/*;q=0.8")
                    .header("Referer", "https://www.instagram.com/")
                    .build()

                InDownHttp.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.byteStream()?.use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }

    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ResultRow(result: DownloadResult) {
    val success = result.isSuccess
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (success) Color.White else Color(0xFFFFF1EF),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.fileName ?: "실패",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (success) "${kindLabel(result.item.kind)} 저장됨" else result.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7A5A63),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun kindLabel(kind: MediaKind): String =
    when (kind) {
        MediaKind.Image -> "이미지"
        MediaKind.Video -> "영상"
        MediaKind.Unknown -> "파일"
    }

private fun sourceLabel(source: String): String =
    when {
        source.startsWith("direct") -> "직접 미디어"
        source == "meta" -> "공개 메타"
        source == "json" -> "게시물 데이터"
        source == "embedded" -> "게시물 미디어"
        else -> "미디어 후보"
    }

private fun extensionLabel(url: String): String =
    url.substringBefore("?")
        .substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.uppercase(Locale.US)
        ?: "MEDIA"

private fun mediaHost(url: String): String =
    runCatching { URI(url).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?: "미디어 파일"

private fun needsLegacyStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
