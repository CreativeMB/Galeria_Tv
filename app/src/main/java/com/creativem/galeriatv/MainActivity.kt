package com.creativem.galeriatv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.creativem.galeriatv.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter

    private var currentFolderFile: File? = null
    private val folderStack = mutableListOf<File>()

    companion object {
        private const val PREFS_NAME = "gallery_prefs"
        private const val KEY_LAST_PATH = "last_folder_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderAdapter = FolderAdapter(this) { fileItem, isFolder ->
            if (isFolder) loadFolder(fileItem.file)
            else ViewerActivity.start(this, Uri.fromFile(fileItem.file))
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = folderAdapter

        binding.selectFolderButton.setOnClickListener { openFolderPicker() }

        // Cargar última carpeta usada
        val lastPath = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PATH, null)

        if (lastPath != null) {
            val savedFile = File(lastPath)
            if (savedFile.exists()) loadFolder(savedFile)
            else openFolderPicker()
        } else {
            openFolderPicker()
        }
    }

    private fun openFolderPicker() {
        val roots = getStorageRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, "No se encontraron unidades de almacenamiento", Toast.LENGTH_SHORT).show()
            return
        }

        // Mostrar unidades raíz
        folderAdapter.submitList(roots)

        // Reiniciar stack
        folderStack.clear()
        folderStack.addAll(roots.map { it.file })

        // Guardar la primera raíz como carpeta actual
        currentFolderFile = folderStack.first()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PATH, currentFolderFile!!.absolutePath)
            .apply()
    }

    private fun getStorageRoots(): List<FileItem> {
        val roots = mutableListOf<FileItem>()

        // Interna principal
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists() && primary.canRead()) {
            roots.add(FileItem(primary, "Almacenamiento interno", true))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { vol ->
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else null
                if (dir != null && dir.exists() && dir.canRead() && roots.none { it.file == dir }) {
                    val name = vol.getDescription(this)
                    roots.add(FileItem(dir, name, true))
                }
            }
        }

        return roots
    }

    private fun loadFolder(folder: File, saveAsCurrent: Boolean = true, addToStack: Boolean = true) {
        lifecycleScope.launch(Dispatchers.IO) {
            val children = UriHelper.listFiles(folder)
            withContext(Dispatchers.Main) { folderAdapter.submitList(children) }
        }

        if (addToStack) folderStack.add(folder)
        if (saveAsCurrent) {
            currentFolderFile = folder
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PATH, folder.absolutePath)
                .apply()
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (folderStack.size > 1) {
            folderStack.removeLast()
            val previousFolder = folderStack.last()
            loadFolder(previousFolder, saveAsCurrent = false, addToStack = false)
        } else {
            super.onBackPressed()
        }
    }
}
