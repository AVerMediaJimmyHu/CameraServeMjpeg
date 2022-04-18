package com.avermedia.samples.cameraserve

import android.os.Process
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class MjpegServerKt(
    private val onJpegFrame: OnJpegFrame,
    private var port: Int = 8080,
    private val timeout: Int = 5000,
) : Runnable {
    interface OnJpegFrame {
        fun onJpegFrame(): ByteArray
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        var server: ServerSocket
        try {
            server = ServerSocket(port)
            server.soTimeout = timeout
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }

        while (true) {
            try {
                val socket = server.accept()
                if (socket.inetAddress.isSiteLocalAddress) {
                    val mjpegSocket = MjpegSocketKt(socket, onJpegFrame)
                    Thread(mjpegSocket).start()
                } else {
                    socket.close()
                }
            } catch (ste: SocketTimeoutException) {
                // continue silently
            } catch (ioe: IOException) {
                ioe.printStackTrace()
            }
            if (port != server.localPort) {
                try {
                    server.close()
                    server = ServerSocket(port)
                    server.soTimeout = timeout
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    class MjpegSocketKt(private val socket: Socket, private val onJpegFrame: OnJpegFrame) :
        Runnable {
        private val boundary = "CameraServeDataBoundary"

        override fun run() {
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
                while (true) {
                    val frame = onJpegFrame.onJpegFrame()
                    val frameHeader = "Content-type: image/jpeg\r\n" +
                            "Content-Length: " + frame.size + "\r\n\r\n"
                    stream.write(frameHeader.toByteArray())
                    stream.write(frame)
                    stream.write("\r\n--$boundary\r\n".toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}