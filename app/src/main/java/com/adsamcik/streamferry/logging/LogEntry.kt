package com.adsamcik.streamferry.logging

enum class LogLevel { DEBUG, INFO, WARN, ERROR, EVENT, TRACE }

data class LogEntry(
    val timeMillis: Long,
    val level: LogLevel,
    val category: String,
    val message: String,
)
