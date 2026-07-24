package com.sbancuz.plannh.data.effect;

public class EffectResult {

    private int durationTicks;
    private long energyPerT;
    private int throughputFactor;

    public EffectResult(int durationTicks, long energyPerT, int throughputFactor) {
        this.durationTicks = durationTicks;
        this.energyPerT = energyPerT;
        this.throughputFactor = throughputFactor;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public long energyPerT() {
        return energyPerT;
    }

    public int throughputFactor() {
        return throughputFactor;
    }

    public void durationTicks(int value) {
        this.durationTicks = value;
    }

    public void energyPerT(long value) {
        this.energyPerT = value;
    }

    public void throughputFactor(int value) {
        this.throughputFactor = value;
    }
}
