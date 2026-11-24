package com.clipdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import com.clipdownloader.databinding.ActivityMainBinding
import com.clipdownloader.databinding.ItemPlatformChannelBinding
import com.clipdownloader.download.DownloadManager
import com.clipdownloader.download.DouyinWebCookieSeeder
import com.clipdownloader.download.PlatformParser
import com.clipdownloader.model.DownloadBatchState
import com.clipdownloader.model.DownloadRecord
import com.clipdownloader.model.LibraryWorks
import com.clipdownloader.model.Platform
import java.io.File
import com.clipdownloader.util.DownloadRecordStore
import com.clipdownloader.util.FileActions
import com.clipdownloader.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloadManager: DownloadManager by lazy { DownloadManager.get(this) }
    private val recordStore: DownloadRecordStore by lazy { DownloadRecordStore.get(this) }

    private lateinit var downloadsAdapter: DownloadsAdapter
    private lateinit var profileAdapter: DownloadsAdapter
    private lateinit var libraryAdapter: LibraryAdapter

    /** 作品库上次归并时的输入签名：记录与批次都没变就不重新扫磁盘 */
    private var libraryInputSignature = ""
    private var libraryJob: kotlinx.coroutines.Job? = null

    private var batchListener: ((List<DownloadBatchState>) -> Unit)? = null
    private var recordListener: ((List<DownloadRecord>) -> Unit)? = null

    /** 本次进程内是否已弹过通知权限申请（避免每次 onResume 都弹） */
    private var notifPermissionPrompted = false

    private companion object {
        const val UI_REFRESH_MS = 600L

        /** savedInstanceState 键：「通知弹窗后待弹媒体授权」标志 */
        const val KEY_PENDING_STORAGE_PROMPT = "pending_storage_prompt"

        /**
         * 列表刷新合并粒度：进度通知(200ms)、批量心跳(500ms)、页面轮询(600ms)、
         * 记录写入等多个刷新源全部汇聚到 scheduleListRefresh()，实际刷新最多
         * 5 次/秒，避免主线程被全量重绑淹没导致卡顿
         */
        const val LIST_REFRESH_MIN_INTERVAL_MS = 200L

        /** 短链展开用 UA（抖音要移动端，其余桌面端） */
        const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 当前所在 Tab：切主题等操作会重建 Activity，用类变量记住避免跳回首页 */
        var currentTabId: Int = R.id.nav_home
    }

    /** 首页解析结果（勾选下载用） */
    private val pickAdapter = MediaPickAdapter()
    private var homeParsedPlatform: com.clipdownloader.model.Platform? = null
    private var homeParsedText: String = ""

    /** 「主页下载」栏解析状态（汇总+画质滑块+下载用） */
    private var profileWorks: List<com.clipdownloader.model.ParsedMedia> = emptyList()
    private var profileLabels: List<String> = emptyList()
    private var profileSelectedLabel: String? = null
    private var profileAuthor: String = ""
    private var profileAvatar: String = ""
    private var profilePlatform: com.clipdownloader.model.Platform? = null
    private var profileText: String = ""
    private var profileTotalWorks: Int = 0
    private var profileSuccessWorks: Int = 0
    private var profileSkippedWorks: Int = 0
    @Volatile private var profileParsing = false

    /** 首页解析进度监听（解析期间显示通道状态，onDestroy 兜底移除） */
    private var parseProgressListener: ((String) -> Unit)? = null

    /**
     * 已经触发过自动下载的剪贴板内容（持久化，避免同一条链接反复下载）。
     * 只在「真正从其他 App 切回 / 冷启动」时检查一次剪贴板，
     * 通知栏下拉上滑等瞬时焦点变化不会触发。
     */
    private val consumedClips = mutableSetOf<String>()

    /** 标记：App 刚从后台回到前台，需要做一次剪贴板检查（onStart 置位，检查后置 false） */
    private var pendingClipboardCheck = false

    /** 首次启动串行授权：通知弹窗结束后接着弹媒体授权 */
    private var pendingStoragePrompt = false

    /**
     * 进程级前后台观察者。必须存字段并在 onDestroy 移除——
     * ProcessLifecycleOwner 与进程同寿，匿名注册不移除的话，
     * 每次 Activity 重建（切主题/旋转）就泄漏一个完整 Activity 实例
     */
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            pendingClipboardCheck = true
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "通知权限被拒绝，下载完成时不会有提醒", Toast.LENGTH_LONG).show()
        }
        refreshPermissionStatus()
        if (pendingStoragePrompt) {
            pendingStoragePrompt = false
            requestStoragePermissions()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            Toast.makeText(this, "需要存储权限才能保存文件", Toast.LENGTH_LONG).show()
        }
        // 授权结果返回后立刻刷新设置页状态
        refreshPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 恢复「通知弹窗后待弹媒体授权」标志：权限弹窗期间旋转/切主题重建时，
        // 新实例 pendingStoragePrompt 默认 false 会让首次串行授权静默中断
        pendingStoragePrompt = savedInstanceState?.getBoolean(KEY_PENDING_STORAGE_PROMPT) ?: false

        prefs = PreferencesManager(this)
        consumedClips.addAll(prefs.getConsumedClips())

        // 作品库的适配器要先建好：底部导航恢复到「作品库」页签时会立刻刷新它
        setupLibrary()
        setupBottomNav()
        setupHome()
        setupDownloads()
        setupProfileDownloads()
        setupSettings()
        requestPermissions()

        // 监听进程级前后台切换：只在真正从后台回到前台时标记一次剪贴板检查，
        // 通知栏下拉/上滑、Dialog 关闭等瞬时焦点变化不会置位
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        // 冷启动也算一次前台进入
        pendingClipboardCheck = true

        // 分享入口（ShareActivity）带 EXTRA_TEXT 过来时处理
        handleShareIntent(intent)
    }

    // ==================== 底部导航 ====================

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            currentTabId = item.itemId
            when (item.itemId) {
                R.id.nav_home -> showTab(binding.viewHome.root, "剪贴板下载器", "")
                R.id.nav_downloads -> {
                    showTab(binding.viewDownloads.root, "下载", "查看进度与记录")
                    refreshDownloadsList()
                }
                R.id.nav_profile -> {
                    showTab(binding.viewProfile.root, "主页下载", "博主作品批量下载")
                    refreshDownloadsList()
                }
                R.id.nav_library -> {
                    showTab(binding.viewLibrary.root, "作品库", "已下载的作品，一个作品一张卡片")
                    refreshLibrary(force = true)
                }
                R.id.nav_settings -> showTab(binding.viewSettings.root, "设置", "基础配置与平台管理")
            }
            true
        }
        // 恢复上次所在 Tab（切主题重建后停留在原页面，而不是跳回首页）
        if (currentTabId != binding.bottomNav.selectedItemId) {
            binding.bottomNav.selectedItemId = currentTabId // 触发 listener 完成切换
        } else {
            showTab(binding.viewHome.root, "剪贴板下载器", "")
        }
    }

    private fun showTab(view: View, title: String, subtitle: String) {
        binding.viewHome.root.visibility = if (view === binding.viewHome.root) View.VISIBLE else View.GONE
        binding.viewDownloads.root.visibility = if (view === binding.viewDownloads.root) View.VISIBLE else View.GONE
        binding.viewProfile.root.visibility = if (view === binding.viewProfile.root) View.VISIBLE else View.GONE
        binding.viewLibrary.root.visibility = if (view === binding.viewLibrary.root) View.VISIBLE else View.GONE
        binding.viewSettings.root.visibility = if (view === binding.viewSettings.root) View.VISIBLE else View.GONE
        binding.toolbar.title = title
        binding.toolbar.subtitle = subtitle
    }

    // ==================== 首页 ====================

    private fun setupHome() {
        binding.viewHome.btnPaste.setOnClickListener {
            try {
                val text = readClipboard()
                if (text.isNullOrBlank()) {
                    Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
                } else {
                    binding.viewHome.editLink.setText(text)
                    binding.viewHome.editLink.setSelection(text.length)
                    Toast.makeText(this, "已读取剪贴板内容", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Android 12+ 偶发窗口失焦时读取剪贴板抛 SecurityException
                Toast.makeText(this, "读取剪贴板失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // 解析：只解析不下载，结果显示在下方列表里供勾选
        binding.viewHome.btnParse.setOnClickListener {
            val text = binding.viewHome.editLink.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, "请先粘贴分享链接或文本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            parseAndShowInline(text)
        }

        // 下载：下载勾选中的解析结果（视频直链已按当前所选画质替换）
        binding.viewHome.btnDownload.setOnClickListener {
            val selected = pickAdapter.getChecked()
            if (selected.isEmpty()) {
                Toast.makeText(this, "请先点「解析」，并勾选要下载的作品", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "开始下载 ${selected.size} 个媒体", Toast.LENGTH_SHORT).show()
            binding.bottomNav.selectedItemId = R.id.nav_downloads
            downloadManager.enqueueMediaList(
                selected,
                homeParsedPlatform ?: com.clipdownloader.model.Platform.OTHER,
                homeParsedText
            )
            refreshDownloadsList()
        }

        // 全选/全不选（作品级）
        binding.viewHome.btnParsedAll.setOnClickListener {
            pickAdapter.setAll(pickAdapter.checkedGroupCount() != pickAdapter.groupCount())
        }
        // 音乐条目默认勾不勾，跟设置里的「保存图集背景音乐」走
        pickAdapter.musicCheckedByDefault = { prefs.isSaveBgm() }

        binding.viewHome.recyclerParsed.layoutManager = LinearLayoutManager(this)
        binding.viewHome.recyclerParsed.adapter = pickAdapter
        binding.viewHome.recyclerParsed.isNestedScrollingEnabled = false

        // 首次进入顺手把剪贴板里东西填到输入框（不自动下载，让用户确认）
        val cached = readClipboard()
        if (!cached.isNullOrBlank() && PlatformParser.extractUrl(cached) != null) {
            binding.viewHome.editLink.setText(cached)
        }
    }

    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析并把结果显示在首页内嵌列表里供勾选下载（手动粘贴 / 分享面板进来共用）。
     * 分享进来的链接先填进输入框，再走和点「解析」完全一样的流程，不弹窗。
     */
    private fun parseAndShowInline(text: String) {
        scope.launch {
            val platform = withContext(Dispatchers.IO) { PlatformParser.detectPlatform(text) }
            if (platform == null) {
                Toast.makeText(this@MainActivity, "未识别到支持的平台链接", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(this@MainActivity, "正在解析 ${platform.displayName} 链接…", Toast.LENGTH_SHORT).show()

            // 解析状态行：显示每个解析通道的尝试与结果（保留最近几条）
            val statusLines = ArrayDeque<String>()
            binding.viewHome.tvParseStatus.visibility = View.VISIBLE
            binding.viewHome.tvParseStatus.text = "开始解析…"
            val listener: (String) -> Unit = { line ->
                statusLines.addLast(line)
                while (statusLines.size > 8) statusLines.removeFirst()
                binding.viewHome.tvParseStatus.text = statusLines.joinToString("\n")
            }
            downloadManager.addParseProgressListener(listener)
            parseProgressListener = listener
            val media = withContext(Dispatchers.IO) {
                try {
                    downloadManager.parseOnce(text, platform, withQualities = true)
                } catch (e: Exception) {
                    com.clipdownloader.util.LogFile.e("MainActivity", "parseOnce 异常", e)
                    emptyList()
                }
            }
            downloadManager.removeParseProgressListener(listener)
            com.clipdownloader.util.LogFile.d("MainActivity", "首页解析返回：${media.size} 个媒体")
            if (media.isEmpty()) {
                val err = downloadManager.lastParseError
                if (err.isNotBlank()) {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("解析失败 · 通道明细")
                        .setMessage(err)
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    Toast.makeText(this@MainActivity, "解析失败，未能获取媒体", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            homeParsedPlatform = platform
            homeParsedText = text
            pickAdapter.submitList(media)
            setupQualitySlider(media)
            binding.viewHome.cardParsed.visibility = View.VISIBLE
            binding.viewHome.tvParsedTitle.text =
                "解析到 ${pickAdapter.groupCount()} 个作品 · 共 ${media.size} 个媒体 · 勾选要下载的"
        }
    }

    /**
     * 画质选择滑块：选项 = 本次解析全部视频画质档标签并集（高度升序，左低右高），
     * 默认最高档；切换时各作品左上角体积与媒体行画质/体积即时跟随。
     * 单档/无画质信息时隐藏滑块，视频按各自最高档下载。
     */
    private fun setupQualitySlider(media: List<com.clipdownloader.model.ParsedMedia>) {
        val h = binding.viewHome
        val labels = media.flatMap { it.qualities }.map { it.label }
            .distinct()
            .sortedBy { com.clipdownloader.model.MediaQuality.heightOf(it) }
        if (labels.size < 2) {
            h.layoutQuality.visibility = View.GONE
            pickAdapter.setSelectedQuality(null)
            return
        }
        h.layoutQuality.visibility = View.VISIBLE
        h.sliderQuality.clearOnChangeListeners()
        h.sliderQuality.valueFrom = 0f
        h.sliderQuality.valueTo = (labels.size - 1).toFloat()
        h.sliderQuality.value = (labels.size - 1).toFloat()
        h.sliderQuality.setLabelFormatter { idx -> labels[idx.toInt()] }
        h.tvQualityLabel.text = labels.last()
        pickAdapter.setSelectedQuality(labels.last())
        h.sliderQuality.addOnChangeListener { _, value, _ ->
            val label = labels[value.toInt()]
            h.tvQualityLabel.text = label
            pickAdapter.setSelectedQuality(label)
        }
    }

    /**
     * 处理一条分享/剪贴板文本：
     * - 博主主页链接 → 一律转到「主页下载」栏目粘贴框解析（主页视频只在那里下载）
     * - 普通作品 + preview=false（剪贴板自动）→ 直接全量下载
     * - 普通作品 + preview=true（分享面板）→ 首页填入输入框解析勾选
     */
    fun startDownload(text: String, preview: Boolean = false) {
        scope.launch {
            val platform = withContext(Dispatchers.IO) {
                PlatformParser.detectPlatform(text)
            }
            if (platform == null) {
                Toast.makeText(this@MainActivity, "未识别到支持的平台链接", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 博主主页链接 → 「主页下载」栏目（粘贴框解析后手动下载）。
            // 主页短链（v.douyin.com 等）要先跟随重定向才能识别，否则会被当成单个作品
            val profile = withContext(Dispatchers.IO) {
                PlatformParser.detectProfile(text)
                    ?: if (PlatformParser.extractUrl(text)?.let { PlatformParser.isShortLink(it) } == true) {
                        resolveProfileLink(text)
                    } else {
                        null
                    }
            }
            if (profile != null && profile.first == platform) {
                binding.bottomNav.selectedItemId = R.id.nav_profile
                binding.viewProfile.editProfileLink.setText(text)
                binding.viewProfile.editProfileLink.setSelection(text.length)
                parseAndShowProfile(text)
                return@launch
            }

            if (!preview) {
                // 自动路径：直接全量下载
                Toast.makeText(this@MainActivity, "开始解析 ${platform.displayName} 链接…", Toast.LENGTH_SHORT).show()
                binding.bottomNav.selectedItemId = R.id.nav_downloads
                downloadManager.enqueue(text, platform)
                runOnUiThread { refreshDownloadsList() }
                return@launch
            }

            // 手动路径（分享进来）：回到首页，把链接填进输入框，内嵌列表勾选，不弹窗
            binding.bottomNav.selectedItemId = R.id.nav_home
            binding.viewHome.editLink.setText(text)
            binding.viewHome.editLink.setSelection(text.length)
            parseAndShowInline(text)
        }
    }

    // ==================== 分享入口 ====================

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: return
        if (text.isBlank()) return
        // 消费掉这个 intent：旋转/切主题导致 Activity 重建时 onCreate 会再次拿到
        // 同一个 ACTION_SEND，不清掉会重复解析、冲掉用户已勾选的状态
        intent.action = null
        scope.launch {
            startDownload(text.trim(), preview = true)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // ==================== 下载页 ====================

    private fun setupDownloads() {
        downloadsAdapter = DownloadsAdapter(
            onCancel = { batch -> downloadManager.cancelBatch(batch.batchId) },
            onRemoveBatch = { batch ->
                downloadManager.removeBatch(batch.batchId)
                refreshDownloadsList()
            },
            onRemoveRecord = { record ->
                recordStore.remove(record.batchId)
                // 同批次的已完成条目一并移除，避免记录删除后批次条目重新顶上来
                downloadManager.removeBatch(record.batchId)
                refreshDownloadsList()
            },
            onOpenFile = { path -> openFile(path) },
            onOpenFolder = { path -> openFolder(path) },
            onRedownload = { record -> showRedownloadDialog(record) }
        )

        binding.viewDownloads.recyclerDownloads.layoutManager = LinearLayoutManager(this)
        binding.viewDownloads.recyclerDownloads.adapter = downloadsAdapter
        // 批量条→历史记录条换绑、插入删除时关闭交叉淡入动画，
        // 否则条目切换看起来在反复闪烁
        (binding.viewDownloads.recyclerDownloads.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)
            ?.supportsChangeAnimations = false

        batchListener = { _ ->
            runOnUiThread { scheduleListRefresh() }
        }
        downloadManager.addBatchListener(batchListener!!)

        recordListener = { runOnUiThread { scheduleListRefresh() } }
        recordStore.addListener(recordListener!!)

        // 只清普通下载记录（主页下载记录在「主页下载」栏目里单独清）
        binding.viewDownloads.btnClearHistory.setOnClickListener {
            recordStore.getRecords().filter { !it.isProfile }.forEach { recordStore.remove(it.batchId) }
            downloadManager.getBatches().filter { !it.isActive && !it.isProfile }
                .forEach { downloadManager.removeBatch(it.batchId) }
            refreshDownloadsList()
            Toast.makeText(this, "已清空历史记录", Toast.LENGTH_SHORT).show()
        }

        // 只清普通下载里失败的记录与批次
        binding.viewDownloads.btnClearFailed.setOnClickListener {
            val failed = recordStore.getRecords().filter { !it.isProfile && !it.success }
            if (failed.isEmpty()) {
                Toast.makeText(this, "没有失败记录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            failed.forEach { recordStore.remove(it.batchId) }
            downloadManager.getBatches().filter { !it.isActive && !it.isProfile }
                .filter { b -> failed.any { it.batchId == b.batchId } }
                .forEach { downloadManager.removeBatch(it.batchId) }
            refreshDownloadsList()
            Toast.makeText(this, "已清除 ${failed.size} 条失败记录", Toast.LENGTH_SHORT).show()
        }
    }

    /** 「主页下载」栏目：粘贴框解析（共N/成功M/总大小+画质滑块）→ 下载；列表只显示文字行 */
    private fun setupProfileDownloads() {
        profileAdapter = DownloadsAdapter(
            onCancel = { batch -> downloadManager.cancelBatch(batch.batchId) },
            onRemoveBatch = { batch ->
                downloadManager.removeBatch(batch.batchId)
                refreshDownloadsList()
            },
            onRemoveRecord = { record ->
                recordStore.remove(record.batchId)
                downloadManager.removeBatch(record.batchId)
                refreshDownloadsList()
            },
            onOpenFile = { path -> openFile(path) },
            onOpenFolder = { path -> openFolder(path) },
            onRedownload = { record -> showRedownloadDialog(record) }
        )

        binding.viewProfile.recyclerDownloads.layoutManager = LinearLayoutManager(this)
        binding.viewProfile.recyclerDownloads.adapter = profileAdapter
        (binding.viewProfile.recyclerDownloads.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)
            ?.supportsChangeAnimations = false

        // 剪贴板填入
        binding.viewProfile.btnProfilePaste.setOnClickListener {
            try {
                val text = readClipboard()
                if (text.isNullOrBlank()) {
                    Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
                } else {
                    binding.viewProfile.editProfileLink.setText(text)
                    binding.viewProfile.editProfileLink.setSelection(text.length)
                    Toast.makeText(this, "已读取剪贴板内容", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "读取剪贴板失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // 解析：拉主页作品列表 + 逐作解析（带画质档与体积），显示汇总与滑块
        binding.viewProfile.btnProfileParse.setOnClickListener {
            val text = binding.viewProfile.editProfileLink.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, "请先粘贴博主主页链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            parseAndShowProfile(text)
        }

        // 下载：按当前所选画质下载全部解析成功的作品
        binding.viewProfile.btnProfileDownload.setOnClickListener {
            val platform = profilePlatform
            if (profileWorks.isEmpty() || platform == null) {
                Toast.makeText(this, "请先点「解析」获取主页作品", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val works = profileWorks.map { MediaPickAdapter.applyQuality(it, profileSelectedLabel) }
            Toast.makeText(this, "开始下载 ${profileAuthor} 的 ${profileSuccessWorks} 个作品", Toast.LENGTH_SHORT).show()
            downloadManager.enqueueProfileWorks(works, platform, profileText, profileAuthor, profileAvatar)
            refreshDownloadsList()
        }

        // 抖音登录态：游客只能拿到博主的最新作品，登录后作品接口才允许翻页
        binding.viewProfile.btnDouyinLogin.setOnClickListener {
            if (DouyinWebCookieSeeder.isLoggedIn(DouyinWebCookieSeeder.currentCookie())) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("退出抖音登录")
                    .setMessage("退出后只能以游客身份获取抖音博主的最新作品，确定退出？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("退出") { _, _ ->
                        DouyinWebCookieSeeder.clear(this) {
                            Toast.makeText(this, "已退出抖音登录", Toast.LENGTH_SHORT).show()
                            refreshDouyinLoginButton()
                        }
                    }
                    .show()
            } else {
                DouyinLoginDialog.show(this) { refreshDouyinLoginButton() }
            }
        }
        refreshDouyinLoginButton()

        // 强制重下入参（forceRedownload/overwriteExisting）只由设置页两个开关 +
        // 记录卡片「重下」按钮驱动；主页下载栏不设独立强制重下入口

        binding.viewProfile.btnClearHistory.setOnClickListener {
            recordStore.getRecords().filter { it.isProfile }.forEach { recordStore.remove(it.batchId) }
            downloadManager.getBatches().filter { !it.isActive && it.isProfile }
                .forEach { downloadManager.removeBatch(it.batchId) }
            refreshDownloadsList()
            Toast.makeText(this, "已清空主页下载记录", Toast.LENGTH_SHORT).show()
        }

        // 只清主页下载里失败的记录与批次
        binding.viewProfile.btnClearFailed.setOnClickListener {
            val failed = recordStore.getRecords().filter { it.isProfile && !it.success }
            if (failed.isEmpty()) {
                Toast.makeText(this, "没有失败记录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            failed.forEach { recordStore.remove(it.batchId) }
            downloadManager.getBatches().filter { !it.isActive && it.isProfile }
                .filter { b -> failed.any { it.batchId == b.batchId } }
                .forEach { downloadManager.removeBatch(it.batchId) }
            refreshDownloadsList()
            Toast.makeText(this, "已清除 ${failed.size} 条失败记录", Toast.LENGTH_SHORT).show()
        }
    }

    /** 抖音登录入口按钮文案随登录态变化 */
    private fun refreshDouyinLoginButton() {
        binding.viewProfile.btnDouyinLogin.text =
            if (DouyinWebCookieSeeder.isLoggedIn(DouyinWebCookieSeeder.currentCookie())) "已登录抖音 · 退出登录"
            else "登录抖音（获取全部作品）"
    }

    /** 记录卡片「重下」（仅失败记录显示）：直接重新解析下载，覆盖与否由设置开关决定，不弹窗 */
    private fun showRedownloadDialog(record: DownloadRecord) {
        downloadManager.enqueueRedownload(record)
        refreshDownloadsList()
    }

    /**
     * 「主页下载」栏解析主页链接：支持 B站 / 抖音 / 快手（含 b23.tv、v.douyin.com、
     * v.kuaishou.com 等短链，自动展开）。拉作品列表（跳过已下载的），补齐直链与体积，
     * 完成后显示「共N/成功M/总大小」与画质滑块。
     */
    private fun parseAndShowProfile(text: String) {
        if (profileParsing) {
            Toast.makeText(this, "正在解析主页作品，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val h = binding.viewProfile
            h.tvProfileStatus.visibility = View.VISIBLE
            h.tvProfileStatus.text = "正在识别主页链接…"
            val profile = withContext(Dispatchers.IO) { resolveProfileLink(text) }
            if (profile == null) {
                Toast.makeText(this@MainActivity, "未识别到博主主页链接（支持 B站 / 抖音 / 快手的主页地址或分享短链）", Toast.LENGTH_LONG).show()
                return@launch
            }
            val platform = profile.first

            profileParsing = true
            h.btnProfileParse.isEnabled = false
            // 状态行：解析计数 + 最近几条通道明细
            val statusLines = ArrayDeque<String>()
            fun renderStatus(counter: String?) {
                h.tvProfileStatus.text = buildString {
                    counter?.let { append(it).append('\n') }
                    statusLines.takeLast(4).forEach { append(it).append('\n') }
                }.trim()
            }
            val listener: (String) -> Unit = { line ->
                statusLines.addLast(line)
                while (statusLines.size > 8) statusLines.removeFirst()
                renderStatus(null)
            }
            downloadManager.addParseProgressListener(listener)
            try {
                // 列表拉取：接口风控是间歇性的，失败后退避重试（共 3 次）
                renderStatus("正在获取 ${platform.displayName} 主页作品列表…")
                var listing: ProfileListing? = null
                var listError = ""
                var douyinParser: com.clipdownloader.download.DouyinUserParser? = null
                for (attempt in 1..3) {
                    listing = withContext(Dispatchers.IO) {
                        when (platform) {
                            com.clipdownloader.model.Platform.BILIBILI ->
                                com.clipdownloader.download.BilibiliUserParser().listVideos(profile.second)
                                    ?.let { ProfileListing(it.author, it.avatar, it.total, it.videos) }
                            com.clipdownloader.model.Platform.DOUYIN -> {
                                // 抖音通道需要 Activity 创建 WebView 取网页身份（游客或登录 cookie）
                                val p = com.clipdownloader.download.DouyinUserParser()
                                douyinParser = p
                                p.listVideos(profile.second, activity = this@MainActivity)
                                    ?.let { ProfileListing(it.author, it.avatar, it.total, it.works) }
                            }
                            com.clipdownloader.model.Platform.KUAISHOU -> {
                                val p = com.clipdownloader.download.KuaishouUserParser()
                                val r = p.listVideos(profile.second)
                                if (r == null) listError = p.lastError
                                r?.let { ProfileListing(it.author, it.avatar, it.total, it.works) }
                            }
                            else -> null
                        }
                    }
                    if (listing != null) break
                    if (attempt < 3) {
                        val waitMs = attempt * 3000L
                        renderStatus("列表拉取失败，${waitMs / 1000}s 后重试（${attempt + 1}/3）")
                        kotlinx.coroutines.delay(waitMs)
                    }
                }
                if (listing == null) {
                    // 优先显示解析器给出的具体原因（如无 WebView / 网关拒绝），再退回通用风控提示
                    val hint = listOf(listError, douyinParser?.lastError.orEmpty())
                        .firstOrNull { it.isNotBlank() }
                        ?: "接口风控，请稍后重试或更换网络"
                    h.tvProfileStatus.text = "获取${platform.displayName}主页列表失败（$hint）"
                    Toast.makeText(
                        this@MainActivity,
                        "获取${platform.displayName}主页列表失败（$hint）",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 增量：跳过已下载过的作品（记录 + 本地文件夹双保险——
                // 记录被清掉后，文件名里内置的作品标识仍能认出本地已有文件）
                val doneIds = prefs.getBloggerDone()
                val existingMarkers = downloadManager.existingMarkers()
                val todo = listing.videos.filter {
                    it.mediaId !in doneIds && downloadManager.workMarkerOf(it) !in existingMarkers
                }
                // 作品数按作品 ID 去重：实况图在列表里拆成「图片 + 视频」两个条目，不能拿条目数当作品数
                fun workKeyOf(m: com.clipdownloader.model.ParsedMedia) =
                    m.mediaId.ifBlank { m.rawShareText.ifBlank { m.title } }
                val workCount = listing.videos.map { workKeyOf(it) }.distinct().size
                profileTotalWorks = workCount
                profileSkippedWorks = workCount - todo.map { workKeyOf(it) }.distinct().size
                if (todo.isEmpty()) {
                    h.tvProfileStatus.text = "${listing.author}：没有新作品（已下载 $workCount 个）"
                    h.cardProfileResult.visibility = View.VISIBLE
                    h.tvProfileSummary.text = "共 $workCount 个作品 · 已全部下载过 · 没有新作品"
                    h.layoutProfileQuality.visibility = View.GONE
                    profileWorks = emptyList()
                    profileAuthor = listing.author
                    profilePlatform = platform
                    profileText = text
                    return@launch
                }

                // 逐作补齐：列表已带直链的直接用（抖音/快手）；缺直链的走
                // DownloadManager.parseProfileWorks——该平台全部可用通道轮换 +
                // 失败自动重试 + 风控指数退避（串行推进，避免并发触发风控）
                renderStatus("解析作品 0/${todo.size}")
                val (expanded, successCount) = downloadManager.parseProfileWorks(
                    todo, platform
                ) { attempted, ok, total ->
                    scope.launch { renderStatus("解析作品 $attempted/$total · 成功 $ok") }
                }

                // 有直链但缺体积的（快手 photoUrl 等）并行 HEAD 探测补齐
                val works = withContext(Dispatchers.IO) { downloadManager.probeSizes(expanded) }

                profileWorks = works
                profileAuthor = listing.author
                profileAvatar = listing.avatar
                profilePlatform = platform
                profileText = text
                profileSuccessWorks = successCount
                profileLabels = profileWorks.flatMap { it.qualities }.map { it.label }
                    .distinct()
                    .sortedBy { com.clipdownloader.model.MediaQuality.heightOf(it) }
                profileSelectedLabel = profileLabels.lastOrNull()

                if (profileWorks.isEmpty()) {
                    h.tvProfileStatus.text = "解析失败：${todo.size} 个作品全部解析失败"
                    h.cardProfileResult.visibility = View.GONE
                    return@launch
                }

                // 画质滑块（与首页一致：左低右高，默认最高档）
                if (profileLabels.size >= 2) {
                    h.layoutProfileQuality.visibility = View.VISIBLE
                    h.sliderProfileQuality.clearOnChangeListeners()
                    h.sliderProfileQuality.valueFrom = 0f
                    h.sliderProfileQuality.valueTo = (profileLabels.size - 1).toFloat()
                    h.sliderProfileQuality.value = (profileLabels.size - 1).toFloat()
                    h.sliderProfileQuality.setLabelFormatter { idx -> profileLabels[idx.toInt()] }
                    h.tvProfileQualityLabel.text = profileLabels.last()
                    h.sliderProfileQuality.addOnChangeListener { _, value, _ ->
                        profileSelectedLabel = profileLabels[value.toInt()]
                        h.tvProfileQualityLabel.text = profileSelectedLabel
                        refreshProfileSummary()
                    }
                } else {
                    h.layoutProfileQuality.visibility = View.GONE
                }
                h.cardProfileResult.visibility = View.VISIBLE
                // 抖音游客态只返回首页作品：明确提示登录后才能拿全量
                val guestNote = if (platform == Platform.DOUYIN && douyinParser?.guestLimited == true)
                    "\n（游客身份仅含最新作品；点「登录抖音」后重新解析可获取全部）" else ""
                h.tvProfileStatus.text = "解析完成 · ${listing.author}$guestNote"
                refreshProfileSummary()
            } finally {
                downloadManager.removeParseProgressListener(listener)
                profileParsing = false
                h.btnProfileParse.isEnabled = true
            }
        }
    }

    /** 「主页下载」栏解析结果（三平台统一结构） */
    private data class ProfileListing(
        val author: String,
        val avatar: String,
        val total: Int,
        val videos: List<com.clipdownloader.model.ParsedMedia>
    )

    /**
     * 主页链接识别：先直接匹配；未命中则跟随分享短链重定向（b23.tv /
     * v.douyin.com / v.kuaishou.com 等，最多 5 跳），每一跳 URL 都尝试匹配。
     */
    private suspend fun resolveProfileLink(text: String): Pair<com.clipdownloader.model.Platform, String>? {
        PlatformParser.detectProfile(text)?.let { return it }
        var url = PlatformParser.extractUrl(text) ?: return null
        val noRedirect = okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        repeat(5) {
            val next = withContext(Dispatchers.IO) {
                try {
                    val ua = if (url.contains("douyin.com")) MOBILE_UA else DESKTOP_UA
                    noRedirect.newCall(
                        okhttp3.Request.Builder().url(url).header("User-Agent", ua).build()
                    ).execute().use { resp ->
                        when {
                            resp.code in 300..399 -> resp.header("Location")
                            resp.isSuccessful -> resp.request.url.toString() // 已是最终页
                            else -> null
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            } ?: return null
            PlatformParser.detectProfile(next)?.let { return it }
            if (next == url) return null
            url = next
        }
        return null
    }

    /** 主页下载栏汇总行：共N/成功M/跳过K/总大小（总大小随所选画质实时变化） */
    private fun refreshProfileSummary() {
        if (profileWorks.isEmpty()) return
        val h = binding.viewProfile
        var total = 0L
        var hasUnknown = false
        profileWorks.forEach { m ->
            val sz = MediaPickAdapter.displaySize(m, profileSelectedLabel)
            if (sz > 0) total += sz else hasUnknown = true
        }
        val skipped = if (profileSkippedWorks > 0) " · 已下载过 ${profileSkippedWorks}" else ""
        h.tvProfileSummary.text =
            "共 ${profileTotalWorks} 个作品$skipped · 解析成功 ${profileSuccessWorks} · " +
                "总大小 ${if (hasUnknown) "≥ " else ""}${DownloadsAdapter.formatBytes(total)}"
    }

    private fun refreshDownloadsList() {
        if (!::downloadsAdapter.isInitialized) return
        try {
            refreshDownloadsListInner()
        } catch (e: Exception) {
            // 任何隐藏异常只记日志，不能中断轮询链路（否则列表/统计从此不再刷新）
            com.clipdownloader.util.LogFile.e("MainActivity", "刷新下载列表异常", e)
        }
    }

    private fun refreshDownloadsListInner() {
        val batches = downloadManager.getBatches()
        val records = recordStore.getRecords()

        // 按「主页下载」标记把批量与记录拆到各自栏目
        fun buildItems(profile: Boolean): MutableList<Any> {
            val items = mutableListOf<Any>()
            val activeIds = batches.filter { it.isActive && it.isProfile == profile }
                .map { it.batchId }.toSet()
            batches.filter { it.isProfile == profile && (it.isActive || records.none { r -> r.batchId == it.batchId }) }
                .forEach { items.add(it) }
            records.filter { it.isProfile == profile && it.batchId !in activeIds }
                .forEach { items.add(it) }
            return items
        }

        val items = buildItems(false)
        val profileItems = buildItems(true)
        if (::downloadsAdapter.isInitialized) downloadsAdapter.setItems(items)
        if (::profileAdapter.isInitialized) profileAdapter.setItems(profileItems)

        // 「下载」栏目
        val hasItems = items.isNotEmpty()
        binding.viewDownloads.recyclerDownloads.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.viewDownloads.layoutEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
        binding.viewDownloads.btnClearHistory.visibility =
            if (records.any { !it.isProfile }) View.VISIBLE else View.GONE

        val active = batches.count { !it.isProfile && it.isActive }
        val done = records.count { !it.isProfile && it.success }
        binding.viewDownloads.tvSummary.text = when {
            active > 0 -> "下载中 $active 个 · 已完成 $done 个"
            done > 0 -> "已完成 $done 个下载"
            else -> "暂无下载记录"
        }

        // 「主页下载」栏目
        val hasProfileItems = profileItems.isNotEmpty()
        binding.viewProfile.recyclerDownloads.visibility = if (hasProfileItems) View.VISIBLE else View.GONE
        binding.viewProfile.layoutEmpty.visibility = if (hasProfileItems) View.GONE else View.VISIBLE
        binding.viewProfile.btnClearHistory.visibility =
            if (records.any { it.isProfile }) View.VISIBLE else View.GONE

        val pActive = batches.count { it.isProfile && it.isActive }
        val pDone = records.count { it.isProfile && it.success }
        binding.viewProfile.tvSummary.text = when {
            pActive > 0 -> "主页下载中 $pActive 个 · 已完成 $pDone 个"
            pDone > 0 -> "已完成 $pDone 个主页下载"
            else -> "暂无主页下载记录"
        }

        // 作品库跟着记录与批次变化刷新（输入没变时内部直接跳过）
        refreshLibrary(force = false)
    }

    /** 打开已下载的文件（相册 / 播放器），实现在 [com.clipdownloader.util.FileActions] */
    private fun openFile(path: String) = FileActions.open(this, path)

    /** 打开文件所在的文件夹，实现在 [com.clipdownloader.util.FileActions] */
    private fun openFolder(path: String) = FileActions.openFolder(this, path)

    // ==================== 作品库 ====================

    /** 「作品库」栏目：已下载的作品按封面卡片墙排列，点开进作品详情 */
    private fun setupLibrary() {
        libraryAdapter = LibraryAdapter(onOpen = { WorkDetailActivity.start(this, it.token) })
        binding.viewLibrary.recyclerLibrary.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(this, 2)
        binding.viewLibrary.recyclerLibrary.adapter = libraryAdapter
        (binding.viewLibrary.recyclerLibrary.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)
            ?.supportsChangeAnimations = false
    }

    /**
     * 重新归并作品库。归并要扫作品目录（补上记录里没登记的封面等文件），放到 IO 线程；
     * 下载页的轮询刷新也会调到这里，所以记录与批次都没变、也不是刚切到这个页签时直接跳过。
     *
     * @param force 刚切到「作品库」页签：不管签名都重新归并（用户可能在文件管理器里动过文件）
     */
    private fun refreshLibrary(force: Boolean) {
        if (!::libraryAdapter.isInitialized) return
        val signature = recordStore.getRecords().joinToString(",") { "${it.batchId}:${it.itemCount}" } +
            "|" + downloadManager.getBatches().joinToString(",") { "${it.batchId}:${it.phase}" }
        if (!force && signature == libraryInputSignature) return
        libraryInputSignature = signature
        libraryJob?.cancel()
        libraryJob = scope.launch {
            val works = withContext(Dispatchers.IO) {
                LibraryWorks.build(recordStore.getRecords(), File(prefs.getSavePath()))
            }
            libraryAdapter.submit(works)
            val has = works.isNotEmpty()
            val l = binding.viewLibrary
            l.recyclerLibrary.visibility = if (has) View.VISIBLE else View.GONE
            l.layoutLibraryEmpty.visibility = if (has) View.GONE else View.VISIBLE
            l.tvLibrarySummary.text = if (has) {
                "共 ${works.size} 个作品 · ${works.sumOf { it.files.size }} 个文件"
            } else "作品库还是空的"
        }
    }

    // ==================== 设置页 ====================

    private fun setupSettings() {
        val s = binding.viewSettings

        // 保存路径
        s.settingSavePath.tvSettingTitle.text = "保存路径"
        s.settingSavePath.tvSettingSummary.text = prefs.getSavePath()
        s.settingSavePath.root.setOnClickListener {
            Toast.makeText(this, "默认保存到: ${prefs.getSavePath()}", Toast.LENGTH_SHORT).show()
        }

        // 仅 WiFi 下载
        s.switchWifiOnly.isChecked = prefs.isWifiOnly()
        s.switchWifiOnly.setOnCheckedChangeListener { _, checked ->
            prefs.setWifiOnly(checked)
        }

        // 打开时自动读剪贴板
        s.switchAutoClipboard.isChecked = prefs.isAutoReadClipboardOnLaunch()
        s.switchAutoClipboard.setOnCheckedChangeListener { _, checked ->
            prefs.setAutoReadClipboardOnLaunch(checked)
            if (!checked) {
                consumedClips.clear()
                prefs.clearConsumedClips()  // 清消费记录（持久化 + 内存），方便切换后重新触发
            }
        }

        // 下载完成通知
        s.switchNotify.isChecked = prefs.isShowDownloadResult()
        s.switchNotify.setOnCheckedChangeListener { _, checked ->
            prefs.setShowDownloadResult(checked)
        }

        // 重复视频重新下载：开=遇到本地已有的重复作品重新下载（此时才显示「覆盖旧文件」开关）；
        // 关=跳过不下载。覆盖开=删旧文件与旧记录，关=另存新文件
        fun refreshRedownloadSwitches() {
            s.layoutRedownloadOverwrite.visibility =
                if (prefs.isRedownloadDuplicates()) View.VISIBLE else View.GONE
        }
        s.switchRedownloadDuplicates.isChecked = prefs.isRedownloadDuplicates()
        s.switchRedownloadDuplicates.setOnCheckedChangeListener { _, checked ->
            prefs.setRedownloadDuplicates(checked)
            refreshRedownloadSwitches()
        }
        s.switchRedownloadOverwrite.isChecked = prefs.isRedownloadOverwrite()
        s.switchRedownloadOverwrite.setOnCheckedChangeListener { _, checked ->
            prefs.setRedownloadOverwrite(checked)
        }
        refreshRedownloadSwitches()

        // 主题切换（跟随系统 / 浅色 / 深色）
        val themeMode = prefs.getThemeMode()
        (when (themeMode) {
            1 -> s.btnThemeLight; 2 -> s.btnThemeDark; else -> s.btnThemeSystem
        }).isChecked = true
        fun setThemeModeChecked(mode: Int) {
            s.btnThemeSystem.isChecked = mode == 0
            s.btnThemeLight.isChecked = mode == 1
            s.btnThemeDark.isChecked = mode == 2
        }
        s.themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btn_theme_light -> 1
                R.id.btn_theme_dark -> 2
                else -> 0
            }
            prefs.setThemeMode(mode)
            setThemeModeChecked(mode)
            com.clipdownloader.App.applyTheme(mode)
        }

        // 保存到平台独立文件夹
        s.switchPlatformMaster.isChecked = prefs.isPlatformFoldersEnabled()
        s.switchPlatformMaster.setOnCheckedChangeListener { _, checked ->
            prefs.setPlatformFoldersEnabled(checked)
        }

        // 图集背景音乐（默认不存）
        s.switchSaveBgm.isChecked = prefs.isSaveBgm()
        s.switchSaveBgm.setOnCheckedChangeListener { _, checked ->
            prefs.setSaveBgm(checked)
        }

        // 微博解析端开关（仅首选通道=本地解析时显示）：开=PC 端，关=移动端
        s.switchWeiboEndpoint.isChecked = prefs.isWeiboPcEndpoint()
        s.switchWeiboEndpoint.setOnCheckedChangeListener { _, checked ->
            prefs.setWeiboPcEndpoint(checked)
        }

        // 首选解析通道：每个平台独立下拉（本地解析 / iiiLab / 云端通道）
        refreshAllChannelSpinners()

        // 云解析通道统一管理（独立设置行：新增/删除/编辑支持平台）
        s.btnManageChannels.setOnClickListener { showChannelEditor() }

        // 权限管理
        s.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionPrompted = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "当前系统版本无需通知权限", Toast.LENGTH_SHORT).show()
            }
        }
        s.btnGrantMedia.setOnClickListener {
            requestStoragePermissions()
        }

        // 导出日志：直接以纯文字分享，用于排查本地与云端解析失败原因
        s.btnExportLog.setOnClickListener { exportLog() }

        // 清除日志：清空日志文件（导出旁，带确认）
        s.btnClearLog.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("清除日志")
                .setMessage("确定清空全部日志？清除后无法恢复。")
                .setPositiveButton("清除") { _, _ ->
                    com.clipdownloader.util.LogFile.clear()
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 版本号跟随构建版本（build.gradle versionName），不再写死
        s.tvAppVersion.text = "ClipDownloader v${BuildConfig.VERSION_NAME}"

        refreshPermissionStatus()
    }

    /** 把日志文本通过系统分享面板直接以纯文字导出（不发 .txt 附件，排查解析失败用） */
    private fun exportLog() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { com.clipdownloader.util.LogFile.getLogText() }
            try {
                // 只走 EXTRA_TEXT 纯文字：带 EXTRA_STREAM 时微信等会把分享当成文件附件发 .txt；
                // 超长时截断保留最近部分，避免撑爆 Binder 缓冲（TransactionTooLargeException）
                val maxChars = 100_000
                val share = if (text.length > maxChars)
                    "（日志过长，已截断为最近部分）\n" + text.takeLast(maxChars)
                else text
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ClipDownloader 日志")
                    putExtra(Intent.EXTRA_TEXT, share)
                }
                startActivity(Intent.createChooser(intent, "导出日志"))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "导出日志失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 首选解析通道下拉（每个平台独立一个，按支持通道装配） ====================

    private fun spinnerPickListener(values: List<String>, onPick: (String) -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (position in values.indices) onPick(values[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

    /** 按平台重建全部首选解析通道下拉（国内 5+其他 + 海外 6+其他） */
    private fun refreshAllChannelSpinners() {
        binding.viewSettings.containerDomesticChannels.removeAllViews()
        binding.viewSettings.containerOverseasChannels.removeAllViews()
        for (platform in Platform.values()) {
            if (platform == Platform.OTHER) continue
            val key = platform.name.lowercase()
            val localLabel = when {
                key !in PreferencesManager.LOCAL_CAPABLE_KEYS -> null
                platform == Platform.TIKTOK -> "本地（tikwm）"
                platform == Platform.YOUTUBE -> "本地（InnerTube）"
                else -> "本地解析"
            }
            val summary = when (platform) {
                Platform.TIKTOK -> "本地=tikwm 免费接口；选中的通道优先，失败自动回落其余通道"
                Platform.YOUTUBE -> "本地=手机直连 InnerTube（含 PO token）；选中的通道优先，失败回落其余通道"
                Platform.INSTAGRAM, Platform.TWITTER, Platform.FACEBOOK, Platform.PINTEREST ->
                    "选中的通道优先，失败回落 iiiLab 与其他云端通道"
                else -> "选中的通道优先，失败自动回落其余通道（本地解析兜底）"
            }
            val container = if (platform.isOverseas)
                binding.viewSettings.containerOverseasChannels
            else
                binding.viewSettings.containerDomesticChannels
            container.addView(buildPlatformChannelRow(platform.displayName, key, localLabel, summary))
        }
        // 未知链接兜底（识别不出的平台按 OTHER 走国外调度，国内其他首选仅云端通道参与）
        binding.viewSettings.containerDomesticChannels.addView(
            buildPlatformChannelRow(
                "国内其他", PreferencesManager.ChannelPlatforms.DOMESTIC_OTHER, null,
                "无法识别的国内链接首选通道，失败自动回落其余通道"
            )
        )
        binding.viewSettings.containerOverseasChannels.addView(
            buildPlatformChannelRow(
                "国外其他", PreferencesManager.ChannelPlatforms.OVERSEAS_OTHER, null,
                "无法识别的国外链接首选通道，失败回落 iiiLab 与其他云端通道"
            )
        )
        refreshWeiboEndpointVisibility()
    }

    /**
     * 平台通道下拉：固定项（本地解析[仅本地支持的平台]/iiiLab，按勾选过滤）
     * + 支持该平台 key 的云端通道（url 为值，名字为显示）
     */
    private fun buildPlatformChannelRow(
        title: String,
        key: String,
        localLabel: String?,
        summary: String
    ): View {
        val row = ItemPlatformChannelBinding.inflate(layoutInflater)
        row.tvPlatformChannelTitle.text = title
        row.tvPlatformChannelSummary.text = summary

        val fixed = buildList {
            if (localLabel != null) add("local" to localLabel)
            if (key in prefs.getIiiLabPlatforms()) add("iiilab" to "iiiLab")
        }
        val channels = prefs.getCloudChannels().filter { it.platforms.isEmpty() || key in it.platforms }
        val values = fixed.map { it.first } + channels.map { it.url }
        val labels = fixed.map { it.second } + channels.map { channelLabel(it) }
        row.spinnerPlatformChannel.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        row.spinnerPlatformChannel.setSelection(
            values.indexOfFirst { it == prefs.getPlatformChannel(key) }.coerceAtLeast(0), false
        )
        row.spinnerPlatformChannel.onItemSelectedListener =
            spinnerPickListener(values) {
                prefs.setPlatformChannel(key, it)
                // 微博选回本地解析时需立刻恢复「微博解析端」开关行
                if (key == "weibo") refreshWeiboEndpointVisibility()
            }
        return row.root
    }

    /** 微博解析端行：仅微博首选通道=本地解析时显示 */
    private fun refreshWeiboEndpointVisibility() {
        binding.viewSettings.layoutWeiboEndpoint.visibility =
            if (prefs.getPlatformChannel("weibo") == PreferencesManager.CHANNEL_LOCAL)
                View.VISIBLE else View.GONE
    }

    private fun channelLabel(c: PreferencesManager.CloudChannel): String {
        return c.name.ifBlank {
            try { java.net.URI(c.url).host ?: c.url.take(40) } catch (_: Exception) { c.url.take(40) }
        }
    }

    /** 云解析通道统一管理：列表（点击编辑 / 删除）+「新增通道」弹窗（通道信息+支持平台） */
    private fun showChannelEditor() {
        val ctx = this
        val channels = prefs.getCloudChannels().toMutableList()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        val scroll = android.widget.ScrollView(ctx)
        val listLayout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        fun rebuild() {
            listLayout.removeAllViews()
            // 内置通道：iiiLab（协议内置，地址不可改；支持平台可配置，点击编辑）
            listLayout.addView(android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                isClickable = true
                setOnClickListener { showIiiLabPlatformsDialog { rebuild() } }
                addView(android.widget.TextView(ctx).apply {
                    text = "iiiLab（内置）"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#FF1B6EF3"))
                })
                addView(android.widget.TextView(ctx).apply {
                    val set = prefs.getIiiLabPlatforms()
                    text = "支持：" + when {
                        set.isEmpty() -> "无（已全部停用）"
                        else -> set.joinToString(" · ") { PreferencesManager.ChannelPlatforms.labelOf(it) }
                    }
                    textSize = 11f
                    setTextColor(0xFF666666.toInt())
                })
            })
            channels.forEachIndexed { i, c ->
                val item = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(0, 12, 0, 12)
                    isClickable = true
                    // 点击条目 → 编辑通道信息与支持平台
                    setOnClickListener {
                        showChannelFormDialog(c) { updated ->
                            channels[i] = updated
                            rebuild()
                        }
                    }
                }
                val head = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                head.addView(android.widget.TextView(ctx).apply {
                    text = channelLabel(c)
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#FF1B6EF3"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                head.addView(android.widget.Button(ctx).apply {
                    text = "删除"
                    textSize = 12f
                    setOnClickListener {
                        channels.removeAt(i)
                        rebuild()
                    }
                })
                item.addView(head)
                item.addView(android.widget.TextView(ctx).apply {
                    text = "支持：${PreferencesManager.ChannelPlatforms.summary(c.platforms)}"
                    textSize = 11f
                    setTextColor(0xFF666666.toInt())
                })
                item.addView(android.widget.TextView(ctx).apply {
                    text = c.url
                    textSize = 10f
                    setTextColor(0xFF888888.toInt())
                    maxLines = 2
                })
                listLayout.addView(item)
            }
            if (channels.isEmpty()) {
                listLayout.addView(android.widget.TextView(ctx).apply {
                    text = "暂无通道（保存后将恢复默认）"
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                })
            }
        }
        rebuild()

        val btnAdd = android.widget.Button(ctx).apply {
            text = "新增通道"
            setOnClickListener {
                showChannelFormDialog(null) { ch ->
                    channels.add(ch)
                    rebuild()
                }
            }
        }
        container.addView(listLayout)
        container.addView(btnAdd)
        scroll.addView(container)

        fun refreshAllChannelSpinners() = this@MainActivity.refreshAllChannelSpinners()

        android.app.AlertDialog.Builder(this)
            .setTitle("云解析通道（国内/国外统一管理）")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                if (channels.isEmpty()) {
                    Toast.makeText(this, "至少保留一个通道，已恢复默认", Toast.LENGTH_SHORT).show()
                }
                prefs.setCloudChannels(channels)
                refreshAllChannelSpinners()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                prefs.setCloudChannels(emptyList())
                refreshAllChannelSpinners()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 平台勾选区（全选 + 国内/国外分组 + 两个「其他」），加到 root 里。
     * 返回 (全选框, key→勾选框)；勾选状态由调用方初始化。
     */
    private fun addPlatformCheckboxes(
        root: android.widget.LinearLayout
    ): Pair<android.widget.CheckBox, LinkedHashMap<String, android.widget.CheckBox>> {
        val ctx = this
        fun header(title: String) = android.widget.TextView(ctx).apply {
            text = title
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#FF1B6EF3"))
            setPadding(0, 16, 0, 4)
        }
        val allCheck = android.widget.CheckBox(ctx).apply { text = "支持平台：全选" }
        val boxes = LinkedHashMap<String, android.widget.CheckBox>()
        fun addOption(key: String, label: String) {
            android.widget.CheckBox(ctx).apply {
                text = label
                boxes[key] = this
                root.addView(this)
            }
        }

        root.addView(allCheck)
        root.addView(header("国内平台"))
        PreferencesManager.ChannelPlatforms.DOMESTIC.forEach { (k, v) -> addOption(k, v) }
        addOption(PreferencesManager.ChannelPlatforms.DOMESTIC_OTHER, "国内其他")
        root.addView(header("国外平台"))
        PreferencesManager.ChannelPlatforms.OVERSEAS.forEach { (k, v) -> addOption(k, v) }
        addOption(PreferencesManager.ChannelPlatforms.OVERSEAS_OTHER, "国外其他")

        // 全选用 Click 监听：程序设置 isChecked（单项联动刷新全选态）不会触发它，
        // 避免「取消一个单项 → 全选被置否 → 全选监听又清空所有项」的回环。
        // 注意必须先把目标值取出再用：循环里设置单项会触发单项监听器改写
        // allCheck.isChecked（中途有未勾选项时被置 false），若循环内每次重读
        // allCheck.isChecked，第二个起的单项就会被错误地设为 false（点全选只多选一个）。
        allCheck.setOnClickListener {
            val target = allCheck.isChecked
            boxes.values.forEach { cb -> cb.isChecked = target }
        }
        boxes.values.forEach { cb ->
            cb.setOnCheckedChangeListener { _, _ ->
                allCheck.isChecked = boxes.values.all { it.isChecked }
            }
        }
        return allCheck to boxes
    }

    /** iiiLab 内置通道：设置支持平台（国内+国外都可选；保存后立即生效并刷新下拉） */
    private fun showIiiLabPlatformsDialog(onSaved: () -> Unit) {
        val ctx = this
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        root.addView(android.widget.TextView(ctx).apply {
            text = "iiiLab 是内置解析通道（协议内置、地址不可改）。勾选的平台才会走 iiiLab；" +
                "全部取消 = 所有平台停用 iiiLab。保存后立即生效。"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 8)
        })
        val (allCheck, boxes) = addPlatformCheckboxes(root)
        val current = prefs.getIiiLabPlatforms()
        boxes.forEach { (k, cb) -> cb.isChecked = k in current }
        allCheck.isChecked = boxes.values.all { it.isChecked }

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }
        android.app.AlertDialog.Builder(this)
            .setTitle("iiiLab（内置）· 支持平台")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                prefs.setIiiLabPlatforms(boxes.filterValues { it.isChecked }.keys)
                refreshAllChannelSpinners()
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 通道信息表单弹窗（新增/编辑共用）：名称、地址、支持平台勾选（全选 + 国内/国外分组 + 其他） */
    private fun showChannelFormDialog(
        initial: PreferencesManager.CloudChannel?,
        onSave: (PreferencesManager.CloudChannel) -> Unit
    ) {
        val ctx = this
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        val inputName = android.widget.EditText(ctx).apply {
            hint = "名称（可选，如：MaxHelper）"
            isSingleLine = true
            initial?.name?.takeIf { it.isNotBlank() }?.let { setText(it) }
        }
        val inputUrl = android.widget.EditText(ctx).apply {
            hint = "https://your-api/parse （以 ?url= 拼接分享链接）"
            isSingleLine = true
            initial?.url?.let { setText(it) }
        }
        root.addView(inputName)
        root.addView(inputUrl)

        val (allCheck, boxes) = addPlatformCheckboxes(root)
        // 初始勾选：编辑按已有配置（空集=旧数据视为全选）；新增默认全选
        val selected = initial?.platforms?.takeIf { it.isNotEmpty() }
        boxes.forEach { (k, cb) -> cb.isChecked = selected?.let { k in it } ?: true }
        allCheck.isChecked = boxes.values.all { it.isChecked }

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }

        android.app.AlertDialog.Builder(this)
            .setTitle(if (initial == null) "新增云解析通道" else "编辑云解析通道")
            .setView(scroll)
            .setPositiveButton("确定") { _, _ ->
                val url = inputUrl.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http")) {
                    Toast.makeText(this, "未保存：地址需以 http 开头", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val name = inputName.text?.toString()?.trim().orEmpty().ifBlank {
                    try { java.net.URI(url).host ?: url.take(20) } catch (_: Exception) { url.take(20) }
                }
                onSave(
                    PreferencesManager.CloudChannel(
                        name = name,
                        url = url,
                        note = initial?.note ?: "自定义通道",
                        platforms = boxes.filterValues { it.isChecked }.keys
                    )
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshPermissionStatus() {
        val s = binding.viewSettings
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        s.tvNotificationStatus.text = if (notifGranted) "已授予" else "未授予"
        s.tvMediaStatus.text = if (hasStoragePermission()) "已授予" else "未授予（无法保存文件）"
    }

    private fun hasStoragePermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT > Build.VERSION_CODES.Q ->
                true // Android 11+ 分区存储，无需显式申请
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                // Android 10：requestLegacyExternalStorage 仍需 WRITE_EXTERNAL_STORAGE，
                // 与 requestStoragePermissions() 的申请列表保持一致，不能一律报已授予
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            else ->
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 首次启动：先弹通知权限，结果回来后再弹媒体权限（串行，避免两个弹窗叠一起） */
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionPrompted = true
                pendingStoragePrompt = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                // 媒体权限等通知弹窗结果回来后由 launcher 回调拉起
                return
            }
        }
        requestStoragePermissions()
    }

    /** 申请媒体/存储权限（设置页「授权」按钮与首次启动共用） */
    private fun requestStoragePermissions() {
        val storagePerms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            storagePerms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            storagePerms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePerms.add(Manifest.permission.READ_MEDIA_IMAGES)
            storagePerms.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        if (storagePerms.isNotEmpty()) {
            val toRequest = storagePerms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (toRequest.isNotEmpty()) {
                storagePermissionLauncher.launch(toRequest.toTypedArray())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 只在「真正从后台回到前台」后的第一次获焦时检查剪贴板。
        // pendingClipboardCheck 由 ProcessLifecycleOwner.onStart（前台进入）/ 冷启动置位，
        // 通知栏下拉上滑、Dialog 开关等瞬时焦点变化不会置位，因此不会重复触发下载。
        // Android 12+ 要求 App 拥有输入焦点才能 getPrimaryClip()，所以读剪贴板放在获焦回调里。
        // 走合并刷新：弹窗开关、下拉通知栏等焦点变化很频繁，直接全量刷新会闪
        if (::binding.isInitialized && hasFocus) scheduleListRefresh()
        if (hasFocus && pendingClipboardCheck) {
            pendingClipboardCheck = false
            autoDownloadFromClipboard()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshPermissionStatus()
        // 刚从作品详情 / 相册 / 分享面板回来：磁盘上的文件可能变了而记录没变，作品库要重新归并
        libraryInputSignature = ""
        refreshDownloadsList()
        // 兜底：下载在 IO 协程推进，事件通知万一丢失也能靠轮询保证列表实时
        uiHandler.removeCallbacks(uiRefreshRunnable)
        uiHandler.postDelayed(uiRefreshRunnable, UI_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefreshRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_STORAGE_PROMPT, pendingStoragePrompt)
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var listRefreshScheduled = false

    /** 合并刷新请求：200ms 内多次触发只执行一次，执行时读取最新状态（不会用旧快照） */
    private fun scheduleListRefresh() {
        if (listRefreshScheduled) return
        listRefreshScheduled = true
        uiHandler.postDelayed({
            listRefreshScheduled = false
            refreshDownloadsList()
        }, LIST_REFRESH_MIN_INTERVAL_MS)
    }

    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) scheduleListRefresh()
            uiHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private fun autoDownloadFromClipboard() {
        if (!prefs.isAutoReadClipboardOnLaunch()) return
        val text = readClipboard() ?: return
        if (text.isBlank()) return
        if (consumedClips.contains(text)) return
        PlatformParser.extractUrl(text) ?: return
        // 记录已消费（内存 + 持久化），同一条链接不会重复自动下载
        consumedClips.add(text)
        prefs.addConsumedClip(text)
        Toast.makeText(this, "检测到剪贴板有链接，自动开始下载", Toast.LENGTH_SHORT).show()
        startDownload(text)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 进程级 observer 必须移除：它与进程同寿，不移除每次重建泄漏一个 Activity
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        // 清掉已 post 的延迟任务，销毁后不再触碰 binding
        uiHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        parseProgressListener?.let { downloadManager.removeParseProgressListener(it) }
        batchListener?.let { downloadManager.removeBatchListener(it) }
        recordListener?.let { recordStore.removeListener(it) }
    }
}
