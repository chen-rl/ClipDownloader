package com.clipdownloader

import android.widget.ImageView
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.clipdownloader.databinding.ItemLibraryWorkBinding
import com.clipdownloader.model.LibraryWorks
import com.clipdownloader.model.PublishTime
import com.clipdownloader.util.ThumbLoader

/**
 * 作品库的卡片墙适配器：一个作品一张卡片
 * （封面 + 平台与类型角标 + 文件数 + 文案 + 作者与发布日期）。
 *
 * 数据没变时不刷新：下载页的轮询刷新也会走到这里，不能让封面反复闪占位图。
 */
class LibraryAdapter(
    private val onOpen: (LibraryWorks.Work) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.VH>() {

    private var items: List<LibraryWorks.Work> = emptyList()

    init {
        setHasStableIds(true)
    }

    class VH(val binding: ItemLibraryWorkBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<LibraryWorks.Work>) {
        if (list == items) return
        items = list
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].token.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLibraryWorkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val work = items[position]
        val b = holder.binding
        b.tvTitle.text = work.title.ifBlank { "作品" }
        b.tvType.text = "${work.platform.displayName} · ${work.typeLabel}"
        b.tvFiles.text = "${work.files.size} 个文件"
        b.tvMeta.text = listOf(work.author, PublishTime.dateText(work.publishTimeMs))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { work.platform.displayName }
        if (work.coverPath.isBlank()) {
            // 只有背景音乐、也没有远程封面的作品：显示音乐图标
            b.imgCover.setTag(R.id.tag_cover_url, null)
            b.imgCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.imgCover.setImageResource(R.drawable.ic_music)
        } else {
            b.imgCover.scaleType = ImageView.ScaleType.CENTER_CROP
            // 本地文件优先，读不出来退回记录里的远程封面
            ThumbLoader.load(b.imgCover, work.coverUrl, work.coverPath, work.platform)
        }
        b.root.setOnClickListener { onOpen(work) }
    }
}
