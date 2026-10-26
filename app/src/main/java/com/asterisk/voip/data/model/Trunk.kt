package com.asterisk.voip.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * An external VoIP service account (SIP trunk / gateway) this server logs in to.
 * Several can be active at the same time.
 */
@Parcelize
@Entity(tableName = "trunks")
data class Trunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 5060,
    val username: String,
    val password: String,
    /** Auth user when different from [username] (blank = same). */
    val authUser: String = "",
    /** SIP domain used in From/To/Request-URI (blank = [host]). */
    val domain: String = "",
    /** Optional outbound proxy "host[:port]". */
    val outboundProxy: String = "",
    /** true = REGISTER to the provider; false = IP-authenticated trunk (only pinged). */
    val register: Boolean = true,
    val expiry: Int = 300,
    /** NAT keep-alive interval in seconds (0 = off). */
    val keepAlive: Int = 25,
    /** Number shown to the called party (blank = username). */
    val callerId: String = "",
    /** Outbound: comma separated dial prefixes that use this trunk. "*" = any other number. */
    val outPrefixes: String = "*",
    /** Outbound: leading digits removed from the dialed number. */
    val stripDigits: Int = 0,
    /** Outbound: text put in front of the number sent to the provider. */
    val addPrefix: String = "",
    /** Lower number = tried first when several trunks match. */
    val priority: Int = 10,
    /** Codecs offered to the provider (ulaw,alaw,gsm,g722,g729). Blank = no filtering. */
    val codecs: String = "ulaw,alaw",
    /** Inbound: extension that rings for calls from this trunk. */
    val inboundTarget: String = "",
    /** Inbound: per-number routing "DID=ext,DID=ext". */
    val inboundMap: String = "",
    /** Max simultaneous calls (0 = unlimited). */
    val maxChannels: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable
