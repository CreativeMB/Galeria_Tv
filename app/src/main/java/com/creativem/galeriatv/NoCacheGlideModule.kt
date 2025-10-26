package com.creativem.galeriatv

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.DiskCache
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

@GlideModule
class NoCacheGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {

        // 🔹 Configura Glide para usar menos RAM y no guardar imágenes
        val requestOptions = RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565) // usa la mitad de memoria que ARGB_8888
            .disallowHardwareConfig() // evita problemas con GPU limitada (Android TV)

        builder.setDefaultRequestOptions(requestOptions)

        // 🚫 Desactiva totalmente cachés (RAM y disco)
        builder.setMemoryCache(LruResourceCache(0))
        builder.setDiskCache(object : DiskCache.Factory {
            override fun build(): DiskCache? = null // sin cache en disco
        })
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
