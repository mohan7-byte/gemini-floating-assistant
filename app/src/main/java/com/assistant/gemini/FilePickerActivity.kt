package com.assistant.gemini

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Opens a SAF document picker and extracts text from .txt, .md, and text-based PDF files. */
class FilePickerActivity : Activity() {
    private val request = 4807
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/pdf"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, request)
    }

    @Deprecated("Uses callback for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != request) return
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) {
            ScreenshotBridge.statusReceiver?.invoke("File selection cancelled.")
            ScreenshotBridge.captureFinishedReceiver?.invoke()
            finish()
            return
        }
        Thread({
            try {
                val (name, size) = queryFileMetadata(uri)
                if (size > MAX_PDF_BYTES) throw IllegalArgumentException("This file is over 12 MB. Choose a smaller file to keep memory use low.")
                val extension = name.substringAfterLast('.', "").lowercase()
                val extracted = when (extension) {
                    "txt", "md", "markdown" -> readTextLimited(contentResolver.openInputStream(uri) ?: error("Unable to open file"))
                    "pdf" -> extractPdf(uri)
                    else -> throw IllegalArgumentException("Choose a .txt, .md, or .pdf file.")
                }
                if (extracted.isBlank()) throw IllegalArgumentException("No readable text was found. Scanned/image-only PDFs need OCR and aren't supported by this text extractor yet.")
                ScreenshotBridge.fileTextReceiver?.invoke(name, extracted.take(MAX_EXTRACTED_CHARS))
                ScreenshotBridge.statusReceiver?.invoke("Preparing $name for Gemini Live…")
            } catch (e: Exception) {
                ScreenshotBridge.statusReceiver?.invoke(e.message ?: "Could not read that file.")
            } finally {
                ScreenshotBridge.captureFinishedReceiver?.invoke()
                runOnUiThread { finish() }
            }
        }, "GeminiFileExtraction").start()
    }

    private fun queryFileMetadata(uri: Uri): Pair<String, Long> {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: "selected-file"
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else 0L
                name to size
            } else "selected-file" to 0L
        } finally { cursor?.close() }
    }

    private fun readTextLimited(input: InputStream): String {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                total += count
                if (total > MAX_FILE_BYTES) throw IllegalArgumentException("This text file is too large. Keep it under 512 KB.")
                out.write(buffer, 0, count)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun extractPdf(uri: Uri): String {
        PDFBoxResourceLoader.init(applicationContext)
        val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Unable to open PDF")
        input.use { stream ->
            PDDocument.load(stream).use { document ->
                if (document.numberOfPages < 1) return ""
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = minOf(document.numberOfPages, MAX_PDF_PAGES)
                }
                return stripper.getText(document)
            }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 512 * 1024
        private const val MAX_PDF_BYTES = 12L * 1024 * 1024
        private const val MAX_EXTRACTED_CHARS = 24_000
        private const val MAX_PDF_PAGES = 25
    }
}
