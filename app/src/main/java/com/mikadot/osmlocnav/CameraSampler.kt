package com.mikadot.osmlocnav

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraSampler(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null

    fun start(onReady: (Boolean, String?) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(82)
                    .build()
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }.onSuccess { onReady(true, null) }.onFailure { onReady(false, it.message) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun captureJpeg(callback: (Result<ByteArray>) -> Unit) {
        val c = capture ?: return callback(Result.failure(IllegalStateException("Камера не готова")))
        if (!busy.compareAndSet(false, true)) {
            callback(Result.failure(IllegalStateException("Предыдущий кадр ещё обрабатывается")))
            return
        }
        val file = File.createTempFile("osmloc_", ".jpg", context.cacheDir)
        c.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val result = runCatching { file.readBytes() }
                    file.delete(); busy.set(false); callback(result)
                }
                override fun onError(exception: ImageCaptureException) {
                    file.delete(); busy.set(false); callback(Result.failure(exception))
                }
            })
    }

    fun close() { provider?.unbindAll(); executor.shutdown() }
}
