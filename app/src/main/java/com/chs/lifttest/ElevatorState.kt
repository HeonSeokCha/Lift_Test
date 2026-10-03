package com.chs.lifttest

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ElevatorState { IDLE, RIDING }

object ElevatorEvents {
    internal val _direction = MutableStateFlow<ElevatorDetector.Direction?>(null)
    val direction: StateFlow<ElevatorDetector.Direction?> = _direction.asStateFlow()

    internal val _phase = MutableStateFlow(ElevatorDetector.Phase.GATED_WALKING)
    val phase: StateFlow<ElevatorDetector.Phase> = _phase.asStateFlow()

    internal val _rides = MutableStateFlow<ElevatorDetector.ElevatorRide?>(null)
    val rides: StateFlow<ElevatorDetector.ElevatorRide?> = _rides.asStateFlow()
}
