package com.codex.indown

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramMediaResolverTest {
    @Test
    fun resolveUsesApiImageCandidateThatMatchesOriginalRatio() = runBlocking {
        val shortcode = "DZO-swVvu8e"
        val mediaId = "3913340880676646686"
        val croppedPreviewUrl =
            "https://scontent.cdninstagram.com/v/t51.29350-15/preview.jpg?stp=c288.0.864.864a_dst-jpg_e35_s640x640"
        val wrongLargeUrl = "https://scontent.cdninstagram.com/v/t51.29350-15/wrong.jpg?stp=dst-jpg_e35_p1440x1440"
        val originalRatioUrl = "https://scontent.cdninstagram.com/v/t51.29350-15/original.jpg?stp=dst-jpg_e35_p1440x1800"
        val client = OkHttpClient.Builder()
            .addInterceptor(
                StaticHtmlInterceptor(
                    listOf(
                        StubResponse("www.instagram.com", "/p/$shortcode/") to StubPayload(
                            """
                                <html><head>
                                    <meta property="og:image" content="$croppedPreviewUrl">
                                </head></html>
                            """.trimIndent(),
                        ),
                        StubResponse("www.instagram.com", "/p/$shortcode/embed/") to StubPayload(
                            """
                                <html><head>
                                    <meta property="og:image" content="$croppedPreviewUrl">
                                </head></html>
                            """.trimIndent(),
                        ),
                        StubResponse("www.instagram.com", "/api/v1/media/$mediaId/info/") to StubPayload(
                            """
                                {
                                  "status": "ok",
                                  "items": [{
                                    "original_width": 1440,
                                    "original_height": 1800,
                                    "image_versions2": {
                                      "candidates": [
                                        {"url": "$croppedPreviewUrl", "width": 640, "height": 640},
                                        {"url": "$wrongLargeUrl", "width": 1440, "height": 1440},
                                        {"url": "$originalRatioUrl", "width": 1440, "height": 1800}
                                      ]
                                    }
                                  }]
                                }
                            """.trimIndent(),
                            "application/json; charset=utf-8",
                        ),
                    ),
                ),
            )
            .build()

        val resolved = InstagramMediaResolver(client).resolve("https://www.instagram.com/p/$shortcode/")

        assertEquals(listOf(MediaKind.Image), resolved.items.map { it.kind })
        assertEquals(listOf(originalRatioUrl), resolved.items.map { it.url })
    }

    @Test
    fun resolveUsesApiVideoCandidateWithLargestDimensions() = runBlocking {
        val shortcode = "DZO-swVvu8e"
        val mediaId = "3913340880676646686"
        val previewUrl = "https://scontent.cdninstagram.com/v/t51.29350-15/cover.jpg?stp=dst-jpg_e35_p1080x1920"
        val lowVideoUrl = "https://scontent.cdninstagram.com/v/t50.16885-16/low.mp4"
        val highVideoUrl = "https://scontent.cdninstagram.com/v/t50.16885-16/high.mp4"
        val client = OkHttpClient.Builder()
            .addInterceptor(
                StaticHtmlInterceptor(
                    listOf(
                        StubResponse("www.instagram.com", "/p/$shortcode/") to StubPayload("<html></html>"),
                        StubResponse("www.instagram.com", "/p/$shortcode/embed/") to StubPayload("<html></html>"),
                        StubResponse("www.instagram.com", "/api/v1/media/$mediaId/info/") to StubPayload(
                            """
                                {
                                  "status": "ok",
                                  "items": [{
                                    "image_versions2": {
                                      "candidates": [
                                        {"url": "$previewUrl", "width": 1080, "height": 1920}
                                      ]
                                    },
                                    "video_versions": [
                                      {"url": "$lowVideoUrl", "width": 640, "height": 360},
                                      {"url": "$highVideoUrl", "width": 1080, "height": 1920}
                                    ]
                                  }]
                                }
                            """.trimIndent(),
                            "application/json; charset=utf-8",
                        ),
                    ),
                ),
            )
            .build()

        val resolved = InstagramMediaResolver(client).resolve("https://www.instagram.com/p/$shortcode/")

        assertEquals(listOf(MediaKind.Video), resolved.items.map { it.kind })
        assertEquals(listOf(highVideoUrl), resolved.items.map { it.url })
        assertEquals(listOf(previewUrl), resolved.items.map { it.previewUrl })
    }

    @Test
    fun resolveUsesVideoMetadataFallbackWhenInstagramOnlyReturnsPreviewImage() = runBlocking {
        val shortcode = "DZNJZ5ypFcv"
        val instagramImageUrl = "https://scontent.cdninstagram.com/v/t51.29350-15/preview.jpg?stp=dst-jpg"
        val mirrorVideoUrl = "https://www.vxinstagram.com/offload/$shortcode/0.mp4"
        val client = OkHttpClient.Builder()
            .addInterceptor(
                StaticHtmlInterceptor(
                    listOf(
                        StubResponse("www.instagram.com", "/reel/$shortcode/") to StubPayload(
                            """
                            <html><head>
                                <meta property="og:image" content="$instagramImageUrl">
                            </head></html>
                            """.trimIndent(),
                        ),
                        StubResponse("www.instagram.com", "/reel/$shortcode/embed/") to StubPayload(
                            """
                            <html><head>
                                <meta property="og:image" content="$instagramImageUrl">
                            </head></html>
                            """.trimIndent(),
                        ),
                        StubResponse("www.vxinstagram.com", "/reel/$shortcode/") to StubPayload(
                            """
                            <html><head>
                                <meta property="og:type" content="video.other">
                                <meta property="og:video" content="$mirrorVideoUrl">
                                <meta property="og:video:type" content="video/mp4">
                                <meta property="og:video:width" content="720">
                                <meta property="og:video:height" content="1280">
                            </head></html>
                            """.trimIndent(),
                        ),
                    ),
                ),
            )
            .build()

        val resolved = InstagramMediaResolver(client).resolve("https://www.instagram.com/reel/$shortcode/")

        assertEquals(listOf(MediaKind.Video), resolved.items.map { it.kind })
        assertEquals(listOf(mirrorVideoUrl), resolved.items.map { it.url })
    }

    private class StaticHtmlInterceptor(
        private val payloadByRequest: List<Pair<StubResponse, StubPayload>>,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val payload = payloadByRequest
                .firstOrNull { (stub, _) ->
                    request.url.host == stub.host && request.url.encodedPath == stub.encodedPath
                }
                ?.second
                ?: StubPayload("<html></html>")

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.body.toResponseBody(payload.contentType.toMediaType()))
                .build()
        }
    }

    private data class StubPayload(
        val body: String,
        val contentType: String = "text/html; charset=utf-8",
    )

    private data class StubResponse(
        val host: String,
        val encodedPath: String,
    )
}
