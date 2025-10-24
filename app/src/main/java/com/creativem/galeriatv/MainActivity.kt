package com.creativem.galeriatv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.creativem.galeriatv.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val folderStack = mutableListOf<Uri>()

    private lateinit var binding: ActivityMainBinding
    private lateinit var folderAdapter: FolderAdapter

    // Carpeta actual que se está mostrando
    private var currentFolderUri: Uri? = null

    // Carpeta raíz seleccionada por el usuario (para construir URIs correctas)
    private var rootFolderUri: Uri? = null

    companion object {
        private const val PREFS_NAME = "gallery_prefs"
        private const val KEY_LAST_URI = "last_folder_uri"
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Guardar carpeta raíz
            rootFolderUri = uri
            currentFolderUri = uri

            // Conceder permisos persistentes
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // Guardar URI raíz en preferencias
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_URI, uri.toString())
                .apply()

            loadFolder(uri)
        } else {
            Toast.makeText(this, "No se seleccionó ninguna carpeta", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderAdapter = FolderAdapter(this) { fileUri, isFolder ->
            if (isFolder) {
                // Navegar dentro de la subcarpeta
                loadFolder(fileUri)
            } else {
                ViewerActivity.start(this, fileUri)
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = folderAdapter

        binding.selectFolderButton.setOnClickListener {
            openFolderPicker()
        }

        // Cargar carpeta raíz guardada o pedir selección la primera vez
        val lastUriString = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URI, null)

        if (lastUriString != null) {
            val savedUri = Uri.parse(lastUriString)
            rootFolderUri = savedUri
            currentFolderUri = savedUri
            loadFolder(savedUri)
        } else {
            openFolderPicker()
        }
    }

    private fun openFolderPicker() {
        folderPickerLauncher.launch(null)
    }

    private fun loadFolder(folderUri: Uri, saveAsCurrent: Boolean = true, addToStack: Boolean = true) {
        val root = rootFolderUri ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val children = UriHelper.listFiles(this@MainActivity, root, folderUri)
            withContext(Dispatchers.Main) {
                folderAdapter.submitList(children)
            }
        }

        if (addToStack) {
            folderStack.add(folderUri)
        }

        if (saveAsCurrent) {
            currentFolderUri = folderUri
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_URI, rootFolderUri.toString())
                .apply()
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (folderStack.size > 1) {
            // Quitamos la carpeta actual del stack
            folderStack.removeLast()
            // Cargamos la carpeta anterior sin agregar al stack
            val previousFolder = folderStack.last()
            loadFolder(previousFolder, saveAsCurrent = false, addToStack = false)
        } else {
            super.onBackPressed() // salir de la app si estamos en la raíz
        }
    }

}
