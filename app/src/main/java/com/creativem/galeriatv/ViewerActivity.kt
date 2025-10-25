package com.creativem.galeriatv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.creativem.galeriatv.databinding.ActivityViewerBinding
import com.github.chrisbanes.photoview.PhotoView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var exoPlayer: ExoPlayer? = null

    private lateinit var currentFile: File
    private lateinit var folder: File

    companion object {
        private const val EXTRA_FILE_URI = "extra_file_uri"
        private const val EXTRA_FOLDER_PATH = "extra_folder_path"

        fun start(context: Context, fileUri: Uri, folderPath: String) {
            val intent = Intent(context, ViewerActivity::class.java)
            intent.putExtra(EXTRA_FILE_URI, fileUri.toString())
            intent.putExtra(EXTRA_FOLDER_PATH, folderPath)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fileUri = intent.getStringExtra(EXTRA_FILE_URI)?.let { Uri.parse(it) }
        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)
        if (fileUri == null || folderPath == null) {
            Toast.makeText(this, "Archivo no válido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentFile = File(fileUri.path!!)
        folder = File(folderPath)

        if (currentFile.extension.lowercase() in listOf("mp4", "mkv")) {
            // Video con ExoPlayer
            binding.photoViewContainer.removeAllViews()
            val playerView = PlayerView(this)
            binding.photoViewContainer.addView(playerView)

            exoPlayer = ExoPlayer.Builder(this).build()
            playerView.player = exoPlayer

            val mediaItem = MediaItem.fromUri(fileUri)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
        } else {
            // Imagen con PhotoView y Glide
            binding.photoViewContainer.removeAllViews()
            val photoView = PhotoView(this)
            binding.photoViewContainer.addView(photoView)

            // Cargar imagen con Glide (más seguro que setImageURI)
            Glide.with(this)
                .load(fileUri)
                .into(photoView)

            // Botón Play para slideshow
            binding.fabPlay.setOnClickListener {
                SlideshowActivity.start(this, folder.absolutePath)
            }

            // Botón Configuración para personalizar vista
            binding.fabSettings.setOnClickListener {
                Toast.makeText(this, "Configuración aún no implementada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}
