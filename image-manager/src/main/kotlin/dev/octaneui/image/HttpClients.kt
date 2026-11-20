package dev.octaneui.image

import dev.octaneui.core.AppInfo
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    fun create(userAgent: String = "OctaneUi/0.1 (+${AppInfo.GITHUB_REPO_URL})"): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
}
