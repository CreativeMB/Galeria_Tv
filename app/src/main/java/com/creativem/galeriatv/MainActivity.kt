package com.creativem.galeriatv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.creativem.galeriatv.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter
    private var selectingDefaultFolder = false
    private var currentFolderFile: File? = null
    private val folderStack = java.util.LinkedList<File>()


    companion object {
        private const val PREFS_NAME = "gallery_prefs"
        private const val KEY_DEFAULT_FOLDER = "default_folder_path"
    }

    private var recyclerWidthMeasured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initAdapter()
        initRecycler()
        initMenu()
        loadDefaultFolderOrPicker()
        checkStoragePermissions()



    }

    private fun initAdapter() {
        folderAdapter = FolderAdapter(this) { fileItem, isFolder ->
            if (selectingDefaultFolder) {
                if (isFolder) {
                    saveDefaultFolder(fileItem.file)
                } else {
                    Toast.makeText(this, "Selecciona una carpeta, no un archivo", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (isFolder) {
                    loadFolder(fileItem.file)
                } else {
                    val ext = fileItem.file.extension.lowercase()
                    if (ext in listOf("mp4","mkv","avi","mov","wmv","flv")) {
                        openVideo(fileItem.file.absolutePath)
                    } else {
                        ViewerActivity.start(this, Uri.fromFile(fileItem.file), fileItem.file.parent ?: "")
                    }
                }
            }
        }
    }

    private fun initRecycler() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val columnas = prefs.getInt("grid_columns", 4)
        gridLayoutManager = GridLayoutManager(this, columnas)
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = folderAdapter

        binding.recyclerView.post {
            val recyclerWidth = binding.recyclerView.width
            folderAdapter.setSpanCount(columnas)
            folderAdapter.setRecyclerWidth(recyclerWidth)
            recyclerWidthMeasured = true
        }
    }

    private fun initMenu() {
        binding.imgMenu.setOnClickListener {
            val popup = androidx.appcompat.widget.PopupMenu(this, binding.imgMenu)
            popup.menuInflater.inflate(R.menu.top_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when(menuItem.itemId) {
                    R.id.filas -> {
                        showColumnSelectionDialog()
                        true
                    }
                    R.id.home -> {
//

                        true
                    }
                    R.id.carpeta1, R.id.carpeta2 -> {
                        Toast.makeText(this, "Galería TV v1.0", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        binding.selectFolderButton.setOnClickListener {
            val selectedFolder = folderAdapter.getSelectedFolder()
            if (selectedFolder != null) {
                saveDefaultFolder(selectedFolder)
            } else {
                Toast.makeText(this, "Primero selecciona una carpeta", Toast.LENGTH_SHORT).show()
            }
        }



    }
    private fun saveDefaultFolder(folder: File) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT_FOLDER, folder.absolutePath)
            .apply()

        Toast.makeText(this, "Carpeta predeterminada guardada", Toast.LENGTH_SHORT).show()
        folderStack.clear()
        folderStack.add(folder) // Carpeta principal
        loadFolder(folder, saveAsCurrent = true, addToStack = false)

        selectingDefaultFolder = false
        binding.selectFolderButton.visibility = View.GONE
    }



    private fun showColumnSelectionDialog() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentColumns = prefs.getInt("grid_columns", 4)
        val opciones = (1..8).map {
            if (it == currentColumns) "$it ítems por fila ✅" else "$it ítems por fila"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Selecciona cantidad de ítems por fila")
            .setItems(opciones) { _, index ->
                val columnasSeleccionadas = index + 1
                prefs.edit().putInt("grid_columns", columnasSeleccionadas).apply()
                updateColumnCount(columnasSeleccionadas)
            }
            .show()
    }

    private fun updateColumnCount(columnCount: Int) {
        gridLayoutManager.spanCount = columnCount
        if (recyclerWidthMeasured) {
            folderAdapter.setSpanCount(columnCount)
            folderAdapter.setRecyclerWidth(binding.recyclerView.width)
        } else {
            binding.recyclerView.post {
                folderAdapter.setSpanCount(columnCount)
                folderAdapter.setRecyclerWidth(binding.recyclerView.width)
                recyclerWidthMeasured = true
            }
        }
    }

    private fun openVideo(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "El archivo no existe", Toast.LENGTH_SHORT).show()
            return
        }
        ViewerActivity.start(this, Uri.fromFile(file), file.parent ?: "")
    }

    override fun onBackPressed() {
        try {
            if (folderStack.size > 1) {
                folderStack.removeLast()
                val previousFolder = folderStack.last()
                loadFolder(previousFolder, saveAsCurrent = false, addToStack = false)
            } else {
                showExitConfirmationDialog()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showExitConfirmationDialog()
        }
    }


    private fun loadDefaultFolderOrPicker() {
        val defaultPath = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_FOLDER, null)

        if (defaultPath != null) {
            val defaultFolder = File(defaultPath)
            if (defaultFolder.exists()) {
                // ⚡ Solo agregar carpeta principal si stack está vacío
                if (folderStack.isEmpty()) {
                    folderStack.add(defaultFolder)
                }
                currentFolderFile = defaultFolder
                loadFolder(defaultFolder, saveAsCurrent = true, addToStack = false)
                binding.selectFolderButton.visibility = View.GONE
            } else openFolderPicker()
        } else openFolderPicker()
    }


    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Salir de Galería TV")
            .setMessage("¿Deseas salir de la aplicación?")
            .setPositiveButton("Sí") { _, _ -> finish() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun openFolderPicker() {
        val roots = getStorageRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, "No se encontraron unidades de almacenamiento", Toast.LENGTH_SHORT).show()
            return
        }

        // Mostrar las raíces
        folderAdapter.submitList(roots)

        if (!selectingDefaultFolder) {
            // ⚡ Solo añadir roots al stack si el stack está vacío
            if (folderStack.isEmpty()) {
                folderStack.addAll(roots.map { it.file })
                currentFolderFile = roots.firstOrNull()?.file
            }
        } else {
            currentFolderFile = null
            folderStack.clear()
        }
    }


    fun loadFolder(folder: File, saveAsCurrent: Boolean = true, addToStack: Boolean = true) {
        // 🔹 Guardar carpeta actual solo si no estamos seleccionando carpeta
        if (!selectingDefaultFolder && saveAsCurrent) {
            currentFolderFile = folder
        }

        // 🔹 Actualizar stack de carpetas solo si no estamos seleccionando carpeta
        if (addToStack && !selectingDefaultFolder) {
            if (folderStack.isEmpty() || folderStack.last() != folder) {
                folderStack.add(folder)
            }
        }

        // 🔹 Marcar carpeta como visitada
        folderAdapter.markFolderAsVisited(folder)

        // 🔹 Cargar archivos en background
        lifecycleScope.launch(Dispatchers.IO) {
            val children = UriHelper.listFiles(folder)

            val sorted = children.sortedWith(
                compareBy<FileItem> {
                    when {
                        it.isFolder -> 0
                        it.file.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif") -> 1
                        it.file.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv") -> 2
                        else -> 3
                    }
                }.thenByDescending { it.file.lastModified() }
            )

            withContext(Dispatchers.Main) {
                folderAdapter.submitList(sorted)

                // 🔹 SOLO forzar foco si NO estamos seleccionando carpeta
                if (!selectingDefaultFolder && binding.recyclerView.childCount > 0) {
                    binding.recyclerView.post {
                        binding.recyclerView.getChildAt(0)?.requestFocus()
                    }
                }
            }
        }
    }


    private fun getStorageRoots(): List<FileItem> {
        val roots = mutableListOf<FileItem>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists() && primary.canRead()) roots.add(FileItem(primary, "Almacenamiento interno", true))

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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) openFolderPicker()
        else Toast.makeText(this, "Permisos necesarios para acceder al almacenamiento", Toast.LENGTH_SHORT).show()
    }

    private fun checkStoragePermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val perms = arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
                if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) openFolderPicker()
                else storagePermissionLauncher.launch(perms)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) openAppAllFilesPermission()
                else openFolderPicker()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    storagePermissionLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
                else openFolderPicker()
            }
            else -> openFolderPicker()
        }
    }

    private fun openAppAllFilesPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(this, "Concede el permiso de almacenamiento y vuelve a abrir la app", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir configuración de permisos", Toast.LENGTH_SHORT).show()
        }
    }
}
