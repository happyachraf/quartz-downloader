package com.quartz.downloader

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Real HTTP transfers with range support; never included in the application APK. */
class ThrottledMediaServer(private val bytes: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val sent = ConcurrentHashMap<String, AtomicLong>()
    val ranges = AtomicLong()
    @Volatile var fast = false
    val port get() = server.localPort
    init {
        workers.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                sockets.add(client)
                workers.execute {
                    try { serve(client) } catch (_: Exception) { } finally { sockets.remove(client); client.close() }
                }
            }
        }
    }
    private fun serve(client: Socket) {
        client.soTimeout = 20000
        val reader = client.getInputStream().bufferedReader()
        val first = reader.readLine()?.split(' ') ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) break
            val pair = line.split(':', limit = 2)
            if (pair.size == 2) headers[pair[0].lowercase()] = pair[1].trim()
        }
        val offset = Regex("bytes=(\\d+)-").find(headers["range"].orEmpty())?.groupValues?.get(1)?.toInt() ?: 0
        if (offset > 0) ranges.incrementAndGet()
        val output = client.getOutputStream()
        val extra = if (offset > 0) "Content-Range: bytes $offset-${bytes.size - 1}/${bytes.size}\r\n" else ""
        output.write(("HTTP/1.1 ${if (offset > 0) "206 Partial Content" else "200 OK"}\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size - offset}\r\nAccept-Ranges: bytes\r\n${extra}Connection: close\r\n\r\n").toByteArray())
        output.flush()
        if (first[0] == "HEAD") return
        val counter = sent.computeIfAbsent(first[1]) { AtomicLong() }
        var position = offset
        while (position < bytes.size) {
            val count = minOf(if (fast) 32768 else 1024, bytes.size - position)
            output.write(bytes, position, count); output.flush(); position += count; counter.addAndGet(count.toLong())
            if (!fast) Thread.sleep(100)
        }
    }
    override fun close() { server.close(); sockets.forEach { it.close() }; workers.shutdownNow() }
}
