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
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
        private const val KEY_DEFAULT_VIDEO_PLAYER = "default_video_player"
    }

    private var recyclerWidthMeasured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar adaptador
        folderAdapter = FolderAdapter(this) { fileItem, isFolder ->
            if (isFolder) {
                loadFolder(fileItem.file)
            } else {
                if (fileItem.file.extension.lowercase() in listOf("mp4","mkv","avi","mov","wmv","flv")) {
                    openVideo(fileItem.file)
                } else {
                    ViewerActivity.start(
                        this,
                        Uri.fromFile(fileItem.file),
                        fileItem.file.parent ?: ""
                    )
                }
            }
        }

        // Columnas guardadas
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val columnas = prefs.getInt("grid_columns", 1)
        gridLayoutManager = GridLayoutManager(this, columnas)
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = folderAdapter

        binding.recyclerView.post {
            val recyclerWidth = binding.recyclerView.width
            folderAdapter.setSpanCount(columnas)
            folderAdapter.setRecyclerWidth(recyclerWidth)
            recyclerWidthMeasured = true
        }

        // Configuración
        binding.imgConfig.setOnClickListener {
            val configDialog = ConfigDialogFragment()

            configDialog.setOnColumnChangeListener(object : ConfigDialogFragment.OnColumnChangeListener {
                override fun onColumnCountSelected(columnCount: Int) {
                    updateColumnCount(columnCount)
                }
            })

            configDialog.setOnFolderChangeListener(object : ConfigDialogFragment.OnFolderChangeListener {
                override fun onFolderSelected() {
                    openFolderPicker()
                    binding.selectFolderButton.visibility = View.VISIBLE
                }
            })

            configDialog.show(supportFragmentManager, "ConfigDialog")
        }

        // Guardar carpeta predeterminada
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

        // Cargar carpeta predeterminada
        val defaultPath = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_FOLDER, null)

        if (defaultPath != null) {
            val defaultFolder = File(defaultPath)
            if (defaultFolder.exists()) {
                folderStack.clear()
                folderStack.add(defaultFolder)
                loadFolder(defaultFolder, saveAsCurrent = true, addToStack = false)
                binding.selectFolderButton.visibility = View.GONE
            } else openFolderPicker()
        } else openFolderPicker()

        checkStoragePermissions()
    }

    fun selectDefaultVideoPlayer() {
        try {
            val pm = packageManager

            // Lista de apps de video populares
            val videoApps = listOf(
                "org.videolan.vlc",       // VLC
                "com.mxtech.videoplayer.ad", // MX Player
                "com.mxtech.videoplayer.pro", // MX Player Pro
                "com.google.android.videos", // Google TV / Play Movies
                "com.android.gallery3d"   // Galería
            )

            // Detectar apps instaladas
            val installedApps = mutableListOf<Pair<String, String>>() // Pair<packageName, label>
            videoApps.forEach { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    installedApps.add(Pair(pkg, label))
                    Log.d("SelectVideoPlayer", "App instalada: $pkg - $label")
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.d("SelectVideoPlayer", "App no instalada: $pkg")
                }
            }

            if (installedApps.isEmpty()) {
                Toast.makeText(this, "No se encontraron apps de video instaladas", Toast.LENGTH_LONG).show()
                return
            }

            // 🔹 Mostrar diálogo con todas las apps instaladas (sin selección automática)
            val labels = installedApps.map { it.second }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Selecciona reproductor de video predeterminado")
                .setItems(labels) { _, index ->
                    val selectedPackage = installedApps[index].first
                    saveDefaultVideoPlayer(selectedPackage)
                    Toast.makeText(this, "Reproductor guardado: ${installedApps[index].second}", Toast.LENGTH_SHORT).show()
                    Log.d("SelectVideoPlayer", "Usuario seleccionó: ${installedApps[index].first}")
                }
                .show()

        } catch (e: Exception) {
            Log.e("SelectVideoPlayer", "Error al seleccionar reproductor", e)
            Toast.makeText(this, "Error al seleccionar reproductor", Toast.LENGTH_SHORT).show()
        }
    }

    /** Guardar reproductor predeterminado */
    private fun saveDefaultVideoPlayer(packageName: String) {
        getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT_VIDEO_PLAYER, packageName)
            .apply()
    }


    /** Abrir video con reproductor predeterminado */
    fun openVideo(file: File) {
        try {
            Log.d("OpenVideoDebug", "Intentando abrir video: ${file.absolutePath}")

            val videoUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            Log.d("OpenVideoDebug", "Video URI generado: $videoUri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(videoUri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPlayer = prefs.getString(KEY_DEFAULT_VIDEO_PLAYER, null)

            if (!defaultPlayer.isNullOrEmpty()) {
                // Verificar que la app aún existe
                val resolve = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .find { it.activityInfo.packageName == defaultPlayer }

                if (resolve != null) {
                    intent.setPackage(defaultPlayer)
                } else {
                    // Si ya no existe, limpiar la preferencia
                    prefs.edit().remove(KEY_DEFAULT_VIDEO_PLAYER).apply()
                    Toast.makeText(this, "El reproductor predeterminado ya no está instalado", Toast.LENGTH_SHORT).show()
                }
            }

            val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            Log.d("OpenVideoDebug", "Apps encontradas: ${resolveInfos.size}")

            if (resolveInfos.isEmpty()) {
                Toast.makeText(this, "No hay ninguna app que pueda abrir este video", Toast.LENGTH_LONG).show()
                return
            }

            startActivity(intent)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "No se pudo abrir el video", Toast.LENGTH_SHORT).show()
        }
    }


    /** Cargar carpeta */
    fun loadFolder(folder: File, saveAsCurrent: Boolean = true, addToStack: Boolean = true) {
        if (addToStack && (folderStack.isEmpty() || folderStack.last() != folder)) {
            folderStack.add(folder)
        }
        currentFolderFile = folder

        lifecycleScope.launch(Dispatchers.IO) {
            val children = UriHelper.listFiles(folder)
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
            }
        }
    }

    /** Control de back en carpetas */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onBackPressed() {
        if (folderStack.size > 1) {
            folderStack.removeLast()
            loadFolder(folderStack.last(), saveAsCurrent = false, addToStack = false)
        } else super.onBackPressed()
    }

    /** Cambiar columnas */
    private fun updateColumnCount(columnCount: Int) {
        getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit().putInt("grid_columns", columnCount).apply()
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

    /** Abrir selector de carpetas */
    private fun openFolderPicker() {
        val roots = getStorageRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, "No se encontraron unidades de almacenamiento", Toast.LENGTH_SHORT).show()
            return
        }
        folderAdapter.submitList(roots)
        folderStack.clear()
        folderStack.addAll(roots.map { it.file })
        currentFolderFile = folderStack.first()
    }

    /** Raíces de almacenamiento disponibles */
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

    /** Permisos de almacenamiento */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) openFolderPicker()
        else Toast.makeText(this, "Permisos necesarios para acceder al almacenamiento", Toast.LENGTH_SHORT).show()
    }

    private fun checkStoragePermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val perms = arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                )
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
