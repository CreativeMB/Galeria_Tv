package com.creativem.galeriatv

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.creativem.galeriatv.databinding.ItemFileBinding
import java.io.File

class FolderAdapter(
    private val context: Context,
    private val onItemClick: (fileItem: FileItem, isFolder: Boolean) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    private var items: List<FileItem> = emptyList()

    fun submitList(newList: List<FileItem>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(context), parent, false)
        return FolderViewHolder(binding)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = items[position]
        holder.binding.fileName.text = item.name

        // Usar thumbnail si existe, si no la propia carpeta/archivo
        val thumbFile = item.thumbnailFile ?: item.file
        Glide.with(context)
            .load(thumbFile)
            .centerCrop()
            .placeholder(R.drawable.icono)
            .into(holder.binding.fileIcon)

        // Si es carpeta, agregar overlay
        holder.binding.fileIcon.foreground = if (item.isFolder) {
            context.getDrawable(R.drawable.overlay_folder_border)
        } else null

        // Click listener
        holder.binding.root.setOnClickListener {
            onItemClick(item, item.isFolder)
        }
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)
}
