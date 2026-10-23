// SPDX-License-Identifier: MIT
package com.example.ssa;

import org.junit.Test;

import static org.junit.Assert.*;

public class AutoExposureTest {

    private static final long MS = 1000000L;
    private static final long MIN = 42000L; // 0.042 ms
    private static final long MAX = 14000 * MS;

    // 露光時間に比例して明るくなる光源. fullNs で白レベルに届く
    private static AutoExposure.Outcome run(AutoExposure ae, double fullNs) {
        while (true) {
            double fill = ae.exposureNs() / fullNs;
            boolean saturated = fill >= 0.98;
            if (!ae.feed(saturated, Math.min(fill, 1.0))) {
                return ae.outcome();
            }
        }
    }

    @Test
    public void convergesFromTooDark() {
        AutoExposure ae = new AutoExposure(100 * MS, MIN, MAX);
        assertEquals(AutoExposure.Outcome.OK, run(ae, 3000.0 * MS));
        double fill = ae.exposureNs() / (3000.0 * MS);
        assertTrue(fill >= AutoExposure.LOW && fill <= AutoExposure.HIGH);
        assertTrue(ae.shots() <= 3);
    }

    @Test
    public void convergesFromSaturated() {
        AutoExposure ae = new AutoExposure(100 * MS, MIN, MAX);
        assertEquals(AutoExposure.Outcome.OK, run(ae, 0.5 * MS));
        double fill = ae.exposureNs() / (0.5 * MS);
        assertTrue(fill >= AutoExposure.LOW && fill <= AutoExposure.HIGH);
    }

    @Test
    public void alreadyGood() {
        AutoExposure ae = new AutoExposure(100 * MS, MIN, MAX);
        assertEquals(AutoExposure.Outcome.OK, run(ae, 140.0 * MS));
        assertEquals(1, ae.shots());
        assertEquals(100 * MS, ae.exposureNs());
    }

    @Test
    public void tooBright() {
        AutoExposure ae = new AutoExposure(100 * MS, MIN, MAX);
        assertEquals(AutoExposure.Outcome.TOO_BRIGHT, run(ae, 0.01 * MS));
        assertEquals(MIN, ae.exposureNs());
    }

    @Test
    public void tooDark() {
        AutoExposure ae = new AutoExposure(100 * MS, MIN, MAX);
        assertEquals(AutoExposure.Outcome.TOO_DARK, run(ae, 1000000.0 * MS));
        assertEquals(MAX, ae.exposureNs());
    }

    @Test
    public void brightButNotSaturatedAtShortestIsAccepted() {
        AutoExposure ae = new AutoExposure(MIN, MIN, MAX);
        assertFalse(ae.feed(false, 0.95));
        assertEquals(AutoExposure.Outcome.OK, ae.outcome());
        assertEquals(MIN, ae.exposureNs());
    }

    @Test
    public void notConvergedUsesBestUnsaturatedShot() {
        AutoExposure ae = new AutoExposure(100 * MS, MIN, MAX);
        // 測るたびに明るさが変わる (光源がちらつく) ので決まらない
        boolean more = true;
        for (int i = 0; more; i++) {
            more = ae.feed(i % 2 == 0, i % 2 == 0 ? 1.0 : (i == 3 ? 0.4 : 0.1));
        }
        assertEquals(AutoExposure.Outcome.NOT_CONVERGED, ae.outcome());
        assertEquals(AutoExposure.MAX_SHOTS, ae.shots());
        assertEquals(0.4, ae.fill(), 1e-12);
    }

    @Test
    public void fillSubtractsBlackLevelAndUsesBrightestChannel() {
        SaturationChecker.Result r = new SaturationChecker.Result(1023);
        r.level[SaturationChecker.R] = 300;
        r.level[SaturationChecker.G] = 544; // 64 + (1023 - 64) / 2
        r.level[SaturationChecker.B] = 100;
        assertEquals(0.5, AutoExposure.fill(r, 64), 1e-3);
        // 黒レベルより暗い値は 0
        SaturationChecker.Result dark = new SaturationChecker.Result(1023);
        assertEquals(0.0, AutoExposure.fill(dark, 64), 0);
    }
}
