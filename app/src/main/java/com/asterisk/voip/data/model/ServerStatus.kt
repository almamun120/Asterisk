package com.asterisk.voip.data.model

data class PeerInfo(
    val extension: String,
    val ip: String,
    val port: Int,
    val transport: String,
    val userAgent: String,
    val lastSeen: Long,
    val reachable: Boolean
)

data class CallInfo(
    val id: String,
    val fromExt: String,
    val toExt: String,
    val state: String,
    val startedAt: Long,
    val relayed: Boolean,
    val video: Boolean,
    val packets: Long
)

data class TrunkInfo(
    val id: Long,
    val name: String,
    val host: String,
    val username: String,
    val state: String,
    val detail: String,
    val lastOk: Long,
    val activeCalls: Int,
    val publicAddr: String
)

data class ServerStatus(
    val isRunning: Boolean = false,
    val uptime: Long = 0,
    val activeCalls: Int = 0,
    val registeredPeers: Int = 0,
    val cpuUsage: Float = 0f,
    val memoryUsage: Float = 0f,
    val sipPort: Int = 5060,
    val bindAddress: String = "",
    val mediaMode: String = "auto",
    val relayKbps: Int = 0,
    val rtpRange: String = "",
    val peers: List<PeerInfo> = emptyList(),
    val calls: List<CallInfo> = emptyList(),
    val trunks: List<TrunkInfo> = emptyList()
)
