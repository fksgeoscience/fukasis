// SPDX-License-Identifier: MIT
package com.example.ssa;

// 露光時間のつまみ (ダイヤル / 目盛り) の共通部分. 値は ExposureScale の対数 (log10 ms)
public interface ExposureKnob {

    interface Listener {
        // 指で動かしたときだけ呼ばれる
        void onKnobValueChanged(double log);
    }

    void setListener(Listener listener);

    // 指で動かせる範囲. 今の値が範囲の外なら範囲に収める (Listener は呼ばない)
    void setRange(double minLog, double maxLog);

    // プログラムから値を変える (Listener は呼ばない)
    void setValue(double log);
}
