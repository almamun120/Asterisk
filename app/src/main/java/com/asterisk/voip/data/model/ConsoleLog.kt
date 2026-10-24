package com.asterisk.voip.data.model

data class ConsoleLog(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val tag: String,
    val message: String
)

enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARNING, ERROR
}