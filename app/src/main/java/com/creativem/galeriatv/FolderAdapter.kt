package com.creativem.galeriatv

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.creativem.galeriatv.databinding.ItemFileBinding
import java.io.File

class FolderAdapter(
    private val context: Context,
    private val onItemClick: (fileItem: FileItem, isFolder: Boolean) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {
    private var selectedFolder: File? = null

    private val visitedFolders = mutableSetOf<String>()
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

        val imageToLoad = if (item.isFolder) {
            val files = item.file.listFiles { f ->
                val name = f.name.lowercase()
                name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")
            }
            files?.firstOrNull()
        } else {
            item.file
        }

        val extension = item.file.extension.lowercase()
        val isVideo = extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv")

        // 🔹 Determinar el fondo correcto según estado
        fun updateBackground() {
            holder.binding.root.background = when {
                item.isFolder && item.file == selectedFolder -> {
                    ContextCompat.getDrawable(context, R.drawable.item_background_selector_unvisited)
                }
                item.isFolder && visitedFolders.contains(item.file.absolutePath) -> {
                    ContextCompat.getDrawable(context, R.drawable.item_background_selector_visited)
                }
                else -> {
                    ContextCompat.getDrawable(context, R.drawable.item_background_selector_unvisited)
                }
            }
        }

        updateBackground() // aplica el fondo inicial

        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start()
                view.elevation = 16f
                view.background = ContextCompat.getDrawable(context, R.drawable.item_background_selector)
            } else {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                view.elevation = 0f
                updateBackground() // 🔹 restaurar fondo según estado real
            }
        }

        Glide.with(context)
            .load(imageToLoad ?: R.drawable.icono)
            .centerCrop()
            .placeholder(R.drawable.icono)
            .into(holder.binding.fileIcon)

        holder.binding.fileIcon.foreground = if (item.isFolder) {
            context.getDrawable(R.drawable.overlay_folder_border)
        } else null

        holder.binding.playOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

        holder.binding.root.setOnClickListener {
            onItemClick(item, item.isFolder)

            if (item.isFolder) {
                markFolderAsVisited(item.file)
                selectedFolder = item.file
                notifyDataSetChanged() // 🔹 fuerza redraw para aplicar fondo correcto
            }
        }
    }


    // 🔹 Método público para obtener la carpeta seleccionada
    fun getSelectedFolder(): File? {
        return selectedFolder
    }


    fun markFolderAsVisited(folder: File) {
        visitedFolders.add(folder.absolutePath)
        notifyDataSetChanged() // fuerza redraw para que se vea el cambio
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
