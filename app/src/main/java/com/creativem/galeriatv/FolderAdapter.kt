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
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.creativem.galeriatv.databinding.ItemFileBinding
import java.io.File

class FolderAdapter(
    private val context: Context,
    private val onItemClick: (fileItem: FileItem, isFolder: Boolean) -> Unit,
    private val onAudioFolderClick: (() -> Unit)? = null
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    private val visitedFolders = mutableSetOf<String>()
    private val items = mutableListOf<FileItem>()
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

        // --- Configuración básica de la vista (sin cambios) ---
        holder.binding.fileName.text = item.name
        holder.binding.fileName.isSelected = true
        holder.binding.root.layoutParams.width = itemWidth
        holder.binding.root.layoutParams.height = itemWidth + dpToPx(40)
        holder.binding.root.requestLayout()

        // --- Lógica de UI: Fondos (sin cambios) ---
        fun updateBackground() {
            holder.binding.root.background = when {
                item.isFolder && visitedFolders.contains(item.file.absolutePath) ->
                    ContextCompat.getDrawable(context, R.drawable.item_background_selector_visited)
                else ->
                    ContextCompat.getDrawable(context, R.drawable.item_background_selector_unvisited)
            }
        }
        updateBackground()

        // --- Lógica de UI: Animación de Foco (sin cambios) ---
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

        // --- LÓGICA DE CARGA DE IMAGEN: PEREZOSA Y MINIMALISTA ---

        // 1. Limpiamos el ImageView para evitar ver la imagen anterior en vistas recicladas.
        holder.binding.fileIcon.setImageDrawable(null)

        // 2. Buscamos la miniatura "justo a tiempo". Si no se encuentra, 'imageToLoad' será null.
        val imageToLoad: Any? = if (item.isFolder) {
            item.file.listFiles { file ->
                file.isFile && (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true))
            }?.firstOrNull()
        } else {
            item.file
        }

        // 3. Carga con Glide optimizado. Si 'imageToLoad' es null, el ImageView quedará en blanco.
        val targetSize = itemWidth
        Glide.with(holder.binding.fileIcon.context)
            .load(imageToLoad) // Carga el archivo, o null si no hay miniatura
            .override(targetSize, targetSize)
            .fitCenter() // Muestra la imagen completa sin recortar
            .diskCacheStrategy(DiskCacheStrategy.NONE) // No usa almacenamiento
            .skipMemoryCache(false) // Sí usa RAM para scroll fluido
            .dontAnimate() // Evita animaciones de fade-in de Glide
            .into(holder.binding.fileIcon)


        // --- Overlays y Listeners (sin cambios) ---
        val extension = item.file.extension.lowercase()
        val isVideo = extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv")

        holder.binding.fileIcon.foreground =
            if (item.isFolder) context.getDrawable(R.drawable.overlay_folder_border) else null
        holder.binding.playOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

        // Click normal
        holder.binding.root.setOnClickListener {
            // PRIMERO, revisa si es el ítem especial
            if (item.isAudioFolderItem) {
                // Si lo es, llama a la función especial para la carpeta de audio
                // que pasaste en el constructor del adapter.
                onAudioFolderClick?.invoke()
            } else {
                // SI NO lo es, entonces es un archivo o carpeta normal.
                // Usa el callback normal.
                onItemClick(item, item.isFolder)
            }
        }

        // Click largo para eliminar
        holder.binding.root.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            val fileItem = items[pos]

            AlertDialog.Builder(context)
                .setTitle("Eliminar")
                .setMessage("¿Deseas eliminar '${fileItem.name}' permanentemente?")
                .setPositiveButton("Eliminar") { _, _ ->
                    if (deleteRecursive(fileItem.file)) {
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

        // Lógica para enfocar el primer ítem (sin cambios)
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
                fileOrDirectory.listFiles()?.forEach { child ->
                    if (!deleteRecursive(child)) return false
                }
            }
            fileOrDirectory.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ✅ Devuelve la carpeta actual del explorador (no la última clickeada)
    fun getSelectedFolder(): File? =
        (context as? MainActivity)?.currentFolderFile

    fun markFolderAsVisited(folder: File) {
        visitedFolders.add(folder.absolutePath)
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    override fun onViewRecycled(holder: FolderViewHolder) {
        Glide.with(holder.binding.fileIcon.context).clear(holder.binding.fileIcon)
        super.onViewRecycled(holder)
    }
}
