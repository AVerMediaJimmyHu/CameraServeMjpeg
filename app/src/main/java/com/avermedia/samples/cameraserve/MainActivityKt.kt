package com.avermedia.samples.cameraserve

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.arkconcepts.cameraserve.R
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.system.exitProcess

class MainActivityKt : AppCompatActivity(), PreviewCallback,
    MjpegServerKt.OnJpegFrame, TextureView.SurfaceTextureListener {
    private var camera: Camera? = null
    private var cameraId: Int = 0
    private var previewRunning: Boolean = false
    private var surface: SurfaceTexture? = null

    private lateinit var mjpegServer: MjpegServerKt
    private lateinit var serverThread: Thread
    private val frameLock = ReentrantReadWriteLock()
    private val previewStream = ByteArrayOutputStream()
    private var jpegFrame: ByteArray = ByteArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main_kt)
        prepareUi()
        // FIXME: check camera permission
        mjpegServer = MjpegServerKt(this)
        serverThread = Thread((mjpegServer))
    }

    override fun onResume() {
        super.onResume()
        openCamAndPreview()
        if (!serverThread.isAlive) serverThread.start()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        this.finish()
        exitProcess(0)
    }

    private fun prepareUi() {
        findViewById<View>(R.id.flipButton)?.setOnClickListener {
            toggleCamera()
            openCamAndPreview()
        }
        findViewById<TextureView>(R.id.textureView)?.let { view ->
            view.surfaceTextureListener = this@MainActivityKt
        }
    }

    private fun toggleCamera() {
        val cams = Camera.getNumberOfCameras()
        cameraId++
        if (cameraId > cams - 1) cameraId = 0
        if (previewRunning) stopPreview()
        camera?.release()
        camera = null
    }

    private fun startPreview() {
        if (previewRunning) stopPreview()
        (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.let { display ->
            camera?.setDisplayOrientation(
                when (display.rotation) {
                    Surface.ROTATION_0 -> 90
                    Surface.ROTATION_270 -> 180
                    else -> 0
                }
            )
        }

        camera?.parameters?.let { params ->
            params.setPreviewSize(640, 480) // (resWidth, resHeight)
            camera?.parameters = params
        }
        try {
            surface?.let { camera?.setPreviewTexture(it) }
            camera?.setPreviewCallback(this)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        camera?.startPreview()

        previewRunning = true
    }

    private fun stopPreview() {
        if (!previewRunning) return
        camera?.stopPreview()
        camera?.setPreviewCallback(null)
        previewRunning = false
    }

    private fun openCamAndPreview() {
        try {
            if (camera == null) camera = Camera.open(cameraId)
            startPreview()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPreviewFrame(array: ByteArray?, camera: Camera?) {
        if (array == null || camera == null) return

        previewStream.reset()
        val p = camera.parameters

        var previewHeight = p.previewSize.height
        var previewWidth = p.previewSize.width

//        val bytes = when (rotationSteps) {
//            1 -> Rotator.rotateYUV420Degree90(array, previewWidth, previewHeight)
//            2 -> Rotator.rotateYUV420Degree180(array, previewWidth, previewHeight)
//            3 -> Rotator.rotateYUV420Degree270(array, previewWidth, previewHeight)
//            else -> array
//        }
//
//        if (rotationSteps == 1 || rotationSteps == 3) {
//            val tmp = previewHeight
//            previewHeight = previewWidth
//            previewWidth = tmp
//        }

        val format = p.previewFormat
        YuvImage(array, format, previewWidth, previewHeight, null)
            .compressToJpeg(Rect(0, 0, previewWidth, previewHeight), 100, previewStream)

        try {
            frameLock.writeLock().lock()
            jpegFrame = previewStream.toByteArray()
        } finally {
            frameLock.writeLock().unlock()
        }
    }

    override fun onJpegFrame(): ByteArray {
        return try {
            frameLock.readLock().lock()
            jpegFrame
        } finally {
            frameLock.readLock().unlock()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = texture
        camera?.release()
        camera = null
        openCamAndPreview()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surface = texture
        openCamAndPreview()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        stopPreview()
        camera?.release()
        camera = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
    }
}