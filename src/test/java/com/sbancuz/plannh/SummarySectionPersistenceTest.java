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
    void foldedSectionsSurviveEncodeDecode() {
        final SlotSet set = oneSlot();
        set.collapsedSummarySections.clear();
        set.collapsedSummarySections.add(SummarySection.MESSAGES);
        set.collapsedSummarySections.add(SummarySection.STATISTICS);

        final SlotSet decoded = Serializer.decodeSlotSet(Serializer.encode(set));

        assertEquals(EnumSet.of(SummarySection.MESSAGES, SummarySection.STATISTICS), decoded.collapsedSummarySections);
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
    void savesWithoutTheKeyKeepTheDefaults() {
        final String json = Serializer.encode(oneSlot())
            .replace("\"summarySectionFolds\"", "\"unusedKey\"");

        final SlotSet decoded = Serializer.decodeSlotSet(json);

        assertEquals(new SlotSet().collapsedSummarySections, decoded.collapsedSummarySections);
    }

    /**
     * The reason the folds are stored section by section: a save written before a section existed
     * must not drag that section open, or every new section arrives expanded for existing users.
     */
    @Test
    void aSectionTheSaveNeverHeardOfKeepsItsDefault() {
        final SlotSet set = oneSlot();
        set.collapsedSummarySections.clear();
        set.collapsedSummarySections.add(SummarySection.STATISTICS);
        final String json = Serializer.encode(set)
            .replace("\"" + SummarySection.HELP.name() + "\"", "\"SECTION_FROM_A_LATER_BUILD\"");

        final SlotSet decoded = Serializer.decodeSlotSet(json);

        assertEquals(
            EnumSet.of(SummarySection.STATISTICS, SummarySection.HELP),
            decoded.collapsedSummarySections,
            "the unmentioned section keeps the default fold, the mentioned ones keep the save's");
    }
}
