package com.creativem.galeriatv

import java.io.File

object UriHelper {

    fun listFiles(folderFile: File): List<FileItem> {
        val children = mutableListOf<FileItem>()
        if (!folderFile.exists() || !folderFile.isDirectory) return children

        folderFile.listFiles()?.forEach { file ->
            val isFolder = file.isDirectory
            val thumbnail = if (isFolder) findFirstMediaInFolder(file) else file

            children.add(
                FileItem(
                    file = file,
                    name = file.name,
                    isFolder = isFolder,
                    thumbnailFile = thumbnail
                )
            )
        }

        return children
    }

    private fun findFirstMediaInFolder(folder: File, depth: Int = 0, maxDepth: Int = 5): File? {
        if (depth > maxDepth) return null
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val nested = findFirstMediaInFolder(file, depth + 1, maxDepth)
                if (nested != null) return nested
            } else if (file.extension.lowercase() in listOf("jpg","jpeg","png","mp4","mkv","avi")) {
                return file
            }
        }
        return null
    }
}
