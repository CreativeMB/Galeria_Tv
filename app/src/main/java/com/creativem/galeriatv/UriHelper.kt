package com.creativem.galeriatv

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object UriHelper {

    /**
     * listFiles recibe:
     * rootUri = URI raíz seleccionada por el usuario
     * folderUri = carpeta que queremos listar (puede ser root o subcarpeta)
     */
    fun listFiles(context: Context, rootUri: Uri, folderUri: Uri): List<FileItem> {
        val children = mutableListOf<FileItem>()

        // ⚠️ Si es la raíz, usamos getTreeDocumentId, si no, getDocumentId
        val docId = if (folderUri == rootUri) {
            DocumentsContract.getTreeDocumentId(folderUri)
        } else {
            DocumentsContract.getDocumentId(folderUri)
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, docId)

        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )

        cursor?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                val documentId = c.getString(1)
                val mime = c.getString(2)
                val isFolder = DocumentsContract.Document.MIME_TYPE_DIR == mime
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)

                val thumbnail = if (isFolder) {
                    findFirstMediaInFolder(context, rootUri, fileUri)
                } else {
                    fileUri
                }

                children.add(FileItem(fileUri, name, isFolder, thumbnailUri = thumbnail))
            }
        }

        return children
    }

    // Buscar primer archivo multimedia en una carpeta (recursiva)
    private fun findFirstMediaInFolder(context: Context, rootUri: Uri, folderUri: Uri, depth: Int = 0, maxDepth: Int = 5): Uri? {
        if (depth > maxDepth) return null

        val docId = DocumentsContract.getDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, docId)

        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )

        cursor?.use { c ->
            while (c.moveToNext()) {
                val documentId = c.getString(0)
                val mime = c.getString(1)
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)

                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                    val nested = findFirstMediaInFolder(context, rootUri, fileUri, depth + 1, maxDepth)
                    if (nested != null) return nested
                } else if (mime.startsWith("image") || mime.startsWith("video")) {
                    return fileUri
                }
            }
        }

        return null
    }
}
