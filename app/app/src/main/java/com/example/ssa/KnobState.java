// SPDX-License-Identifier: MIT
package com.example.ssa;

// 露光時間のつまみの値と, 目盛り・端を越えたときの手応え (ダイヤルと目盛りで共通)
class KnobState {

    private final KnobHaptics haptics;
    private double value;
    private double minLog = ExposureScale.LOG_MIN;
    private double maxLog = ExposureScale.LOG_MAX;
    private long lastDetent;
    private boolean atLimit = false;

    KnobState(KnobHaptics haptics, double value) {
        this.haptics = haptics;
        setValue(value);
    }

    double value() {
        return value;
    }

    double minLog() {
        return minLog;
    }

    double maxLog() {
        return maxLog;
    }

    // 範囲を変える. 値が範囲の外に出て変わったら true
    boolean setRange(double minLog, double maxLog) {
        this.minLog = minLog;
        this.maxLog = Math.max(minLog, maxLog);
        double clamped = clamp(value);
        if (clamped == value) {
            return false;
        }
        setValue(clamped);
        return true;
    }

    void setValue(double log) {
        value = clamp(log);
        lastDetent = ExposureScale.detentIndex(value);
        atLimit = false;
    }

    // 指で動かした分を足し, 目盛りや端を越えたら振動させる. 値が変わったら true
    boolean moveBy(double delta) {
        double target = value + delta;
        double next = clamp(target);
        if (next != target) {
            hitLimit();
        } else {
            atLimit = false;
        }
        if (next == value) {
            return false;
        }
        value = next;
        long detent = ExposureScale.detentIndex(value);
        if (detent != lastDetent) {
            // 増える向きなら新しい目盛り, 減る向きなら越えた目盛り (lastDetent) の位置で鳴る
            long crossed = detent > lastDetent ? detent : lastDetent;
            haptics.detent(ExposureScale.isMajorDetent(crossed));
            lastDetent = detent;
        }
        return true;
    }

    // 端に当たった. 当たり続けている間は 1 回だけ振動させる
    void hitLimit() {
        if (!atLimit) {
            atLimit = true;
            haptics.limit();
        }
    }

    // 指を離した
    void release() {
        atLimit = false;
    }

    private double clamp(double log) {
        return Math.max(minLog, Math.min(maxLog, log));
    }
}
