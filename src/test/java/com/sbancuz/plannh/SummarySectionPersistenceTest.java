package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Serializer;
import com.sbancuz.plannh.data.flowchart.SlotSet;
import com.sbancuz.plannh.data.flowchart.Summary.SummarySection;

/**
 * Folded summary sections are a per-chart preference: losing them re-expands panels every launch,
 * and sharing them between slots followed one unfolded panel into every chart the user opened next.
 */
class SummarySectionPersistenceTest {

    private static SlotSet twoSlots() {
        final SlotSet set = new SlotSet();
        set.slots.add(new SlotSet.Slot("Slot 1", new Graph()));
        set.slots.add(new SlotSet.Slot("Slot 2", new Graph()));
        return set;
    }

    @Test
    void foldedSectionsSurviveEncodeDecodePerSlot() {
        final SlotSet set = twoSlots();
        set.slots.get(0).collapsedSummarySections.clear();
        set.slots.get(0).collapsedSummarySections.add(SummarySection.MESSAGES);
        set.slots.get(1).collapsedSummarySections.clear();

        final SlotSet decoded = Serializer.decodeSlotSet(Serializer.encode(set));

        assertEquals(
            EnumSet.of(SummarySection.MESSAGES),
            decoded.slots.get(0).collapsedSummarySections,
            "one chart's folds");
        assertEquals(
            EnumSet.noneOf(SummarySection.class),
            decoded.slots.get(1).collapsedSummarySections,
            "an explicitly empty set is 'everything open', not 'never saved'");
    }

    @Test
    void aNewSlotStartsFromTheDefaultsRatherThanTheLastChartsPanel() {
        final SlotSet set = twoSlots();
        set.slots.get(0).collapsedSummarySections.clear();

        set.slots.add(new SlotSet.Slot("Slot 3", new Graph()));

        assertEquals(SlotSet.defaultSummaryFolds(), set.slots.get(2).collapsedSummarySections);
        assertEquals(
            SlotSet.defaultSummaryFolds(),
            Serializer.decodeSlotSet(Serializer.encode(set)).slots.get(2).collapsedSummarySections,
            "and still does after a round trip");
    }

    /** Saves written before folds were per slot open every chart the way a fresh install would. */
    @Test
    void savesWithoutTheKeyKeepTheDefaults() {
        final String json = Serializer.encode(twoSlots())
            .replace("\"sectionFolds\"", "\"unusedKey\"");

        final SlotSet decoded = Serializer.decodeSlotSet(json);

        for (final SlotSet.Slot slot : decoded.slots) {
            assertEquals(SlotSet.defaultSummaryFolds(), slot.collapsedSummarySections);
        }
    }

    /**
     * The reason the folds are stored section by section: a save written before a section existed
     * must not drag that section open, or every new section arrives expanded for existing users.
     */
    @Test
    void aSectionTheSaveNeverHeardOfKeepsItsDefault() {
        final SlotSet set = twoSlots();
        for (final SlotSet.Slot slot : set.slots) {
            slot.collapsedSummarySections.clear();
            slot.collapsedSummarySections.add(SummarySection.STATISTICS);
        }
        final String json = Serializer.encode(set)
            .replace("\"" + SummarySection.HELP.name() + "\"", "\"SECTION_FROM_A_LATER_BUILD\"");

        final SlotSet decoded = Serializer.decodeSlotSet(json);

        assertEquals(
            EnumSet.of(SummarySection.STATISTICS, SummarySection.HELP),
            decoded.slots.get(0).collapsedSummarySections,
            "the unmentioned section keeps the default fold, the mentioned ones keep the save's");
    }

    /** Solver messages are the one section that starts open: a failed balance must not be silent. */
    @Test
    void solverMessagesStartOpen() {
        assertEquals(
            EnumSet.of(SummarySection.MACHINE_COUNTS, SummarySection.STATISTICS, SummarySection.HELP),
            SlotSet.defaultSummaryFolds());
    }
}
