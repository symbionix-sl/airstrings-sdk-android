package com.airstrings.sdk

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class LocalHttpServer private constructor(
    private val socket: ServerSocket,
    private val handler: ((OutputStream) -> Unit)?,
) : Closeable {

    val hits: AtomicInteger = AtomicInteger()
    val requestHeads: MutableList<String> = CopyOnWriteArrayList()
    val baseUrl: String = "http://${socket.inetAddress.hostAddress}:${socket.localPort}"

    init {
        if (handler != null) {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val connection = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    thread(isDaemon = true) { serve(connection, handler) }
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }

    private fun serve(connection: Socket, handler: (OutputStream) -> Unit) {
        try {
            connection.use {
                requestHeads.add(readHead(it.getInputStream()))
                hits.incrementAndGet()
                handler(it.getOutputStream())
            }
        } catch (e: IOException) {
            return
        }
    }

    private fun readHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) break
            head.append(byte.toChar())
        }
        return head.toString()
    }

    internal companion object {
        fun hang(): LocalHttpServer = LocalHttpServer(newSocket(), handler = null)

        fun refused(): LocalHttpServer = LocalHttpServer(newSocket(), handler = null).also { it.close() }

        fun respond(
            status: Int,
            body: ByteArray = ByteArray(0),
            headers: Map<String, String> = emptyMap(),
            bodyDelayMs: Long = 0,
        ): LocalHttpServer = LocalHttpServer(newSocket()) { out ->
            val head = buildString {
                append("HTTP/1.1 $status Status\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val split = if (bodyDelayMs > 0) body.size / 2 else body.size
            out.write(head.toByteArray())
            out.write(body, 0, split)
            out.flush()
            if (bodyDelayMs > 0) Thread.sleep(bodyDelayMs)
            out.write(body, split, body.size - split)
            out.flush()
        }

        fun dropBody(): LocalHttpServer = LocalHttpServer(newSocket()) { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\npartial".toByteArray())
            out.flush()
        }

        private fun newSocket(): ServerSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    }
}
