package com.creativem.galeriatv

import java.io.File

data class FileItem(
    val file: File,
    val name: String,
    val isFolder: Boolean,
    val thumbnailFile: File? = null, // <-- NUEVO CAMPO
    val isAudioFolderItem: Boolean = false // Si sigues usando esto
)