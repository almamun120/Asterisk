package com.asterisk.voip.data.model

data class StunTurnConfig(
    val stunEnabled: Boolean = false,
    val stunPort: Int = 3478,
    val turnEnabled: Boolean = false,
    val turnPort: Int = 3478,
    val turnUsername: String = "asterisk",
    val turnPassword: String = "turnpass",
    val turnRealm: String = "asterisk.local",
    val turnMinPort: Int = 49152,
    val turnMaxPort: Int = 65535
)