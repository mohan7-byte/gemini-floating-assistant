package com.assistant.gemini

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraPreviewController(
    context: Context,
    private val previewView: PreviewView,
    private val onStatus: (String) -> Unit,
    private val onFrame: (Bitmap) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val owner = PreviewLifecycleOwner()
    @Volatile private var provider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var captureUseCase: ImageCapture? = null
    @Volatile private var stopped = false

    fun start() {
        stopped = false
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        owner.resume()
        CameraForegroundService.start(appContext)
        onStatus("Opening camera preview…")
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (stopped) return@addListener
            try {
                provider = future.get()
                val preview = Preview.Builder().setTargetResolution(Size(320, 240)).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(55)
                    .build()
                previewUseCase = preview
                captureUseCase = imageCapture
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                onStatus("Camera ready · low-resolution preview")
            } catch (e: Exception) {
                CameraForegroundService.stop(appContext)
                onStatus("Camera couldn't start: ${e.message ?: "permission or device error"}")
            }
        }, mainExecutor)
    }

    fun captureFrame() {
        val capture = captureUseCase
        if (stopped || capture == null) {
            onStatus("Camera is still starting. Try again in a moment.")
            return
        }
        onStatus("Capturing a low-resolution frame…")
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val plane = image.planes.firstOrNull() ?: throw IllegalStateException("No image data")
                    val buffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException("Camera image couldn't be decoded")
                    val rotation = image.imageInfo.rotationDegrees
                    if (rotation != 0) {
                        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                        if (rotated !== bitmap) bitmap.recycle()
                        bitmap = rotated
                    }
                    val scale = minOf(1f, 640f / maxOf(bitmap.width, bitmap.height))
                    if (scale < 1f) {
                        val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                        if (resized !== bitmap) bitmap.recycle()
                        bitmap = resized
                    }
                    val finalBitmap = bitmap
                    mainExecutor.execute {
                        if (stopped) finalBitmap.recycle() else onFrame(finalBitmap)
                    }
                } catch (e: Exception) {
                    mainExecutor.execute { onStatus("Camera capture failed: ${e.message ?: "couldn't read frame"}") }
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                mainExecutor.execute { onStatus("Camera capture failed: ${exception.message ?: "device error"}") }
            }
        })
    }

    fun stop() {
        stopped = true
        runCatching {
            provider?.unbind(*listOfNotNull(previewUseCase, captureUseCase).toTypedArray())
        }
        previewUseCase = null
        captureUseCase = null
        provider = null
        owner.destroy()
        captureExecutor.shutdownNow()
        CameraForegroundService.stop(appContext)
    }

    private class PreviewLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun destroy() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
