package com.example.bluetoothtool.model

data class ThroughputStats(
    val bytes: Long = 0,
    val elapsedMillis: Long = 0,
) {
    val mbps: Double
        get() = if (elapsedMillis <= 0L) 0.0 else (bytes * 8.0 / 1_000_000.0) / (elapsedMillis / 1_000.0)
}
