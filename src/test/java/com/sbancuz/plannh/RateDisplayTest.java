package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.gui.GuiHelper;

/**
 * The two display rules that mean something rather than merely describe the formatter: a running
 * machine must not read as idle, and a big rate must not read as a bucket count.
 */
class RateDisplayTest {

    @Test
    void aRunningMachineNeverDisplaysAsZero() {
        // AUTO returns exact fractional counts, and a fast machine feeding a long assembly line
        // lands well under a hundredth - where two decimals would round it to "0" and claim the
        // machine is idle.
        for (final double count : new double[] { 4e-3, 1e-3, 1e-4, 1e-5 }) {
            assertNotEquals("0", GuiHelper.formatCount(count), "count " + count + " rendered as idle");
        }
    }

    @Test
    void bigRatesUseGigaNotBuckets() {
        // B is what fluid amounts use for buckets; a 1.5e9/s rate must not read as 1.5 buckets.
        assertEquals("1.5G", GuiHelper.formatRate(1.5e9f));
    }
}
