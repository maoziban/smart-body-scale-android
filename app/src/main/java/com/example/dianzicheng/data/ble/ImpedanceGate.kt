package com.example.dianzicheng.data.ble

/**
 * 阻抗接收闸门：决定一帧解析结果里的阻抗能否更新当前测量的阻抗值。
 *
 * 背景：用户下秤或锁定结束后，部分秤会继续发送"空载/复位"帧，帧内阻抗是脚底未接触电极时的
 * 空载值（例如 1344Ω 骤降为 384Ω）。若此时已锁定的体重仍保留，这个空载阻抗会被当成真实阻抗
 * 参与体成分计算，导致体脂率等指标异常。
 */
object ImpedanceGate {
    /** 人体足底 BIA 的合理阻抗范围（Ω） */
    private const val MIN_OHM = 100.0
    private const val MAX_OHM = 1500.0

    /**
     * @param impedanceOhm   本帧阻抗
     * @param frameStable    本帧自身是否带稳定/锁定标志（空载复位帧为 false）
     * @param frameWeightKg  本帧体重（0 表示空载帧；阻抗专属包按约定也为 0 但 frameStable = true）
     * @param lockedWeightKg 当前已锁定的体重
     */
    fun accept(
        impedanceOhm: Double,
        frameStable: Boolean,
        frameWeightKg: Double,
        lockedWeightKg: Double
    ): Boolean {
        if (impedanceOhm !in MIN_OHM..MAX_OHM) return false
        // 必须是真人在秤上：已锁定体重或本帧体重有效
        if (lockedWeightKg < 3.0 && frameWeightKg < 3.0) return false
        // 本帧必须带稳定标志；未锁定的动态帧/下秤复位帧里的阻抗一律丢弃
        return frameStable
    }
}
