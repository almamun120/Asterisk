package com.asterisk.voip.core

import com.asterisk.voip.data.model.LogLevel
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer

class StunServer(
    private val logger: (LogLevel, String, String) -> Unit
) {
    private var stunSocket: DatagramSocket? = null
    private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var stunPort = 3478

    companion object {
        const val STUN_BINDING_REQUEST = 0x0001
        const val STUN_BINDING_RESPONSE = 0x0101
        const val STUN_MAGIC_COOKIE = 0x2112A442

        const val ATTR_MAPPED_ADDRESS = 0x0001
        const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
        const val ATTR_SOFTWARE = 0x8022
        const val ATTR_FINGERPRINT = 0x8028
    }

    fun configure(port: Int) {
        stunPort = port
    }

    suspend fun start(): Boolean {
        if (running) return false

        return try {
            stunSocket = DatagramSocket(stunPort)
            running = true

            log("STUN", "STUN Server started on port $stunPort")

            scope.launch { receiveLoop() }
            true
        } catch (e: Exception) {
            log("STUN", "Failed to start: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        stunSocket?.close()
        scope.coroutineContext.cancelChildren()
        log("STUN", "Server stopped")
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(2048)

        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                stunSocket?.receive(pkt)

                scope.launch {
                    processStunRequest(pkt)
                }
            } catch (e: Exception) {
                if (running) delay(10)
            }
        }
    }

    private fun processStunRequest(packet: DatagramPacket) {
        try {
            val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)

            val messageType = buffer.short.toInt() and 0xFFFF
            val messageLength = buffer.short.toInt() and 0xFFFF
            val magicCookie = buffer.int
            val transactionId = ByteArray(12)
            buffer.get(transactionId)

            if (magicCookie != STUN_MAGIC_COOKIE) {
                return
            }

            when (messageType) {
                STUN_BINDING_REQUEST -> {
                    log("STUN", "Binding request from ${packet.address.hostAddress}:${packet.port}")
                    sendBindingResponse(packet.address, packet.port, transactionId)
                }
            }
        } catch (e: Exception) {
            // Ignore malformed packets
        }
    }

    private fun sendBindingResponse(address: InetAddress, port: Int, transactionId: ByteArray) {
        val response = buildBindingResponse(address, port, transactionId)
        val packet = DatagramPacket(response, response.size, address, port)
        stunSocket?.send(packet)
    }

    private fun buildBindingResponse(address: InetAddress, port: Int, transactionId: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(512)

        val xorMappedAddr = buildXorMappedAddress(address, port, transactionId)
        val software = "Asterisk-Android".toByteArray()
        val softwareAttr = buildAttribute(ATTR_SOFTWARE, software)

        val totalLength = xorMappedAddr.size + softwareAttr.size + 8

        // STUN header
        buffer.putShort(STUN_BINDING_RESPONSE.toShort())
        buffer.putShort(totalLength.toShort())
        buffer.putInt(STUN_MAGIC_COOKIE)
        buffer.put(transactionId)

        buffer.put(xorMappedAddr)
        buffer.put(softwareAttr)

        val fingerprint = calculateFingerprint(buffer.array(), buffer.position())
        buffer.putShort(ATTR_FINGERPRINT.toShort())
        buffer.putShort(4.toShort())
        buffer.putInt(fingerprint)

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    private fun buildXorMappedAddress(address: InetAddress, port: Int, transactionId: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(12)

        buffer.putShort(ATTR_XOR_MAPPED_ADDRESS.toShort())
        buffer.putShort(8.toShort())
        buffer.put(0.toByte())
        buffer.put(0x01.toByte())

        val xorPort = port xor (STUN_MAGIC_COOKIE ushr 16)
        buffer.putShort(xorPort.toShort())

        val addrBytes = address.address
        val xorAddr = ByteArray(4)
        val cookieBytes = ByteBuffer.allocate(4).putInt(STUN_MAGIC_COOKIE).array()
        for (i in 0..3) {
            xorAddr[i] = (addrBytes[i].toInt() xor cookieBytes[i].toInt()).toByte()
        }
        buffer.put(xorAddr)

        return buffer.array()
    }

    private fun buildAttribute(type: Int, value: ByteArray): ByteArray {
        val padding = (4 - (value.size % 4)) % 4
        val buffer = ByteBuffer.allocate(4 + value.size + padding)

        buffer.putShort(type.toShort())
        buffer.putShort(value.size.toShort())
        buffer.put(value)
        repeat(padding) { buffer.put(0.toByte()) }

        return buffer.array()
    }

    private fun calculateFingerprint(data: ByteArray, length: Int): Int {
        val crc32 = java.util.zip.CRC32()
        crc32.update(data, 0, length)
        return (crc32.value xor 0x5354554e).toInt()
    }

    private fun log(tag: String, msg: String) {
        logger(LogLevel.INFO, tag, msg)
    }
}