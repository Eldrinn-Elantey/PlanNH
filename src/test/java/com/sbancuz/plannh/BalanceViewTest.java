package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.BalanceView;
import com.sbancuz.plannh.data.flowchart.BalanceView.Boundary;
import com.sbancuz.plannh.data.flowchart.BalanceView.Choice;
import com.sbancuz.plannh.data.flowchart.BalanceView.Kind;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Serializer;
import com.sbancuz.plannh.harness.GtnhFlowLoader;

/**
 * The choices workflow driven end to end without a screen. Everything the canvas and the summary
 * panel show about the chart's boundary is decided in {@link BalanceView}, so this exercises the
 * same rows a player reads - what is voided, what the alternatives are, what picking one does -
 * rather than asserting against solver internals nobody sees.
 */
class BalanceViewTest {

    private static Graph chart(final String name) {
        return GtnhFlowLoader.load(name)
            .graph();
    }

    private static List<Boundary> of(final List<Boundary> flows, final Kind kind) {
        return flows.stream()
            .filter(b -> b.kind() == kind)
            .toList();
    }

    @Test
    void surplusIsNamedAtItsPortAndReadsAsSomethingToCollect() {
        // The reported bug in one assertion: the surplus is pinned to the port it leaves by, and
        // named as a surplus rather than as destruction - a player empties that bus like any other.
        final List<Boundary> flows = chart("excess_choice").boundary();

        final List<Boundary> voided = of(flows, Kind.EXCESS);
        assertEquals(1, voided.size());
        assertEquals(
            "nitrogen",
            voided.get(0)
                .ingredient());
        assertEquals(
            530.0,
            voided.get(0)
                .ratePerSecond(),
            1e-4);
        assertTrue(
            voided.get(0)
                .label()
                .startsWith("excess "),
            () -> "reads as surplus, not as waste: " + voided.get(0)
                .label());
        assertFalse(
            voided.get(0)
                .label()
                .contains("void"),
            "nothing here is destroyed");

        assertTrue(
            of(flows, Kind.PRODUCT).stream()
                .noneMatch(
                    b -> b.ingredient()
                        .equals("nitrogen")),
            "and is not double-counted as a separate product line");
        assertTrue(
            of(flows, Kind.PRODUCT).stream()
                .anyMatch(
                    b -> b.ingredient()
                        .equals("coal tar")),
            "while the thing the chart is for still does");
        assertTrue(
            of(flows, Kind.SUPPLY).stream()
                .anyMatch(
                    b -> b.ingredient()
                        .equals("oak wood")));
    }

    @Test
    void aBalancedChartHasNothingToChooseAndNothingToVoid() {
        final Graph graph = chart("light_fuel");
        assertFalse(BalanceView.hasChoices(graph), "no gates, so no question to ask");
        assertTrue(of(graph.boundary(), Kind.EXCESS).isEmpty(), "and no surplus anywhere");
        assertTrue(
            graph.choices()
                .rows()
                .isEmpty(),
            "and so nothing to present");
    }

    @Test
    void everyAnswerIsLabelledAndExactlyOneIsMarkedCurrent() {
        for (final String name : List.of("symmetric_choice", "excess_choice", "mk1", "loopGraph")) {
            final BalanceView.Choices choices = chart(name).choices();
            assertTrue(
                choices.rows()
                    .size() > 1,
                () -> name + " should offer a choice");
            for (final BalanceView.Group group : choices.groups()) {
                assertEquals(
                    1,
                    group.rows()
                        .stream()
                        .filter(Choice::active)
                        .count(),
                    () -> name + " must mark one row per decision as the one on screen");
                assertTrue(
                    group.rows()
                        .get(0)
                        .active(),
                    () -> name + " lists each decision's current answer first");
                assertFalse(
                    group.heading()
                        .isBlank(),
                    () -> name + " has an unnamed decision");
            }
            for (final Choice row : choices.rows()) {
                assertFalse(
                    row.label()
                        .isBlank(),
                    () -> name + " has an unlabelled row");
                assertFalse(
                    row.label()
                        .contains("null"),
                    () -> name + " failed to resolve an ingredient: " + row.label());
                if (!row.active()) {
                    assertFalse(
                        row.reason()
                            .isBlank(),
                        () -> name + " listed an alternative with no reason given");
                }
            }
        }
    }

