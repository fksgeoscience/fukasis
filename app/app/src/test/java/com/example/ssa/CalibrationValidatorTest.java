// SPDX-License-Identifier: MIT
package com.example.ssa;

import org.junit.Test;

import static org.junit.Assert.*;

public class CalibrationValidatorTest {

    // README の calibration 画面の例 (0次光 490, 輝線 2122, 2476, 2616, 2700) に相当する値
    private static final double[] T_REF = { 1632, 1986, 2126, 2210 };
    private static final double[] C_REF = { 435.8, 546.1, 588.0, 611.6 };

    @Test
    public void interpolationPassesThroughReferencePoints() {
        for (int i = 0; i < T_REF.length; i++) {
            assertEquals(C_REF[i], CalibrationValidator.wavelengthAt(T_REF, C_REF, T_REF[i]), 1e-9);
        }
    }

    @Test
    public void typicalCalibrationIsOk() {
        assertEquals(CalibrationValidator.OK, CalibrationValidator.validate(T_REF, C_REF));
    }

    @Test
    public void swappedWavelengthsAreNotMonotonic() {
        double[] t = { 1900, 2100, 2300, 2500 };
        double[] c = { 430, 550, 490, 610 };
        assertEquals(CalibrationValidator.NOT_MONOTONIC, CalibrationValidator.validate(t, c));
    }

    @Test
    public void samePositionIsDuplicate() {
        double[] t = { 1900, 2100, 2100, 2500 };
        double[] c = { 430, 490, 490, 610 };
        assertEquals(CalibrationValidator.DUPLICATE_POSITION, CalibrationValidator.validate(t, c));
    }

    @Test
    public void wavelengthsOutOfRangeGiveNoOutput() {
        double[] t = { 1900, 2100, 2300, 2500 };
        double[] c = { 1430, 1490, 1550, 1610 };
        assertEquals(CalibrationValidator.NO_OUTPUT, CalibrationValidator.validate(t, c));
    }
}
