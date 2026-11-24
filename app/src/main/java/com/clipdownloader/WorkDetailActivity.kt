package com.clipdownloader

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipdownloader.databinding.ActivityWorkDetailBinding
import com.clipdownloader.databinding.ItemWorkFileBinding
import com.clipdownloader.model.DownloadRecord
import com.clipdownloader.model.LibraryWorks
import com.clipdownloader.model.Platform
import com.clipdownloader.model.PublishTime
import com.clipdownloader.model.WorkLayout
import com.clipdownloader.util.DownloadRecordStore
import com.clipdownloader.util.FileActions
import com.clipdownloader.util.PreferencesManager
import com.clipdownloader.util.ThumbLoader
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 作品详情：作品库里点开一个作品后，看它的文案、作者、发布日期、所在目录和下面的全部文件。
 * 点文件打开，长按分享或删除；右上角菜单可以删除整个作品（连同文件）。
 * 作品库以磁盘上的文件为准，所以没有「只移出作品库、保留文件」这种操作——文件还在，作品就还在。
 *
 * 作品的数据每次都从下载记录与磁盘重新归并，文件或记录一变页面跟着变；作品不在了就关闭。
 */
class WorkDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkDetailBinding
    private val recordStore by lazy { DownloadRecordStore.get(this) }
    private val saveRoot by lazy { File(PreferencesManager(this).getSavePath()) }

    private val filesAdapter = WorkFilesAdapter(
        onOpen = { FileActions.open(this, it.path) },
        onMore = { item -> showFileMenu(item) }
    )

    private var work: LibraryWorks.Work? = null

    private val recordListener: (List<DownloadRecord>) -> Unit = { reload() }

    /** 等系统删除确认框返回后要收尾的那一批文件，以及删完后要做的事（参数：真正删掉的文件数） */
    private var pendingDelete: List<String> = emptyList()
    private var pendingDeleteDone: ((Int) -> Unit)? = null

    private val deleteConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // 用户在系统弹窗里可能只勾了一部分，也可能直接取消：以磁盘上还剩什么为准
            val paths = pendingDelete
            val done = pendingDeleteDone
            pendingDelete = emptyList()
            pendingDeleteDone = null
            finishDelete(paths, done)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = "作品"
        // 主题是 NoActionBar：返回箭头自己画，颜色跟随主题的前景色
        AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)?.let {
            val wrapped = DrawableCompat.wrap(it.mutate())
            DrawableCompat.setTint(
                wrapped, MaterialColors.getColor(binding.toolbar, MaterialR.attr.colorOnSurface)
            )
            binding.toolbar.navigationIcon = wrapped
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_work_detail)
        binding.toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }

        binding.recyclerFiles.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerFiles.adapter = filesAdapter
        (binding.recyclerFiles.itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
        binding.btnOpenFolder.setOnClickListener {
            work?.let { FileActions.openFolder(this, it.dir) }
        }
    }

    override fun onStart() {
        super.onStart()
        recordStore.addListener(recordListener)
    }

    override fun onStop() {
        super.onStop()
        recordStore.removeListener(recordListener)
    }

    /** 从下载记录与磁盘重新归并出这个作品；记录一变就会走到这里 */
    private fun reload() {
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) {
                LibraryWorks.build(recordStore.getRecords(), saveRoot).firstOrNull { it.token == token }
            }
            if (isFinishing || isDestroyed) return@launch
            if (current == null) {
                // 作品的文件已经全部删掉
                finish()
                return@launch
            }
            work = current
            bind(current)
        }
    }

    private fun bind(w: LibraryWorks.Work) {
        val title = w.title.ifBlank { "作品" }
        binding.toolbar.title = title
        binding.tvDetailTitle.text = title
        val date = PublishTime.dateText(w.publishTimeMs)
        binding.tvDetailMeta.text = listOf(
            w.platform.displayName, w.author, if (date.isBlank()) "" else "发布 $date", "${w.files.size} 个文件"
        ).filter { it.isNotBlank() }.joinToString(" · ")
        binding.tvDetailDir.text = w.dir
        binding.tvFilesTitle.text = "文件（${w.files.size}）"
        filesAdapter.submit(w.files, w.platform)
    }

    // ==================== 删除 ====================

    /**
     * 删除文件。App 自己创建的文件直接删。清除过应用数据之后、或者文件是别处拷来的，
     * 系统不再认它们归本 App 所有（靠媒体权限仍然看得到），直接删会被静默拒绝。
     * 这类文件先试系统的删除确认框（MediaStore.createDeleteRequest，Android 11+，不需要额外权限，
     * 但只接受图片 / 视频 / 音频这类媒体项——保存在 Download 目录下的文件通常不算）；
     * 走不通就如实告诉用户原因，请他用文件管理器删除。这里没有申请「所有文件访问」权限。
     *
     * @param done 删完以后在主线程执行，参数是真正删掉的文件数（用户取消系统确认框时为 0）
     */
    private fun deleteFiles(paths: List<String>, done: (Int) -> Unit) {
        if (paths.isEmpty()) {
            done(0)
            return
        }
        lifecycleScope.launch {
            val denied = withContext(Dispatchers.IO) { paths.filter { !File(it).delete() && File(it).exists() } }
            if (denied.isEmpty()) {
                finishDelete(paths, done)
                return@launch
            }
            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteRequestOf(denied) else null
            if (sender == null) {
                // 原因只在这里说明一次；直接删掉的那几个照常收尾，不再回调（避免重复提示）
                android.app.AlertDialog.Builder(this@WorkDetailActivity)
                    .setTitle("有 ${denied.size} 个文件无法删除")
                    .setMessage(
                        "清除过应用数据之后，或者文件不是本应用下载的，系统就不再允许本应用直接删除它们" +
                            "（仍然可以查看和打开）。请用系统的文件管理器删除。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                finishDelete(paths, null)
                reload()
            } else {
                pendingDelete = paths
                pendingDeleteDone = done
                deleteConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
    }

    /**
     * 删除收尾：以磁盘为准统计真正删掉的，从下载记录里去掉对应条目，
     * 作品目录空了就一并删掉，最后执行调用方的收尾动作。
     * 在下载记录里的作品，记录变化会触发 [recordListener] → [reload]；只凭磁盘列出来的作品没有记录，
     * 所以收尾动作里都再 [reload] 一次，页面跟着磁盘走。
     */
    private fun finishDelete(paths: List<String>, done: ((Int) -> Unit)?) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val gone = paths.filter { !File(it).exists() }
                dropFromRecords(gone.toSet())
                gone.mapNotNull { File(it).parentFile }.distinct().forEach { dir ->
                    // 只动带作品标识的作品目录；目录里还有本 App 看不见的文件时系统不会让删
                    if (WorkLayout.tokenOf(dir.name) != null && dir.list()?.isEmpty() == true) dir.delete()
                }
                gone.size
            }
            done?.invoke(count)
        }
    }

    /** 系统删除确认框需要的 IntentSender；这些文件在媒体库里查不到时返回 null */
    private fun deleteRequestOf(paths: List<String>): android.content.IntentSender? {
        val resolver = contentResolver
        val collection = MediaStore.Files.getContentUri("external_primary")
        val uris = paths.mapNotNull { path ->
            runCatching {
                resolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null
                )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
            }.getOrNull()
        }
        if (uris.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(resolver, uris).intentSender }
            .onFailure { com.clipdownloader.util.LogFile.w("WorkDetail", "系统删除确认框创建失败: ${it.message}") }
            .getOrNull()
    }

    /** 下载记录里指向这些文件的条目移除（条目去光的记录整条删掉） */
    private fun dropFromRecords(paths: Set<String>) {
        if (paths.isEmpty()) return
        recordStore.getRecords().forEach { old ->
            if (old.filePaths.any { it in paths }) recordStore.withoutPaths(old, paths)
        }
    }

    // ==================== 单个文件 ====================

    private fun showFileMenu(item: LibraryWorks.Item) {
        android.app.AlertDialog.Builder(this)
            .setTitle(item.label)
            .setItems(arrayOf("打开", "分享", "删除这个文件")) { _, which ->
                when (which) {
                    0 -> FileActions.open(this, item.path)
                    1 -> FileActions.share(this, item.path)
                    2 -> confirm(
                        "删除文件",
                        "删除「${item.name}」？对应的下载记录一并移除，删除后无法恢复。"
                    ) {
                        deleteFiles(listOf(item.path)) { count ->
                            if (count > 0) {
                                Toast.makeText(this, "已删除 ${item.name}", Toast.LENGTH_SHORT).show()
                            }
                            reload()
                        }
                    }
                }
            }
            .show()
    }

    // ==================== 整个作品 ====================

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        val w = work ?: return false
        when (item.itemId) {
            R.id.action_delete_work -> confirm(
                "删除作品",
                "删除这个作品的 ${w.files.size} 个文件？删除后无法恢复。"
            ) {
                val paths = w.files.map { it.path }
                deleteFiles(paths) { count ->
                    Toast.makeText(
                        this,
                        when {
                            // 一个都没删：要么用户取消了系统确认框，要么系统不允许（原因已经提示过）
                            count == 0 -> return@deleteFiles
                            count < paths.size -> "已删除 $count 个文件，其余系统不允许本应用删除，可以用文件管理器删除"
                            else -> "已删除 $count 个文件"
                        },
                        if (count < paths.size) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                    // 文件全删光了作品就不在了，reload 会关掉这个页面；删掉一部分就留在页面上看剩下的
                    reload()
                }
            }

            else -> return false
        }
        return true
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val EXTRA_TOKEN = "token"

        /** 打开某个作品的详情；[token] 是作品库里的定位键（[LibraryWorks.Work.token]） */
        fun start(context: Context, token: String) {
            context.startActivity(
                Intent(context, WorkDetailActivity::class.java).putExtra(EXTRA_TOKEN, token)
            )
        }
    }
}

