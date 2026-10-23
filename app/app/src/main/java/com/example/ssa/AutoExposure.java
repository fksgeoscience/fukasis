// SPDX-License-Identifier: MIT
package com.example.ssa;

// 自動露出: 試し撮りを繰り返し, 一次光領域のピークが白レベルの TARGET 前後になる露光時間を探す.
// 1 枚撮るごとに feed() に結果を渡し, true が返ったら exposureNs() でもう 1 枚撮る
public class AutoExposure {

    public enum Outcome {
        OK, // ピークが LOW - HIGH に入った
        TOO_BRIGHT, // 最短の露光時間でも白飛びする
        TOO_DARK, // 最長の露光時間でも LOW に届かない
        NOT_CONVERGED, // MAX_SHOTS 枚で決まらなかった (一番よかった露光時間を使う)
    }

    // 白レベルに対するピークの割合 (黒レベルを引いた値) の目標と, それで良しとする範囲
    static final double TARGET = 0.7;
    static final double LOW = 0.55;
    static final double HIGH = 0.85;
    static final int MAX_SHOTS = 8;
    // 白飛びしていると本当の明るさが分からないので, 大きめに縮める
    static final double SATURATED_DIVISOR = 8.0;
    // 暗すぎてノイズしか写っていないときに, 一度に伸ばす上限
    static final double MAX_GAIN = 32.0;

    private final long minNs;
    private final long maxNs;
    private long ns;
    private int shots = 0;
    private Outcome outcome;
    private double fill;
    // 白飛びしていない測定のうち, 目標に一番近いもの
    private long bestNs = -1;
    private double bestFill;

    public AutoExposure(long startNs, long minNs, long maxNs) {
        this.minNs = minNs;
        this.maxNs = Math.max(minNs, maxNs);
        this.ns = clamp(startNs);
    }

    // 次に撮る (終わった後は, 決まった) 露光時間
    public long exposureNs() {
        return ns;
    }

    // 終わっていなければ null
    public Outcome outcome() {
        return outcome;
    }

    // 決まった露光時間での, 白レベルに対するピークの割合
    public double fill() {
        return fill;
    }

    public int shots() {
        return shots;
    }

    // exposureNs() で撮った結果. もう 1 枚撮るなら true
    public boolean feed(boolean saturated, double fill) {
        shots++;
        this.fill = fill;
        if (!saturated && (bestNs < 0 || Math.abs(fill - TARGET) < Math.abs(bestFill - TARGET))) {
            bestNs = ns;
            bestFill = fill;
        }

        long next;
        if (saturated) {
            if (ns <= minNs) {
                return finish(Outcome.TOO_BRIGHT);
            }
            next = Math.round(ns / SATURATED_DIVISOR);
        } else if (fill >= LOW && fill <= HIGH) {
            return finish(Outcome.OK);
        } else if (fill < LOW) {
            if (ns >= maxNs) {
                return finish(Outcome.TOO_DARK);
            }
            next = Math.round(ns * TARGET / Math.max(fill, TARGET / MAX_GAIN));
        } else {
            // 白飛びはしていないが明るすぎる. 最短ならそれ以上は縮められないので, そのまま使う
            if (ns <= minNs) {
                return finish(Outcome.OK);
            }
            next = Math.round(ns * TARGET / fill);
        }

        if (shots >= MAX_SHOTS) {
            if (bestNs >= 0) {
                ns = bestNs;
                this.fill = bestFill;
            }
            return finish(Outcome.NOT_CONVERGED);
        }
        ns = clamp(next);
        return true;
    }

    private boolean finish(Outcome outcome) {
        this.outcome = outcome;
        return false;
    }

    private long clamp(long v) {
        return Math.max(minNs, Math.min(maxNs, v));
    }

    // 白飛びチェックの結果から, 一番明るいチャンネルのピークの割合 (0 - 1) を求める
    public static double fill(SaturationChecker.Result result, double blackLevel) {
        int level = 0;
        for (int ch = 0; ch < 3; ch++) {
            level = Math.max(level, result.level[ch]);
        }
        double range = result.whiteLevel - blackLevel;
        if (range <= 0) {
            return 0;
        }
        return Math.max(0.0, Math.min(1.0, (level - blackLevel) / range));
    }
}
