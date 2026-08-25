package com.example.bluetoothtool.model

data class SppThroughputSample(
    val totalBytes: Long,
    val elapsedMillis: Long,
    val intervalBytes: Long,
    val intervalMillis: Long,
)

data class SppBidirectionalThroughputSample(
    val txBytes: Long,
    val rxBytes: Long,
    val elapsedMillis: Long,
    val intervalTxBytes: Long,
    val intervalRxBytes: Long,
    val intervalMillis: Long,
)

data class SppThroughputStats(
    val totalBytes: Long = 0,
    val elapsedMillis: Long = 0,
    val intervalBytes: Long = 0,
    val intervalMillis: Long = 0,
) {
    constructor(sample: SppThroughputSample) : this(
        totalBytes = sample.totalBytes,
        elapsedMillis = sample.elapsedMillis.coerceAtLeast(1L),
        intervalBytes = sample.intervalBytes,
        intervalMillis = sample.intervalMillis.coerceAtLeast(1L),
    )

    val currentMbps: Double
        get() = mbps(intervalBytes, intervalMillis)

    val averageMbps: Double
        get() = mbps(totalBytes, elapsedMillis)
}

data class SppBidirectionalThroughputStats(
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val elapsedMillis: Long = 0,
    val intervalTxBytes: Long = 0,
    val intervalRxBytes: Long = 0,
    val intervalMillis: Long = 0,
) {
    constructor(sample: SppBidirectionalThroughputSample) : this(
        txBytes = sample.txBytes,
        rxBytes = sample.rxBytes,
        elapsedMillis = sample.elapsedMillis.coerceAtLeast(1L),
        intervalTxBytes = sample.intervalTxBytes,
        intervalRxBytes = sample.intervalRxBytes,
        intervalMillis = sample.intervalMillis.coerceAtLeast(1L),
    )

    val currentTxMbps: Double
        get() = mbps(intervalTxBytes, intervalMillis)

    val currentRxMbps: Double
        get() = mbps(intervalRxBytes, intervalMillis)

    val currentTotalMbps: Double
        get() = currentTxMbps + currentRxMbps

    val averageTxMbps: Double
        get() = mbps(txBytes, elapsedMillis)

    val averageRxMbps: Double
        get() = mbps(rxBytes, elapsedMillis)

    val averageTotalMbps: Double
        get() = averageTxMbps + averageRxMbps

    val totalBytes: Long
        get() = txBytes + rxBytes
}

private fun mbps(bytes: Long, millis: Long): Double {
    return if (millis <= 0L) {
        0.0
    } else {
        (bytes * 8.0 / 1_000_000.0) / (millis / 1_000.0)
    }
}