/** 作品详情里的文件网格：点开打开，长按弹出分享 / 删除 */
private class WorkFilesAdapter(
    private val onOpen: (LibraryWorks.Item) -> Unit,
    private val onMore: (LibraryWorks.Item) -> Unit
) : RecyclerView.Adapter<WorkFilesAdapter.VH>() {

    private var items: List<LibraryWorks.Item> = emptyList()
    private var platform = Platform.OTHER

    init {
        setHasStableIds(true)
    }

    class VH(val binding: ItemWorkFileBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<LibraryWorks.Item>, platform: Platform) {
        if (list == items && platform == this.platform) return
        items = list
        this.platform = platform
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWorkFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        if (item.kind == "音乐") {
            b.imgFile.setTag(R.id.tag_cover_url, null)
            b.imgFile.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.imgFile.setImageResource(R.drawable.ic_music)
        } else {
            b.imgFile.scaleType = ImageView.ScaleType.CENTER_CROP
            ThumbLoader.load(b.imgFile, null, item.path, platform)
        }
        b.tvFileType.text = item.label
        b.tvFileSize.text = if (item.sizeBytes > 0) DownloadsAdapter.formatBytes(item.sizeBytes) else ""
        b.tvFileSize.visibility = if (item.sizeBytes > 0) View.VISIBLE else View.GONE
        b.tvFileDuration.text = if (item.durationMs > 0) DownloadsAdapter.formatDuration(item.durationMs) else ""
        b.tvFileDuration.visibility = if (item.durationMs > 0) View.VISIBLE else View.GONE
        b.tvFileLabel.text = item.name
        b.root.setOnClickListener { onOpen(item) }
        b.root.setOnLongClickListener { onMore(item); true }
    }
}
