package com.avermedia.samples.cameraserve

import android.os.Process
import android.util.Log
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class MjpegServerKt(private val runnable: ServerRunnable) : Thread(runnable) {
    companion object {
        private const val TAG = "MjpegServer"
    }

    interface OnJpegFrame {
        fun onJpegFrame(): ByteArray
    }

    class ServerRunnable(
        private val onJpegFrame: OnJpegFrame,
        private var port: Int = 8080,
        private val timeout: Int = 5000,
    ) : Runnable {
        var running: Boolean = true
        private val socketRunnable = ArrayList<SocketRunnable>()

        override fun run() {
            Log.v(TAG, "ServerSocket RUN")
            socketRunnable.clear()
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            var server: ServerSocket
            try {
                server = ServerSocket(port)
                server.soTimeout = timeout
            } catch (e: IOException) {
                Log.e(TAG, "ServerSocket: ${e.message}")
                return
            }

            while (running) {
                try {
                    val socket = server.accept()
                    Log.v(TAG, "accept: ${socket.inetAddress.hostAddress}:${socket.port}")
                    if (socket.inetAddress.isSiteLocalAddress) {
                        val mjpegSocket = SocketRunnable(socket, onJpegFrame)
                        Thread(mjpegSocket).start()
                        socketRunnable.add(mjpegSocket) // add to list
                    } else {
                        socket.close()
                    }
                } catch (ste: SocketTimeoutException) {
                    // continue silently
                } catch (ioe: IOException) {
                    Log.e(TAG, "IOException: ${ioe.message}")
                }
                if (port != server.localPort) { // keep listening
                    try {
                        server.close()
                        server = ServerSocket(port)
                        server.soTimeout = timeout
                    } catch (e: IOException) {
                        Log.e(TAG, "ServerSocket: ${e.message}")
                    }
                }
            }

            try {
                socketRunnable.forEach { runnable -> runnable.running = false } // close socket
                server.close()
            } catch (e: IOException) {
                Log.e(TAG, "IOException: ${e.message}")
            }
            Log.v(TAG, "ServerSocket END")
        }
    }

    class SocketRunnable(
        private val socket: Socket,
        private val onJpegFrame: OnJpegFrame,
    ) : Runnable {
        private val boundary = "CameraServeDataBoundary"
        var running: Boolean = true

        override fun run() {
            Log.v(TAG, "SocketRunnable(${socket.port}) RUN")
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            try {
                val stream = DataOutputStream(socket.getOutputStream())
                val httpHeader = "HTTP/1.0 200 OK\r\n" +
                        "Server: CameraServe\r\n" +
                        "Connection: close\r\n" +
                        "Max-Age: 0\r\n" +
                        "Expires: 0\r\n" +
                        "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, " +
                        "post-check=0, max-age=0\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=" + boundary + "\r\n" +
                        "\n--" + boundary + "\r\n"
                stream.write(httpHeader.toByteArray())
                stream.flush()
                while (running) {
                    val frame = onJpegFrame.onJpegFrame()
                    val frameHeader = "Content-type: image/jpeg\r\n" +
                            "Content-Length: " + frame.size + "\r\n\r\n"
                    stream.write(frameHeader.toByteArray())
                    stream.write(frame)
                    stream.write("\r\n--$boundary\r\n".toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "MjpegSocket(${socket.port}): ${e.message}")
            }
            Log.v(TAG, "SocketRunnable(${socket.port}) END")
        }
    }

    fun resumeServer() {
        if (!isAlive) {
            Log.v(TAG, "RESUME")
            runnable.running = true
            start()
        }
    }

    fun pauseServer() {
        Log.v(TAG, "PAUSE")
        runnable.running = false
    }
}

