package com.sbancuz.plannh.data.flowchart;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.sbancuz.plannh.data.flowchart.Summary.SummaryMode;
import com.sbancuz.plannh.data.flowchart.Summary.SummarySection;

public class SlotSet {

    public static final int DEFAULT_SUMMARY_X = 210;
    public static final int DEFAULT_SUMMARY_Y = 46;

    /**
     * Sections a fresh chart folds away: the reference material, not the answer. Solver messages
     * stay open - a chart that failed to balance has to say so without being asked.
     */
    public static EnumSet<SummarySection> defaultSummaryFolds() {
        return EnumSet.of(SummarySection.MACHINE_COUNTS, SummarySection.STATISTICS, SummarySection.HELP);
    }

    public static class Slot {

        public String name;
        public Graph graph;
        /** Per-slot and session-only: edits also arrive from the NEI overlay with no screen open. */
        public final transient UndoHistory undoHistory = new UndoHistory();
        /**
         * Per slot rather than per set: a fold says "not on this chart", and the next chart is a
         * different question.
         */
        public final EnumSet<SummarySection> collapsedSummarySections = defaultSummaryFolds();

        public Slot(final String name, final Graph graph) {
            this.name = name;
            this.graph = graph;
        }
    }

    public final List<Slot> slots = new ArrayList<>();
    public int activeSlot = 0;
    public int summaryX = DEFAULT_SUMMARY_X;
    public int summaryY = DEFAULT_SUMMARY_Y;
    public boolean summaryCollapsed = false;
    public SummaryMode summaryMode = SummaryMode.CYCLES;

    /** Clamps activeSlot and guarantees a slot exists. */
    private Slot activeSlot() {
        if (slots.isEmpty()) {
            slots.add(new Slot("Slot 1", new Graph()));
        }
        if (activeSlot < 0 || activeSlot >= slots.size()) {
            activeSlot = 0;
        }
        return slots.get(activeSlot);
    }

    public Graph getActiveGraph() {
        return activeSlot().graph;
    }

    public void setActiveGraph(final Graph graph) {
        activeSlot().graph = graph;
    }

    public UndoHistory getActiveUndoHistory() {
        return activeSlot().undoHistory;
    }

    /** The summary folds of the chart on screen; mutated in place by the panel's headers. */
    public EnumSet<SummarySection> getActiveSummaryFolds() {
        return activeSlot().collapsedSummarySections;
    }
}
