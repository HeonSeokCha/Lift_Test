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
    /** |a|가 9.81에서 이 값 이상 벗어나면(흔들림/엘리베이터 가속) 중력 추정을 갱신하지 않는다 */
    val gravityUpdateTolerance: Float = 0.8f,
    val verticalSmoothTauSec: Float = 0.3f,
    val verticalImpulse: Float = 0.2f,      // m/s²
    val impulseLookbackNs: Long = 3_000_000_000L,
    // --- 기압 ---
    /** 기압 평활 시정수(초). 샘플링 주기와 무관하게 동작하도록 시간 기반 EMA 사용 */
    val pressureSmoothTauSec: Float = 0.16f,
    val slopeWindowNs: Long = 2_000_000_000L,
    /** 저주기 기압계(1~5Hz)에서도 기울기 계산이 가능하도록 창 안에 최소 유지할 샘플 수 */
    val slopeMinSamples: Int = 4,
    /** 샘플 유지 최대 창 (저주기 기기에서 창이 무한정 늘어나는 것 방지) */
    val slopeMaxWindowNs: Long = 8_000_000_000L,
    val rideStartSlope: Float = 0.06f,      // hPa/s ≈ 0.5 m/s (에스컬레이터 0.3 m/s 대 배제)
    val rideEndSlope: Float = 0.025f,       // hPa/s ≈ 0.2 m/s
    val minSustainNs: Long = 1_500_000_000L,   // 트리거 후 기울기 유지 시간
    val minCandidateAltitudeM: Float = 1.2f,   // RIDING 확정에 필요한 누적 고도 변화
    val endHoldNs: Long = 1_500_000_000L,
    /** RIDING 중 '걷기'가 이 시간 이상 지속되면 하차한 것으로 보고 즉시 종료 */
    val walkEndNs: Long = 2_000_000_000L,
    val maxRideNs: Long = 180_000_000_000L,
    /** 층고 낮은 건물(2.7m 등)의 1개 층 이동도 잡도록 2.5 → 2.0 */
    val minAltitudeChangeM: Float = 2.0f,
    val floorHeightM: Float = 3.0f,
    val metersPerHpa: Float = 8.3f,         // 해수면 근처 근사치

    // 정차 후 재출발을 기다리는 시간
    val stopMergeWindowNs: Long = 15_000_000_000L,

    // --- 디버그 ---
    /** 탑승 추적 중 기울기/상태 로그 출력 간격 */
    val debugIntervalNs: Long = 1_000_000_000L,
)
