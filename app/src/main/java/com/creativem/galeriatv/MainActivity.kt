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
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.creativem.galeriatv.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private val folderStack = ArrayDeque<File>()
    private var currentFolder: File? = null
    private var selectingDefaultFolder = false
    private var recyclerWidthMeasured = false

    companion object {
        private const val PREFS_NAME = "gallery_prefs"
        private const val KEY_DEFAULT_FOLDER = "default_folder_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        initRecycler()
        initAdapter()
        initMenu()
        loadDefaultFolderOrPicker()
        checkStoragePermissions()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun initRecycler() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val columnas = prefs.getInt("grid_columns", 4)

        gridLayoutManager = GridLayoutManager(this, columnas)
        binding.recyclerView.apply {
            layoutManager = gridLayoutManager
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(8)
            recycledViewPool.setMaxRecycledViews(0, 12)
        }
    }

    private fun initAdapter() {
        val activityRef = WeakReference(this)
        folderAdapter = FolderAdapter(this) { fileItem, isFolder ->
            val activity = activityRef.get() ?: return@FolderAdapter
            if (selectingDefaultFolder) {
                if (isFolder) {
                    saveDefaultFolder(fileItem.file)
                } else {
                    Toast.makeText(activity, "Selecciona una carpeta, no un archivo", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (isFolder) {
                    loadFolder(fileItem.file)
                } else {
                    val ext = fileItem.file.extension.lowercase()
                    val parentFolder = fileItem.file.parentFile ?: return@FolderAdapter
                    if (ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "jpg", "jpeg", "png", "gif")) {
                        ViewerActivity.start(activity, Uri.fromFile(fileItem.file), parentFolder.absolutePath)
                    } else {
                        Toast.makeText(activity, "Formato no soportado", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.recyclerView.adapter = folderAdapter
        binding.recyclerView.post {
            val recyclerWidth = binding.recyclerView.width
            folderAdapter.setSpanCount(gridLayoutManager.spanCount)
            folderAdapter.setRecyclerWidth(recyclerWidth)
            recyclerWidthMeasured = true
        }
    }

    private fun initMenu() {
        binding.imgMenu.setOnClickListener {
            val popup = androidx.appcompat.widget.PopupMenu(this, binding.imgMenu)
            popup.menuInflater.inflate(R.menu.top_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.info -> { mostrarInfoApp(); true }
                    R.id.proyectos -> { abrirPaginaProyectos(); true }
                    else -> false
                }
            }
            popup.show()
        }

        binding.selectFolderButton.setOnClickListener {
            val selected = folderAdapter.getSelectedFolder()
            if (selected != null) saveDefaultFolder(selected)
            else Toast.makeText(this, "Primero selecciona una carpeta", Toast.LENGTH_SHORT).show()
        }

        binding.imgConfig.setOnClickListener { showImageConfigDialog() }
        binding.imgFilas.setOnClickListener { showColumnSelectionDialog() }
    }

    private fun saveDefaultFolder(folder: File) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT_FOLDER, folder.absolutePath)
            .apply()

        Toast.makeText(this, "Carpeta predeterminada guardada", Toast.LENGTH_SHORT).show()
        folderStack.clear()
        folderStack.add(folder)
        loadFolder(folder, saveAsCurrent = true, addToStack = false)
        selectingDefaultFolder = false
        binding.selectFolderButton.visibility = View.GONE
    }

    private fun loadDefaultFolderOrPicker() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_DEFAULT_FOLDER, null)

        if (path != null) {
            val folder = File(path)
            if (folder.exists() && folder.isDirectory && folder.canRead()) {
                folderStack.clear()
                folderStack.add(folder)
                loadFolder(folder, saveAsCurrent = true, addToStack = false)
            } else {
                Toast.makeText(this, "La carpeta guardada no existe o no se puede leer", Toast.LENGTH_SHORT).show()
                openFolderPicker()
            }
        } else {
            openFolderPicker()
        }
    }

    fun loadFolder(folder: File, saveAsCurrent: Boolean = true, addToStack: Boolean = true) {
        if (!selectingDefaultFolder && saveAsCurrent) currentFolder = folder
        if (addToStack && !selectingDefaultFolder && (folderStack.isEmpty() || folderStack.last() != folder))
            folderStack.add(folder)

        folderAdapter.markFolderAsVisited(folder)

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

            withContext(Dispatchers.Main.immediate) {
                folderAdapter.submitList(sorted)
                if (!selectingDefaultFolder && binding.recyclerView.childCount > 0) {
                    binding.recyclerView.post { binding.recyclerView.getChildAt(0)?.requestFocus() }
                }
            }
        }
    }

    private fun showImageConfigDialog() {
        val opciones = arrayOf(
            "Activar efectos suaves (recomendado)",
            "Desactivar efectos para máximo rendimiento"
        )

        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val efectosActivos = prefs.getBoolean("efectos_activos", true)

        var seleccion = if (efectosActivos) 0 else 1

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Configuración de efectos visuales")
        builder.setSingleChoiceItems(opciones, seleccion) { _, which ->
            seleccion = which
        }

        builder.setPositiveButton("Guardar") { dialog, _ ->
            val activar = (seleccion == 0)
            prefs.edit().putBoolean("efectos_activos", activar).apply()

            Toast.makeText(
                this,
                if (activar) "Efectos visuales activados" else "Efectos desactivados para mayor rendimiento",
                Toast.LENGTH_SHORT
            ).show()

            dialog.dismiss()
        }

        builder.setNegativeButton("Cancelar", null)
        val dialog = builder.create()
        dialog.show()
    }

    private fun showColumnSelectionDialog() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val columnasActuales = prefs.getInt("grid_columns", 4)
        val opciones = arrayOf("3 columnas", "4 columnas", "5 columnas", "6 columnas")

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Seleccionar número de columnas")
        builder.setSingleChoiceItems(opciones, columnasActuales - 3) { _, which ->
            val columnas = which + 3
            prefs.edit().putInt("grid_columns", columnas).apply()
            gridLayoutManager.spanCount = columnas
            Toast.makeText(this, "Columnas actualizadas: $columnas", Toast.LENGTH_SHORT).show()
        }
        builder.setPositiveButton("Cerrar", null)
        builder.show()
    }

    override fun onBackPressed() {
        if (folderStack.size > 1) {
            folderStack.removeLast()
            loadFolder(folderStack.last(), saveAsCurrent = false, addToStack = false)
        } else {
            showExitConfirmationDialog()
        }
    }

    private fun getStorageRoots(): List<FileItem> {
        val roots = mutableListOf<FileItem>()
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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.all { it.value }) openFolderPicker()
        else Toast.makeText(this, "Se requieren permisos de almacenamiento", Toast.LENGTH_SHORT).show()
    }

    private fun checkStoragePermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val perms = arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                )
                if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    openFolderPicker()
                } else storagePermissionLauncher.launch(perms)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) openAppAllFilesPermission()
                else openFolderPicker()
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val read = android.Manifest.permission.READ_EXTERNAL_STORAGE
                val write = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                if (checkSelfPermission(read) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(write) != PackageManager.PERMISSION_GRANTED
                ) storagePermissionLauncher.launch(arrayOf(read, write))
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
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        Toast.makeText(this, "Concede el permiso y vuelve a abrir la app", Toast.LENGTH_LONG).show()
    }

    private fun openFolderPicker() {
        val roots = getStorageRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, "No se encontraron unidades de almacenamiento", Toast.LENGTH_SHORT).show()
            return
        }
        folderAdapter.submitList(roots)
        if (!selectingDefaultFolder && folderStack.isEmpty()) {
            folderStack.addAll(roots.map { it.file })
            currentFolder = roots.firstOrNull()?.file
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Salir de Galería TV")
            .setMessage("¿Deseas salir de la aplicación?")
            .setPositiveButton("Sí") { _, _ -> finish() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun mostrarInfoApp() {
        AlertDialog.Builder(this)
            .setTitle("📺 Galería TV")
            .setMessage(
                """
                Bienvenido a Galería TV
                
                Un explorador de archivos diseñado para Android TV.
                ✨ Reproduce fotos y videos directamente desde tus carpetas.
                ⚡ Optimizado para televisores con pocos recursos.
                👨‍💻 Desarrollado por Tobias Martínez
                """.trimIndent()
            )
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun abrirPaginaProyectos() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://creativem.carrd.co/"))
        startActivity(intent)
    }
}
