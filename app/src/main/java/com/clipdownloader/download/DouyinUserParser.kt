package com.clipdownloader.download

import android.app.Activity
import com.clipdownloader.download.sign.DouyinRequestSigner
import com.clipdownloader.model.MediaQuality
import com.clipdownloader.model.ParsedMedia
import com.clipdownloader.model.Platform
import com.clipdownloader.util.LogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 抖音博主主页解析：列出用户作品（「主页下载」栏目用）。
 *
 * 通道按可靠性顺序尝试：
 * 1. 桌面站作品接口 /aweme/v1/web/aweme/post/：请求由纯 Kotlin 签名（a_bogus +
 *    x-secsdk-web-signature，见 [com.clipdownloader.download.sign.DouyinRequestSigner]），
 *    身份 cookie（ttwid / UIFID_TEMP）由 WebView 访问一次用户页取得。游客只能拿到首页
 *    （置顶 + 最新若干条），应用内登录抖音后可翻页拿全量；
 * 2. share/user 页内嵌 _ROUTER_DATA JSON；
 * 3. www.douyin.com/user 页 RENDER_DATA。
 * 后两个通道仅在页面未被风控替换时有效，作为兜底保留。
 *
 * 列表项自带 play_addr 直链与 bit_rate 清晰度档（体积），无需逐作二次解析。
 * 图文作品：images[] 每张出一个图片媒体；带内嵌视频的实况图额外出动图视频媒体。
 */
class DouyinUserParser {

    data class UserWorks(
        val author: String,
        val avatar: String,
        val total: Int,
        val works: List<ParsedMedia>
    )

    /** 最近一次失败原因（网关拒绝 / 无 WebView 等），供 UI 明确提示；为空表示未记录 */
    var lastError: String = ""
        private set

    /** 本次结果被游客态登录墙截断（只拿到首页）；登录抖音后重新解析可获取全部 */
    var guestLimited: Boolean = false
        private set

    private val cookieManager = java.net.CookieManager()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(okhttp3.JavaNetCookieJar(cookieManager))
        .build()

    /** 桌面站作品接口专用：不带本地 cookieJar，cookie 全部由 WebView 身份显式给出 */
    private val desktopClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * @param activity 用于创建 WebView 取抖音网页身份；为 null 时跳过通道 1（只有兜底通道）
     */
    suspend fun listVideos(
        secUid: String,
        maxPages: Int = 20,
        activity: Activity? = null,
    ): UserWorks? = withContext(Dispatchers.IO) {
        lastError = ""
        guestLimited = false
        try {
            // 通道 1：桌面站作品接口
            if (activity != null) {
                val viaApi = tryViaDesktopApi(secUid, maxPages, activity)
                if (viaApi != null) return@withContext viaApi
            }

            warmUpCookie()
            registerTtwid()

            // 通道 2：share/user 页 SSR
            val viaPage = tryViaSharePage(secUid)
            if (viaPage != null) return@withContext viaPage

            // 通道 3：www.douyin.com/user 页 RENDER_DATA（桌面 SSR，首屏约 18 个作品）
            val viaDesktop = tryViaDesktopPage(secUid)
            if (viaDesktop != null) return@withContext viaDesktop

            LogFile.w(TAG, "所有通道均未获取到作品列表 secUid=${secUid.take(16)}…")
            if (lastError.isBlank()) lastError = "接口风控，请稍后重试或更换网络"
            null
        } catch (e: Exception) {
            LogFile.e(TAG, "拉取主页作品异常", e)
            lastError = "拉取主页作品异常：${e.javaClass.simpleName}"
            null
        }
    }

