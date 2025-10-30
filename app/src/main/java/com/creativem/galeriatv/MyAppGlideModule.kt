package com.creativem.galeriatv

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.DiskCache
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule

/**
 * Glide configurado para no usar caché en memoria ni en disco.
 * Ideal para Android TV o dispositivos con almacenamiento limitado.
 */
@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun isManifestParsingEnabled(): Boolean = false

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 🔹 Desactiva completamente el uso de caché en disco
        builder.setDiskCache { DiskCacheAdapter() }

        // 🔹 Desactiva prácticamente el caché en memoria (1 byte)
        builder.setMemoryCache(LruResourceCache(1))
    }
}
