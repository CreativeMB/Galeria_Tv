package com.creativem.galeriatv

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.creativem.galeriatv.databinding.ItemFileBinding

class FolderAdapter(
    private val context: Context,
    private val onItemClick: (uri: Uri, isFolder: Boolean) -> Unit
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

        // Cargar thumbnail (si hay, si no usar la propia imagen)
        val thumbUri = item.thumbnailUri ?: item.uri
        Glide.with(context)
            .load(thumbUri)
            .centerCrop()
            .placeholder(R.drawable.icono)
            .into(holder.binding.fileIcon)

        // Si es carpeta, aplicar borde de colores simulando carpeta
        if (item.isFolder) {
            holder.binding.fileIcon.foreground =
                context.getDrawable(R.drawable.overlay_folder_border)
        } else {
            holder.binding.fileIcon.foreground = null
        }



        // Click listener
        holder.binding.root.setOnClickListener {
            onItemClick(item.uri, item.isFolder)
        }
    }


    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)
}
