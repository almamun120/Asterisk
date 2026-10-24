package com.asterisk.voip.core

import com.asterisk.voip.data.model.LogLevel
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class TurnServer(
    private val logger: (LogLevel, String, String) -> Unit
) {
    private var turnSocket: DatagramSocket? = null
    private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var turnPort = 3478
    private var username = "asterisk"
    private var password = "turnpass"
    private var realm = "asterisk.local"

    private val allocations = ConcurrentHashMap<String, Allocation>()

    data class Allocation(
        val clientAddress: InetAddress,
        val clientPort: Int,
        val relaySocket: DatagramSocket,
        val relayAddress: String,
        val relayPort: Int,
        var lastActivity: Long = System.currentTimeMillis()
    )

    companion object {
        const val TURN_ALLOCATE_REQUEST = 0x0003
        const val TURN_ALLOCATE_RESPONSE = 0x0103
        const val ATTR_XOR_RELAYED_ADDRESS = 0x0016
        const val ATTR_LIFETIME = 0x000D
    }

    fun configure(port: Int, user: String, pass: String, rlm: String) {
        turnPort = port
        username = user
        password = pass
        realm = rlm
    }

    suspend fun start(): Boolean {
        if (running) return false

        return try {
            turnSocket = DatagramSocket(turnPort)
            running = true

            log("TURN", "TURN Server started on port $turnPort")

            scope.launch { receiveLoop() }
            scope.launch { cleanupLoop() }
            true
        } catch (e: Exception) {
            log("TURN", "Failed to start: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        turnSocket?.close()
        allocations.values.forEach { it.relaySocket.close() }
        allocations.clear()
        scope.coroutineContext.cancelChildren()
        log("TURN", "Server stopped")
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(2048)

        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                turnSocket?.receive(pkt)

                scope.launch {
                    processTurnRequest(pkt)
                }
            } catch (e: Exception) {
                if (running) delay(10)
            }
        }
    }

    private suspend fun cleanupLoop() {
        while (running) {
            delay(30000) // Every 30 seconds

            val now = System.currentTimeMillis()
            allocations.entries.removeIf { (key, allocation) ->
                if (now - allocation.lastActivity > 600000) { // 10 minutes
                    allocation.relaySocket.close()
                    log("TURN", "Cleaned up allocation: $key")
                    true
                } else false
            }
        }
    }

    private fun processTurnRequest(packet: DatagramPacket) {
        try {
            val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)

            val messageType = buffer.short.toInt() and 0xFFFF
            buffer.short // length
            buffer.int // magic cookie
            val transactionId = ByteArray(12)
            buffer.get(transactionId)

            when (messageType) {
                TURN_ALLOCATE_REQUEST -> {
                    log("TURN", "Allocate request from ${packet.address.hostAddress}:${packet.port}")
                    handleAllocateRequest(packet.address, packet.port, transactionId)
                }
            }
        } catch (e: Exception) {
            // Ignore malformed packets
        }
    }

    private fun handleAllocateRequest(address: InetAddress, port: Int, transactionId: ByteArray) {
        try {
            val relaySocket = DatagramSocket()
            val relayPort = relaySocket.localPort
            val relayAddress = getLocalIp()

            val key = "${address.hostAddress}:$port"
            val allocation = Allocation(
                clientAddress = address,
                clientPort = port,
                relaySocket = relaySocket,
                relayAddress = relayAddress,
                relayPort = relayPort
            )

            allocations[key] = allocation

            scope.launch { relayLoop(allocation) }

            sendAllocateResponse(address, port, transactionId, relayAddress, relayPort)

            log("TURN", "Allocated relay $relayAddress:$relayPort for $key")

        } catch (e: Exception) {
            log("TURN", "Allocation failed: ${e.message}")
        }
    }

    private suspend fun relayLoop(allocation: Allocation) {
        val buf = ByteArray(2048)
        allocation.relaySocket.soTimeout = 100

        while (running && allocations.containsValue(allocation)) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                allocation.relaySocket.receive(pkt)

                // Relay data back to client
                val outPkt = DatagramPacket(
                    pkt.data, pkt.length,
                    allocation.clientAddress, allocation.clientPort
                )
                turnSocket?.send(outPkt)

                allocation.lastActivity = System.currentTimeMillis()

            } catch (e: SocketTimeoutException) {
                // Normal timeout
            } catch (e: Exception) {
                if (running) delay(10)
            }
        }
    }

    private fun sendAllocateResponse(
        address: InetAddress,
        port: Int,
        transactionId: ByteArray,
        relayAddress: String,
        relayPort: Int
    ) {
        val buffer = ByteBuffer.allocate(512)

        val relayAttr = buildXorAddress(ATTR_XOR_RELAYED_ADDRESS,
            InetAddress.getByName(relayAddress), relayPort, transactionId)
        val lifetimeAttr = buildLifetimeAttribute(600)

        val totalLength = relayAttr.size + lifetimeAttr.size

        buffer.putShort(TURN_ALLOCATE_RESPONSE.toShort())
        buffer.putShort(totalLength.toShort())
        buffer.putInt(StunServer.STUN_MAGIC_COOKIE)
        buffer.put(transactionId)
        buffer.put(relayAttr)
        buffer.put(lifetimeAttr)

        val response = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(response)

        val packet = DatagramPacket(response, response.size, address, port)
        turnSocket?.send(packet)
    }

    private fun buildXorAddress(type: Int, address: InetAddress, port: Int, transactionId: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(12)

        buffer.putShort(type.toShort())
        buffer.putShort(8.toShort())
        buffer.put(0.toByte())
        buffer.put(0x01.toByte())

        val xorPort = port xor (StunServer.STUN_MAGIC_COOKIE ushr 16)
        buffer.putShort(xorPort.toShort())

        val addrBytes = address.address
        val xorAddr = ByteArray(4)
        val cookieBytes = ByteBuffer.allocate(4).putInt(StunServer.STUN_MAGIC_COOKIE).array()
        for (i in 0..3) {
            xorAddr[i] = (addrBytes[i].toInt() xor cookieBytes[i].toInt()).toByte()
        }
        buffer.put(xorAddr)

        return buffer.array()
    }

    private fun buildLifetimeAttribute(seconds: Int): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.putShort(ATTR_LIFETIME.toShort())
        buffer.putShort(4.toShort())
        buffer.putInt(seconds)
        return buffer.array()
    }

    private fun getLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    private fun log(tag: String, msg: String) {
        logger(LogLevel.INFO, tag, msg)
    }
}