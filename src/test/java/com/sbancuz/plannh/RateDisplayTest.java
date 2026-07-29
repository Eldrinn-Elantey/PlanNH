package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.gui.GuiHelper;

/**
 * The display is the only thing the user actually reads, so the rounding rules are pinned here:
 * a number that reaches the screen as "0" claims the machine is idle.
 */
class RateDisplayTest {

    @Test
    void integralCountsStayBare() {
        assertEquals("0", GuiHelper.formatCount(0));
        assertEquals("4", GuiHelper.formatCount(4));
        assertEquals("4", GuiHelper.formatCount(4.0001));
    }

    @Test
    void fractionalCountsKeepTwoDecimals() {
        assertEquals("3.12", GuiHelper.formatCount(3.12));
        assertEquals("0.17", GuiHelper.formatCount(1.0 / 6));
    }

    @Test
    void aRunningMachineNeverDisplaysAsZero() {
        // AUTO returns exact fractional counts, and a fast machine feeding a long assembly line
        // lands well under a hundredth.
        for (final double count : new double[] { 4e-3, 1e-3, 1e-4, 1e-5 }) {
            assertNotEquals("0", GuiHelper.formatCount(count), "count " + count + " rendered as idle");
        }
        // Above a hundredth two decimals still read fine; below it they would round to zero.
        assertEquals("0.02", GuiHelper.formatCount(1.0 / 60));
        assertEquals("0.001", GuiHelper.formatCount(1e-3));
    }

    @Test
    void ratesKeepSubUnityPrecision() {
        assertEquals("0.01667", GuiHelper.formatRate(1f / 60));
        assertEquals("25.00", GuiHelper.formatRate(25f));
        assertEquals("1200", GuiHelper.formatRate(1200f));
    }
}
