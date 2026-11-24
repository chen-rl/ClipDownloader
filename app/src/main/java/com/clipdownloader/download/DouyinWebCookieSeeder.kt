package com.clipdownloader.download

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.clipdownloader.util.LogFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 抖音网页端身份 cookie 的获取与登录态管理（「主页下载」的抖音通道用）。
 *
 * 桌面站作品接口的网关只认服务端签发的 UIFID_TEMP（随机值会被 ArgusSecurityPlugin 以
 * Validate Error 拒绝），而签发它的页面受 byted_acrawler 挑战保护（需执行 JS），
 * 纯 OkHttp 拿不到；系统 WebView 能自动过挑战。本类只在缺少身份时用 WebView 访问一次
 * 用户页，拿到 cookie 立即销毁 WebView，后续请求全部走 OkHttp + 纯 Kotlin 签名。
 *
 * 用户在 [DouyinLoginDialog] 登录后，同一 cookie 存储里会多出 sessionid 等，
 * 作品接口即可翻页获取全部作品（游客只能拿到首页）。
 */
object DouyinWebCookieSeeder {

    private const val TAG = "DouyinWebSession"

    /** 桌面站根域：cookie 均种在这里 */
    const val DOMAIN = "https://www.douyin.com/"

    /**
     * 访问桌面站用的 UA：64 位 Windows 上的桌面 Chrome。桌面站按它下发桌面版页面与身份；
     * 作品接口的 a_bogus 与 UA 绑定，因此签名与请求头必须用同一个字符串，
     * 请求参数里的浏览器平台字段也要与它一致（见 DouyinUserParser.buildPostPairs）。
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/159.0.0.0 Safari/537.36"

    /** 当前桌面站的全部 cookie（name=value; …），没有为 null */
    fun currentCookie(): String? = CookieManager.getInstance().getCookie(DOMAIN)

    /** 是否具备访客身份：ttwid 与 UIFID/UIFID_TEMP 都由服务端签发，缺一不可 */
    fun hasIdentity(cookie: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        val names = cookieNames(cookie)
        return "ttwid" in names && ("UIFID_TEMP" in names || "UIFID" in names)
    }

    /** 是否已登录抖音网页版 */
    fun isLoggedIn(cookie: String?): Boolean {
        val names = cookieNames(cookie)
        return "sessionid" in names || "sid_tt" in names
    }

    /** 取 cookie 值（未 URL 解码，原样用于请求头） */
    fun cookieValue(cookie: String?, name: String): String? {
        if (cookie.isNullOrBlank()) return null
        for (part in cookie.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim()
        }
        return null
    }

    private fun cookieNames(cookie: String?): Set<String> {
        if (cookie.isNullOrBlank()) return emptySet()
        return cookie.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim()
        }.toSet()
    }

    /**
     * 确保具备抖音网页身份，返回可用于请求头的 cookie 串。
     *
     * 已有身份时直接返回（不创建 WebView）；否则用 WebView 访问一次 [url] 等服务端签发。
     * [forceRefresh] 用于身份被网关拒绝（403）后重新签发。
     */
    suspend fun ensureIdentity(
        activity: Activity,
        url: String,
        timeoutMs: Long = 25_000L,
        forceRefresh: Boolean = false,
    ): String? {
        if (!forceRefresh) {
            val cached = currentCookie()
            if (hasIdentity(cached)) return cached
        }
        return withContext(Dispatchers.Main) { seedOnMain(activity, url, timeoutMs) }
    }

    /** 必须在主线程调用：WebView 的创建、加载与销毁都只能在主线程 */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun seedOnMain(activity: Activity, url: String, timeoutMs: Long): String? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val webView = try {
            WebView(activity)
        } catch (e: Exception) {
            // 部分精简 ROM / 容器没有 WebView 提供者
            LogFile.w(TAG, "WebView 不可用: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val start = SystemClock.elapsedRealtime()
        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = DESKTOP_UA
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                // 只为取 cookie：不加载图片、不自动播放媒体
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
            }
            webView.webViewClient = object : WebViewClient() {
                // 页面会尝试用 snssdk1128:// 等唤起抖音 App，只放行 http(s)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https" && request.url.scheme != "http"
            }
            webView.loadUrl(url)
            // 挑战页通过后页面会自行 reload，这里不依赖页面回调，轮询 cookie 即可
            while (coroutineContext.isActive) {
                val cookie = cookieManager.getCookie(DOMAIN)
                if (hasIdentity(cookie)) {
                    cookieManager.flush()
                    LogFile.d(TAG, "抖音网页身份就绪，耗时 ${SystemClock.elapsedRealtime() - start}ms，登录态=${isLoggedIn(cookie)}")
                    return cookie
                }
                if (SystemClock.elapsedRealtime() - start >= timeoutMs) break
                delay(400)
            }
            LogFile.w(
                TAG,
                "等待抖音网页身份超时（${timeoutMs}ms）cookie=${cookieManager.getCookie(DOMAIN)?.take(120)}"
            )
            return null
        } finally {
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** 退出登录：清空 WebView 里桌面站的全部 cookie（含访客身份，下次解析会重新签发） */
    fun clear(activity: Activity, onDone: () -> Unit) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies { value ->
            cookieManager.flush()
            activity.runOnUiThread { onDone() }
        }
    }

    /** 便于排查的身份摘要（不含取值） */
    fun describe(cookie: String?): JSONObject = JSONObject().apply {
        put("hasIdentity", hasIdentity(cookie))
        put("loggedIn", isLoggedIn(cookie))
        put("names", cookieNames(cookie).sorted())
    }
}
