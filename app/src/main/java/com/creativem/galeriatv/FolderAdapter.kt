package com.creativem.galeriatv

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.creativem.galeriatv.databinding.ItemFileBinding

class FolderAdapter(
    private val context: Context,
    private val onItemClick: (fileItem: FileItem, isFolder: Boolean) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    private var items: List<FileItem> = emptyList()
    private var spanCount: Int = 1
    private var itemWidth: Int = 0

    fun submitList(newList: List<FileItem>) {
        items = newList
        notifyDataSetChanged()
    }

    fun setSpanCount(newSpanCount: Int) {
        spanCount = newSpanCount
        recalcItemWidth()
        notifyDataSetChanged()
    }

    fun setRecyclerWidth(recyclerWidth: Int) {
        recalcItemWidth(recyclerWidth)
        notifyDataSetChanged()
    }

    private fun recalcItemWidth(recyclerWidth: Int = 0) {
        if (recyclerWidth > 0) {
            val marginPx = dpToPx(8)
            itemWidth = (recyclerWidth - marginPx * (spanCount + 1)) / spanCount
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(context), parent, false)
        return FolderViewHolder(binding)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = items[position]
        holder.binding.fileName.text = item.name
        holder.binding.root.layoutParams.width = itemWidth
        holder.binding.root.layoutParams.height = itemWidth + dpToPx(40)
        holder.binding.root.requestLayout()

        Glide.with(context)
            .load(item.thumbnailFile ?: item.file)
            .centerCrop()
            .placeholder(R.drawable.icono)
            .into(holder.binding.fileIcon)

        holder.binding.fileIcon.foreground = if (item.isFolder) {
            context.getDrawable(R.drawable.overlay_folder_border)
        } else null

        holder.binding.root.setOnClickListener {
            onItemClick(item, item.isFolder)
        }
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}