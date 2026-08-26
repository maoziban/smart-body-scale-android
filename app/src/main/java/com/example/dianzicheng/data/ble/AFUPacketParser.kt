package com.example.dianzicheng.data.ble

/**
 * Parser for AFU-series body scale 20-byte BLE notifications on characteristic
 * 0000FFB2-0000-1000-8000-00805F9B34FB.
 *
 * Frame layout (verified against 3 real captures on 2026-08-26 — 11.8 kg, 18.7 kg,
 * 103.5 kg — plus the README protocol table):
 *
 *   [0]    = 0xAC header
 *   [1]    = MAC[0] (constant 0x29 on this scale)
 *   [2]    = Status byte (0x00 weighing / 0x80 / 0x02 …) — NOT the stable flag.
 *   [3..5] = Weight (24-bit big-endian integer). The encoding is piecewise:
 *
 *              ── Segment 1 (default, ~0–102 kg) ──
 *              weightKg = (raw - 0x680000) / 1000.0
 *              where raw = (data[3]<<16) | (data[4]<<8) | data[5].
 *
 *              In this segment data[3] is a 0x68..0x6C prefix byte and the
 *              formula simplifies to (data[4]<<8 | data[5]) / 1000 when
 *              data[3] == 0x68. This matches the README protocol table:
 *              "重量 (kg) = ((raw[3] - 0x68) × 65536 + raw[4] × 256 + raw[5]) / 1000".
 *              Verified against 11.8 kg (0x682E18) and 18.7 kg (0x68490C)
 *              captures: 11.800 kg / 18.700 kg to 4 decimal places.
 *
 *              ── Segment 2 (>= ~100 kg, raw[3] == 0x6D) ──
 *              weightKg = (raw - 0x6C0000) / 1000.0
 *
 *              Same /1000 step as segment 1; only the zero point changes to
 *              0x6C0000 when the weight exceeds ~100 kg. Verified against three
 *              real 2026-08-26 locked captures: 0x6D8894→100.5 kg, 0x6D8C18→101.4 kg,
 *              0x6D8F9C→102.3 kg, 0x6D9578→103.8 kg.
 *
 *   [6]    = Stable flag: 0x02 = weight LOCKED and stable (isStable=true);
 *            anything else = still oscillating / step-off (weight bytes may be
 *            garbage). Matches the README table ("第 6 字节 稳定标志位 0x02"),
 *            the iOS client, and the legacy implementation.
 *   [7]    = 0x00 while in use, 0x8F at step-off.
 *   [8..9] = Impedance in ohms (big-endian, e.g. 0x0540 = 1344 Ω).
 *   [10..13] = lock-time / last-locked-weight snapshot.
 *   [14..19] = Reserved / counter / CRC.
 */
object AFUPacketParser {

    private const val HEADER = 0xAC

    /** data[6]==0x02 means the weight is LOCKED and stable (README / iOS / legacy). */
    private const val STABLE_FLAG = 0x02

    /**
     * Segment 1 offset: weight bytes carry (raw - 0x680000) as a 16-bit
     * "raw - prefix" value; dividing by 1000.0 yields kg. Verified against
     * README formula and 11.8 kg / 18.7 kg captures.
     */
    private const val SEGMENT1_OFFSET = 0x680000L
    private const val SEGMENT1_DIVISOR = 1000.0

    /**
     * Segment 2 (high-weight band, raw[3] == 0x6D, >= ~100 kg) offset:
     * kg = (raw - 0x6C0000) / 1000.0. Same /1000 step as segment 1, but the
     * zero point jumps to 0x6C0000.
     *
     * Verified against real locked captures on 2026-08-26:
     *   0x6D8894 → (0x18894)/1000 = 100.500 kg (user wrote 100.5 kg)
     *   0x6D8C18 → 101.400 kg (user wrote 101.4 kg)
     *   0x6D8F9C → 102.300 kg (user wrote 102.3 kg)
     *   0x6D9578 → (0x19578)/1000 = 103.800 kg
     */
    private const val SEGMENT2_OFFSET = 0x6C0000L

    /**
     * The boundary raw value between segment 1 and segment 2. The scale
     * switches to the high-weight encoding when the actual weight exceeds
     * ~100 kg, at which point raw[3] becomes 0x6D and raw lands in the
     * 0x6D0000..0x6Dxxxx range. Anything with raw[3] == 0x6D is segment 2.
     */
    private const val SEGMENT2_RAW_THRESHOLD = 0x6D0000

    private const val MAX_WEIGHT_KG = 655.35

    /**
     * Reject readings that look like the user just stepped off the scale
     * and the sensor is winding down to zero. The scale keeps emitting
     * frames during this period even though the measurement is meaningless
     * (e.g. raw = 0x0001C8 → 0.006 kg → "0.01 kg" on UI).
     * Anything below 1 kg is treated as "no person on the scale".
     */
    private const val MIN_WEIGHT_KG = 1.0

    /**
     * Human impedance at 50 kHz is realistically 200–1200 Ω. Readings
     * outside 100–1500 Ω are either sensor noise (e.g. 0x0180 = 384 Ω
     * seen when the user steps off) or protocol garbage. Reject them so
     * BIA doesn't fall back to a "tall adult" baseline and produce wildly
     * off body-fat numbers that then get clamped to the 5%/55% edges.
     */
    private const val MIN_IMPEDANCE_OHM = 100
    private const val MAX_IMPEDANCE_OHM = 1500

    fun parseWeight(data: ByteArray): WeightData? {
        if (data.size < 10 || (data[0].toInt() and 0xFF) != HEADER) return null
        val w3 = data[3].toInt() and 0xFF
        val w4 = data[4].toInt() and 0xFF
        val w5 = data[5].toInt() and 0xFF
        val raw = (w3 shl 16) or (w4 shl 8) or w5
        val weightKg = if (raw >= SEGMENT2_RAW_THRESHOLD) {
            // Segment 2 (>= ~100 kg, raw[3] == 0x6D): zero point is 0x6C0000.
            (raw - SEGMENT2_OFFSET).toDouble() / SEGMENT1_DIVISOR
        } else {
            // Segment 1: default band, 0–~100 kg.
            // (raw - 0x680000) / 1000.0
            (raw - SEGMENT1_OFFSET).toDouble() / SEGMENT1_DIVISOR
        }.coerceIn(0.0, MAX_WEIGHT_KG)
        if (weightKg < MIN_WEIGHT_KG) return null
        val isStable = (data[6].toInt() and 0xFF) == STABLE_FLAG
        return WeightData(weightKg, isStable)
    }

    fun parseImpedance(data: ByteArray): Double? {
        if (data.size < 10 || (data[0].toInt() and 0xFF) != HEADER) return null
        val imp8 = data[8].toInt() and 0xFF
        val imp9 = data[9].toInt() and 0xFF
        val impedance = (imp8 shl 8) or imp9
        if (impedance < MIN_IMPEDANCE_OHM || impedance > MAX_IMPEDANCE_OHM) return null
        return impedance.toDouble()
    }

    data class WeightData(val weightKg: Double, val isStable: Boolean)
}