    /**
     * 注册 ttwid 设备令牌（字节跳动统一风控放行凭证），供兜底通道 2/3 使用：
     * 无 ttwid 时 share 页不吐 _ROUTER_DATA。注册成功后种到 iesdouyin/douyin 两个域。
     */
    private fun registerTtwid() {
        try {
            val hasTtwid = cookieManager.cookieStore.cookies.any { it.name == "ttwid" }
            if (hasTtwid) return
            val body = JSONObject().apply {
                put("region", "cn")
                put("aid", 1768)
                put("needFid", false)
                put("service", "www.ixigua.com")
                put("migrate_info", JSONObject().apply {
                    put("ticket", ""); put("source", "node")
                })
                put("cbUrlProtocol", "https")
                put("union", true)
            }
            client.newCall(
                Request.Builder().url("https://ttwid.bytedance.com/ttwid/union/register/")
                    .header("User-Agent", MOBILE_UA)
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute().use { resp ->
                val setCookie = resp.header("Set-Cookie") ?: return
                val ttwid = setCookie.substringAfter("ttwid=").substringBefore(";").takeIf { it.isNotBlank() } ?: return
                for (domain in listOf(".iesdouyin.com", ".douyin.com")) {
                    val ck = java.net.HttpCookie("ttwid", ttwid)
                    ck.domain = domain
                    ck.path = "/"
                    cookieManager.cookieStore.add(
                        java.net.URI(if (domain.contains("ies")) "https://www.iesdouyin.com" else "https://www.douyin.com"),
                        ck
                    )
                }
                LogFile.d(TAG, "ttwid 注册成功（len=${ttwid.length}）")
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "ttwid 注册失败: ${e.message}")
        }
    }

    // ==================== 通道 1：桌面站作品接口（a_bogus + x-secsdk-web-signature） ====================

    /**
     * 桌面站作品接口 /aweme/v1/web/aweme/post/。请求由 [DouyinRequestSigner] 纯 Kotlin 签名，
     * 身份 cookie（ttwid / UIFID_TEMP，登录后另有 sessionid 等）由 WebView 访问一次用户页取得。
     *
     * 游客态平台只返回首页（置顶 + 最新若干条），翻页被登录墙截断（has_more=1 但下一页没有
     * aweme_list）；登录后可翻到底。网关以 403 拒绝（身份失效）时强制重新签发身份重试一次。
     */
    private suspend fun tryViaDesktopApi(secUid: String, maxPages: Int, activity: Activity): UserWorks? {
        val pageUrl = "https://www.douyin.com/user/$secUid"
        var cookie = obtainIdentity(activity, pageUrl, forceRefresh = false) ?: return null
        val loggedIn = DouyinWebCookieSeeder.isLoggedIn(cookie)
        val signer = DouyinRequestSigner(DouyinWebCookieSeeder.DESKTOP_UA)

        val all = mutableListOf<JSONObject>()
        val seen = HashSet<String>()
        var author = ""
        var cursor = 0L
        var page = 1
        var refreshed = false
        // 上一页是否声明还有更多：登录墙的空响应只有 {"status_code":0}，需据此判断是被截断还是取完
        var lastHasMore = false
        while (page <= maxPages) {
            val uifid = DouyinWebCookieSeeder.cookieValue(cookie, "UIFID")
                ?: DouyinWebCookieSeeder.cookieValue(cookie, "UIFID_TEMP")
                ?: run {
                    lastError = "抖音网页身份缺少 uifid，请重新解析"
                    LogFile.w(TAG, "cookie 中没有 UIFID/UIFID_TEMP，无法签名")
                    return if (all.isEmpty()) null else convert(all, author)
                }
            val signed = signer.signProtectedQuery(buildPostPairs(secUid, cursor, uifid), uifid).query
            val (code, json) = httpGetJsonWithHeaders(POST_API + "?" + signed, desktopHeaders(secUid, cookie, uifid))

            if (code == 403 && !refreshed) {
                // ArgusSecurityPlugin 拒绝：访客身份已失效，重新签发后重试本页
                LogFile.w(TAG, "作品接口被网关拒绝（403），重新获取抖音网页身份")
                refreshed = true
                cookie = obtainIdentity(activity, pageUrl, forceRefresh = true) ?: run {
                    lastError = "抖音网页身份已失效，且无法重新获取"
                    return if (all.isEmpty()) null else convert(all, author)
                }
                continue
            }
            if (json == null || json.optInt("status_code", -1) != 0) {
                val status = json?.optInt("status_code", -1) ?: -1
                if (all.isEmpty()) {
                    lastError = "抖音作品接口无响应（HTTP $code status=$status），请稍后重试"
                    LogFile.w(TAG, "桌面作品接口失败：HTTP $code status=$status")
                    return null
                }
                LogFile.w(TAG, "桌面作品接口第 $page 页失败（HTTP $code status=$status），返回已取 ${all.size} 个作品")
                break
            }
            val list = json.optJSONArray("aweme_list")
            if (list == null || list.length() == 0) {
                // status_code=0 但没有 aweme_list：游客到此被登录墙截断，或确实没有更多作品
                if (all.isEmpty()) {
                    lastError = if (loggedIn) "该账号没有可见作品" else "未取到作品，请登录抖音后重试"
                    LogFile.w(TAG, "作品接口返回空列表（登录态=$loggedIn）")
                    return null
                }
                // status_code=0 但没有 aweme_list：上一页声明还有更多却被截断，即游客撞上登录墙
                if (!loggedIn && lastHasMore) guestLimited = true
                LogFile.d(TAG, "作品列表结束于第 $page 页（登录态=$loggedIn，游客受限=$guestLimited）")
                break
            }
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("aweme_id")
                if (id.isNotBlank() && seen.add(id)) all.add(item)
            }
            if (author.isBlank()) {
                author = list.optJSONObject(0)?.optJSONObject("author")?.optString("nickname").orEmpty()
            }
            lastHasMore = json.optInt("has_more", 0) == 1
            val next = json.optLong("max_cursor", 0L)
            if (!lastHasMore || next <= 0L || next == cursor) break
            cursor = next
            page++
        }
        if (all.isEmpty()) return null
        LogFile.d(TAG, "桌面作品接口：${all.size} 个作品 · $author（登录态=$loggedIn，游客受限=$guestLimited）")
        return convert(all, author)
    }

