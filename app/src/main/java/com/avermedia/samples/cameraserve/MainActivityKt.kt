package com.avermedia.samples.cameraserve

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.arkconcepts.cameraserve.R
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.system.exitProcess

class MainActivityKt : AppCompatActivity(), TextureView.SurfaceTextureListener,
    MjpegServerKt.OnJpegFrame {

    companion object {
        private const val TAG = "MainUi"
    }

    private var previewRunning: Boolean = false
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSize = Size(640, 480)

    private val cameraManager: CameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /**
     * A reference to the current [android.hardware.camera2.CameraCaptureSession] for
     * preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    private lateinit var mjpegServer: MjpegServerKt
    private lateinit var serverThread: Thread
    private val frameLock = ReentrantReadWriteLock()
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
        startBackgroundThread()
        // openCamAndPreview() // onResume
        if (!serverThread.isAlive) serverThread.start()
    }

    override fun onPause() {
        stopBackgroundThread()
        super.onPause()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        this.finish()
        exitProcess(0)
    }

    private fun prepareUi() {
//        findViewById<View>(R.id.flipButton)?.setOnClickListener {
//            toggleCamera()
//            openCamAndPreview() // prepareUi
//        }
        findViewById<TextureView>(R.id.textureView)?.let { view ->
            view.surfaceTextureListener = this@MainActivityKt
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * A reference to the opened [android.hardware.camera2.CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            Log.d(TAG, "onOpened: ${device.id}")
            cameraOpenCloseLock.release()
            cameraDevice = device
            startPreview()
        }

        override fun onDisconnected(device: CameraDevice) {
            Log.d(TAG, "onDisconnected: ${device.id}")
            stopPreview() // onDisconnected
            cameraOpenCloseLock.release()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            Log.d(TAG, "onError: ${device.id} $error")
            stopPreview() // onError
            cameraOpenCloseLock.release()
            cameraDevice = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = cameraManager.cameraIdList.find { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_FRONT
            }
            if (cameraId != null) {
                setupImageReader()
                cameraManager.openCamera(cameraId, cameraStateCallback, null)
            } else {
                throw NullPointerException("no camera found")
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access the camera.")
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Log.e(TAG, "NullPointerException: ${e.message}")
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    private fun startPreview() {
        if (previewRunning) stopPreview()

        try {
            closeCameraSession()

            cameraDevice?.let { device ->
                openCameraSession(device)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "startPreview: ${e.message}")
        }
    }

    private fun openCameraSession(device: CameraDevice) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

        surfaceTexture?.let { texture ->
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            val previewSurface = Surface(texture)
            builder.addTarget(previewSurface)

            val readerSurface = imageReader.surface
            builder.addTarget(readerSurface)

            device.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        builder.set(
                            CaptureRequest.CONTROL_MODE,
                            CameraMetadata.CONTROL_MODE_AUTO
                        )
                        session.setRepeatingRequest(
                            builder.build(),
                            null,
                            backgroundHandler
                        )
                        captureSession = session
                        previewRunning = true
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        captureSession = null
                    }
                },
                backgroundHandler,
            )
        }
    }

    private fun stopPreview() {
        if (!previewRunning) return

        try {
            cameraOpenCloseLock.acquire()
            closeImageReader() // stopPreview
            closeCameraSession() // stopPreview
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }

        previewRunning = false
    }

    private fun closeCameraSession() {
        captureSession?.close()
        captureSession = null
    }

    private lateinit var imageReader: ImageReader

    private fun setupImageReader() {
        imageReader =
            ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            val buffer = image.planes[0].buffer
//            val data = ByteArray(buffer.remaining())
//            buffer.get(data)
            updateJpegFrame(buffer)
            image.close()
        }, null)
    }

    private fun closeImageReader() {
        imageReader.close()
    }

    private fun updateJpegFrame(buffer: ByteBuffer) {
        try {
            frameLock.writeLock().lock()
            jpegFrame = ByteArray(buffer.remaining())
            buffer.get(jpegFrame)
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
        surfaceTexture = texture
        openCamera() // onSurfaceTextureAvailable
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture = texture
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        stopPreview() // onSurfaceTextureDestroyed
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
    }
}