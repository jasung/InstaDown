package com.codex.indown

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal const val InstagramUserAgent =
    "Mozilla/5.0 (Linux; Android 14; InDown) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

internal object InDownHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
