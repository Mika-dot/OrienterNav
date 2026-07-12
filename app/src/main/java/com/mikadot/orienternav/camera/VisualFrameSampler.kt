package com.mikadot.orienternav.camera

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

class VisualFrameSampler(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null

    fun start(onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                val preview =
                    Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                imageCapture =
                    ImageCapture
                        .Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(82)
                        .build()
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }.onSuccess { onReady(true) }.onFailure { onReady(false) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun capture(onResult: (Result<ByteArray>) -> Unit) {
        val capture = imageCapture ?: return onResult(Result.failure(IllegalStateException("Камера не готова")))
        if (!capturing.compareAndSet(false, true)) return
        val file = File.createTempFile("orienter_frame_", ".jpg", context.cacheDir)
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            output,
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val result = runCatching { file.readBytes() }
                    file.delete()
                    capturing.set(false)
                    onResult(result)
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    capturing.set(false)
                    onResult(Result.failure(exception))
                }
            },
        )
    }

    fun stop() {
        provider?.unbindAll()
        imageCapture = null
    }

    fun close() {
        stop()
        ioExecutor.shutdown()
    }
}
