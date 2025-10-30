package com.creativem.galeriatv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.creativem.galeriatv.databinding.ActivityViewerBinding
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ViewerActivity : AppCompatActivity() {

    private var audioPlayer: ExoPlayer? = null
    private var isAudioPlayingForSlideShow = false
    private var lastPlayedAudioUri: Uri? = null
    private lateinit var binding: ActivityViewerBinding

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var photoView1: PhotoView? = null
    private var photoView2: PhotoView? = null
    private var isPhotoView1Active = true

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
                // proteger
            }
        }
    }

    private val mediaUnmountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        registerReceiver(mediaUnmountReceiver, filter)

        initReusableViews()

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
                ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "jpg", "jpeg", "png", "gif")
            }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Toast.makeText(this, "No hay permiso para acceder a la carpeta", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al listar archivos", Toast.LENGTH_SHORT).show()
            finish()
        }

        val selectedFile = try { File(Uri.parse(fileUriString).path!!) } catch (e: Exception) { null }
        currentIndex = selectedFile?.let { mediaFiles.indexOfFirst { it.absolutePath == selectedFile.absolutePath } } ?: -1
        if (currentIndex == -1) currentIndex = 0

        binding.btnPlayPause.setOnClickListener { togglePlayback() }

        setupFocusHighlight()
        loadAudioFolder()

        showMedia(currentIndex)
    }

    private fun initReusableViews() {
        val createPhotoView = {
            PhotoView(this).apply {
                maximumScale = 3f
                minimumScale = 1f
                isZoomable = true
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        photoView1 = createPhotoView()
        photoView2 = createPhotoView()
        binding.photoViewContainer.addView(photoView1)
        binding.photoViewContainer.addView(photoView2)

        playerView = PlayerView(this).apply {
            useController = false
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
    }

    private fun setupFocusHighlight() {
        binding.btnPlayPause.setBackgroundResource(R.drawable.item_background_selector)
        binding.btnPlayPause.isFocusable = true
        binding.btnPlayPause.isFocusableInTouchMode = true
    }

    @UnstableApi
    private fun showMedia(index: Int) {
        if (mediaFiles.isEmpty() || index !in mediaFiles.indices) return

        currentIndex = index
        val file = mediaFiles[index]

        binding.photoViewContainer.removeAllViews()
        releaseVideoPlayer()
        cancelSlideRunnable()

        binding.videoCenterIcon.visibility = View.GONE
        if (isVideo(file)) {
            if (!isSupportedVideo(file)) {
                Toast.makeText(this, "Formato de video no compatible: ${file.extension}", Toast.LENGTH_LONG).show()
                binding.txtFileName.text = "Formato no compatible. Usa VLC."
                return
            }

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
                            nextMediaVideo()
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Error al reproducir video", Toast.LENGTH_SHORT).show()
                nextMediaVideo()
            }
        } else {
            try {
                binding.photoViewContainer.addView(photoView1)
                binding.photoViewContainer.addView(photoView2)

                val activeView = if (isPhotoView1Active) photoView1!! else photoView2!!
                val inactiveView = if (isPhotoView1Active) photoView2!! else photoView1!!

                activeView.translationX = 0f
                activeView.alpha = 1f
                activeView.visibility = View.VISIBLE
                inactiveView.visibility = View.GONE

                val picMaxDim = calculateTargetImageSize()
                Glide.with(this)
                    .asBitmap()
                    .load(file)
                    .override(picMaxDim, picMaxDim)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    // --- CAMBIO: No guardar en caché de memoria RAM ---
                    .skipMemoryCache(true)
                    .thumbnail(0.15f)
                    .into(activeView)

            } catch (e: Exception) {
                Toast.makeText(this, "Error al mostrar imagen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSlideShowPreferences(): Int {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("slide_interval", 3).coerceIn(1, 30)
    }

    private fun isSupportedVideo(file: File): Boolean {
        return file.extension.lowercase() in listOf("mp4", "mkv", "mov")
    }

    private fun cancelSlideRunnable() {
        slideRunnable?.let {
            handler.removeCallbacks(it)
            slideRunnable = null
        }
        slideRunning.set(false)
    }

    private fun startSlideShowSafe(intervalMs: Long) {
        if (slideRunning.get()) return
        slideRunning.set(true)

        if (!isAudioPlayingForSlideShow) {
            playRandomAudioContinuously()
            isAudioPlayingForSlideShow = true
        }

        slideRunnable = object : Runnable {
            override fun run() {
                if (!advanceToNextImage()) {
                    slideRunning.set(false)
                    binding.txtFileName.text = "Fin de la presentación"
                    audioPlayer?.stop()
                    audioPlayer?.release()
                    audioPlayer = null
                    isAudioPlayingForSlideShow = false
                    return
                }

                val activeView = if (isPhotoView1Active) photoView1!! else photoView2!!
                val nextView = if (isPhotoView1Active) photoView2!! else photoView1!!

                val nextFile = mediaFiles[currentIndex]
                Glide.with(this@ViewerActivity)
                    .asBitmap()
                    .load(nextFile)
                    .override(calculateTargetImageSize(), calculateTargetImageSize())
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .thumbnail(0.15f)
                    .into(nextView)

                // --- INICIO DE LA NUEVA LÓGICA DE TRANSICIÓN LENTA Y MEZCLADA ---

                // 1. Duración de la animación. Aumentamos a 2.5 segundos para un efecto lento.
                //    ¡Puedes ajustar este valor a tu gusto!
                val transitionDuration = 2500L // 2.5 segundos

                // 2. La vista activa (la de abajo) se mantiene visible y opaca.
                activeView.alpha = 1f

                // 3. Preparamos la vista nueva (la que aparecerá encima).
                //    La hacemos visible, pero completamente transparente al inicio.
                nextView.alpha = 0f
                nextView.visibility = View.VISIBLE

                // 4. Aseguramos que la nueva vista esté al frente para que aparezca sobre la antigua.
                nextView.bringToFront()

                // 5. Animamos SOLAMENTE la nueva vista para que aparezca lentamente.
                nextView.animate()
                    .alpha(1f) // Se vuelve completamente opaca
                    .setDuration(transitionDuration)
                    // Usamos un interpolador que suaviza el inicio y el final de la animación.
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction {
                        // ESTO SE EJECUTA CUANDO LA ANIMACIÓN TERMINA
                        // Ahora que la nueva imagen cubre por completo a la antigua...

                        // a. Ocultamos la vista antigua, que ya no se ve.
                        activeView.visibility = View.GONE

                        // b. Limpiamos sus recursos de memoria.
                        if (isActivityAlive()) {
                            Glide.with(this@ViewerActivity).clear(activeView)
                        }

                        // c. Cambiamos el puntero a la vista activa.
                        isPhotoView1Active = !isPhotoView1Active
                    }
                    .start()

                // --- FIN DE LA NUEVA LÓGICA ---

                if (!isFinishing && slideRunning.get()) {
                    // El siguiente cambio se programará después del intervalo + la duración de la transición.
                    handler.postDelayed(this, intervalMs + transitionDuration)
                }
            }
        }

        handler.postDelayed(slideRunnable!!, intervalMs)
    }


    // --- NUEVA FUNCIÓN: Para evitar crashes al limpiar la vista si la activity ya no existe ---
    private fun isActivityAlive(): Boolean {
        return !isFinishing && !isDestroyed
    }


    private fun advanceToNextImage(): Boolean {
        var next = currentIndex + 1
        while (next < mediaFiles.size) {
            if (!isVideo(mediaFiles[next])) {
                currentIndex = next
                return true
            }
            next++
        }
        return false
    }

    @UnstableApi
    private fun nextMediaVideo() {
        releaseVideoPlayer()
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
            // Esta parte para los videos está perfecta, no se toca.
            exoPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
                updatePlayPauseUI(player.isPlaying)
            }
        } else {
            // --- CAMBIO CLAVE PARA LAS IMÁGENES ---
            if (slideRunning.get()) {
                // Si el carrusel está corriendo, simplemente lo detenemos.
                // NO TOCAMOS EL AUDIO.
                cancelSlideRunnable()
                updatePlayPauseUI(false)
            } else {
                // Si el carrusel está detenido, lo iniciamos.
                // La función startSlideShowSafe ya es inteligente y solo
                // iniciará el audio si no está sonando ya.
                val intervalSec = loadSlideShowPreferences()
                startSlideShowSafe(intervalSec * 1000L)
                updatePlayPauseUI(true)
            }
        }
    }

    private fun updatePlayPauseUI(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val isCurrentlyVideo = isVideo(mediaFiles.getOrNull(currentIndex) ?: return)
        binding.videoCenterIcon.visibility = if (isCurrentlyVideo && !isPlaying) View.VISIBLE else View.GONE
    }

    private fun calculateTargetImageSize(): Int {
        val displayMetrics = resources.displayMetrics
        val screenShort = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels)
        return Math.min(1080, Math.max(720, (screenShort * 0.8).toInt()))
    }

    private fun releaseVideoPlayer() {
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
    }

    private fun releasePlayer() {
        releaseVideoPlayer()
        audioPlayer?.release()
        audioPlayer = null
        isAudioPlayingForSlideShow = false
    }

    private fun finishSafely() {
        releasePlayer()
        cancelSlideRunnable()
        if (!isFinishing) finish()
    }

    @UnstableApi
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                advanceOrNext()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                previousOrPrevious()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                toggleBottomBarVisibility()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.bottomBar.visibility == View.VISIBLE) {
                    binding.btnPlayPause.callOnClick()
                } else {
                    togglePlayback()
                }
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
    }

    private fun toggleBottomBarVisibility() {
        val isVisible = binding.bottomBar.visibility == View.VISIBLE
        binding.bottomBar.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (isVisible) {
            binding.photoViewContainer.requestFocus()
        } else {
            binding.btnPlayPause.requestFocus()
        }
    }

    @UnstableApi
    private fun previousOrPrevious() {
        cancelSlideRunnable()
        releaseVideoPlayer()
        if (mediaFiles.isEmpty()) return

        var prevIndex = currentIndex - 1
        if (prevIndex < 0) prevIndex = mediaFiles.size - 1

        currentIndex = prevIndex
        showMedia(currentIndex)
    }

    @UnstableApi
    private fun advanceOrNext() {
        cancelSlideRunnable()
        releaseVideoPlayer()
        if (mediaFiles.isEmpty()) return

        var nextIndex = currentIndex + 1
        if (nextIndex >= mediaFiles.size) nextIndex = 0

        currentIndex = nextIndex
        showMedia(currentIndex)
    }


    private fun isVideo(file: File): Boolean {
        return file.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv")
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
        cancelSlideRunnable()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mediaUnmountReceiver)
        } catch (e: Exception) { /* ignore */ }
        finishSafely()
    }

    private fun formatTimeSafe(ms: Long): String {
        if (ms <= 0 || ms == Long.MIN_VALUE || ms == Long.MAX_VALUE) return "00:00"
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private var audioUris: List<Uri> = emptyList()

    private fun loadAudioFolder() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val folderPath = prefs.getString(KEY_AUDIO_FOLDER, null) ?: return
            val audioFolder = File(folderPath)
            if (!audioFolder.exists() || !audioFolder.isDirectory) {
                audioUris = emptyList()
                return
            }
            audioUris = audioFolder.listFiles { file ->
                file.isFile && file.extension.lowercase() in listOf("mp3", "wav", "m4a")
            }?.map { Uri.fromFile(it) } ?: emptyList()
        } catch (e: Exception) {
            audioUris = emptyList()
        }
    }

    private fun playRandomAudioContinuously() {
        try {
            if (audioUris.isEmpty()) return
            if (audioPlayer?.isPlaying == true) return

            if (audioPlayer == null) {
                audioPlayer = ExoPlayer.Builder(this).build()
                audioPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == ExoPlayer.STATE_ENDED && slideRunning.get()) {
                            playRandomAudioContinuously()
                        }
                    }
                })
            }

            if (audioPlayer?.playbackState == ExoPlayer.STATE_READY) {
                audioPlayer?.play()
            } else {
                var newRandomUri: Uri?
                if (audioUris.size > 1) {
                    do {
                        newRandomUri = audioUris.random()
                    } while (newRandomUri == lastPlayedAudioUri)
                } else {
                    newRandomUri = audioUris.firstOrNull()
                }

                if (newRandomUri != null) {
                    lastPlayedAudioUri = newRandomUri
                    audioPlayer?.setMediaItem(MediaItem.fromUri(newRandomUri))
                    audioPlayer?.prepare()
                    audioPlayer?.play()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al reproducir audio", Toast.LENGTH_SHORT).show()
        }
    }
}
