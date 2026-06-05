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
    fun resolveUsesVideoMetadataFallbackWhenInstagramOnlyReturnsPreviewImage() = runBlocking {
        val shortcode = "DZNJZ5ypFcv"
        val instagramImageUrl = "https://scontent.cdninstagram.com/v/t51.29350-15/preview.jpg?stp=dst-jpg"
        val mirrorVideoUrl = "https://www.vxinstagram.com/offload/$shortcode/0.mp4"
        val client = OkHttpClient.Builder()
            .addInterceptor(
                StaticHtmlInterceptor(
                    listOf(
                        StubResponse("www.instagram.com", "/reel/$shortcode/") to """
                            <html><head>
                                <meta property="og:image" content="$instagramImageUrl">
                            </head></html>
                        """.trimIndent(),
                        StubResponse("www.instagram.com", "/reel/$shortcode/embed/") to """
                            <html><head>
                                <meta property="og:image" content="$instagramImageUrl">
                            </head></html>
                        """.trimIndent(),
                        StubResponse("www.vxinstagram.com", "/reel/$shortcode/") to """
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
            )
            .build()

        val resolved = InstagramMediaResolver(client).resolve("https://www.instagram.com/reel/$shortcode/")

        assertEquals(listOf(MediaKind.Video), resolved.items.map { it.kind })
        assertEquals(listOf(mirrorVideoUrl), resolved.items.map { it.url })
    }

    private class StaticHtmlInterceptor(
        private val htmlByRequest: List<Pair<StubResponse, String>>,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val html = htmlByRequest
                .firstOrNull { (stub, _) ->
                    request.url.host == stub.host && request.url.encodedPath == stub.encodedPath
                }
                ?.second
                ?: "<html></html>"

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
    }

    private data class StubResponse(
        val host: String,
        val encodedPath: String,
    )
}
