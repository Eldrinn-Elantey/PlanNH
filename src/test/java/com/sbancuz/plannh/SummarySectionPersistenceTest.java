package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Serializer;
import com.sbancuz.plannh.data.flowchart.SlotSet;
import com.sbancuz.plannh.data.flowchart.Summary.SummarySection;

/** Folded summary sections are a per-user preference: losing them re-expands panels every launch. */
class SummarySectionPersistenceTest {

    private static SlotSet oneSlot() {
        final SlotSet set = new SlotSet();
        set.slots.add(new SlotSet.Slot("Slot 1", new Graph()));
        return set;
    }

    @Test
    void collapsedSectionsSurviveEncodeDecode() {
        final SlotSet set = oneSlot();
        set.collapsedSummarySections.clear();
        set.collapsedSummarySections.add(SummarySection.NOTES);
        set.collapsedSummarySections.add(SummarySection.PROPERTIES);

        final SlotSet decoded = Serializer.decodeSlotSet(Serializer.encode(set));

        assertEquals(
            EnumSet.of(SummarySection.NOTES, SummarySection.PROPERTIES),
            decoded.collapsedSummarySections);
    }

    /** An explicitly empty set is "everything open", not "never saved". */
    @Test
    void allSectionsOpenSurvivesEncodeDecode() {
        final SlotSet set = oneSlot();
        set.collapsedSummarySections.clear();

        final SlotSet decoded = Serializer.decodeSlotSet(Serializer.encode(set));

        assertEquals(EnumSet.noneOf(SummarySection.class), decoded.collapsedSummarySections);
    }

    /** Saves written before sections were foldable open like a fresh install. */
    @Test
    void savesWithoutTheKeyKeepTheDefault() {
        final String json = Serializer.encode(oneSlot())
            .replace("\"summaryCollapsedSections\"", "\"unusedKey\"");

        final SlotSet decoded = Serializer.decodeSlotSet(json);

        assertEquals(EnumSet.of(SummarySection.OPERATIONS), decoded.collapsedSummarySections);
    }
}
