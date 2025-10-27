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

        // Evitar que la pantalla se apague mientras la app está abierta
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ocultar barra de notificaciones (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Para versiones anteriores a Android 11
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            actionBar?.hide()
        }



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
                    Toast.makeText(
                        this,
                        "Selecciona una carpeta, no un archivo",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                if (isFolder) {
                    loadFolder(fileItem.file)
                } else {
                    val ext = fileItem.file.extension.lowercase()
                    val parentFolder = fileItem.file.parentFile ?: return@FolderAdapter

                    if (ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv")) {
                        // Abrir ViewerActivity con todos los videos de la carpeta
                        ViewerActivity.start(
                            this,
                            Uri.fromFile(fileItem.file),
                            parentFolder.absolutePath
                        )
                    } else if (ext in listOf("jpg", "jpeg", "png", "gif")) {
                        // Abrir ViewerActivity con todas las imágenes de la carpeta
                        ViewerActivity.start(
                            this,
                            Uri.fromFile(fileItem.file),
                            parentFolder.absolutePath
                        )
                    } else {
                        Toast.makeText(this, "Formato no soportado", Toast.LENGTH_SHORT).show()
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

        // 🧩 Optimización para Android TV (sin cache persistente)
        binding.recyclerView.apply {
            setHasFixedSize(true)               // evita relayout innecesario
            itemAnimator = null                 // elimina animaciones costosas
            setItemViewCacheSize(8)             // solo mantiene pocas vistas vivas
            recycledViewPool.setMaxRecycledViews(0, 12) // máximo 12 vistas en pool
        }

        // 🔹 Calcula el ancho para los ítems una vez medido el RecyclerView
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
                when (menuItem.itemId) {
                    R.id.filas -> {
//                        showColumnSelectionDialog()
                        true
                    }

                    R.id.diapositivas -> {
//                        showImageConfigDialog()
                        true
                    }

                    R.id.carpeta1, R.id.carpeta2 -> {

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
        binding.imgConfig.setOnClickListener {
            showImageConfigDialog()
        }
        binding.imgFilas.setOnClickListener {
            showColumnSelectionDialog()
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
            Toast.makeText(this, "No se encontraron unidades de almacenamiento", Toast.LENGTH_SHORT)
                .show()
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
                        it.file.extension.lowercase() in listOf(
                            "mp4",
                            "mkv",
                            "avi",
                            "mov",
                            "wmv",
                            "flv"
                        ) -> 2

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
        if (primary.exists() && primary.canRead()) roots.add(
            FileItem(
                primary,
                "Almacenamiento interno",
                true
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { vol ->
                val dir =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else null
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
        // Verifica si todos los permisos fueron concedidos
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            openFolderPicker()
        } else {
            Toast.makeText(
                this,
                "Se requieren permisos de almacenamiento para continuar",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkStoragePermissions() {
        when {
            // ✅ Android 13 y superior (Tiramisu o más)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val perms = arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                )
                if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    openFolderPicker()
                } else {
                    storagePermissionLauncher.launch(perms)
                }
            }

            // ✅ Android 11 y 12 (R y S)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    openAppAllFilesPermission()
                } else {
                    openFolderPicker()
                }
            }

            // ✅ Android 6 a 10
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val readPerm = android.Manifest.permission.READ_EXTERNAL_STORAGE
                val writePerm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

                if (checkSelfPermission(readPerm) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(writePerm) != PackageManager.PERMISSION_GRANTED
                ) {
                    storagePermissionLauncher.launch(arrayOf(readPerm, writePerm))
                } else {
                    openFolderPicker()
                }
            }

            // ✅ Android 5 o menor
            else -> {
                openFolderPicker()
            }
        }
    }

    private fun openAppAllFilesPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Concede el permiso de acceso a todos los archivos y vuelve a abrir la app",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            try {
                // Fallback en caso de error
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(
                    this,
                    "No se pudo abrir la configuración de permisos",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showImageConfigDialog() {
        val intervals = (1..10).map { "$it s" }.toTypedArray()
        val effects = ViewerActivity.SlideEffect.values().map { it.name }.toTypedArray()
        val defaultEffect = "TRANSLATE" // efecto obligatorio
        val selectedEffects = mutableSetOf<Int>() // índices seleccionados
        var selectedInterval = 3
        var randomMode = true

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Configuración de diapositivas")

        val dialogView = layoutInflater.inflate(R.layout.dialog_image_config, null)
        val intervalSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_interval)
        val randomSwitch = dialogView.findViewById<android.widget.Switch>(R.id.switch_random)
        val effectsListView = dialogView.findViewById<android.widget.ListView>(R.id.list_effects)

        // Spinner de intervalos
        intervalSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            intervals
        )
        intervalSpinner.setSelection(selectedInterval - 1)

        // Lista de efectos con multiple choice
        effectsListView.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            effects
        )
        effectsListView.choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE

        // Recuperar efectos guardados
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val savedEffects = prefs.getStringSet("slide_effects", setOf(defaultEffect)) ?: setOf(defaultEffect)

        effects.forEachIndexed { index, effectName ->
            if (savedEffects.contains(effectName)) effectsListView.setItemChecked(index, true)
        }

        // Evitar desmarcar el efecto por defecto
        effectsListView.setOnItemClickListener { _, _, position, _ ->
            if (effects[position] == defaultEffect) {
                effectsListView.setItemChecked(position, true)
            }
        }

        randomSwitch.isChecked = prefs.getBoolean("slide_random", true)
        intervalSpinner.setSelection(prefs.getInt("slide_interval", 3) - 1)

        builder.setView(dialogView)

        builder.setPositiveButton("Aceptar") { _, _ ->
            selectedInterval = intervalSpinner.selectedItemPosition + 1
            selectedEffects.clear()
            for (i in 0 until effects.size) {
                if (effectsListView.isItemChecked(i)) selectedEffects.add(i)
            }
            randomMode = randomSwitch.isChecked

            // Guardar configuración
            val editor = getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit()
            editor.putInt("slide_interval", selectedInterval)
            editor.putBoolean("slide_random", randomMode)
            editor.putStringSet("slide_effects", selectedEffects.map { effects[it] }.toSet())
            editor.apply()
        }

        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }



}
