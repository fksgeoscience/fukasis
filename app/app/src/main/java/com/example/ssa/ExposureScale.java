// SPDX-License-Identifier: MIT
package com.example.ssa;

import java.math.BigDecimal;
import java.math.MathContext;

// 露光時間のつまみの目盛り. つまみの値は ms の常用対数で持つ (0.04 ms から 14 s までを 1 本で扱うため)
public final class ExposureScale {

    // 以前の short / long の 2 つのつまみを合わせた範囲 (0.04166 ms - 14000 ms)
    public static final double LOG_MIN = -1.3802807343883;
    public static final double LOG_MAX = 4.1461280356782;

    // 目盛り (クリック) の間隔. 10^0.1 = 1.259 倍で, ほぼ 1/3 段. 1, 2, 5 倍の位置にもほぼ重なる
    public static final double DETENT = 0.1;
    public static final int DETENTS_PER_DECADE = 10;

    private ExposureScale() {
    }

    public static double clampLog(double log) {
        return Math.max(LOG_MIN, Math.min(LOG_MAX, log));
    }

    public static double toMs(double log) {
        return Math.pow(10.0, log);
    }

    public static double toLog(double ms) {
        return Math.log10(ms);
    }

    // つまみの値をカメラに渡す露光時間 (ns) にする. 表示と合うよう有効数字 3 桁に丸める
    public static long toNs(double log) {
        return Math.round(roundMs(toMs(log)) * 1000000.0);
    }

    public static double nsToLog(long ns) {
        return toLog(ns / 1000000.0);
    }

    // 有効数字 3 桁に丸めた ms
    public static double roundMs(double ms) {
        return BigDecimal.valueOf(ms).round(new MathContext(3)).doubleValue();
    }

    // 表示用. 1 s 未満は ms, それ以上は s で有効数字 3 桁 (例: 0.0417 ms, 123 ms, 1.23 s)
    public static String format(double ms) {
        return ms < 1000.0 ? plain(ms) + " ms" : plain(ms / 1000.0) + " s";
    }

    // 目盛りのラベル用の短い表記 (例: 0.1ms, 50ms, 2s)
    public static String formatShort(double ms) {
        return ms < 1000.0 ? plain(ms) + "ms" : plain(ms / 1000.0) + "s";
    }

    // 有効数字 3 桁の数字だけ (単位なし)
    public static String plain(double v) {
        return BigDecimal.valueOf(v).round(new MathContext(3)).stripTrailingZeros().toPlainString();
    }

    // 何番目の目盛りか. 値が目盛りをまたいだかを調べるのに使う
    public static long detentIndex(double log) {
        // 目盛りちょうどの値が誤差で 1 つ手前に数えられないようにする
        return (long) Math.floor(log / DETENT + 1e-6);
    }

    // 10 倍ごとの太い目盛りか
    public static boolean isMajorDetent(long index) {
        return Math.floorMod(index, DETENTS_PER_DECADE) == 0;
    }

    // ラベルを付ける目盛り (1, 2, 5 倍の位置) なら, その値 (ms). ラベルなしなら 0
    public static double labelMs(long index) {
        int k = Math.floorMod(index, DETENTS_PER_DECADE);
        double decade = Math.pow(10.0, Math.floorDiv(index, DETENTS_PER_DECADE));
        if (k == 0) {
            return decade;
        } else if (k == 3) {
            return 2.0 * decade;
        } else if (k == 7) {
            return 5.0 * decade;
        }
        return 0;
    }
}
