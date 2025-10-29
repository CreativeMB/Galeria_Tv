package com.creativem.galeriatv

import android.R.attr.duration
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.BitmapImageViewTarget
import com.creativem.galeriatv.databinding.ActivityViewerBinding
import com.github.chrisbanes.photoview.PhotoView
import jp.wasabeef.glide.transformations.BlurTransformation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.Exception
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewerActivity optimizada para TV de bajos recursos.
 *
 * Principales cambios:
 *  - Reutiliza una única PhotoView y PlayerView para evitar crear muchas vistas.
 *  - Carga imágenes en baja resolución para usar menos memoria.
 *  - Aplica blur de fondo con downscale para no consumir demasiada RAM/CPU.
 *  - Detecta desconexión de unidad y maneja errores de IO.
 *  - Evita múltiples postDelayed encolados.
 */
class ViewerActivity : AppCompatActivity() {

    private var audioFiles: List<File> = emptyList()
    private var audioPlayer: ExoPlayer? = null
    private var currentAudioIndex = 0
    private var shuffleAudio = true
    private var isAudioPlayingForSlideShow = false

    private var effectIndex = 0

    private lateinit var binding: ActivityViewerBinding

    // Player y vistas reutilizables
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var photoView: PhotoView? = null

    private var mediaFiles: List<File> = emptyList()
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var slideRunnable: Runnable? = null
    private val slideRunning = AtomicBoolean(false)

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            try {
                exoPlayer?.let { player ->
                    val duration = player.duration
                    val current = player.currentPosition
                    val remainingMs = if (duration > 0) duration - current else 0L
                    binding.txtFileName.text = formatTimeSafe(remainingMs)
                }
                if (!isFinishing && exoPlayer != null) handler.postDelayed(this, 500)
            } catch (e: Exception) {
                // proteger contra errores del player
            }
        }
    }

    // Receiver para detectar que la unidad se desmontó / extraída
    private val mediaUnmountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Si la tarjeta/usb se desmonta, avisar y cerrar activity limpiamente
            Toast.makeText(this@ViewerActivity, "Unidad de almacenamiento desconectada", Toast.LENGTH_SHORT).show()
            finishSafely()
        }
    }

    companion object {
        private const val EXTRA_FILE_URI = "extra_file_uri"
        private const val EXTRA_FOLDER_PATH = "extra_folder_path"
        private const val PREFS_NAME = "gallery_prefs"
        private const val KEY_AUDIO_FOLDER = "audio_folder_uri"

        fun start(context: Context, fileUri: Uri, folderPath: String) {
            val intent = Intent(context, ViewerActivity::class.java)
            intent.putExtra(EXTRA_FILE_URI, fileUri.toString())
            intent.putExtra(EXTRA_FOLDER_PATH, folderPath)
            context.startActivity(intent)
        }
    }
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Registrar receiver para eventos de almacenamiento removido
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        registerReceiver(mediaUnmountReceiver, filter)

        // Inicializar vistas reutilizables
        initReusableViews()

        // Tomar parámetros
        val fileUriString = intent.getStringExtra(EXTRA_FILE_URI)
        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)
        if (fileUriString == null || folderPath == null) {
            Toast.makeText(this, "Archivo no válido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            Toast.makeText(this, "Carpeta no encontrada", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            mediaFiles = folder.listFiles { f ->
                val ext = f.extension.lowercase()
                ext in listOf("mp4","mkv","avi","mov","wmv","flv","jpg","jpeg","png","gif")
            }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Toast.makeText(this, "No hay permiso para acceder a la carpeta", Toast.LENGTH_SHORT).show()
            finish()
            return
        } catch (e: Exception) {
            Toast.makeText(this, "Error al listar archivos", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Ubicar índice inicial de archivo seleccionado (fallback 0)
        val selectedFile = try {
            File(Uri.parse(fileUriString).path!!)
        } catch (e: Exception) {
            null
        }
        currentIndex = selectedFile?.let { mediaFiles.indexOfFirst { it.absolutePath == selectedFile.absolutePath } } ?: -1
        if (currentIndex == -1) currentIndex = 0

        binding.btnPlayPause.setOnClickListener { togglePlayback() }

        // focus y accesibilidad básica
        setupFocusHighlight()
        loadAudioFolder()

        // arrancar mostrando el archivo actual (no iniciar slideshow hasta que se muestre)
        showMedia(currentIndex)
    }

    private fun initReusableViews() {
        // PhotoView único reutilizable
        photoView = PhotoView(this).also {
            it.maximumScale = 3f
            it.minimumScale = 1f
            it.isZoomable = true
            // no agregamos todavía; se agrega en photoContainer cuando se muestre
        }

        // PlayerView único reutilizable
        playerView = PlayerView(this).apply {
            useController = false
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        // No lo añadimos ahora si no es necesario; lo añadimos al container cuando toca reproducir video
    }

    private fun setupFocusHighlight() {
        binding.btnPlayPause.setBackgroundResource(R.drawable.item_background_selector)
        binding.btnPlayPause.isFocusable = true
        binding.btnPlayPause.isFocusableInTouchMode = true
    }

    @UnstableApi
    @OptIn(UnstableApi::class)
    private fun showMedia(index: Int) {
        if (mediaFiles.isEmpty()) return
        if (index !in mediaFiles.indices) return

        currentIndex = index
        val file = mediaFiles[index]

        // Limpiar container solo si se va a mostrar algo
        binding.photoViewContainer.removeAllViews()

        // Detener player y slideshow
        releasePlayer()
        cancelSlideRunnable()

        binding.videoCenterIcon.visibility = View.GONE
        if (isVideo(file)) {
            // Verificar compatibilidad
            if (!isSupportedVideo(file)) {
                Toast.makeText(this, "Formato de video no compatible: ${file.extension}", Toast.LENGTH_LONG).show()
                binding.txtFileName.text = "Formato no compatible Usa Vlc "
                return
            }

            // --- VIDEO ---
            try {
                binding.photoViewContainer.addView(playerView)
                playerView?.visibility = View.VISIBLE

                if (exoPlayer == null) exoPlayer = ExoPlayer.Builder(this).build()
                playerView?.player = exoPlayer
                exoPlayer?.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                exoPlayer?.prepare()
                exoPlayer?.play()
                updatePlayPauseUI(true)
                handler.post(updateTimeRunnable)

                exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == ExoPlayer.STATE_ENDED) {
                            if (currentIndex >= mediaFiles.lastIndex) {
                                // Último video: detener player y slideshow
                                releasePlayer()
                                binding.txtFileName.text = "Último video"
                            } else {
                                nextMediaVideo()
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Error al reproducir video", Toast.LENGTH_SHORT).show()
                if (currentIndex < mediaFiles.lastIndex) nextMediaVideo() else releasePlayer()
            }

        } else {
            // --- IMAGEN ---
            try {
                binding.photoViewContainer.addView(photoView)
                photoView?.visibility = View.VISIBLE



                val picMaxDim = calculateTargetImageSize()
                Glide.with(this)
                    .asBitmap()
                    .load(file)
                    .override(picMaxDim, picMaxDim)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .thumbnail(0.15f)
                    .into(photoView!!)

// Verificar si hay más imágenes para slideshow
                val hasMoreImages = mediaFiles.drop(currentIndex + 1).any { !isVideo(it) }

                if (hasMoreImages) {
                    // Cargar preferencias guardadas
                    val (intervalSec, randomMode, savedEffects) = loadSlideShowPreferences()
                    val intervalMs = intervalSec * 1000L

                    // Iniciar slideshow con efectos guardados y modo aleatorio si corresponde
                    startSlideShowSafe(intervalMs, savedEffects, randomMode)
                } else {
                    // Última imagen: mostrar solo, sin slideshow
                    binding.txtFileName.text = "Última imagen"
                    cancelSlideRunnable()
                }




                updatePlayPauseUI(true)

            } catch (e: Exception) {
                Toast.makeText(this, "Error al mostrar imagen", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun loadSlideShowPreferences(): Triple<Int, Boolean, List<ViewerActivity.SlideEffect>> {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE) // mismo nombre
        val interval = prefs.getInt("slide_interval", 3).coerceIn(2, 30)
        val randomMode = prefs.getBoolean("slide_random", true)

        val savedEffectsString = prefs.getString("slide_effects_ordered", "TRANSLATE") ?: "TRANSLATE"
        val effects = savedEffectsString
            .split(",")
            .mapNotNull { runCatching { ViewerActivity.SlideEffect.valueOf(it) }.getOrNull() }
            .ifEmpty { listOf(ViewerActivity.SlideEffect.TRANSLATE) }

        // Log para depuración
        Log.d("ViewerActivity", "Cargando slideshow prefs: interval=$interval, random=$randomMode, effects=${effects.joinToString()}")

        return Triple(interval, randomMode, effects)
    }


    private fun isSupportedVideo(file: File): Boolean {
        return file.extension.lowercase() in listOf("mp4","mkv","mov") // formatos seguros
    }

    // Evitar multiples runnables
    private fun cancelSlideRunnable() {
        slideRunnable?.let {
            handler.removeCallbacks(it)
            slideRunnable = null
        }
        slideRunning.set(false)
    }

    private fun startSlideShowSafe(intervalMs: Long, effects: List<SlideEffect>, random: Boolean) {
        if (slideRunning.get()) return
        slideRunning.set(true)
        effectIndex = 0
        if (!isAudioPlayingForSlideShow) {
            playRandomAudioContinuously()
            isAudioPlayingForSlideShow = true
        }
        slideRunnable = object : Runnable {
            override fun run() {
                val advanced = advanceToNextImage()
                if (!advanced) {
                    // fin de presentación
                    slideRunning.set(false)
                    binding.txtFileName.text = "Fin de la presentación"

                    // Detener audio al final
                    audioPlayer?.stop()
                    audioPlayer?.release()
                    audioPlayer = null
                    return
                }

                val currentFile = mediaFiles[currentIndex]

                Glide.with(this@ViewerActivity)
                    .asBitmap()
                    .load(currentFile)
                    .override(calculateTargetImageSize(), calculateTargetImageSize())
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .thumbnail(0.15f)
                    .into(photoView!!)

                updateBlurBackgroundOptimized(currentFile)

                photoView?.alpha = 0f
                photoView?.animate()?.alpha(1f)?.setDuration(500)?.start()

                // Seleccionar efecto
                val effect = if (random) effects.random()
                else {
                    val e = effects[effectIndex % effects.size]
                    effectIndex++
                    e
                }

                // Aplicar efecto optimizado, duración proporcional al intervalo
                applyEffectOptimized(photoView!!, effect, intervalMs * 0.7f.toLong())

                if (!isFinishing && slideRunning.get()) handler.postDelayed(this, intervalMs)
            }
        }

        slideRunning.set(true)
        slideRunnable?.let { handler.postDelayed(it, intervalMs) }
    }


    private fun advanceToNextImage(): Boolean {
        var next = currentIndex + 1
        while (next < mediaFiles.size) {
            val f = mediaFiles[next]
            if (!isVideo(f)) {
                currentIndex = next
                return true
            }
            next++
        }
        return false // última imagen, no hacer nada
    }


    @androidx.annotation.OptIn(UnstableApi::class)
    private fun nextMediaVideo() {
        releasePlayer()
        var nextIndex = currentIndex + 1
        while (nextIndex < mediaFiles.size) {
            val file = mediaFiles[nextIndex]
            if (isVideo(file)) {
                currentIndex = nextIndex
                showMedia(currentIndex)
                return
            }
            nextIndex++
        }
        binding.txtFileName.text = "Último video"
    }

    private fun togglePlayback() {
        val file = mediaFiles.getOrNull(currentIndex) ?: return

        if (isVideo(file)) {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    updatePlayPauseUI(false)
                    Toast.makeText(this, "Video pausado", Toast.LENGTH_SHORT).show()
                } else {
                    player.play()
                    updatePlayPauseUI(true)
                    Toast.makeText(this, "Video reproduciéndose", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            if (!slideRunning.get()) {
                // iniciar slideshow con configuración por defecto segura
                startSlideShowSafe(3000L, listOf(SlideEffect.TRANSLATE, SlideEffect.FADE), true)
                Toast.makeText(this, "Iniciando presentación", Toast.LENGTH_SHORT).show()
                updatePlayPauseUI(true)
            } else {
                cancelSlideRunnable()
                Toast.makeText(this, "Presentación detenida", Toast.LENGTH_SHORT).show()
                updatePlayPauseUI(false)
            }
        }
    }

    private fun updatePlayPauseUI(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        binding.videoCenterIcon.visibility = if (isPlaying) View.GONE else View.VISIBLE
    }

    private fun calculateTargetImageSize(): Int {
        // Reducir la resolución de las imágenes para TV de bajos recursos:
        val displayMetrics = resources.displayMetrics
        val screenShort = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels)
        // limitar a como máximo 1080, pero en TVs de bajos recursos preferir 800
        return Math.min(1080, Math.max(720, (screenShort * 0.8).toInt()))
    }

    private fun updateBlurBackgroundOptimized(file: File) {
        // Para ahorrar CPU/RAM: cargamos una versión muy reducida para el blur
        try {
            val blurSize = 200 // pequeño -> bajo consumo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Para Android 12+ usamos RenderEffect pero con bitmap downscaled
                Glide.with(this)
                    .asBitmap()
                    .load(file)
                    .override(blurSize, blurSize)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(object : BitmapImageViewTarget(binding.blurBackground) {
                        override fun setResource(resource: Bitmap?) {
                            super.setResource(resource)
                            resource?.let {
                                try {
                                    val drawable = BitmapDrawable(resources, it)
                                    binding.blurBackground.setImageDrawable(drawable)
                                    val blurEffect = RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
                                    binding.blurBackground.setRenderEffect(blurEffect)
                                } catch (e: Exception) {
                                    // fallback a Glide transform si falla
                                    Glide.with(this@ViewerActivity)
                                        .load(file)
                                        .transform(BlurTransformation(10, 2))
                                        .into(binding.blurBackground)
                                }
                            }
                        }
                    })
            } else {
                // Para versiones antiguas usar la transformación de Glide pero con parámetros reducidos
                Glide.with(this)
                    .load(file)
                    .override(blurSize, blurSize)
                    .transform(BlurTransformation(10, 2))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(binding.blurBackground)
            }
        } catch (e: Exception) {
            // no bloquear UI si falla el blur
        }
    }

    private fun releasePlayer() {
        try {
            playerView?.player = null
            exoPlayer?.release()
            exoPlayer = null
            handler.removeCallbacks(updateTimeRunnable)
        } catch (e: Exception) {
            // proteger
        } finally {
            playerView?.visibility = View.GONE
        }
        audioPlayer?.release()
        audioPlayer = null

    }

    private fun finishSafely() {
        // cerrar limpiamente
        releasePlayer()
        cancelSlideRunnable()
        if (!isFinishing) finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                advanceOrNext()
                hideBottomBarIfVisible()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                previousOrPrevious()
                hideBottomBarIfVisible()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                advanceOrNext()  // también avanzar con UP si quieres
                hideBottomBarIfVisible()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                previousOrPrevious() // también retroceder con DOWN si quieres
                hideBottomBarIfVisible()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                toggleBottomBar()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
    }
    private fun previousOrPrevious() {
        cancelSlideRunnable()
        releasePlayer()
        if (mediaFiles.isEmpty()) return

        var prevIndex = currentIndex - 1
        if (prevIndex < 0) prevIndex = mediaFiles.size - 1

        currentIndex = prevIndex
        showMedia(currentIndex)
    }


    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(UnstableApi::class)
    private fun advanceOrNext() {
        val file = mediaFiles.getOrNull(currentIndex) ?: return
        cancelSlideRunnable() // evitar animaciones simultáneas

        if (isVideo(file)) {
            nextMediaVideo()
        } else {
            val hasNext = advanceToNextImage()
            if (hasNext) showMedia(currentIndex)
            else binding.txtFileName.text = "Última imagen"
        }
    }


    // Método auxiliar para ocultar bottomBar si está visible
    private fun hideBottomBarIfVisible() {
        if (binding.bottomBar.visibility == View.VISIBLE) {
            binding.bottomBar.visibility = View.GONE
        }
    }

    private fun toggleBottomBar() {
        val bottomBarVisible = binding.bottomBar.visibility == View.VISIBLE
        if (bottomBarVisible) {
            binding.bottomBar.visibility = View.GONE
            binding.photoViewContainer.requestFocus()
        } else {
            binding.bottomBar.visibility = View.VISIBLE
            binding.btnPlayPause.requestFocus()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun previousMedia() {
        cancelSlideRunnable()
        releasePlayer()
        if (mediaFiles.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) mediaFiles.size - 1 else currentIndex - 1
        showMedia(currentIndex)
    }

    private fun isVideo(file: File): Boolean {
        return file.extension.lowercase() in listOf("mp4","mkv","avi","mov","wmv","flv")
    }

    override fun onStop() {
        super.onStop()
        // liberar recursos cuando la activity no está visible -> reduce OOM en TV
        releasePlayer()
        cancelSlideRunnable()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mediaUnmountReceiver)
        } catch (e: Exception) { /* ignore */ }
        releasePlayer()
        cancelSlideRunnable()
    }

    // Formateo seguro de tiempo mostrado
    private fun formatTimeSafe(ms: Long): String {
        if (ms <= 0 || ms == Long.MIN_VALUE || ms == Long.MAX_VALUE) return "00:00"
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // --- Animaciones y efectos ligeros ---
    enum class SlideEffect {
        TRANSLATE,
        ZOOM,
        FADE,
        SCALE,
        BOUNCE
    }

    // Animaciones optimizadas para TV: delta pequeño, duración proporcional
    private fun applyEffectOptimized(photoView: PhotoView, effect: ViewerActivity.SlideEffect, duration: Long) {
        val interpolator = DecelerateInterpolator()

        try {
            when (effect) {
                ViewerActivity.SlideEffect.TRANSLATE -> {
                    val dx = 15f
                    val dy = 15f
                    photoView.translationX = 0f
                    photoView.translationY = 0f
                    photoView.animate()
                        .translationX(dx)
                        .translationY(dy)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .withEndAction {
                            photoView.translationX = 0f
                            photoView.translationY = 0f
                        }.start()
                }

                ViewerActivity.SlideEffect.ZOOM -> {
                    val scale = 1.05f
                    photoView.scaleX = 1f
                    photoView.scaleY = 1f
                    photoView.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .withEndAction {
                            photoView.scaleX = 1f
                            photoView.scaleY = 1f
                        }.start()
                }

                ViewerActivity.SlideEffect.FADE -> {
                    photoView.alpha = 0.85f
                    photoView.animate()
                        .alpha(1f)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .start()
                }

                ViewerActivity.SlideEffect.SCALE -> {
                    val scale = 0.96f
                    photoView.scaleX = 1f
                    photoView.scaleY = 1f
                    photoView.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .withEndAction {
                            photoView.scaleX = 1f
                            photoView.scaleY = 1f
                        }.start()
                }

                ViewerActivity.SlideEffect.BOUNCE -> {
                    photoView.translationY = -8f
                    photoView.animate()
                        .translationY(0f)
                        .setDuration(duration)
                        .setInterpolator(BounceInterpolator())
                        .start()
                }
            }
        } catch (e: Exception) {
            // Protege TV de bajo rendimiento
        }
    }

    private var audioUris: List<Uri> = emptyList()

    private fun loadAudioFolder() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val folderPath = prefs.getString(KEY_AUDIO_FOLDER, null)
            if (folderPath.isNullOrEmpty()) {
                audioUris = emptyList()
                return
            }

            val audioFolder = File(folderPath)
            if (!audioFolder.exists() || !audioFolder.isDirectory) {

                audioUris = emptyList()
                return
            }
            val children = audioFolder.listFiles()
            if (children == null) {
                audioUris = emptyList()
                return
            }



            audioUris = children
                .filter { it.isFile && (it.extension.equals("mp3", true) || it.extension.equals("wav", true) || it.extension.equals("m4a", true)) }
                .map { Uri.fromFile(it) }
            if (audioUris.isEmpty()) {
            } else {

            }

        } catch (e: Exception) {

            audioUris = emptyList()
        }
    }

    private fun playRandomAudioContinuously() {
        try {
            if (audioUris.isEmpty()) {

                Toast.makeText(this, "No hay audios para reproducir", Toast.LENGTH_SHORT).show()
                return
            }
            // Detener audio anterior
            audioPlayer?.release()
            audioPlayer = null
            val randomUri = audioUris.random()
            Toast.makeText(this, "Reproduciendo: ${randomUri.lastPathSegment}", Toast.LENGTH_SHORT).show()
            audioPlayer = ExoPlayer.Builder(this).build().apply {
                setMediaItem(MediaItem.fromUri(randomUri))
                prepare()
                play()
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {

                        if (state == ExoPlayer.STATE_ENDED) {
                            if (slideRunning.get()) {
                                playRandomAudioContinuously()
                            }
                        }
                    }
                })
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al reproducir audio", Toast.LENGTH_SHORT).show()
        }
    }



}
