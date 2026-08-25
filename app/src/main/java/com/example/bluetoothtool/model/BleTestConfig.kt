package com.example.bluetoothtool.model

data class BleTestConfig(
    val role: TestRole = TestRole.Client,
    val trafficDirection: TrafficDirection = TrafficDirection.Tx,
)
