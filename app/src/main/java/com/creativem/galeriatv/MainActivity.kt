package com.creativem.galeriatv

import android.Manifest
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.Log
import androidx.recyclerview.widget.GridLayoutManager
import com.creativem.galeriatv.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

    private var audioUris: List<Uri> = emptyList()

    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    var currentFolderFile: File? = null
    private val folderStack = LinkedList<File>()
    private var recyclerWidthMeasured = false
    private var selectingDefaultFolder = false
    private var selectingAudioFolder = false
    private var previousFolderFile: File? = null

    companion object {
        private const val PREFS_NAME = "gallery_prefs"
        private const val KEY_DEFAULT_FOLDER = "default_folder_path"
        private const val KEY_AUDIO_FOLDER = "audio_folder_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        initAdapter()
        initRecycler()
        initMenu()
        checkStoragePermissions()  // Primero pedir permisos

        binding.audiocarpeta.setOnClickListener {
            if (!selectingAudioFolder) {
                // Primer toque: iniciar selección de carpeta
                selectingAudioFolder = true
                previousFolderFile = currentFolderFile  // Guardar para volver luego

                Toast.makeText(this, "Navega hasta la carpeta de audios y toca nuevamente para guardar", Toast.LENGTH_LONG).show()

                // Mostrar raíz del almacenamiento
                val roots = getStorageRoots()
                folderAdapter.submitList(roots)
                currentFolderFile = null

                // Mostrar botón de seleccionar carpeta
                binding.selectFolderButton.visibility = View.VISIBLE

            } else {
                // Segundo toque: usar mismo método que el botón principal
                selectingDefaultFolder = false
                selectingAudioFolder = true

                // Ahora el usuario toca el botón de "selectFolderButton" para guardar
                Toast.makeText(this, "Toca el botón para guardar la carpeta de audios seleccionada", Toast.LENGTH_SHORT).show()
            }
        }



    }




    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            actionBar?.hide()
        }
    }

    /** ------------------ ADAPTER Y RECYCLER ------------------ **/

    private fun initAdapter() {
        folderAdapter = FolderAdapter(
            this,
            onItemClick = { fileItem, isFolder ->
                when {
                    // Si está seleccionando carpeta principal o de audios
                    // solo navega (NO guarda todavía)
                    selectingDefaultFolder || selectingAudioFolder -> {
                        if (isFolder) {
                            loadFolder(fileItem.file)
                        } else {
                            Toast.makeText(this, "Selecciona una carpeta, no un archivo", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // Si es el ítem especial para carpeta de audios
                    fileItem.isAudioFolderItem -> {
                        openAudioFolderNavigator()
                    }

                    // Navegación normal
                    else -> {
                        if (isFolder) loadFolder(fileItem.file)
                        else openFile(fileItem.file)
                    }
                }
            }
        )
        binding.recyclerView.adapter = folderAdapter
    }


    private fun initRecycler() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val columnas = prefs.getInt("grid_columns", 4)

        gridLayoutManager = GridLayoutManager(this, columnas)
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = folderAdapter

        binding.recyclerView.apply {
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(8)
            recycledViewPool.setMaxRecycledViews(0, 12)
        }

        binding.recyclerView.post {
            val recyclerWidth = binding.recyclerView.width
            folderAdapter.setSpanCount(columnas)
            folderAdapter.setRecyclerWidth(recyclerWidth)
            recyclerWidthMeasured = true
        }
    }

    /** ------------------ MENÚ Y CONFIGURACIÓN ------------------ **/

    private fun initMenu() {
        // Menú superior
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

        // Botón para guardar carpeta seleccionada (principal o de audio)
        binding.selectFolderButton.setOnClickListener {
            val selected = folderAdapter.getSelectedFolder()
            if (selected != null && selected.isDirectory) {

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                when {
                    selectingDefaultFolder -> {
                        // Guardar carpeta principal
                        prefs.edit()
                            .putString(KEY_DEFAULT_FOLDER, selected.absolutePath)
                            .apply()
                        Toast.makeText(this, "Carpeta principal guardada", Toast.LENGTH_SHORT).show()

                        // Actualizar estado de la app
                        folderStack.clear()
                        folderStack.add(selected)
                        currentFolderFile = selected
                        loadFolder(selected, saveAsCurrent = true, addToStack = false)
                    }

                    selectingAudioFolder -> {
                        // Guardar carpeta de audios (con path en vez de Uri)
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putString(KEY_AUDIO_FOLDER, selected.absolutePath)
                            .apply()
                        Toast.makeText(this, "Carpeta de audios guardada", Toast.LENGTH_SHORT).show()

                        // Volver a la carpeta principal
                        previousFolderFile?.let { folder ->
                            currentFolderFile = folder
                            loadFolder(folder, saveAsCurrent = true, addToStack = false)
                        }
                    }

                }

                // Resetear banderas y ocultar botón
                selectingDefaultFolder = false
                selectingAudioFolder = false
                binding.selectFolderButton.visibility = View.GONE

            } else {
                Toast.makeText(this, "Primero selecciona una carpeta válida", Toast.LENGTH_SHORT).show()
            }
        }

        // Otros botones de configuración
        binding.imgConfig.setOnClickListener { showImageConfigDialog() }
        binding.imgFilas.setOnClickListener { showColumnSelectionDialog() }
    }
    private fun loadDefaultFolderOrPicker() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mainPath = prefs.getString(KEY_DEFAULT_FOLDER, null)
        val audioPath = prefs.getString(KEY_AUDIO_FOLDER, null)

        if (mainPath != null) {
            val folder = File(mainPath)
            if (folder.exists()) {
                if (folderStack.isEmpty()) folderStack.add(folder)
                currentFolderFile = folder
                loadFolder(folder, saveAsCurrent = true, addToStack = false)
                binding.selectFolderButton.visibility = View.GONE
            } else openFolderPicker()
        } else openFolderPicker()

        // ✅ Mostrar si ya hay carpeta de audios guardada
        if (audioPath != null) {
            val audioFolder = File(audioPath)
            if (!audioFolder.exists()) {
                Toast.makeText(this, "La carpeta de audios ya no existe", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFolderPicker() {
        selectingDefaultFolder = true
        selectingAudioFolder = false

        val roots = getStorageRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, "No se encontraron unidades de almacenamiento", Toast.LENGTH_SHORT).show()
            return
        }

        folderAdapter.submitList(roots)
        binding.selectFolderButton.visibility = View.VISIBLE
        Toast.makeText(this, "Selecciona la carpeta principal", Toast.LENGTH_SHORT).show()
    }


    // Abrir selector para carpeta de audios
    private fun openAudioFolderNavigator() {
        selectingAudioFolder = true
        previousFolderFile = currentFolderFile ?: folderStack.lastOrNull()
        val roots = getStorageRoots()
        folderAdapter.submitList(roots)
        binding.selectFolderButton.visibility = View.VISIBLE
        Toast.makeText(this, "Navega y selecciona la carpeta de audios", Toast.LENGTH_SHORT).show()
    }

    private fun showColumnSelectionDialog() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentColumns = prefs.getInt("grid_columns", 4)
        val options = (1..8).map { if (it == currentColumns) "$it ítems por fila ✅" else "$it ítems por fila" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Selecciona cantidad de ítems por fila")
            .setItems(options) { _, index ->
                val columnas = index + 1
                prefs.edit().putInt("grid_columns", columnas).apply()
                updateColumnCount(columnas)
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

    /** ------------------ CARGA DE CARPETAS Y ARCHIVOS ------------------ **/

    private fun saveDefaultFolder(folder: File) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEFAULT_FOLDER, folder.absolutePath)
            .apply()
        Toast.makeText(this, "Carpeta predeterminada guardada", Toast.LENGTH_SHORT).show()
        folderStack.clear()
        folderStack.add(folder)
        loadFolder(folder, saveAsCurrent = true, addToStack = false)
        selectingDefaultFolder = false
        binding.selectFolderButton.visibility = View.GONE
    }

    private fun openFile(file: File) {
        val ext = file.extension.lowercase()
        val parentFolder = file.parentFile ?: return
        if (ext in listOf("mp4","mkv","avi","mov","wmv","flv","jpg","jpeg","png","gif")) {
            ViewerActivity.start(this, Uri.fromFile(file), parentFolder.absolutePath)
        } else {
            Toast.makeText(this,"Formato no soportado",Toast.LENGTH_SHORT).show()
        }
    }

    fun loadFolder(folder: File, saveAsCurrent: Boolean = true, addToStack: Boolean = true) {
        if (saveAsCurrent) currentFolderFile = folder
        if (addToStack && (folderStack.isEmpty() || folderStack.last != folder)) folderStack.add(folder)

        folderAdapter.markFolderAsVisited(folder)

        lifecycleScope.launch(Dispatchers.IO) {
            val children = UriHelper.listFiles(folder).toMutableList()

            // ✅ Ya no agregamos el item falso de "Seleccionar carpeta de audios"

            val sorted = children.sortedWith(
                compareBy<FileItem> {
                    when {
                        it.isFolder -> 0
                        it.file.extension.lowercase() in listOf("jpg","jpeg","png","gif") -> 1
                        it.file.extension.lowercase() in listOf("mp4","mkv","avi","mov","wmv","flv") -> 2
                        else -> 3
                    }
                }.thenByDescending { it.file.lastModified() }
            )

            withContext(Dispatchers.Main) {
                folderAdapter.submitList(sorted)
                if (binding.recyclerView.childCount > 0) {
                    binding.recyclerView.post { binding.recyclerView.getChildAt(0)?.requestFocus() }
                }
            }
        }
    }


    private fun getStorageRoots(): List<FileItem> {
        val roots = mutableListOf<FileItem>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists() && primary.canRead()) roots.add(FileItem(primary,"Almacenamiento interno",true))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { vol ->
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else null
                if (dir != null && dir.exists() && dir.canRead() && roots.none { it.file==dir }) {
                    roots.add(FileItem(dir, vol.getDescription(this), true))
                }
            }
        }
        return roots
    }

    /** ------------------ BACK PRESS ------------------ **/

    override fun onBackPressed() {
        try {
            if (folderStack.size > 1) {
                folderStack.removeLast()
                loadFolder(folderStack.last, saveAsCurrent=false, addToStack=false)
            } else {
                showExitConfirmationDialog()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showExitConfirmationDialog()
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

    /** ------------------ PERMISOS ------------------ **/

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) loadDefaultFolderOrPicker()
        else Toast.makeText(this,"Se requieren permisos de almacenamiento",Toast.LENGTH_SHORT).show()
    }

    private fun checkStoragePermissions() {
        val isTv = isAndroidTV()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val perms = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,  Manifest.permission.READ_MEDIA_AUDIO)
                if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    loadDefaultFolderOrPicker()
                } else {
                    storagePermissionLauncher.launch(perms)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    loadDefaultFolderOrPicker()
                } else {
                    if (isTv) {
                        showTvPermissionDialog()
                    } else {
                        openAppAllFilesPermission()
                    }
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    loadDefaultFolderOrPicker()
                } else {
                    storagePermissionLauncher.launch(perms)
                }
            }
            else -> loadDefaultFolderOrPicker()
        }
    }

    private fun isAndroidTV(): Boolean {
        val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType
        return uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // Para TVs que no soportan burbuja de permisos
    private fun showTvPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso requerido")
            .setMessage(
                "Para acceder a tus carpetas de fotos y videos, la aplicación necesita permiso de almacenamiento completo.\n\n" +
                        "En algunos TV, debes abrir Ajustes > Aplicaciones > Galería TV > Permisos y conceder el acceso manualmente."
            )
            .setPositiveButton("Entendido", null)
            .setCancelable(false)
            .show()
    }

    // Función original para abrir ajustes en móviles
    private fun openAppAllFilesPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        Toast.makeText(this, "Concede el permiso y vuelve a abrir la app", Toast.LENGTH_LONG).show()
    }

    private fun mostrarInfoApp() {
        AlertDialog.Builder(this)
            .setTitle("📺 Galería TV")
            .setMessage(
                """
                Bienvenido a Galería TV Un explorador de archivos diseñado especialmente para Android TV. Con esta aplicación puedes visualizar y reproducir fácilmente tus fotos y videos, disfrutando de transiciones fluidas y elegantes al estilo de diapositivas animadas. 
                ✨ Características principales: 
                • Seleciona su carpeta de raiz para que siempre busque el contenido. 
                • Reproduce imágenes y videos directamente desde tus carpetas. 
                • Navegación optimizada para control remoto de TV. 
                • Animaciones suaves y modernas entre fotos. 
                • Experiencia rápida, eficiente y sin complicaciones. 
                📦 Versión: 1.0 © Todos los derechos reservados 👨
                ‍💻 Desarrollado por Tobias Martínez 
                📍 Colombia 📞 WhatsApp: +57 315 072 5566
            """.trimIndent()
            )
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun abrirPaginaProyectos() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://creativem.carrd.co/")))
    }

    /** ------------------ CONFIGURACIÓN DIAPOSITIVAS ------------------ **/

    private fun showImageConfigDialog() {
        val intervals = (1..10).map { "$it s" }.toTypedArray()
        val effects = ViewerActivity.SlideEffect.values().map { it.name }.toTypedArray()
        val defaultEffect = "TRANSLATE"

        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Configuración de diapositivas")

        val dialogView = layoutInflater.inflate(R.layout.dialog_image_config, null)
        val intervalSpinner = dialogView.findViewById<Spinner>(R.id.spinner_interval)
        val randomSwitch = dialogView.findViewById<Switch>(R.id.switch_random)
        val effectsListView = dialogView.findViewById<ListView>(R.id.list_effects)
        val selectAllBtn = dialogView.findViewById<Button>(R.id.btn_select_all)
        val clearAllBtn = dialogView.findViewById<Button>(R.id.btn_clear_all)

        // Adaptadores
        intervalSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals)
        effectsListView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        effectsListView.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, effects)

        // Restaurar preferencias
        intervalSpinner.setSelection(prefs.getInt("slide_interval", 3) - 1)
        randomSwitch.isChecked = prefs.getBoolean("slide_random", true)

        // Recuperar efectos guardados en orden
        val savedEffectsString = prefs.getString("slide_effects_ordered", defaultEffect)
        val savedEffects = savedEffectsString?.split(",") ?: listOf(defaultEffect)

        // Marcar en el ListView
        effects.forEachIndexed { index, e ->
            if (savedEffects.contains(e)) effectsListView.setItemChecked(index, true)
        }

        // Botón seleccionar todos
        selectAllBtn?.setOnClickListener {
            for (i in effects.indices) effectsListView.setItemChecked(i, true)
        }

        // Botón limpiar selección
        clearAllBtn?.setOnClickListener {
            for (i in effects.indices) effectsListView.setItemChecked(i, false)
        }

        builder.setView(dialogView)

        builder.setPositiveButton("Aceptar") { _, _ ->
            val selectedInterval = intervalSpinner.selectedItemPosition + 1
            val randomMode = randomSwitch.isChecked

            // Guardar efectos seleccionados en orden de aparición en la lista
            val selectedEffects = mutableListOf<String>()
            for (i in effects.indices) {
                if (effectsListView.isItemChecked(i)) selectedEffects.add(effects[i])
            }

            // Si no selecciona ninguno, usar el predeterminado
            if (selectedEffects.isEmpty()) selectedEffects.add(defaultEffect)

            // Guardar intervalos, modo y efectos ordenados
            prefs.edit()
                .putInt("slide_interval", selectedInterval)
                .putBoolean("slide_random", randomMode)
                .putString("slide_effects_ordered", selectedEffects.joinToString(","))
                .apply()

            Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        }

        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }



}
