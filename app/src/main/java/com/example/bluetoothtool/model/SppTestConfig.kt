package com.example.bluetoothtool.model

data class SppTestConfig(
    val role: TestRole = TestRole.Client,
    val trafficDirection: TrafficDirection = TrafficDirection.Tx,
)

enum class TestRole {
    Client,
    Server,
}

enum class TrafficDirection {
    Tx,
    Rx,
    TxRx,
}
