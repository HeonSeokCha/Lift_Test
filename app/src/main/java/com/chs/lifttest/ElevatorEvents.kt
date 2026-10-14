package com.chs.lifttest

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object ElevatorEvents {
    internal val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 탑승 중일 때만 값이 있다. 하차/취소/서비스 종료 시 null. */
    internal val _direction = MutableStateFlow<ElevatorDetector.Direction?>(null)
    val direction: StateFlow<ElevatorDetector.Direction?> = _direction.asStateFlow()

    internal val _phase = MutableStateFlow(ElevatorDetector.Phase.GATED_WALKING)
    val phase: StateFlow<ElevatorDetector.Phase> = _phase.asStateFlow()

    /** 마지막으로 완료된 탑승 기록(표시용). '탑승 중' 판단에는 쓰지 말 것. */
    internal val _lastRide = MutableStateFlow<ElevatorDetector.ElevatorRide?>(null)
    val lastRide: StateFlow<ElevatorDetector.ElevatorRide?> = _lastRide.asStateFlow()

    /** 탑승 완료 1회성 이벤트 (같은 값이어도 누락 없이 전달) */
    internal val _rideFinished = MutableSharedFlow<ElevatorDetector.ElevatorRide>(extraBufferCapacity = 16)
    val rideFinished: SharedFlow<ElevatorDetector.ElevatorRide> = _rideFinished.asSharedFlow()

    internal fun resetLiveState() {
        _direction.value = null
        _phase.value = ElevatorDetector.Phase.GATED_WALKING
    }
}
