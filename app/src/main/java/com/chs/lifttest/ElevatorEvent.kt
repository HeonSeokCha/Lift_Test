package com.chs.lifttest

data class ElevatorEvent(
    val state: ElevatorState,
    val direction: Int,
    val verticalSpeed: Float,
    val timestampMs: Long
)
