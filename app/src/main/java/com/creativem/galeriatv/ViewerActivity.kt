package com.creativem.galeriatv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import kotlin.math.abs
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var exoPlayer: ExoPlayer? = null
    private var bottomBarVisible = false
    private var slideRunnable: Runnable? = null
    private var isSlideShowRunning = false

    private lateinit var folder: File
    private var mediaFiles: List<File> = emptyList()
    private var currentIndex = 0

    private lateinit var gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())

    // Runnable para actualizar tiempo restante de video
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    val remainingMs = player.duration - player.currentPosition
                    binding.txtFileName.text = formatTime(remainingMs)
                }
                handler.postDelayed(this, 500)
            }
        }
    }

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
// Evitar que la pantalla se apague mientras la app está abierta
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val fileUriString = intent.getStringExtra(EXTRA_FILE_URI)
        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)

        if (fileUriString == null || folderPath == null) {
            Toast.makeText(this, "Archivo no válido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        folder = File(folderPath)
        val selectedFile = File(Uri.parse(fileUriString).path!!)

        mediaFiles = folder.listFiles { f ->
            shownImages.clear()
            shownVideos.clear()
            val ext = f.extension.lowercase()
            ext in listOf("mp4","mkv","avi","mov","wmv","flv","jpg","jpeg","png","gif")
        }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
        currentIndex = mediaFiles.indexOfFirst { it.absolutePath == selectedFile.absolutePath }
        if (currentIndex == -1) currentIndex = 0



        // Gestos para pasar archivos con swipe
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null || e2 == null) return false
                val diffX = e2.x - e1.x
                if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) nextMediaImage() else previousMedia()
                    return true
                }
                return false
            }
        })

        binding.btnPlayPause.setOnClickListener { toggleSlideShow() }

        showMedia(currentIndex)
        setupFocusHighlight()
    }

    private fun setupFocusHighlight() {
        val focusableButtons = listOf(binding.btnPlayPause)
        focusableButtons.forEach { button ->
            button.setBackgroundResource(R.drawable.item_background_selector)
            button.isFocusable = true
            button.isFocusableInTouchMode = true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (bottomBarVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { binding.btnPlayPause.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { toggleBottomBar(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { nextMediaImage(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { previousMedia(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { toggleBottomBar(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }
    }

    private fun toggleBottomBar() {
        bottomBarVisible = !bottomBarVisible
        if (bottomBarVisible) {
            binding.bottomBar.visibility = View.VISIBLE
            binding.btnPlayPause.isFocusable = true
            binding.btnPlayPause.requestFocus()
            binding.photoViewContainer.isFocusable = false
        } else {
            binding.bottomBar.visibility = View.GONE
            binding.photoViewContainer.isFocusable = true
            binding.photoViewContainer.requestFocus()
        }
    }

    // --- Ver un archivo (video o imagen) ---
    @OptIn(UnstableApi::class)
    private fun showMedia(index: Int) {
        if (mediaFiles.isEmpty()) return

        val file = mediaFiles[index]
        binding.photoViewContainer.removeAllViews()

        exoPlayer?.release()
        exoPlayer = null
        handler.removeCallbacks(updateTimeRunnable)
        slideRunnable?.let { handler.removeCallbacks(it) }
        isSlideShowRunning = false

        binding.txtFileName.text = file.name

        if (isVideo(file)) {
            // --- VIDEO ---
            val playerView = PlayerView(this).apply { useController = false }
            binding.photoViewContainer.addView(playerView)

            exoPlayer = ExoPlayer.Builder(this).build().also { player ->
                playerView.player = player
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
            }

            binding.videoCenterIcon.visibility = View.GONE
            handler.post(updateTimeRunnable)

            // Avanzar automáticamente al siguiente archivo al terminar
            exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == ExoPlayer.STATE_ENDED) {
                        nextMediaVideo()
                    }
                }
            })

        } else {
            // --- IMAGEN ---
            val photoView = PhotoView(this)
            binding.photoViewContainer.addView(photoView)
            Glide.with(this).load(file).into(photoView)
            binding.videoCenterIcon.visibility = View.GONE

            // Si el slideshow está activo, continuar
            if (isSlideShowRunning) {
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val intervalMs = prefs.getInt("slide_interval", 3) * 1000L
                val randomMode = prefs.getBoolean("slide_random", true)
                val effectNames = prefs.getStringSet("slide_effects", setOf("TRANSLATE","ZOOM","FADE")) ?: setOf("TRANSLATE")
                val effects = effectNames.map { SlideEffect.valueOf(it) }
                prepareSlideShow(intervalMs, effects, randomMode)
                slideRunnable?.let { handler.post(it) }
            }
        }
    }

    // --- Slideshow ---
    private fun prepareSlideShow(intervalMs: Long, effects: List<SlideEffect>, random: Boolean) {
        slideRunnable?.let { handler.removeCallbacks(it) }

        slideRunnable = object : Runnable {
            override fun run() {
                nextMediaImage()
                val currentFile = mediaFiles[currentIndex]

                if (!isVideo(currentFile)) {
                    val photoView = binding.photoViewContainer.getChildAt(0) as? PhotoView
                        ?: PhotoView(this@ViewerActivity).also {
                            binding.photoViewContainer.removeAllViews()
                            binding.photoViewContainer.addView(it)
                        }
                    Glide.with(this@ViewerActivity).load(currentFile).into(photoView)
                    binding.txtFileName.text = currentFile.name

                    val effect = if (random) effects.random() else effects.firstOrNull() ?: SlideEffect.TRANSLATE
                    applyEffect(photoView, effect)

                    currentIndex = (currentIndex + 1) % mediaFiles.size
                }

                handler.postDelayed(this, intervalMs)
            }
        }
    }

    private fun toggleSlideShow() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val intervalMs = prefs.getInt("slide_interval", 3) * 1000L
        val randomMode = prefs.getBoolean("slide_random", true)
        val effectNames = prefs.getStringSet("slide_effects", setOf("TRANSLATE","ZOOM","FADE")) ?: setOf("TRANSLATE")
        val effects = effectNames.map { SlideEffect.valueOf(it) }

        if (!isSlideShowRunning) {
            prepareSlideShow(intervalMs, effects, randomMode)
            slideRunnable?.let { handler.post(it) }
            isSlideShowRunning = true
        } else {
            slideRunnable?.let { handler.removeCallbacks(it) }
            isSlideShowRunning = false
        }
    }

    enum class SlideEffect {
        TRANSLATE, ZOOM, FADE, ROTATE, SCALE,
        ROTATE_SCALE, ZOOM_FADE, TRANSLATE_FADE,
        BOUNCE, SHADOW, FLIP_HORIZONTAL, FLIP_VERTICAL
    }

    private fun applyEffect(photoView: PhotoView, effect: SlideEffect) {
        when(effect) {
            SlideEffect.TRANSLATE -> photoView.animate().translationX(50f).translationY(50f).setDuration(1000)
                .withEndAction { photoView.translationX = 0f; photoView.translationY = 0f }.start()
            SlideEffect.ZOOM -> photoView.animate().scaleX(1.2f).scaleY(1.2f).setDuration(1000)
                .withEndAction { photoView.scaleX = 1f; photoView.scaleY = 1f }.start()
            SlideEffect.FADE -> photoView.animate().alpha(0f).setDuration(500)
                .withEndAction { photoView.alpha = 1f }.start()
            SlideEffect.ROTATE -> photoView.animate().rotationBy(360f).setDuration(1000).start()
            SlideEffect.SCALE -> photoView.animate().scaleX(0.8f).scaleY(0.8f).setDuration(500)
                .withEndAction { photoView.scaleX = 1f; photoView.scaleY = 1f }.start()
            SlideEffect.ROTATE_SCALE -> photoView.animate().rotationBy(180f).scaleX(1.3f).scaleY(1.3f).setDuration(1000)
                .withEndAction { photoView.rotation = 0f; photoView.scaleX = 1f; photoView.scaleY = 1f }.start()
            SlideEffect.ZOOM_FADE -> photoView.animate().scaleX(1.3f).scaleY(1.3f).alpha(0f).setDuration(1000)
                .withEndAction { photoView.scaleX = 1f; photoView.scaleY = 1f; photoView.alpha = 1f }.start()
            SlideEffect.TRANSLATE_FADE -> photoView.animate().translationX(100f).translationY(50f).alpha(0f).setDuration(1000)
                .withEndAction { photoView.translationX = 0f; photoView.translationY = 0f; photoView.alpha = 1f }.start()
            SlideEffect.BOUNCE -> photoView.animate().translationY(-50f).setDuration(300)
                .withEndAction { photoView.animate().translationY(0f).setDuration(300).start() }.start()
            SlideEffect.SHADOW -> photoView.animate().translationZ(20f).setDuration(500)
                .withEndAction { photoView.translationZ = 0f }.start()
            SlideEffect.FLIP_HORIZONTAL -> photoView.animate().rotationYBy(180f).setDuration(800)
                .withEndAction { photoView.rotationY = 0f }.start()
            SlideEffect.FLIP_VERTICAL -> photoView.animate().rotationXBy(180f).setDuration(800)
                .withEndAction { photoView.rotationX = 0f }.start()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // Listas para recordar los archivos ya mostrados
    private val shownImages = mutableSetOf<File>()
    private val shownVideos = mutableSetOf<File>()

    private fun nextMediaVideo() {
        if (mediaFiles.isEmpty()) return
        exoPlayer?.release()
        exoPlayer = null

        // Buscar el siguiente video que NO se haya mostrado aún
        var nextIndex = currentIndex + 1
        while (nextIndex < mediaFiles.size) {
            val file = mediaFiles[nextIndex]
            if (isVideo(file) && !shownVideos.contains(file)) {
                shownVideos.add(file)
                currentIndex = nextIndex
                showMedia(currentIndex)
                return
            }
            nextIndex++
        }

        // Si no quedan más videos sin mostrar
        binding.txtFileName.text = "Último video"
        isSlideShowRunning = false
    }

    private fun nextMediaImage() {
        if (mediaFiles.isEmpty()) return
        slideRunnable?.let { handler.removeCallbacks(it); isSlideShowRunning = false }

        // Buscar la siguiente imagen que NO se haya mostrado aún
        var nextIndex = currentIndex + 1
        while (nextIndex < mediaFiles.size) {
            val file = mediaFiles[nextIndex]
            if (!isVideo(file) && !shownImages.contains(file)) {
                shownImages.add(file)
                currentIndex = nextIndex
                showMedia(currentIndex)
                return
            }
            nextIndex++
        }

        // Si no quedan más imágenes sin mostrar
        binding.txtFileName.text = "Última imagen"
        isSlideShowRunning = false
    }


    private fun previousMedia() {
        if (mediaFiles.isEmpty()) return
        slideRunnable?.let { handler.removeCallbacks(it); isSlideShowRunning = false }
        exoPlayer?.release()
        exoPlayer = null

        currentIndex = if (currentIndex - 1 < 0) mediaFiles.size - 1 else currentIndex - 1
        showMedia(currentIndex)
    }

    private fun isVideo(file: File): Boolean {
        return file.extension.lowercase() in listOf("mp4","mkv","avi","mov","wmv","flv")
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        handler.removeCallbacks(updateTimeRunnable)
        slideRunnable?.let { handler.removeCallbacks(it) }
    }
}
