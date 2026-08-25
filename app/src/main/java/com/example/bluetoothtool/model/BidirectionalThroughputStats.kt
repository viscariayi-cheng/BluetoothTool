package com.example.bluetoothtool.model

data class BidirectionalThroughputStats(
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val elapsedMillis: Long = 0,
) {
    val txMbps: Double
        get() = mbps(txBytes)

    val rxMbps: Double
        get() = mbps(rxBytes)

    val totalMbps: Double
        get() = txMbps + rxMbps

    val totalBytes: Long
        get() = txBytes + rxBytes

    private fun mbps(bytes: Long): Double {
        return if (elapsedMillis <= 0L) {
            0.0
        } else {
            (bytes * 8.0 / 1_000_000.0) / (elapsedMillis / 1_000.0)
        }
    }
}
