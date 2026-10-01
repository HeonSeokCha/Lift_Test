package com.chs.lifttest

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ElevatorState { IDLE, RIDING }

object ElevatorEvents {
    internal val _phase = MutableStateFlow(ElevatorDetector.Phase.GATED_WALKING)
    val phase: StateFlow<ElevatorDetector.Phase> = _phase.asStateFlow()

    internal val _rides = MutableSharedFlow<ElevatorDetector.ElevatorRide>(extraBufferCapacity = 16)
    val rides: SharedFlow<ElevatorDetector.ElevatorRide> = _rides.asSharedFlow()
}
