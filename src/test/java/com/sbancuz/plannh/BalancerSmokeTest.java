package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.sbancuz.plannh.data.flowchart.Balancer;
import com.sbancuz.plannh.data.flowchart.Balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.Balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;

/**
 * Behavior of the balancer over the corpus: it must never crash, never exceed the per-solve
 * budget, and always return a usable result (the ILP falls back to configured counts when
 * infeasible). Accuracy against the corpus ground truths is covered by {@link GroundTruthTest}.
 */
class BalancerSmokeTest {

    // Target pins anchor the sink counts, which is what makes the big charts expensive:
    // nanocircuits (400 assembly lines for 1/s) takes ~19s anchored against ~6.7s un-anchored.
    // TODO: 30s is the relaxed beta figure; bring it back down as solve times allow.
    private static final Duration BUDGET = Duration.ofSeconds(30);

    static String[] corpus() {
        return GtnhFlowLoader.CORPUS;
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void noneModeUsesConfiguredCounts(final String name) {
        final LoadedChart chart = GtnhFlowLoader.load(name);
        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.NONE, false);
        assertNotNull(result);
        for (final Node node : chart.machines()) {
            assertTrue(
                result.nodeBalances()
                    .containsKey(node.id),
                "missing balance for " + node.machineName);
        }
    }

    /**
     * AUTO mode through the {@link Balancer} entry point: always returns a usable result inside
     * the interactive budget (worst case: two floor passes of up to three stages each), with the
     * displayed operation count being the ceiling of the fractional machine count.
     */
    @ParameterizedTest
    @MethodSource("corpus")
    void autoModeStaysWithinBudget(final String name) {
        final LoadedChart chart = GtnhFlowLoader.load(name);
        final BalanceResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(60),
            () -> Balancer.balance(chart.graph(), BalanceMode.AUTO, false),
            name + " exceeded the auto-balance budget");
        assertNotNull(result);
        for (final Node node : chart.machines()) {
            assertTrue(
                result.nodeBalances()
                    .containsKey(node.id),
                "missing balance for " + node.machineName);
        }
    }

    /**
     * AUTO must never write solved counts back into the node configs: viewing a chart is not
     * editing it. The reported operation count is the exact fractional machine count - no
     * rounding anywhere, so every displayed number can be checked against every other by hand.
     */
    @org.junit.jupiter.api.Test
    void autoModeReportsExactFractionalCounts_andDoesNotWriteThemBack() {
        final LoadedChart chart = GtnhFlowLoader.load("loopGraph");
        final Node lcr = chart.machine(1);
        assertTrue(!lcr.isMachineCountFixed());
        lcr.machineConfig.setMachineCount(3);

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.AUTO, false);

        assertTrue(lcr.machineConfig.getMachineCount() == 3, "configured count must survive viewing");
        assertEquals(
            8.0 / 15.0,
            result.nodeBalances()
                .get(lcr.id)
                .operations(),
            1e-6,
            "the exact fractional machine count, not a ceiling");
    }

    /**
     * The gtnh-flow contract at the Balancer level: an unpinned chart in AUTO mode shows NO
     * quantities - zero counts, empty effective rates (so no throughput rows and an empty
     * summary), and a note telling the user how to ask for a balance. mk1's only pin is a
     * target: pin, which the loader turns into an OUTPUT/INPUT-shaped machine-count anchor;
     * AUTO takes target pins as real constraints instead, so that anchor is cleared first.
     */
    @org.junit.jupiter.api.Test
    void autoModeUnpinned_showsNoQuantities() {
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        GtnhFlowLoader.clearTargetAnchors(chart);

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.AUTO, false);

        assertEquals(0.0, result.totalOperations(), 1e-9);
        for (final Node node : chart.machines()) {
            final var nb = result.nodeBalances()
                .get(node.id);
            assertEquals(0.0, nb.operations(), 1e-9, node.machineName + " must show no count");
            assertTrue(
                nb.effectiveOutputs()
                    .isEmpty()
                    && nb.effectiveInputs()
                        .isEmpty(),
                node.machineName + " must show no rates");
        }
        assertTrue(
            result.notes()
                .stream()
                .anyMatch(n -> n.contains("pin")),
            "the summary must say how to ask for a balance, got: " + result.notes());
    }

    /**
     * Solver notes must reach the BalanceResult (and from there the summary widget): the
     * missing-edge diagnostic was useless while it only went to the log.
     */
    @org.junit.jupiter.api.Test
    void autoModeSurfacesMissingEdgeNotes() {
        final LoadedChart chart = GtnhFlowLoader.load("mk1_tiberium");
        final Node fusion = chart.machine(0);
        chart.graph()
            .getEdges()
            .stream()
            .filter(e -> e.targetNodeId.equals(fusion.id) && e.targetInputIndex == 0)
            .map(e -> e.id)
            .toList()
            .forEach(
                id -> chart.graph()
                    .removeEdge(id));

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.AUTO, false);

        assertTrue(
            result.notes()
                .stream()
                .anyMatch(n -> n.contains("missing an edge")),
            "the wiring diagnostic must reach the summary, got: " + result.notes());
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void outputModeStaysWithinBudget(final String name) {
        final LoadedChart chart = GtnhFlowLoader.load(name);
        final BalanceResult result = assertTimeoutPreemptively(
            BUDGET,
            () -> Balancer.balance(chart.graph(), BalanceMode.OUTPUT, false),
            name + " exceeded the " + BUDGET.toSeconds() + "s solve budget");
        assertNotNull(result);
        for (final Node node : chart.machines()) {
            final double ops = result.nodeBalances()
                .get(node.id)
                .operations();
            assertTrue(ops >= 1, node.machineName + " solved to " + ops + " machines");
        }
    }
}
