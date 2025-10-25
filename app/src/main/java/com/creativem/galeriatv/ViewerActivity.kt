package com.creativem.galeriatv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.creativem.galeriatv.databinding.ActivityViewerBinding
import com.github.chrisbanes.photoview.PhotoView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.abs

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var exoPlayer: ExoPlayer? = null

    private lateinit var folder: File
    private var mediaFiles: List<File> = emptyList()
    private var currentIndex = 0

    private lateinit var gestureDetector: GestureDetector

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

        val fileUriString = intent.getStringExtra(EXTRA_FILE_URI)
        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)

        if (fileUriString == null || folderPath == null) {
            Toast.makeText(this, "Archivo no válido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        folder = File(folderPath)

        mediaFiles = folder.listFiles { f ->
            val ext = f.extension.lowercase()
            ext in listOf("jpg", "jpeg", "png", "mp4", "mkv")
        }?.toList() ?: emptyList()



        val selectedFile = File(Uri.parse(fileUriString).path!!)
        currentIndex = mediaFiles.indexOfFirst { it.absolutePath == selectedFile.absolutePath }
        if (currentIndex == -1) currentIndex = 0


        // Mostrar el archivo inicial
        showMedia(currentIndex)
        // Configurar GestureDetector para swipes horizontales
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || e2 == null) return false
                val diffX = e2.x - e1.x
                if (kotlin.math.abs(diffX) > SWIPE_THRESHOLD && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) previousMedia() else nextMedia()
                    return true
                }
                return false
            }
        })


        showMedia(currentIndex)
    }

    @OptIn(UnstableApi::class)
    private fun showMedia(index: Int) {
        if (mediaFiles.isEmpty()) return

        val file = mediaFiles[index]
        binding.photoViewContainer.removeAllViews()
        exoPlayer?.release()
        exoPlayer = null

        if (file.extension.lowercase() in listOf("mp4", "mkv")) {
            // PlayerView sin controles
            val playerView = PlayerView(this).apply {
                useController = false
            }
            binding.photoViewContainer.addView(playerView)

            exoPlayer = ExoPlayer.Builder(this).build().also { player ->
                playerView.player = player
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                player.setMediaItem(mediaItem)
                player.prepare()
                player.pause() // pausado al inicio
            }

            // Icono de Play central
            val playIcon = ImageView(this).apply {
                setImageResource(R.drawable.baseline_play_circle_24)
                layoutParams = FrameLayout.LayoutParams(200, 200).apply {
                    gravity = android.view.Gravity.CENTER
                }
                visibility = View.VISIBLE
            }
            binding.photoViewContainer.addView(playIcon)

            // Icono de cambio de escala (top-end)
            val scaleIcon = ImageView(this).apply {
                setImageResource(R.drawable.baseline_picture_in_picture_alt_24)
                layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    topMargin = 80
                    marginEnd = 16
                }
                visibility = View.VISIBLE
            }
            binding.photoViewContainer.addView(scaleIcon)

            // Escalado
            var scaleIndex = 0
            val resizeModes = listOf(
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            )

            scaleIcon.setOnClickListener { _: View ->
                scaleIndex = (scaleIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[scaleIndex]
            }

            // Play/Pause al tocar cualquier parte del video
            playerView.setOnClickListener { _: View ->
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                        playIcon.visibility = View.VISIBLE
                    } else {
                        player.play()
                        playIcon.visibility = View.GONE
                    }
                }
            }

            // Swipe horizontal
            playerView.setOnTouchListener { _: View, event: MotionEvent ->
                gestureDetector.onTouchEvent(event)
                false
            }

        } else {
            // Imagen con PhotoView
            val photoView = PhotoView(this)
            binding.photoViewContainer.addView(photoView)
            Glide.with(this).load(file).into(photoView)

            // Swipe horizontal sobre la imagen
            photoView.setOnTouchListener { _: View, event: MotionEvent ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }
    }


    // Navegación con control remoto (DPAD)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { nextMedia(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { previousMedia(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun nextMedia() {
        if (mediaFiles.isEmpty()) return
        currentIndex = (currentIndex + 1) % mediaFiles.size
        showMedia(currentIndex)
    }

    private fun previousMedia() {
        if (mediaFiles.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) mediaFiles.size - 1 else currentIndex - 1
        showMedia(currentIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}
