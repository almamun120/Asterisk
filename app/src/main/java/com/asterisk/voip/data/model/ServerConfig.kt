package com.asterisk.voip.data.model

data class ServerConfig(
    // Network Settings
    val bindAddress: String = "0.0.0.0",
    val sipPort: Int = 5060,
    val rtpStartPort: Int = 10000,
    val rtpEndPort: Int = 20000,
    val maxCalls: Int = 100,

    // Media routing: "relay" (all media via server), "direct" (device <-> device), "auto"
    val mediaMode: String = "auto",
    // How often (seconds) clients are asked to re-register. Short = fast recovery after restart.
    val registerExpiry: Int = 120,
    // Drop relayed calls that carry no media for this many seconds (0 = never)
    val relayIdleTimeout: Int = 120,

    // SIP Transport
    val sipTransport: String = "UDP,TCP", // UDP, TCP, TLS, WS, WSS
    val tlsEnabled: Boolean = false,
    val tlsCertPath: String = "",
    val tlsKeyPath: String = "",

    // NAT Settings
    val natEnabled: Boolean = true,
    val externalIP: String = "",
    val localNet: String = "192.168.0.0/16",

    // Audio Codecs (ordered by priority)
    val audioCodecs: String = "opus,g722,pcmu,pcma,gsm",
    val allowedAudioCodecs: List<String> = listOf(
        "opus", "g722", "pcmu", "pcma", "g729",
        "gsm", "ilbc", "speex", "amr", "amr-wb"
    ),

    // Video Codecs
    val videoEnabled: Boolean = true,
    val videoCodecs: String = "vp8,h264,vp9,h265",
    val allowedVideoCodecs: List<String> = listOf(
        "h264", "h265", "vp8", "vp9", "h263", "h263p"
    ),
    val videoBitrate: Int = 512, // kbps
    val videoResolution: String = "720p", // 1080p, 720p, 480p, 360p
    val videoFrameRate: Int = 30,

    // DTMF Settings
    val dtmfMode: String = "rfc2833", // rfc2833, inband, sip_info, auto

    // Session Timers
    val sessionTimers: Boolean = true,
    val sessionExpires: Int = 1800, // seconds
    val sessionMinSE: Int = 90,

    // Quality of Service
    val qosEnabled: Boolean = true,
    val dscp: Int = 46, // EF (Expedited Forwarding)
    val tos: Int = 0xB8,

    // Jitter Buffer
    val jitterBufferEnabled: Boolean = true,
    val jitterBufferMin: Int = 40, // ms
    val jitterBufferMax: Int = 200, // ms
    val jitterBufferTarget: Int = 60, // ms

    // Security
    val srtpEnabled: Boolean = false,
    val requireSrtp: Boolean = false,
    val ipWhitelist: String = "", // Comma-separated
    val ipBlacklist: String = "",
    val maxAuthAttempts: Int = 3,
    val banDuration: Int = 3600, // seconds

    // Features
    val allowGuest: Boolean = false,
    val autoAnswer: Boolean = false,

    // Call Features
    val callWaiting: Boolean = true,
    val callForwarding: Boolean = true,
    val callTransfer: Boolean = true,
    val callParking: Boolean = true,
    val callRecording: Boolean = false,
    val recordingFormat: String = "wav", // wav, mp3, gsm

    // Music on Hold
    val musicOnHold: Boolean = true,
    val mohClass: String = "default",
    val mohDirectory: String = "/moh",

    // Voicemail
    val voicemailEnabled: Boolean = true,
    val voicemailFormat: String = "wav",
    val voicemailMaxMessage: Int = 100,
    val voicemailMaxSeconds: Int = 300,
    val voicemailMinSeconds: Int = 3,
    val voicemailEmail: Boolean = false,
    val voicemailAttach: Boolean = false,

    // Offline delivery
    // How long a message for an offline extension is kept and delivered when it comes back online.
    val offlineMessageDays: Int = 3,
    val fileTransferEnabled: Boolean = true,
    val fileMaxSizeMb: Int = 20,
    val fileKeepDays: Int = 3,

    // IVR (Interactive Voice Response)
    val ivrEnabled: Boolean = false,
    val ivrTimeout: Int = 10, // seconds
    val ivrMaxRetries: Int = 3,
    val ivrInvalidRetries: Int = 2,

    // Call Queues
    val callQueuesEnabled: Boolean = false,
    val queueStrategy: String = "ringall", // ringall, leastrecent, fewestcalls, random, rrmemory
    val queueTimeout: Int = 300,
    val queueRetry: Int = 5,
    val queueMaxLen: Int = 0, // 0 = unlimited
    val queueAnnouncePosition: Boolean = true,

    // Ring Groups
    val ringGroupsEnabled: Boolean = false,
    val ringGroupStrategy: String = "simultaneous", // simultaneous, sequential, random

    // Call Detail Records
    val cdrEnabled: Boolean = true,
    val cdrFormat: String = "csv", // csv, json, database

    // Logging
    val logLevel: String = "info", // debug, info, warning, error
    val sipDebug: Boolean = false,
    val rtpDebug: Boolean = false,

    // Advanced
    val maxRetransmits: Int = 10,
    val retransmitInterval: Int = 500, // ms
    val registerTimeout: Int = 3600,
    val qualifyFrequency: Int = 60,

    // Language
    val language: String = "en",
    val timezone: String = "UTC",

    // Performance
    val threadPoolSize: Int = 10,
    val maxConcurrentCalls: Int = 100,

    // Database
    val databaseBackend: String = "sqlite", // sqlite, mysql, postgresql
    val databaseUrl: String = ""
)