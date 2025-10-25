package com.creativem.galeriatv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.creativem.galeriatv.databinding.ActivitySlideshowBinding

class SlideshowActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySlideshowBinding

    companion object {
        private const val EXTRA_FOLDER_PATH = "extra_folder_path"

        fun start(context: Context, folderPath: String) {
            val intent = Intent(context, SlideshowActivity::class.java)
            intent.putExtra(EXTRA_FOLDER_PATH, folderPath)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySlideshowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: return

        // Aquí puedes cargar las imágenes de la carpeta y hacer el slideshow
    }
}