    /** 取抖音网页身份 cookie；失败时记录 [lastError] 并返回 null */
    private suspend fun obtainIdentity(activity: Activity, url: String, forceRefresh: Boolean): String? {
        val cookie = try {
            DouyinWebCookieSeeder.ensureIdentity(activity, url, forceRefresh = forceRefresh)
        } catch (e: Exception) {
            LogFile.w(TAG, "获取抖音网页身份异常: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (cookie == null) lastError = "无法建立抖音网页身份（需要系统 WebView 与可用网络）"
        return cookie
    }

    /**
     * 作品接口的业务参数（字段与顺序取自对抖音网页版用户主页真实请求的抓包）。
     * 浏览器平台相关字段与 [DouyinWebCookieSeeder.DESKTOP_UA]（64 位 Windows 上的 Chrome）及
     * a_bogus 默认的 Windows 1080p browser_info 保持一致——query 与自己的 UA 自相矛盾
     * 等于白白送给平台一个风控信号。
     */
    private fun buildPostPairs(secUid: String, cursor: Long, uifid: String): List<Pair<String, String>> {
        val chrome = "159.0.0.0"
        return listOf(
            "device_platform" to "webapp", "aid" to "6383", "channel" to "channel_pc_web",
            "update_version_code" to "170400", "pc_client_type" to "1", "pc_libra_divert" to "Windows",
            "support_h265" to "0", "support_dash" to "0", "version_code" to "290100",
            "version_name" to "29.1.0", "cookie_enabled" to "true", "screen_width" to "1920",
            "screen_height" to "1080", "browser_language" to "zh-CN", "browser_platform" to "Win32",
            "browser_name" to "Chrome", "browser_version" to chrome, "browser_online" to "true",
            "engine_name" to "Blink", "engine_version" to chrome, "os_name" to "Windows",
            "os_version" to "10", "cpu_core_num" to "12", "device_memory" to "8",
            "platform" to "PC", "downlink" to "10", "effective_type" to "4g", "round_trip_time" to "50",
            "uifid" to uifid,
            "sec_user_id" to secUid, "max_cursor" to cursor.toString(), "locate_query" to "false",
            "show_live_replay_strategy" to "1", "need_time_list" to "1", "time_list_query" to "0",
            "whale_cut_token" to "", "cut_version" to "1", "count" to "18",
            "publish_video_strategy_type" to "2", "from_user_page" to "1",
        )
    }

    private fun desktopHeaders(secUid: String, cookie: String, uifid: String): Map<String, String> = mapOf(
        "User-Agent" to DouyinWebCookieSeeder.DESKTOP_UA,
        "Accept" to "*/*",
        "Referer" to "https://www.douyin.com/user/$secUid",
        // 2026-08 起抖音网关的 ArgusSecurityPlugin 要求这两个请求头存在
        "x-tt-argus" to "1",
        "uifid" to uifid,
        "Cookie" to cookie,
    )

    /** GET 并解析 JSON，返回 (HTTP 状态码, JSON)；非 JSON 响应（如网关 403 的纯文本）的 JSON 为 null */
    private fun httpGetJsonWithHeaders(url: String, headers: Map<String, String>): Pair<Int, JSONObject?> {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            desktopClient.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    null
                }
                resp.code to json
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "GET 异常 ${e.javaClass.simpleName}: ${e.message}")
            -1 to null
        }
    }

    // ==================== 通道 2：share/user 页 SSR ====================

    private fun tryViaSharePage(secUid: String): UserWorks? {
        val html = try {
            val req = Request.Builder().url("https://www.iesdouyin.com/share/user/$secUid")
                .header("User-Agent", MOBILE_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "share/user 页请求失败: ${e.message}")
            return null
        }
        // 括号配对提取 _ROUTER_DATA，不用非贪婪正则——
        // desc 含字面 </script> 或 {} 时正则会在错误位置截断
        val routerRaw = extractRouterData(html)
        val items = mutableListOf<JSONObject>()
        var author = ""
        if (routerRaw != null) {
            val root = try { JSONObject(routerRaw) } catch (_: Exception) { null }
            // 递归找 aweme_list 数组
            val lists = mutableListOf<JSONArray>()
            if (root != null) findAwemeLists(root, lists)
            lists.forEach { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let {
                        items.add(it)
                        if (author.isBlank()) author = it.optJSONObject("author")?.optString("nickname").orEmpty()
                    }
                }
            }
        }
        if (items.isEmpty()) {
            // 容错：站点改版换了解析包裹（RENDER_DATA / 其他内嵌 JSON）时，
            // 直接扫描全部内嵌 script，按「元素带 aweme_id 的数组」特征提取
            collectFromInlineScripts(html, items)
            for (it in items) {
                author = it.optJSONObject("author")?.optString("nickname").orEmpty()
                if (author.isNotBlank()) break
            }
        }
        if (items.isEmpty()) {
            LogFile.w(TAG, "share/user 页无作品数据（可能被风控）")
            return null
        }
        LogFile.d(TAG, "share/user SSR：${items.size} 个作品 · $author")
        return convert(items, author)
    }

    /** 扫描页面全部内嵌 <script>，解析其中的 JSON 并收集带 aweme_id 的对象数组 */
    private fun collectFromInlineScripts(html: String, out: MutableList<JSONObject>) {
        for (match in SCRIPT_TAG_REGEX.findAll(html)) {
            var body = match.groupValues[1].trim()
            if (!body.contains("aweme_id") || body.length > 800_000) continue
            if (!body.startsWith("{") && !body.startsWith("[")) {
                val eq = body.indexOf('=')
                if (eq !in 1..200) continue
                body = body.substring(eq + 1).trim().trimEnd(';')
            }
            val parsed: Any? = try {
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) collectAwemeArrays(arr.opt(i), out)
                    continue
                } else JSONObject(body)
            } catch (_: Exception) {
                continue
            }
            collectAwemeArrays(parsed, out)
        }
    }

    private fun findAwemeLists(node: Any?, out: MutableList<JSONArray>) {
        when (node) {
            is JSONObject -> {
                node.optJSONArray("aweme_list")?.let { out.add(it) }
                for (k in node.keys()) findAwemeLists(node.opt(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) findAwemeLists(node.opt(i), out)
        }
    }

    // ==================== 通道 3：www.douyin.com/user 桌面页 RENDER_DATA ====================

    /**
     * 桌面站用户页 SSR：HTML 里 <script id="RENDER_DATA"> 为 URL 编码的 JSON，
     * 内嵌首屏作品数组（元素带 aweme_id）。结构无固定 key（模块名是哈希），
     * 用「元素为带 aweme_id 的对象」的数组特征递归查找，对改版容错。
     * 反爬壳页（JS 虚拟机挑战）没有 RENDER_DATA，直接失败回落。
     */
    private fun tryViaDesktopPage(secUid: String): UserWorks? {
        val html = try {
            client.newCall(
                Request.Builder().url("https://www.douyin.com/user/$secUid")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://www.douyin.com/")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "桌面页请求失败: ${e.message}")
            return null
        }
        val m = RENDER_DATA_REGEX.find(html) ?: run {
            LogFile.w(TAG, "桌面页无 RENDER_DATA（反爬壳或未登录态）")
            return null
        }
        val decoded = try {
            java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        } catch (_: Exception) {
            return null
        }
        val root = try { JSONObject(decoded) } catch (_: Exception) { return null }
        // 递归收集「元素为带 aweme_id 的对象」的数组
        val items = mutableListOf<JSONObject>()
        collectAwemeArrays(root, items)
        if (items.isEmpty()) return null
        var author = ""
        for (it in items) {
            author = it.optJSONObject("author")?.optString("nickname").orEmpty()
            if (author.isNotBlank()) break
        }
        LogFile.d(TAG, "桌面页 RENDER_DATA：${items.size} 个作品 · $author")
        return convert(items, author)
    }

    /** 递归查找「元素为带 aweme_id 的对象」的 JSON 数组（桌面页模块 key 是哈希，结构不固定） */
    private fun collectAwemeArrays(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> for (k in node.keys()) collectAwemeArrays(node.opt(k), out)
            is JSONArray -> {
                val first = node.optJSONObject(0)
                if (first != null && first.has("aweme_id")) {
                    for (i in 0 until node.length()) node.optJSONObject(i)?.let { out.add(it) }
                } else {
                    for (i in 0 until node.length()) collectAwemeArrays(node.opt(i), out)
                }
            }
        }
    }

    // ==================== 公共转换 ====================

    /** aweme JSON → ParsedMedia（视频直链 + bit_rate 画质档；图文出图片/动图） */
    private fun convert(items: List<JSONObject>, author: String): UserWorks {
        val avatar = items.firstNotNullOfOrNull { it ->
            it.optJSONObject("author")?.optJSONObject("avatar_thumb")
                ?.optJSONArray("url_list")?.optString(0)?.takeIf { u -> u.startsWith("http") }
        }.orEmpty()
        val works = mutableListOf<ParsedMedia>()
        items.forEachIndexed { idx, item ->
            val awemeId = item.optString("aweme_id")
            if (awemeId.isBlank()) return@forEachIndexed
            val title = item.optString("desc")
            val who = item.optJSONObject("author")?.optString("nickname")?.takeIf { it.isNotBlank() } ?: author
            val video = item.optJSONObject("video")

            // bit_rate 各档清晰度（与单视频解析同规则）
            val qualities = mutableListOf<MediaQuality>()
            video?.optJSONArray("bit_rate")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val br = arr.optJSONObject(i) ?: continue
                    val u = resolvePlayUrl(br.optJSONObject("play_addr"))
                    if (u.isBlank() || isAudioUrl(u)) continue
                    val gear = br.optString("gear_name")
                    val height = MediaQuality.heightOf(gear)
                    val size = br.optLong("data_size", 0L).takeIf { it > 0 }
                        ?: br.optJSONObject("play_addr")?.optLong("data_size", 0L) ?: 0L
                    qualities.add(
                        MediaQuality(
                            label = if (height > 0) "${height}p" else gear.ifBlank { "默认" },
                            url = u,
                            sizeBytes = size,
                            height = height
                        )
                    )
                }
            }
            val sorted = qualities
                .sortedWith(compareByDescending<MediaQuality> { it.height }.thenByDescending { it.sizeBytes })
                .distinctBy { it.url }

            fun base() = ParsedMedia(
                platform = Platform.DOUYIN,
                mediaId = awemeId,
                title = title,
                author = who,
                rawShareText = "https://www.douyin.com/video/$awemeId",
                originalUrl = "https://www.douyin.com/video/$awemeId"
            )

            // 图文作品
            val images = item.optJSONArray("images")
            if (images != null && images.length() > 0) {
                for (i in 0 until images.length()) {
                    val img = images.optJSONObject(i) ?: continue
                    val staticUrl = img.optJSONArray("url_list")?.optString(0).orEmpty()
                    val liveUrl = resolvePlayUrl(img.optJSONObject("video")?.optJSONObject("play_addr"))
                        .ifBlank { resolvePlayUrl(img.optJSONObject("video")?.optJSONObject("play_addr_h264")) }
                        .replace("/playwm/", "/play/")
                    if (staticUrl.isNotBlank()) works.add(base().copy(imageUrl = staticUrl))
                    if (liveUrl.isNotBlank() && !isAudioUrl(liveUrl)) {
                        works.add(base().copy(videoUrl = liveUrl, duration = LIVE_PHOTO_ESTIMATE_MS))
                    }
                }
                return@forEachIndexed
            }

            // 视频作品
            val playUrl = resolvePlayUrl(video?.optJSONObject("play_addr"))
                .ifBlank { resolvePlayUrl(video?.optJSONObject("play_addr_h265")) }
                .ifBlank { resolvePlayUrl(video?.optJSONObject("play_addr_264")) }
            if (playUrl.isBlank() || isAudioUrl(playUrl)) return@forEachIndexed
            val best = sorted.firstOrNull()?.url?.takeIf { it.startsWith("http") } ?: playUrl
            val cover = video?.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0)?.ifBlank { null }
            val duration = video?.optLong("duration", 0L) ?: 0L
            works.add(
                base().copy(
                    videoUrl = best,
                    qualities = sorted.map { q -> q.copy(url = q.url.replace("/playwm/", "/play/")) },
                    coverUrl = cover,
                    duration = duration
                )
            )
        }
        // 作品数按 mediaId 去重计数
        val total = works.map { it.mediaId }.distinct().size
        return UserWorks(author, avatar, total, works)
    }

    /** play_addr → 可下载直链（uri 即 CDN 直链的 2026 新格式；其余取 url_list[0] 去水印路径） */
    private fun resolvePlayUrl(playAddr: JSONObject?): String {
        if (playAddr == null) return ""
        val uri = playAddr.optString("uri").trim()
        val url0 = playAddr.optJSONArray("url_list")?.optString(0)?.trim().orEmpty()
        return when {
            url0.contains("video_id=http") && uri.startsWith("http") -> uri
            url0.isNotBlank() && !isAudioUrl(url0) -> url0.replace("/playwm/", "/play/")
            uri.startsWith("http") && !isAudioUrl(uri) -> uri
            else -> ""
        }
    }

    private fun isAudioUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac") ||
            lower.contains(".wav") || lower.contains("music") || lower.contains("ies-music")
    }

    /** 访问 iesdouyin 主页种 ttwid（分享接口没有它常返回空数据） */
    private fun warmUpCookie() {
        try {
            client.newCall(
                Request.Builder().url("https://www.iesdouyin.com/")
                    .header("User-Agent", MOBILE_UA)
                    .build()
            ).execute().close()
        } catch (_: Exception) {
        }
    }

    private fun httpGetJson(url: String, mobileUa: Boolean): JSONObject? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", if (mobileUa) MOBILE_UA else DESKTOP_UA)
                .header("Referer", "https://www.iesdouyin.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogFile.w(TAG, "HTTP ${resp.code} · ${url.take(90)}")
                    return null
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            LogFile.w(TAG, "GET 异常 ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DouyinUserParser"

        /** 桌面站「账号发布作品」接口（抖音网页 SDK 的签名保护路径之一） */
        private const val POST_API = "https://www.douyin.com/aweme/v1/web/aweme/post/"

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 实况图接口不返回时长，预览用 2s 估算 */
        private const val LIVE_PHOTO_ESTIMATE_MS = 2000L

        private val RENDER_DATA_REGEX = Regex("""<script id="RENDER_DATA"[^>]*>([^<]+)</script>""")

        private val SCRIPT_TAG_REGEX = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }

    /** 定位 window._ROUTER_DATA 赋值，从首个 { 起做字符串/转义感知的括号配对 */
    private fun extractRouterData(html: String): String? {
        val keyword = "_ROUTER_DATA"
        var idx = html.indexOf(keyword)
        while (idx >= 0) {
            val eq = html.indexOf('=', idx + keyword.length)
            if (eq > 0 && eq - (idx + keyword.length) <= 4) {
                val start = html.indexOf('{', eq)
                if (start > 0) {
                    var depth = 0
                    var inStr = false
                    var esc = false
                    for (j in start until html.length) {
                        val c = html[j]
                        if (inStr) {
                            if (esc) esc = false
                            else if (c == '\\') esc = true
                            else if (c == '"') inStr = false
                        } else {
                            when (c) {
                                '"' -> inStr = true
                                '{' -> depth++
                                '}' -> {
                                    depth--
                                    if (depth == 0) return html.substring(start, j + 1)
                                }
                            }
                        }
                    }
                }
            }
            idx = html.indexOf(keyword, idx + keyword.length)
        }
        return null
    }
}
