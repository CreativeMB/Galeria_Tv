package com.creativem.galeriatv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
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
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.BitmapImageViewTarget
import jp.wasabeef.glide.transformations.BlurTransformation
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
            val ext = f.extension.lowercase()
            ext in listOf("mp4","mkv","avi","mov","wmv","flv","jpg","jpeg","png","gif")
        }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()

        startSlideShow()
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
            // --- IMAGEN ---
            val photoView = PhotoView(this)
            binding.photoViewContainer.removeAllViews()
            binding.photoViewContainer.addView(photoView)

// Imagen principal
            Glide.with(this)
                .load(file)
                .into(photoView)

// 🔹 Actualiza el fondo difuminado con la misma imagen
            updateBlurBackground(file)


            binding.videoCenterIcon.visibility = View.GONE

            if (isSlideShowRunning) {
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val intervalMs = prefs.getInt("slide_interval", 3) * 1000L
                val randomMode = prefs.getBoolean("slide_random", true)
                val effectNames = prefs.getStringSet("slide_effects", setOf("TRANSLATE","ZOOM","FADE")) ?: setOf("TRANSLATE")
                val effects = effectNames.map { SlideEffect.valueOf(it) }

                // Llamada correcta con los 3 argumentos
                prepareSlideShow(intervalMs, effects, randomMode)

                slideRunnable?.let { handler.post(it) }
            }


        }
    }
    private fun updateBlurBackground(file: File) {
        Glide.with(this)
            .asBitmap()
            .load(file)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(object : BitmapImageViewTarget(binding.blurBackground) {
                override fun setResource(resource: Bitmap?) {
                    super.setResource(resource)
                    resource?.let {
                        applyBlur(binding.blurBackground, it)
                    }
                }
            })
    }


    private fun applyBlur(imageView: ImageView, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val drawable = BitmapDrawable(imageView.resources, bitmap)
            imageView.setImageDrawable(drawable)
            val blurEffect = RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            imageView.setRenderEffect(blurEffect)
        } else {
            Glide.with(imageView.context)
                .load(bitmap)
                .transform(BlurTransformation(25, 3))
                .into(imageView)
        }
    }


    // --- Slideshow ---
    private fun toggleSlideShow() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val intervalMs = prefs.getInt("slide_interval", 3) * 1000L
        val randomMode = prefs.getBoolean("slide_random", true)
        val effectNames = prefs.getStringSet("slide_effects", setOf("TRANSLATE","ZOOM","FADE")) ?: setOf("TRANSLATE")
        val effects = effectNames.map { SlideEffect.valueOf(it) }

        if (!isSlideShowRunning) {
            // Reiniciar registro solo si quieres empezar desde cero
            // shownImages.clear()

            prepareSlideShow(intervalMs, effects, randomMode)
            slideRunnable?.let { handler.post(it) }
            isSlideShowRunning = true
            Toast.makeText(this, "Iniciando presentación", Toast.LENGTH_SHORT).show()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            slideRunnable?.let { handler.removeCallbacks(it) }
            isSlideShowRunning = false
            Toast.makeText(this, "Presentación detenida", Toast.LENGTH_SHORT).show()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    // --- Slideshow ---
    // --- Avanza a la siguiente imagen y retorna true si encontró alguna ---
    private fun nextMediaImage(): Boolean {
        if (mediaFiles.isEmpty()) return false

        // Buscar la siguiente imagen que aún no se haya mostrado
        val nextIndex = mediaFiles.indices.firstOrNull { i ->
            !isVideo(mediaFiles[i]) && !shownImages.contains(mediaFiles[i])
        }

        if (nextIndex == null) {
            // No quedan más imágenes sin mostrar
            isSlideShowRunning = false
            binding.txtFileName.text = "Última imagen"
            slideRunnable?.let { handler.removeCallbacks(it) }
            Toast.makeText(this, "Fin de la presentación", Toast.LENGTH_SHORT).show()
            return false
        }

        currentIndex = nextIndex
        val currentFile = mediaFiles[currentIndex]
        shownImages.add(currentFile) // Marcar como mostrada

        // Mostrar la imagen
        val photoView = binding.photoViewContainer.getChildAt(0) as? PhotoView
            ?: PhotoView(this).also {
                binding.photoViewContainer.removeAllViews()
                binding.photoViewContainer.addView(it)
            }
        Glide.with(this).load(currentFile).into(photoView)
        binding.txtFileName.text = currentFile.name

        return true
    }

    // --- Slideshow ---
    private fun prepareSlideShow(intervalMs: Long, effects: List<SlideEffect>, random: Boolean) {
        slideRunnable?.let { handler.removeCallbacks(it) }

        slideRunnable = object : Runnable {
            override fun run() {
                val advanced = nextMediaImage() // centralizamos toda la lógica aquí
                if (!advanced) return // Si no hay más imágenes, se detiene

                // Obtener el archivo actual después de avanzar
                val currentFile = mediaFiles[currentIndex]

                // Mostrar la imagen con transición suave
                val oldPhoto = binding.photoViewContainer.getChildAt(0) as? PhotoView
                val newPhoto = PhotoView(this@ViewerActivity)

                binding.photoViewContainer.addView(newPhoto)
                newPhoto.alpha = 0f
                Glide.with(this@ViewerActivity).load(currentFile).into(newPhoto)
// 🔹 Actualizar el fondo difuminado
                updateBlurBackground(currentFile)
                // Animar el desvanecimiento
                oldPhoto?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
                    binding.photoViewContainer.removeView(oldPhoto)
                }?.start()

                newPhoto.animate().alpha(1f).setDuration(800).start()

                // Aplicar efecto adicional si se quiere
                val effect = if (random) effects.random() else effects.firstOrNull() ?: SlideEffect.TRANSLATE
                applyEffect(newPhoto, effect)

                // Actualizar nombre
                binding.txtFileName.text = currentFile.name

                // Preparar siguiente paso
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    // --- Inicializar presentación ---
    private fun startSlideShow() {
        // Reiniciar la lista de imágenes mostradas
        shownImages.clear()
        // Siempre comenzar con la imagen más reciente (índice 0 después de ordenar)
        currentIndex = 0
        val currentFile = mediaFiles.getOrNull(currentIndex)
        currentFile?.let { shownImages.add(it) }
        showMedia(currentIndex)
        isSlideShowRunning = true

        // Preparar slideshow con efectos y randomMode
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val intervalMs = prefs.getInt("slide_interval", 3) * 1000L
        val randomMode = prefs.getBoolean("slide_random", true)
        val effectNames = prefs.getStringSet("slide_effects", setOf("TRANSLATE","ZOOM","FADE")) ?: setOf("TRANSLATE")
        val effects = effectNames.map { SlideEffect.valueOf(it) }

        prepareSlideShow(intervalMs, effects, randomMode)
        slideRunnable?.let { handler.postDelayed(it, intervalMs) }
    }


    enum class SlideEffect {
        TRANSLATE, ZOOM, FADE, ROTATE, SCALE,
        ROTATE_SCALE, ZOOM_FADE, TRANSLATE_FADE,
        BOUNCE, SHADOW, FLIP_HORIZONTAL, FLIP_VERTICAL
    }

    private fun applyEffect(photoView: PhotoView, effect: SlideEffect) {
        val duration = 800L
        val interpolator = AccelerateDecelerateInterpolator()

        when(effect) {
            SlideEffect.TRANSLATE -> photoView.animate()
                .translationX(30f).translationY(30f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction {
                    photoView.translationX = 0f
                    photoView.translationY = 0f
                }.start()

            SlideEffect.ZOOM -> photoView.animate()
                .scaleX(1.15f).scaleY(1.15f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction {
                    photoView.scaleX = 1f
                    photoView.scaleY = 1f
                }.start()

            SlideEffect.FADE -> photoView.animate()
                .alpha(0f)
                .setDuration(duration / 2)
                .setInterpolator(interpolator)
                .withEndAction {
                    photoView.alpha = 1f
                }.start()

            SlideEffect.ROTATE -> photoView.animate()
                .rotationBy(180f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction {
                    photoView.rotation = 0f
                }.start()

            SlideEffect.SCALE -> photoView.animate()
                .scaleX(0.85f).scaleY(0.85f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction {
                    photoView.scaleX = 1f
                    photoView.scaleY = 1f
                }.start()

            SlideEffect.TRANSLATE_FADE -> {
                photoView.alpha = 0f
                photoView.translationX = 50f
                photoView.translationY = 50f
                photoView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start()
            }

            SlideEffect.ZOOM_FADE -> {
                photoView.alpha = 0f
                photoView.scaleX = 1.2f
                photoView.scaleY = 1.2f
                photoView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start()
            }

            SlideEffect.BOUNCE -> {
                photoView.translationY = -30f
                photoView.animate()
                    .translationY(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start()
            }

            SlideEffect.FLIP_HORIZONTAL -> {
                photoView.rotationY = -90f
                photoView.animate()
                    .rotationY(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start()
            }

            SlideEffect.FLIP_VERTICAL -> {
                photoView.rotationX = -90f
                photoView.animate()
                    .rotationX(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start()
            }

            SlideEffect.ROTATE_SCALE -> {
                photoView.scaleX = 1.2f
                photoView.scaleY = 1.2f
                photoView.rotation = 45f
                photoView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start()
            }

            SlideEffect.SHADOW -> {
                photoView.translationZ = 20f
                photoView.animate()
                    .translationZ(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start()
            }
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
