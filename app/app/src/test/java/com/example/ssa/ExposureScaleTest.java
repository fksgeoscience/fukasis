// SPDX-License-Identifier: MIT
package com.example.ssa;

import org.junit.Test;

import static org.junit.Assert.*;

public class ExposureScaleTest {

    @Test
    public void rangeMatchesOldShortAndLongSliders() {
        // 以前の short の下端 (0.0417 ms) と long の上端 (14 s)
        assertEquals(0.0417, ExposureScale.roundMs(ExposureScale.toMs(ExposureScale.LOG_MIN)), 1e-9);
        assertEquals(14000.0, ExposureScale.roundMs(ExposureScale.toMs(ExposureScale.LOG_MAX)), 1e-9);
    }

    @Test
    public void toNsRoundsToThreeSignificantDigits() {
        assertEquals(100000000L, ExposureScale.toNs(2.0));
        assertEquals(123000000L, ExposureScale.toNs(ExposureScale.toLog(123.4)));
        assertEquals(41700L, ExposureScale.toNs(ExposureScale.LOG_MIN));
        assertEquals(2.0, ExposureScale.nsToLog(100000000L), 1e-12);
    }

    @Test
    public void format() {
        assertEquals("0.0417 ms", ExposureScale.format(0.0417));
        assertEquals("1.23 ms", ExposureScale.format(1.234));
        assertEquals("100 ms", ExposureScale.format(100.0));
        assertEquals("999 ms", ExposureScale.format(999.0));
        assertEquals("1.5 s", ExposureScale.format(1500.0));
        assertEquals("14 s", ExposureScale.format(14000.0));
        assertEquals("50ms", ExposureScale.formatShort(50.0));
        assertEquals("2s", ExposureScale.formatShort(2000.0));
    }

    @Test
    public void detents() {
        // 目盛りちょうどの値は, その目盛りに数える
        assertEquals(20, ExposureScale.detentIndex(2.0));
        assertEquals(19, ExposureScale.detentIndex(1.99));
        assertEquals(-14, ExposureScale.detentIndex(-1.38));
        assertTrue(ExposureScale.isMajorDetent(20));
        assertTrue(ExposureScale.isMajorDetent(-10));
        assertFalse(ExposureScale.isMajorDetent(-13));
    }

    @Test
    public void labelsAtOneTwoFive() {
        assertEquals(100.0, ExposureScale.labelMs(20), 1e-9);
        assertEquals(200.0, ExposureScale.labelMs(23), 1e-9);
        assertEquals(500.0, ExposureScale.labelMs(27), 1e-9);
        assertEquals(0.0, ExposureScale.labelMs(21), 0);
        assertEquals(0.2, ExposureScale.labelMs(-7), 1e-12);
        assertEquals(0.05, ExposureScale.labelMs(-13), 1e-12);
    }
}
