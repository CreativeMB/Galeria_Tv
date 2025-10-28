package com.creativem.galeriatv

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.creativem.galeriatv.databinding.ItemFileBinding
import java.io.File

class FolderAdapter(
    private val context: Context,
    private val onItemClick: (fileItem: FileItem, isFolder: Boolean) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    private val items = mutableListOf<FileItem>()
    private val visitedFolders = mutableSetOf<String>()
    private var selectedFolder: File? = null
    private var spanCount = 1
    private var itemWidth = 0
    private var focusFirstItemOnNextLoad = true

    fun submitList(newList: List<FileItem>) {
        items.clear()
        items.addAll(newList)
        focusFirstItemOnNextLoad = true
        notifyDataSetChanged()
    }

    fun setSpanCount(newSpanCount: Int) {
        spanCount = newSpanCount
        recalcItemWidth()
    }

    fun setRecyclerWidth(recyclerWidth: Int) {
        recalcItemWidth(recyclerWidth)
    }

    private fun recalcItemWidth(recyclerWidth: Int = 0) {
        if (recyclerWidth > 0) {
            val marginPx = dpToPx(8)
            itemWidth = (recyclerWidth - marginPx * (spanCount + 1)) / spanCount
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.fileName.text = item.name
        binding.fileName.isSelected = true
        binding.root.layoutParams.width = itemWidth
        binding.root.layoutParams.height = itemWidth + dpToPx(40)

        val imageToLoad = if (item.isFolder) {
            item.file.listFiles { f ->
                val name = f.name.lowercase()
                name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")
            }?.firstOrNull()
        } else item.file

        val extension = item.file.extension.lowercase()
        val isVideo = extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv")

        fun updateBackground() {
            binding.root.background = when {
                item.isFolder && item.file == selectedFolder -> ContextCompat.getDrawable(context, R.drawable.item_background_selector_unvisited)
                item.isFolder && visitedFolders.contains(item.file.absolutePath) -> ContextCompat.getDrawable(context, R.drawable.item_background_selector_visited)
                else -> ContextCompat.getDrawable(context, R.drawable.item_background_selector_unvisited)
            }
        }
        updateBackground()

        // Animación de foco optimizada (sin fugas de GPU)
        binding.root.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.12f else 1f
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(150)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            view.elevation = if (hasFocus) 16f else 0f
            view.background = if (hasFocus)
                ContextCompat.getDrawable(context, R.drawable.item_background_selector)
            else {
                updateBackground(); view.background
            }
        }

        // Carga de imagen optimizada para TV
        Glide.with(binding.fileIcon.context)
            .asBitmap()
            .load(imageToLoad ?: R.drawable.icono)
            .format(DecodeFormat.PREFER_RGB_565)
            .centerCrop()
            .placeholder(R.drawable.outline_hangout_video_24)
            .override(itemWidth / 2, itemWidth / 2)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .skipMemoryCache(false)
            .into(binding.fileIcon)

        binding.fileIcon.foreground = if (item.isFolder)
            context.getDrawable(R.drawable.overlay_folder_border)
        else null

        binding.playOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

        // Click normal
        binding.root.setOnClickListener {
            onItemClick(item, item.isFolder)
            if (item.isFolder) markFolderAsVisited(item.file)
            selectedFolder = item.file
            notifyItemChanged(position)
        }

        // Click largo: eliminar
        binding.root.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true

            val itemToDelete = items[pos]
            AlertDialog.Builder(context)
                .setTitle("Eliminar")
                .setMessage("¿Deseas eliminar '${itemToDelete.name}' permanentemente?")
                .setPositiveButton("Eliminar") { _, _ ->
                    if (deleteRecursive(itemToDelete.file)) {
                        items.removeAt(pos)
                        notifyItemRemoved(pos)
                        Toast.makeText(context, "Archivo eliminado", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Error al eliminar archivo", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }

        // Foco inicial
        if (focusFirstItemOnNextLoad && position == 0) {
            holder.itemView.post {
                holder.itemView.requestFocus()
                focusFirstItemOnNextLoad = false
            }
        }
    }

    private fun deleteRecursive(fileOrDirectory: File): Boolean {
        return try {
            if (fileOrDirectory.isDirectory) {
                fileOrDirectory.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
            }
            fileOrDirectory.delete()
        } catch (e: Exception) {
            false
        }
    }

    fun getSelectedFolder(): File? = selectedFolder
    fun markFolderAsVisited(folder: File) { visitedFolders.add(folder.absolutePath) }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    override fun onViewRecycled(holder: FolderViewHolder) {
        Glide.with(holder.binding.fileIcon.context).clear(holder.binding.fileIcon)
        super.onViewRecycled(holder)
    }
}