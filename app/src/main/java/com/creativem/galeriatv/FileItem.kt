package com.creativem.galeriatv

import android.net.Uri

data class FileItem(
    val uri: Uri,
    val name: String,
    val isFolder: Boolean,
    val thumbnailUri: Uri? = null
)

