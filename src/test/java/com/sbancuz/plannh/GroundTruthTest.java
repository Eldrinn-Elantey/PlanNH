package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.AutoBalancer;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.External;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.PortRef;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Result;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Solution;
import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;
import com.sbancuz.plannh.harness.GtnhFlowLoader.Pin;
import com.sbancuz.plannh.harness.TestIngredients;

/**
 * Corpus ground truths asserted against {@link AutoBalancer}. Every expected number is
 * independently derivable from the chart YAML by hand. Vocabulary: a port with no edges is a
 * free terminal; a connected port may get a GATED external (binary cost). "Gates" counts open
 * gated externals only.
 */
class GroundTruthTest {

    private static final double EPS = 1e-4;

    @Test
    void loopGraph_oneSourceInjectingThirdOfLoopDemand() {
        // DT (pinned number:1) consumes 100/s diluted sulfuric acid; the LCR loop returns only
        // 2/3 of it. Expect exactly ONE open gate: a source on the DT's diluted-acid input
        // injecting exactly 1/3 of the pinned demand. The tied alternative (source sulfuric at
        // the LCR instead) must lose at stage 3 on internal flow. All machines run.
        final LoadedChart chart = GtnhFlowLoader.load("loopGraph");
        final Solution s = solve(chart);

        assertEquals(1, s.openGates(), "exactly one gated external");
        assertEquals(
            1,
            s.gatedSources()
                .size(),
            "the gate is a source");
        final External source = s.gatedSources()
            .get(0);
        assertEquals(
            chart.machine(0).id,
            source.port()
                .nodeId(),
            "source sits on the DT (diluted acid input)");
        assertTrue(
            source.port()
                .input());
        assertEquals(100.0 / 3.0, source.ratePerSecond(), EPS, "injects exactly 1/3 of the DT's demand");
        assertAllMachinesRun(chart, s);
        assertEquals(
            1.0,
            s.machineCounts()
                .get(chart.machine(0).id),
            EPS,
            "pinned DT stays at 1");
        assertEquals(
            8.0 / 15.0,
            s.machineCounts()
                .get(chart.machine(1).id),
            EPS,
            "LCR runs at 0.533 machines");
    }

    @Test
    void mk1_exactlyOneGate_sinkExcessPreferred() {
        // Two genuinely tied optima exist: {sink heavy naquadah} and {source light naquadah}.
        // The 1025/1024 source/sink weights must make the deterministic default the SINK
        // (discard excess beats supplying an intermediate). Optima enumeration must find exactly
        // these two.
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final Map<UUID, Double> pins = targetPins(chart);
        final Result result = AutoBalancer.solve(chart.graph(), pins);
        assertTrue(result.isSuccess(), () -> "solve failed: " + result.failure());
        final Solution s = result.solution();

        assertEquals(1, s.openGates(), "exactly one gated external");
        assertEquals(
            1,
            s.gatedSinks()
                .size(),
            "the deterministic default is the sink");
        final External sink = s.gatedSinks()
            .get(0);
        assertEquals(
            chart.machine(1).id,
            sink.port()
                .nodeId(),
            "sink sits on the DT's heavy naquadah output");
        assertEquals(0.25, sink.ratePerSecond(), EPS, "0.25/s heavy naquadah discarded");

        final List<Set<PortRef>> alternatives = AutoBalancer.enumerateAlternatives(chart.graph(), pins);
        assertEquals(2, alternatives.size(), "exactly two tied optima: sink heavy, source light");
    }

