package com.creativem.galeriatv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.creativem.galeriatv.databinding.ActivityMainBinding
import com.creativem.galeriatv.dialogs.ConfigDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter

    private var currentFolderFile: File? = null
    private val folderStack = mutableListOf<File>()

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

        // Inicializar adaptador
        folderAdapter = FolderAdapter(this) { fileItem, isFolder ->
            if (isFolder) loadFolder(fileItem.file)
            else ViewerActivity.start(this, Uri.fromFile(fileItem.file))
        }

        // Leer columnas guardadas
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val columnas = prefs.getInt("grid_columns", 1)

        gridLayoutManager = GridLayoutManager(this, columnas)
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = folderAdapter

        // Esperar a que RecyclerView tenga ancho medido
        binding.recyclerView.post {
            val recyclerWidth = binding.recyclerView.width
            folderAdapter.setSpanCount(columnas)
            folderAdapter.setRecyclerWidth(recyclerWidth)
            recyclerWidthMeasured = true
        }

        // Configuración de columnas desde el menú
        binding.imgConfig.setOnClickListener {
            val configDialog = ConfigDialogFragment()
            configDialog.setOnColumnChangeListener(object :
                ConfigDialogFragment.OnColumnChangeListener {
                override fun onColumnCountSelected(columnCount: Int) {
                    updateColumnCount(columnCount)
                }
            })
            configDialog.show(supportFragmentManager, "ConfigDialog")
        }

        // Botón para seleccionar carpeta inicial
        binding.selectFolderButton.setOnClickListener {
            currentFolderFile?.let { folder ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DEFAULT_FOLDER, folder.absolutePath)
                    .apply()

                Toast.makeText(this, "Carpeta predeterminada guardada", Toast.LENGTH_SHORT).show()
                binding.selectFolderButton.visibility = View.GONE
                loadFolder(folder)
            }
        }

        // Cargar carpeta predeterminada si existe
        val defaultPath = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_FOLDER, null)

        if (defaultPath != null) {
            val defaultFolder = File(defaultPath)
            if (defaultFolder.exists()) {
                loadFolder(defaultFolder, saveAsCurrent = true, addToStack = true)
                binding.selectFolderButton.visibility = View.GONE
            } else openFolderPicker()
        } else openFolderPicker()

        checkStoragePermissions()
    }

    // 🔹 Método central para actualizar columnas sin loop
    private fun updateColumnCount(columnCount: Int) {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("grid_columns", columnCount).apply()

        gridLayoutManager.spanCount = columnCount

        // Recalcular ancho de items si el RecyclerView ya fue medido
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

    private fun openFolderPicker() {
        val roots = getStorageRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, "No se encontraron unidades de almacenamiento", Toast.LENGTH_SHORT)
                .show()
            return
        }

        folderAdapter.submitList(roots)
        folderStack.clear()
        folderStack.addAll(roots.map { it.file })
        currentFolderFile = folderStack.first()
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

    private fun loadFolder(
        folder: File,
        saveAsCurrent: Boolean = true,
        addToStack: Boolean = true
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            var children = UriHelper.listFiles(folder)
            children = children.sortedWith(
                compareByDescending<FileItem> { it.isFolder }.thenBy { it.name.lowercase() }
            )
            withContext(Dispatchers.Main) { folderAdapter.submitList(children) }
        }

        if (saveAsCurrent) currentFolderFile = folder
        if (addToStack) folderStack.add(folder)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (folderStack.size > 1) {
            folderStack.removeLast()
            val previousFolder = folderStack.last()
            loadFolder(previousFolder, saveAsCurrent = false, addToStack = false)
        } else super.onBackPressed()
    }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.values.all { it }
            if (!granted) Toast.makeText(this, "Permisos necesarios para acceder al almacenamiento", Toast.LENGTH_SHORT).show()
            else openFolderPicker()
        }

    private fun checkStoragePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) permissions.add(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        storagePermissionLauncher.launch(permissions.toTypedArray())
    }
}
