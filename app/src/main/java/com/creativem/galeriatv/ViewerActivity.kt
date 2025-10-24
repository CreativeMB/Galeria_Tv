package com.creativem.galeriatv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class ViewerActivity : AppCompatActivity() {

    private var exoPlayer: ExoPlayer? = null

    companion object {
        private const val EXTRA_URI = "extra_uri"
        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, ViewerActivity::class.java)
            intent.putExtra(EXTRA_URI, uri.toString())
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) } ?: return

        if (uri.toString().endsWith(".mp4") || uri.toString().endsWith(".mkv")) {
            // PlayerView de Media3
            val playerView = PlayerView(this)
            setContentView(playerView)

            exoPlayer = ExoPlayer.Builder(this).build()
            playerView.player = exoPlayer

            val mediaItem = MediaItem.fromUri(uri)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
        } else {
            // Imagen con Glide
            val imageView = ImageView(this)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            setContentView(imageView)
            Glide.with(this).load(uri).into(imageView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}