    @Test
    void lightFuel_zeroGates() {
        // Straight-line chart: oil 25/s in, light fuel 25/s out (plus O2, H2S byproducts as
        // free terminals). No gated external may open, and the sub-unity machine counts must be
        // returned fractionally (chemical reactor at 1/60).
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "no gated external may open");
        assertEquals(25.0, terminalRate(chart, s.terminalInputs(), "oil"), EPS, "oil in at 25/s");
        assertEquals(25.0, terminalRate(chart, s.terminalOutputs(), "light fuel"), EPS, "light fuel out at 25/s");
        assertEquals(25.0 / 12.0, terminalRate(chart, s.terminalOutputs(), "oxygen"), EPS);
        assertEquals(25.0 / 12.0, terminalRate(chart, s.terminalOutputs(), "hydrogen sulfide"), EPS);
        assertEquals(
            1.0 / 60.0,
            s.machineCounts()
                .get(chart.machine(0).id),
            EPS,
            "chemical reactor at 1/60");
    }

    @Test
    void lightFuelHydrogenLoop_fullyRecycles() {
        // The hydrogen-loop variant must fully recycle its hydrogen: still zero gates, and the
        // loop's free circulation must be pinned by stage 3 (minimize total internal flow) to a
        // finite value.
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel_hydrogen_loop");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "hydrogen fully recycles, no gates");
        assertAllMachinesRun(chart, s);
        assertTrue(
            s.totalInternalFlow() < 1e7,
            "loop circulation pinned finite by stage 3, got " + s.totalInternalFlow());
    }

    @Test
    void mk1Tiberium_zeroGates_bathConsumesTheExcessHeavy() {
        // mk1 plus a tiberium bath. Adding the bath (a
        // consumer for the excess heavy naquadah) REMOVES mk1's sink-vs-source ambiguity: the
        // zero-gate support is unique - light pins the DT at 3.12 machines, heavy then pins the
        // bath at 1/6 machines - so the solver must find it with no externals and no prompt.
        final LoadedChart chart = GtnhFlowLoader.load("mk1_tiberium");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "no external heavy naquadah - the bath eats the excess");
        assertEquals(
            1.0,
            s.machineCounts()
                .get(chart.machine(0).id),
            EPS,
            "fusion pinned at 1");
        assertEquals(
            3.12,
            s.machineCounts()
                .get(chart.machine(1).id),
            EPS,
            "DT at 3.12 machines");
        assertEquals(
            1.0 / 6.0,
            s.machineCounts()
                .get(chart.machine(2).id),
            EPS,
            "bath at 1/6 machines");
        assertEquals(62.4, terminalRate(chart, s.terminalInputs(), "naquadah solution"), EPS);
    }

    @Test
    void mk1Tiberium_unwiredFusionHeavy_solvesAsDrawn_butFlagsTheMissingEdge() {
        // A state easily reached in-game: the fusion reactor's
        // heavy input was never wired to the DT, only the bath's was. As drawn, the chart is
        // balanceable gate-free: the bath scales to 2.167 machines eating ALL of the DT's 15.6/s
        // heavy, and the fusion reactor's heavy arrives through its free terminal (the summary's
        // "14 mB/s heavy naquadah external input"). That answer is CORRECT for the drawn graph -
        // but it is almost certainly a missing edge, so the solver must say so in its notes.
        final LoadedChart chart = GtnhFlowLoader.load("mk1_tiberium");
        final Node fusion = chart.machine(0);
        final List<UUID> fusionHeavyEdges = chart.graph()
            .getEdges()
            .stream()
            .filter(e -> e.targetNodeId.equals(fusion.id) && e.targetInputIndex == 0)
            .map(e -> e.id)
            .toList();
        assertEquals(1, fusionHeavyEdges.size(), "precondition: the loader wired DT heavy -> fusion");
        chart.graph()
            .removeEdge(fusionHeavyEdges.get(0));

        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "gate-free as drawn: the bath absorbs all routed heavy");
        assertEquals(
            13.0 / 6.0,
            s.machineCounts()
                .get(chart.machine(2).id),
            EPS,
            "bath scales to 2.167 machines");
        assertEquals(
            14.4,
            terminalRate(chart, s.terminalInputs(), "heavy naquadah fuel"),
            EPS,
            "fusion's heavy arrives via its free terminal");
        assertTrue(
            s.notes()
                .stream()
                .anyMatch(n -> n.contains("missing an edge")),
            "the missing-edge diagnostic must fire, got notes: " + s.notes());
    }

    @Test
    void noPin_noBalance() {
        // The gtnh-flow contract: an unpinned chart is just wiring. The model is homogeneous
        // (every solution scales freely), so instead of inventing an anchor the solver refuses
        // with a message telling the user how to ask for a balance. mk1's only pin is a target:
        // pin; dropping the loader's count anchor for it and passing no pins of our own is
        // exactly the unpinned case.
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        GtnhFlowLoader.clearTargetAnchors(chart);
        final Result result = AutoBalancer.solve(chart.graph());

        assertTrue(!result.isSuccess(), "unpinned chart must not be balanced");
        assertEquals(AutoBalancer.NO_PIN, result.failure());
        assertTrue(
            AutoBalancer.enumerateAlternatives(chart.graph(), Map.of())
                .isEmpty(),
            "no alternatives either");
    }

    @Test
    void wellWiredCharts_produceNoMissingEdgeNotes() {
        // The diagnostic must not cry wolf: fully wired charts (including ones with legitimate
        // gated sources like loopGraph and legitimate terminal imports like light_fuel's oil)
        // stay silent.
        for (final String name : new String[] { "loopGraph", "light_fuel", "light_fuel_hydrogen_loop", "mk1",
            "mk1_tiberium" }) {
            final LoadedChart chart = GtnhFlowLoader.load(name);
            final Solution s = solve(chart);
            assertTrue(
                s.notes()
                    .stream()
                    .noneMatch(n -> n.contains("missing an edge")),
                name + " should have no wiring notes, got: " + s.notes());
        }
    }

    @Test
    void palladiumLine_atMostElevenGates_allMachinesRun_withinBudget() {
        // 56 machines. All must run (stage 0 floors). A reference HiGHS solve gave 11 gated
        // externals, matching a historical hand-picked whitelist - but gate counts on
        // floored charts are floor-sensitive and were certified only to
        // +-1. The hard requirements: a validated solution, every machine running, no MORE
        // externals than the historical whitelist, and inside the interactive budget. (The
        // current deletion-filter answer is 9 gates, strictly better than the whitelist.)
        final LoadedChart chart = GtnhFlowLoader.load("palladium_line");
        final Solution s = solve(chart);

        assertAllMachinesRun(chart, s);
        assertTrue(s.openGates() > 0, "palladium line cannot balance gate-free");
        assertTrue(s.openGates() <= 11, "at most the historical whitelist's 11 externals, got " + s.openGates());
        assertTrue(s.wallMillis() < 60_000, "total wall " + s.wallMillis() + "ms");
    }

    @Test
    void nanocircuits_zeroGates_fastPath() {
        // 394 machines, fully balanced chain: zero gates. The zero-gate LP fast path must keep
        // this well under budget despite the model size.
        final LoadedChart chart = GtnhFlowLoader.load("nanocircuits");
        final Solution s = solve(chart);

        assertEquals(0, s.openGates(), "0 gates on 394 machines");
        assertTrue(s.wallMillis() < 15_000, "wall " + s.wallMillis() + "ms");
    }

    @Test
    void mk1_reproducesTheHandDerivedRatios() {
        // Every number here is derived from mk1.yaml by hand at its target of 10 naquadah fuel
        // mk1/s, and matches the worked example in the design notes. The fusion reactor makes 100
        // per 0.25s craft, so 10/s is 0.1 crafts/s; that draws 30x0.1 = 3/s heavy and 65x0.1 =
        // 6.5/s light. The tower makes 10 light per 1s craft, so it runs at 0.65 crafts/s, which
        // also makes 5x0.65 = 3.25/s heavy - 0.25/s more than the reactor can take.
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final Solution s = solve(chart);

        final Node fusion = chart.machine(0);
        final Node tower = chart.machine(1);

        assertEquals(
            0.1,
            s.extentsPerSecond()
                .get(fusion.id),
            EPS,
            "fusion runs at 0.1 crafts/s");
        assertEquals(
            0.65,
            s.extentsPerSecond()
                .get(tower.id),
            EPS,
            "tower runs at 0.65 crafts/s");

        assertEquals(13.0, terminalRate(chart, s.terminalInputs(), "naquadah solution"), EPS, "13/s in");
        assertEquals(10.0, terminalRate(chart, s.terminalOutputs(), "naquadah fuel mk1"), EPS, "10/s out");
        assertEquals(1.3, terminalRate(chart, s.terminalOutputs(), "naquadah asphalt"), EPS);
        assertEquals(39.0, terminalRate(chart, s.terminalOutputs(), "naquadah gas"), EPS);

        final double heavyToFusion = edgeRateInto(chart, s, fusion, "heavy naquadah fuel");
        final double lightToFusion = edgeRateInto(chart, s, fusion, "light naquadah fuel");
        assertEquals(3.0, heavyToFusion, EPS, "3/s heavy reaches the reactor");
        assertEquals(6.5, lightToFusion, EPS, "6.5/s light reaches the reactor");

        assertEquals(
            1,
            s.gatedSinks()
                .size(),
            "the excess heavy is discarded, once");
        final double heavyDiscarded = s.gatedSinks()
            .get(0)
            .ratePerSecond();
        assertEquals(0.25, heavyDiscarded, EPS, "0.25/s heavy discarded");
        // The ratio a human reads off the chart to check it by eye.
        assertEquals(12.0, heavyToFusion / heavyDiscarded, 1e-6, "heavy consumed to heavy discarded is exactly 12:1");
        assertEquals(3.25, heavyToFusion + heavyDiscarded, EPS, "and together they are everything the tower made");
    }

    /** Summed flow on edges delivering the named ingredient into a machine's inputs. */
    private static double edgeRateInto(final LoadedChart chart, final Solution s, final Node machine,
        final String ingredient) {
        double rate = 0;
        for (final Edge edge : chart.graph()
            .getEdges()) {
            if (!edge.targetNodeId.equals(machine.id)) continue;
            if (!TestIngredients.nameOf(machine.inputs.get(edge.targetInputIndex))
                .equals(ingredient)) continue;
            rate += s.edgeFlowsPerSecond()
                .getOrDefault(edge.id, 0.0);
        }
        return rate;
    }

    @Test
    void everyMachineWiredToAPinRuns() {
        // Stage 0 is a constraint, not a preference: a chart whose machines cannot all run is
        // reported as unbalanceable. A solved chart with a machine parked at zero is the failure
        // this guards - it reads on screen as a working plan with a dead machine in it.
        for (final String name : GtnhFlowLoader.CORPUS) {
            final LoadedChart chart = GtnhFlowLoader.load(name);
            final Result result = AutoBalancer.solve(chart.graph(), targetPins(chart));
            assertTrue(result.isSuccess(), () -> name + " failed: " + result.failure());
            assertAllMachinesRun(chart, result.solution());
        }
    }

    @Test
    void aMachineDisconnectedFromThePinIsExempt() {
        // The exemption to stage 0: nothing anchors the scale of a component with no pin in it,
        // so forcing it to run would invent quantities. It must not drag the rest down with it.
        final LoadedChart chart = GtnhFlowLoader.load("mk1_tiberium");
        final Node stranded = chart.machine(2);
        chart.graph()
            .getEdges()
            .stream()
            .filter(e -> e.sourceNodeId.equals(stranded.id) || e.targetNodeId.equals(stranded.id))
            .map(e -> e.id)
            .toList()
            .forEach(
                id -> chart.graph()
                    .removeEdge(id));

        final Result result = AutoBalancer.solve(chart.graph(), targetPins(chart));

        assertTrue(result.isSuccess(), () -> "solve failed: " + result.failure());
        for (final Node machine : chart.machines()) {
            if (machine.id.equals(stranded.id)) continue;
            assertTrue(
                result.solution()
                    .extentsPerSecond()
                    .get(machine.id) > 1e-9,
                machine.machineName + " must still run");
        }
    }

    @Test
    void solutionsScaleWithTheirPins() {
        // The model is homogeneous: scaling every pin by f must scale the whole solution by f and
        // leave the structure alone. Absolute tolerances broke this - a chart pinned at a tenth of
        // the rate used to come back with a different gate count, or with most machines idle.
        for (final String name : new String[] { "mk1", "palladium_line" }) {
            Integer gates = null;
            Double normalizedQuantity = null;
            for (final double f : new double[] { 1.0, 0.5, 0.1, 0.01, 0.001 }) {
                final LoadedChart chart = GtnhFlowLoader.load(name);
                GtnhFlowLoader.clearTargetAnchors(chart);
                final Map<UUID, Double> scaled = new HashMap<>();
                targetPins(chart).forEach((id, extent) -> scaled.put(id, extent * f));

                final Result result = AutoBalancer.solve(chart.graph(), scaled);
                assertTrue(result.isSuccess(), () -> name + " @" + f + " failed: " + result.failure());
                final Solution s = result.solution();

                if (gates == null) {
                    gates = s.openGates();
                    normalizedQuantity = s.externalQuantity() / f;
                } else {
                    assertEquals(gates.intValue(), s.openGates(), () -> name + " @" + f + " changed gate count");
                    assertEquals(
                        normalizedQuantity,
                        s.externalQuantity() / f,
                        Math.max(1e-6, normalizedQuantity * 1e-4),
                        () -> name + " @" + f + " changed external quantity");
                }
                assertAllMachinesRun(chart, s);
            }
        }
    }

    @Test
    void staleEdgePortIndex_isDroppedRatherThanCrashing() {
        // Saved edges keep their port indices; the port lists come back from the live recipe
        // handler and can be shorter. The balance runs from draw(), so an out-of-range index has
        // to be survivable - the ILP modes already skip these edges.
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final Edge stale = chart.graph()
            .getEdges()
            .iterator()
            .next();
        stale.targetInputIndex = 99;

        final Result result = AutoBalancer.solve(chart.graph(), targetPins(chart));

        assertTrue(result.isSuccess(), () -> "solve failed: " + result.failure());
        assertPortsConserve("mk1 with a stale edge", chart, result.solution());
    }

    @Test
    void everySolutionValidatesIndependently() {
        // Conservation is recomputed here from the returned flows rather than asked of the
        // solver: AutoBalancer validates its own solutions, so trusting isSuccess() would only
        // re-assert the solver's opinion of itself.
        for (final String name : GtnhFlowLoader.CORPUS) {
            final LoadedChart chart = GtnhFlowLoader.load(name);
            final Result result = AutoBalancer.solve(chart.graph(), targetPins(chart));
            assertTrue(result.isSuccess(), () -> name + " failed: " + result.failure());
            assertPortsConserve(name, chart, result.solution());
        }
    }

    /**
     * Every port must balance: what the machine produces or consumes there equals the flows on
     * its edges plus whatever external the solver attached to it.
     */
    private static void assertPortsConserve(final String name, final LoadedChart chart, final Solution s) {
        final Map<PortRef, Double> externals = new HashMap<>();
        for (final External e : List.of(s.gatedSources(), s.gatedSinks(), s.terminalInputs(), s.terminalOutputs())
            .stream()
            .flatMap(List::stream)
            .toList()) {
            externals.merge(e.port(), e.ratePerSecond(), Double::sum);
        }

        for (final Node node : chart.machines()) {
            final double extent = s.extentsPerSecond()
                .getOrDefault(node.id, 0.0);
            for (int side = 0; side < 2; side++) {
                final boolean input = side == 0;
                final List<Port<?>> ports = input ? node.inputs : node.outputs;
                for (int p = 0; p < ports.size(); p++) {
                    final int i = p;
                    final double machineRate = extent * TestIngredients.quantityOf(ports.get(i));
                    double edges = 0;
                    for (final Edge edge : chart.graph()
                        .getEdges()) {
                        final boolean hit = input ? edge.targetNodeId.equals(node.id) && edge.targetInputIndex == i
                            : edge.sourceNodeId.equals(node.id) && edge.sourceOutputIndex == i;
                        if (hit) {
                            edges += s.edgeFlowsPerSecond()
                                .getOrDefault(edge.id, 0.0);
                        }
                    }
                    final double edgeRate = edges;
                    final double external = externals.getOrDefault(new PortRef(node.id, i, input), 0.0);
                    final double residual = machineRate - edgeRate - external;
                    assertTrue(
                        Math.abs(residual) <= 1e-6 * Math.max(1.0, machineRate),
                        () -> name + ": "
                            + node.machineName
                            + (input ? " input " : " output ")
                            + i
                            + " does not conserve - machine "
                            + machineRate
                            + ", edges "
                            + edgeRate
                            + ", external "
                            + external);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private static Solution solve(final LoadedChart chart) {
        final Result result = AutoBalancer.solve(chart.graph(), targetPins(chart));
        assertTrue(result.isSuccess(), () -> chart.name() + " solve failed: " + result.failure());
        return result.solution();
    }

    /** Converts the loader's target-rate pins (ingredient/s) into extent pins (crafts/s). */
    private static Map<UUID, Double> targetPins(final LoadedChart chart) {
        final Map<UUID, Double> pins = new HashMap<>();
        for (final Pin pin : chart.pins()) {
            if (!"target".equals(pin.kind())) continue;
            final Node node = chart.machine(pin.machineIndex());
            for (int i = 0; i < node.outputs.size(); i++) {
                if (TestIngredients.nameOf(node.outputs.get(i))
                    .equals(pin.ingredient())) {
                    pins.put(node.id, pin.value() / TestIngredients.quantityOf(node.outputs.get(i)));
                }
            }
        }
        return pins;
    }

    private static void assertAllMachinesRun(final LoadedChart chart, final Solution s) {
        for (final Node machine : chart.machines()) {
            assertTrue(
                s.extentsPerSecond()
                    .get(machine.id) > 1e-9,
                machine.machineName + " must run, extent="
                    + s.extentsPerSecond()
                        .get(machine.id));
        }
    }

    /** Summed rate of terminals on ports carrying the named ingredient. */
    private static double terminalRate(final LoadedChart chart, final List<External> terminals,
        final String ingredient) {
        double total = 0;
        for (final External t : terminals) {
            final Node node = chart.graph().nodes.get(
                t.port()
                    .nodeId());
            final var ports = t.port()
                .input() ? node.inputs : node.outputs;
            if (TestIngredients.nameOf(
                ports.get(
                    t.port()
                        .portIndex()))
                .equals(ingredient)) {
                total += t.ratePerSecond();
            }
        }
        return total;
    }
}
