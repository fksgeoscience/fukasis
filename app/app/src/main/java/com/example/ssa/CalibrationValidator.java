// SPDX-License-Identifier: MIT
package com.example.ssa;

// 波長校正データ (4 点) から, csv 画面でまともなスペクトルが出力できるかを確かめる
public class CalibrationValidator {

    public static final int OK = 0;
    // 同じ位置のバーがあり, 補間の分母が 0 になる
    public static final int DUPLICATE_POSITION = 1;
    // 出力される範囲で波長が折り返している
    public static final int NOT_MONOTONIC = 2;
    // 出力される画素が 1 つも無い
    public static final int NO_OUTPUT = 3;

    // makecsv と同じ値. 0次光からの距離がこの範囲で, かつ波長がこの範囲の画素だけが出力される
    static final int T_MIN = 1800;
    static final int T_MAX = 2800;
    static final double WAVELENGTH_MIN = 400;
    static final double WAVELENGTH_MAX = 700;

    // makecsv と同じ 3 次の Lagrange 補間. t は 0次光からの距離 (px)
    public static double wavelengthAt(double[] tRef, double[] cRef, double t) {
        double wavelength = 0;
        for (int j = 0; j < tRef.length; j++) {
            double nume = 1.0;
            double deno = 1.0;
            for (int k = 0; k < tRef.length; k++) {
                if (k != j) {
                    nume *= (t - tRef[k]);
                    deno *= (tRef[j] - tRef[k]);
                }
            }
            wavelength += cRef[j] * nume / deno;
        }
        return wavelength;
    }

    public static int validate(double[] tRef, double[] cRef) {
        for (int j = 0; j < tRef.length; j++) {
            for (int k = j + 1; k < tRef.length; k++) {
                if (tRef[j] == tRef[k]) {
                    return DUPLICATE_POSITION;
                }
            }
        }

        int count = 0;
        int dir = 0;
        double prev = 0;
        for (int t = T_MIN + 1; t < T_MAX; t++) {
            double wavelength = wavelengthAt(tRef, cRef, t);
            if (!(WAVELENGTH_MIN < wavelength && wavelength < WAVELENGTH_MAX)) {
                continue;
            }
            if (0 < count) {
                int d = Double.compare(wavelength, prev);
                if (d == 0 || (dir != 0 && d != dir)) {
                    return NOT_MONOTONIC;
                }
                dir = d;
            }
            prev = wavelength;
            count++;
        }
        return (count == 0) ? NO_OUTPUT : OK;
    }
}