    @Test
    void theHeuristicThatPickedTheAnswerIsStatedInWords() {
        // mk1's default beats its alternative on a constant somebody chose, not on a measurement.
        // If that sentence ever stops reaching the panel, the tilt is invisible again.
        final List<Choice> rows = chart("mk1").choices()
            .rows();
        assertEquals(
            "imports instead of leaving a surplus",
            rows.get(1)
                .reason());
        assertTrue(
            rows.get(0)
                .label()
                .startsWith("excess "),
            () -> "default leaves a surplus: " + rows.get(0)
                .label());
        assertTrue(
            rows.get(1)
                .label()
                .startsWith("add "),
            () -> "alternative imports: " + rows.get(1)
                .label());
    }

    @Test
    void pickingAnAnswerChangesTheChartAndSticksAcrossASave() {
        final Graph graph = chart("symmetric_choice");
        final Choice before = graph.choices()
            .rows()
            .get(0);
        final Choice alternative = graph.choices()
            .rows()
            .get(1);
        assertFalse(
            before.label()
                .equals(alternative.label()),
            "the two answers void different things");

        // What the summary panel's click handler does, minus the pixels.
        graph.setExcessChoice(alternative.key());

        final BalanceView.Choices after = graph.choices();
        assertTrue(
            after.rows()
                .stream()
                .filter(Choice::active)
                .allMatch(
                    r -> r.key()
                        .equals(alternative.key())),
            "the picked answer is now the one on screen");
        assertEquals(
            alternative.label(),
            of(graph.boundary(), Kind.EXCESS).stream()
                .map(Boundary::label)
                .findFirst()
                .orElse(""),
            "and the canvas voids where the user asked");

        assertEquals(
            alternative.key(),
            Serializer.decode(Serializer.encode(graph))
                .getExcessChoice(),
            "and the pick survives a save");
    }

    @Test
    void theReasonMatchesWhichWayTheAnswerLeans() {
        // Both lose on the same objective - more external quantity - but one throws away and the
        // other imports, and one sentence cannot honestly describe both.
        assertEquals(
            "leaves more excess",
            chart("excess_choice").choices()
                .rows()
                .get(1)
                .reason());
        assertEquals(
            "imports more",
            chart("loopGraph").choices()
                .rows()
                .get(1)
                .reason());
    }

    @Test
    void independentDecisionsAreGroupedRatherThanPooled() {
        // Two branch pairs that never touch, so where each leaves its surplus is a separate
        // question. Flat, this is six answers with no indication that picking one of the first
        // three has nothing to do with the last three.
        final BalanceView.Choices choices = chart("two_decisions").choices();

        assertEquals(
            2,
            choices.groups()
                .size(),
            "two questions");
        for (final BalanceView.Group group : choices.groups()) {
            assertEquals(
                3,
                group.rows()
                    .size(),
                "each with its own three answers");
            assertEquals(
                1,
                group.rows()
                    .stream()
                    .filter(Choice::active)
                    .count());
            assertEquals(
                group.rows()
                    .get(0)
                    .label(),
                group.heading(),
                "named by the answer in force");
        }
        assertEquals(
            6,
            choices.rows()
                .stream()
                .map(Choice::label)
                .distinct()
                .count(),
            "and no answer appears in both");
    }

    @Test
    void aDecisionWithNothingToDecideIsNotShown() {
        // jet_fuel has two open gates but only one of them has any alternative. A heading over a
        // lone row repeating it is an offer that is not being made.
        for (final BalanceView.Group group : chart("jet_fuel").choices()
            .groups()) {
            assertTrue(
                group.rows()
                    .size() > 1,
                () -> "empty decision shown: " + group.heading());
        }
    }

    @Test
    void aTruncatedListSaysSoRatherThanLookingComplete() {
        // palladium_line has far more candidate swaps than the search will try. Silence here would
        // read as "these are all the answers", which is the one thing it must not claim.
        final BalanceView.Choices choices = chart("palladium_line").choices();
        if (!choices.complete()) {
            assertFalse(
                choices.notes()
                    .isEmpty(),
                "an incomplete search has to explain itself");
        }
    }
}
