package com.creativem.galeriatv

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    private var selectedFolder: File? = null
    private val visitedFolders = mutableSetOf<String>()
    private val items = mutableListOf<FileItem>()
    private var spanCount = 1
    private var itemWidth = 0

    // Para forzar foco al cargar
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
            item.file.listFiles { f ->
                val name = f.name.lowercase()
                name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")
            }?.firstOrNull()
        } else item.file

        val extension = item.file.extension.lowercase()
        val isVideo = extension in listOf("mp4","mkv","avi","mov","wmv","flv")

        fun updateBackground() {
            holder.binding.root.background = when {
                item.isFolder && item.file == selectedFolder ->
                    ContextCompat.getDrawable(context, R.drawable.item_background_selector_unvisited)
                item.isFolder && visitedFolders.contains(item.file.absolutePath) ->
                    ContextCompat.getDrawable(context, R.drawable.item_background_selector_visited)
                else -> ContextCompat.getDrawable(context, R.drawable.item_background_selector_unvisited)
            }
        }
        updateBackground()

        // Focus con animación
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start()
                view.elevation = 16f
                view.background = ContextCompat.getDrawable(context, R.drawable.item_background_selector)
            } else {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                view.elevation = 0f
                updateBackground()
            }
        }

        Glide.with(holder.binding.fileIcon.context)
            .asBitmap()
            .load(imageToLoad ?: R.drawable.icono)
            .centerCrop()
            .placeholder(R.drawable.icono)
            .override(itemWidth / 2, itemWidth / 2)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .dontAnimate()
            .into(holder.binding.fileIcon)

        holder.binding.fileIcon.foreground =
            if (item.isFolder) context.getDrawable(R.drawable.overlay_folder_border) else null

        holder.binding.playOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

        // Click normal
        holder.binding.root.setOnClickListener {
            onItemClick(item, item.isFolder)
            if (item.isFolder) markFolderAsVisited(item.file)
            selectedFolder = item.file
            notifyItemChanged(position) // Solo actualizar este item
        }

        // Click largo
        holder.binding.root.setOnLongClickListener {
            AlertDialog.Builder(context)
                .setTitle("Eliminar")
                .setMessage("¿Deseas eliminar '${item.name}' permanentemente?")
                .setPositiveButton("Eliminar") { _, _ ->
                    if (deleteRecursive(item.file)) {
                        items.removeAt(holder.adapterPosition)
                        notifyItemRemoved(holder.adapterPosition)
                    } else Toast.makeText(context, "Error al eliminar", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }

        // Forzar foco en el primer item cargado
        if (focusFirstItemOnNextLoad && position == 0) {
            holder.itemView.post {
                holder.itemView.requestFocus()
                focusFirstItemOnNextLoad = false
            }
        }
    }

    private fun deleteRecursive(fileOrDirectory: File): Boolean {
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { deleteRecursive(it) }
        }
        return fileOrDirectory.delete()
    }

    fun getSelectedFolder(): File? = selectedFolder

    fun markFolderAsVisited(folder: File) {
        visitedFolders.add(folder.absolutePath)
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    override fun onViewRecycled(holder: FolderViewHolder) {
        Glide.with(holder.binding.fileIcon.context).clear(holder.binding.fileIcon)
        super.onViewRecycled(holder)
    }
}