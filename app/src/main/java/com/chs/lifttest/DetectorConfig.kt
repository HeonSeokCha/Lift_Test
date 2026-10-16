package com.chs.lifttest

data class DetectorConfig(
    // --- 움직임 분류 ---
    val motionWindowNs: Long = 2_000_000_000L,
    val walkingAccelStd: Float = 1.0f,      // |a| 표준편차(m/s²) 이상이면 걷기
    val stationaryAccelStd: Float = 0.5f,   // 이하이면 정지 (사이 구간은 이전 상태 유지 = 히스테리시스)
    val stepWindowNs: Long = 3_000_000_000L,
    val stepsForWalking: Int = 3,
    // --- 수직 가속도 ---
    val gravityTauSec: Float = 3f,          // 중력 추정 LPF 시정수 (엘리베이터 가속 1~2s보다 충분히 길게)
    val verticalSmoothTauSec: Float = 0.3f,
    val verticalImpulse: Float = 0.2f,      // m/s²
    val impulseLookbackNs: Long = 3_000_000_000L,
    // --- 기압 ---
    val pressureEmaAlpha: Float = 0.2f,
    val slopeWindowNs: Long = 2_000_000_000L,
    val rideStartSlope: Float = 0.06f,      // hPa/s ≈ 0.5 m/s (에스컬레이터 0.3 m/s 대 배제). 정차 후 '재출발' 판단에도 사용
    val rideEndSlope: Float = 0.025f,       // hPa/s ≈ 0.2 m/s. 이 아래면 '정차'로 본다
    val minSustainNs: Long = 1_500_000_000L,   // 트리거 후 기울기 유지 시간
    val minCandidateAltitudeM: Float = 1.2f,   // RIDING 확정에 필요한 누적 고도 변화
    /** 정차 후 이 시간 안에 재출발(같은 방향)하면 같은 탑승으로 병합. 지나면 하차로 확정 */
    val stopMergeWindowNs: Long = 15_000_000_000L,
    /** 정차 중 걸음이 [leaveStepWindowNs] 안에 이 횟수 이상이면 하차로 보고 병합 대기 없이 즉시 확정 */
    val leaveSteps: Int = 5,
    val leaveStepWindowNs: Long = 5_000_000_000L,
    /** 걸음 센서가 없는 기기용 폴백: 정차 중 '걷기' 상태가 이 시간 이상 지속되면 하차로 확정 */
    val walkEndNs: Long = 6_000_000_000L,
    val maxRideNs: Long = 180_000_000_000L,
    val minAltitudeChangeM: Float = 2.5f,
    val floorHeightM: Float = 3.0f,
    val metersPerHpa: Float = 8.3f,         // 해수면 근처 근사치
)
