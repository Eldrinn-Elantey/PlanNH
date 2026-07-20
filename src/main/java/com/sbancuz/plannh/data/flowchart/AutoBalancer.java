package com.sbancuz.plannh.data.flowchart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import com.sbancuz.plannh.data.MachineConfig;

/**
 * Four-stage lexicographic MILP balancer over PlanNH's machine-node graphs, in recipe-extent
 * form. The chart plus its pins is the entire input:
 * no pin means no balancing (an unpinned chart is just wiring - {@link #NO_PIN}); one or more
 * pins anchor the scale and everything else - including where external sources/sinks go - is
 * decided automatically, never demanded from the user.
 *
 * <p>
 * Model: one extent variable per machine (crafts/second), one flow variable per drawn edge
 * (ingredient/second). Per connected port: {@code sum(edge flows) + external = extent *
 * perCraftQty}. Unconnected ports are free terminals (supplying oil / collecting product is the
 * point of the chart, not a cost). Connected ports get an external whose gate is shared per
 * ingredient and direction: all input-side externals of one ingredient open one SOURCE gate, all
 * output-side externals one SINK gate (ingredients are identified structurally, as the
 * connected components of ports under the drawn edges). Ingredient-level gating keeps the
 * binary count ~2x intermediates instead of one per port.
 *
 * <p>
 * Stages, each optimized subject to the previous optima:
 * <ol>
 * <li>Minimize the number of open gates (sinks weigh 1024, sources 1025, so equal counts prefer
 * "discard the excess" over "supply an intermediate from outside").</li>
 * <li>Minimize total external quantity, weighted gate count capped at stage-1's optimum.</li>
 * <li>Minimize total internal flow, gates fixed to stage-2's support (derived from FLOWS, never
 * binary values - big-M x integrality tolerance leaks otherwise). Pins the free circulation of
 * fully-recycling loops. Supports tied at (count, quantity) are each tried and the least
 * internal flow wins, deterministically.</li>
 * </ol>
 * Stage 0 ("every machine runs") is implemented as scale-relative extent floors applied in a
 * second pass only, when the floor-free solution left machines idle - a fixed floor can exceed a
 * machine's natural rate and conjure phantom externals (the light_fuel trap).
 *
 * <p>
 * A zero-gate LP fast path (all externals closed, minimize internal flow) covers charts that
 * balance without externals in a single solve. Every accepted solution is validated
 * independently against the port-conservation rows; a solution that fails validation is rejected
 * rather than returned (never trust solver status codes).
 */
public final class AutoBalancer {

    private static final double DEFAULT_BIG_M = 1e6;
    private static final int MAX_M_GROWTHS = 3;
    private static final long STAGE_TIME_LIMIT_MILLIS = 15_000;
    /**
     * Budget for the exact stage-1 MILP once the LP deletion filter has already produced a
     * minimal support. Big-M count minimization gives branch-and-bound no usable root bound, so
     * on large charts ojAlgo cannot prove optimality in any budget - the filter's answer is used
     * and marked uncertified instead of burning the full stage budget.
     */
    private static final long MILP_CERT_BUDGET_MILLIS = 5_000;
    /** Flows below this count as zero when deriving gate support. */
    private static final double ZERO = 1e-6;
    /** A machine "runs" if its crafts/s exceeds this. */
    private static final double USE_EPS_DETECT = 1e-7;
    /** Fallback pass-2 floor (crafts/s) when no machine ran at all. */
    private static final double USE_EPS = 1e-4;
    /** Relative slack on the stage-2 quantity cap in stage 3. */
    private static final double QTY_EPS = 1e-7;
    /** Relative residual tolerance for independent solution validation. */
    private static final double VALIDATE_TOL = 1e-6;
    private static final double SNK_WEIGHT = 1024.0;
    private static final double SRC_WEIGHT = 1025.0;
    private static final int TICKS_PER_SECOND = 20;
    private static final int MAX_ENUMERATED_ALTERNATIVES = 10;
    private static final int MAX_TIED_SUPPORTS = 5;

    private AutoBalancer() {}

    /**
     * The failure reason when no machine is pinned. Not an error: an unpinned chart is just
     * wiring (the gtnh-flow contract) - the model is homogeneous and every solution scales
     * freely, so any numbers would be an invented anchor the user never asked for.
     */
    public static final String NO_PIN = "nothing is pinned - fix a machine count to ask for a balance";

    /** A port on a specific machine. {@code input} distinguishes the two port lists. */
    public record PortRef(UUID nodeId, int portIndex, boolean input) {}

    /** An external attached to a port, in ingredient units per second. */
    public record External(PortRef port, double ratePerSecond) {}

    public record Solution(Map<UUID, Double> extentsPerSecond, Map<UUID, Double> machineCounts,
        Map<UUID, Double> edgeFlowsPerSecond, List<External> gatedSources, List<External> gatedSinks,
        List<External> terminalInputs, List<External> terminalOutputs, int openGates, double externalQuantity,
        double totalInternalFlow, boolean floorsUsed, long wallMillis, List<String> notes) {}

    public record Result(Solution solution, String failure) {

        public boolean isSuccess() {
            return solution != null;
        }

        static Result ok(final Solution s) {
            return new Result(s, null);
        }

        static Result fail(final String reason) {
            return new Result(null, reason);
        }
    }

    /** Solves using the graph's fixed machine counts as the only pins. */
    public static Result solve(final Graph graph) {
        return solve(graph, Map.of());
    }

    /**
     * @param extraExtentPins additional extent pins (crafts/second) by node id, e.g. from a
     *                        target-rate pin: extent = targetRate / perCraftQty.
     */
    public static Result solve(final Graph graph, final Map<UUID, Double> extraExtentPins) {
        final long start = System.currentTimeMillis();
        final Ctx ctx = new Ctx(graph, extraExtentPins);
        if (ctx.machines.isEmpty()) {
            return Result.fail("empty graph");
        }
        if (!ctx.anyPin) {
            return Result.fail(NO_PIN);
        }

        // Pass 1: floor-free.
        Attempt attempt = runStages(ctx, null);
        if (attempt.failure != null) {
            return Result.fail(attempt.failure);
        }

        // Stage 0, pass 2: if machines idle, retry with scale-relative floors; keep the honest
        // pass-1 result if the floored model fails.
        boolean floorsUsed = false;
        final double[] floors = ctx.floorsFrom(attempt.extents);
        if (floors != null) {
            final Attempt floored = runStages(ctx, floors);
            if (floored.failure == null) {
                attempt = floored;
                floorsUsed = true;
            }
        }

        final String residualError = ctx.validate(attempt);
        if (residualError != null) {
            return Result.fail("solution failed independent validation: " + residualError);
        }

        return Result.ok(ctx.toSolution(attempt, floorsUsed, System.currentTimeMillis() - start));
    }

    /**
     * Enumerates gate supports tied with the optimum at raw gate COUNT (no-good cuts), so a
     * GUI can ask the user once instead of guessing. Each entry is the set of ports
     * whose externals carried flow; element 0 is the deterministic default {@link #solve} picks.
     * Weighted tiebreaks (sink vs source) do not disqualify an alternative.
     */
    public static List<Set<PortRef>> enumerateAlternatives(final Graph graph, final Map<UUID, Double> extraExtentPins) {
        final Ctx ctx = new Ctx(graph, extraExtentPins);
        if (ctx.machines.isEmpty() || !ctx.anyPin) return List.of();

        final Attempt base = runStages(ctx, null);
        if (base.failure != null) return List.of();
        final double[] floors = ctx.floorsFrom(base.extents);
        final Attempt attempt = floors == null ? base : orElse(runStages(ctx, floors), base);

        final List<Set<PortRef>> supports = new ArrayList<>();
        supports.add(ctx.flowPortRefs(attempt.externals));
        // Alternatives are only enumerable when the optimum was certified: on the filter path
        // there is no optimality frontier to walk, and each cut solve would burn the MILP budget.
        if (attempt.support.isEmpty() || !attempt.certified) return supports;

        final int optimumCount = attempt.support.size();
        final double[] activeFloors = floors != null && attempt != base ? floors : null;
        final Set<Set<Integer>> seen = new HashSet<>();
        seen.add(attempt.support);
        final List<Set<Integer>> cuts = new ArrayList<>(seen);
        while (supports.size() < MAX_ENUMERATED_ALTERNATIVES) {
            final StageSolve s1 = solveStage1(ctx, activeFloors, cuts, null);
            // A repeated flow support means the frontier is exhausted: the cuts forbid the seen
            // BINARY vectors, so the solver can only reproduce a seen support by opening junk
            // zero-flow gates - and a junk combination (k+1 gates) always costs more than any
            // genuine k-gate support would, so genuine alternatives are found first.
            if (s1 == null || s1.support.size() > optimumCount || !seen.add(s1.support)) break;
            supports.add(ctx.flowPortRefs(s1.externals));
            cuts.add(s1.support);
        }
        return supports;
    }

    private static Attempt orElse(final Attempt preferred, final Attempt fallback) {
        return preferred.failure == null ? preferred : fallback;
    }

    // ---------------------------------------------------------------------------------------
    // Stage pipeline
    // ---------------------------------------------------------------------------------------

    /** One full lexicographic run (stages 1-3) under the given floors (null = floor-free). */
    private static Attempt runStages(final Ctx ctx, final double[] floors) {
        // Fast path: if the chart balances with every gate closed, stages 1-2 are trivially
        // optimal (0 gates, 0 external quantity) and this LP IS stage 3.
        final StageSolve fast = solveStage3(ctx, floors, Set.of(), 0.0);
        if (fast != null) {
            return Attempt.of(fast, Set.of(), true, List.of());
        }

        // Stage 1 runs twice: an LP deletion filter that always produces a minimal support fast,
        // then a short exact-MILP slice that certifies (or beats) it when the chart is small
        // enough for branch-and-bound. Large charts keep the filter answer, uncertified.
        final Set<Integer> filterSupport = deletionFilter(ctx, floors);
        if (filterSupport == null) {
            return Attempt.failed("stage 1 (gate count) found no feasible support");
        }
        Set<Integer> s1Support = filterSupport;
        boolean certified = false;
        final StageSolve milp = solveStage1(ctx, floors, List.of(), ctx.weightedCost(filterSupport));
        if (milp != null && ctx.weightedCost(milp.support) <= ctx.weightedCost(filterSupport) + 0.5) {
            s1Support = milp.support;
            certified = milp.provenOptimal;
        }
        final List<String> notes = certified ? List.of()
            : List.of(
                "gate count " + s1Support.size() + " is minimal but not certified optimal (exact search over budget)");
        final double weightedCap = ctx.weightedCost(s1Support);

        final StageSolve s2 = certified ? solveStage2Cut(ctx, floors, weightedCap, List.of())
            : solveStage2Fixed(ctx, floors, s1Support);
        if (s2 == null) {
            return Attempt.failed("stage 2 (external quantity) found no solution within budget");
        }

        // Ties at (count, quantity) are resolved by stage 3's objective: re-run stage 3 for each
        // tied stage-2 support and keep the least internal flow. This is what makes loopGraph
        // deterministically pick the diluted-acid source over the sulfuric one. Only meaningful
        // on the certified path (the filter path has no optimality frontier to enumerate).
        final List<Set<Integer>> candidates = certified ? tiedSupports(ctx, floors, weightedCap, s2)
            : List.of(s2.support);
        StageSolve best = null;
        Set<Integer> bestSupport = null;
        for (final Set<Integer> support : candidates) {
            final StageSolve s3 = solveStage3(ctx, floors, support, s2.externalQuantity);
            if (s3 != null && (best == null || s3.internalFlow < best.internalFlow - ZERO)) {
                best = s3;
                bestSupport = support;
            }
        }
        if (best == null) {
            return Attempt.failed("stage 3 (internal flow) found no solution within budget");
        }
        return Attempt.of(best, bestSupport, certified, notes);
    }

    /** The stage-2 support plus any other supports tied at the same (gate count, quantity). */
    private static List<Set<Integer>> tiedSupports(final Ctx ctx, final double[] floors, final double weightedCap,
        final StageSolve s2) {
        final List<Set<Integer>> candidates = new ArrayList<>();
        candidates.add(s2.support);
        final List<Set<Integer>> cuts = new ArrayList<>();
        cuts.add(s2.support);
        while (candidates.size() < MAX_TIED_SUPPORTS) {
            final StageSolve next = solveStage2Cut(ctx, floors, weightedCap, cuts);
            if (next == null || next.externalQuantity > s2.externalQuantity * (1 + QTY_EPS) + ZERO
                || next.support.size() > s2.support.size()) {
                break;
            }
            candidates.add(next.support);
            cuts.add(next.support);
        }
        return candidates;
    }

    /**
     * Stage-1 LP fallback: a weighted-external-quantity LP opens a starting support, then a
     * deterministic deletion filter closes gates one by one (sources first, thinnest flow first)
     * while feasibility holds. The result is a MINIMAL support - no proper subset is feasible -
     * in a handful of fast LP solves and with no big-M anywhere.
     */
    private static Set<Integer> deletionFilter(final Ctx ctx, final double[] floors) {
        final StageSolve lp0 = solveExternalsLp(ctx, floors, null);
        if (lp0 == null) return null;
        Set<Integer> support = lp0.support;

        final double[] gateFlow = new double[ctx.gates.size()];
        for (int p = 0; p < lp0.externals.length; p++) {
            gateFlow[ctx.portGate[p]] += lp0.externals[p];
        }
        final List<Integer> order = new ArrayList<>(support);
        order.sort(
            Comparator.<Integer, Double>comparing(
                g -> -(ctx.gates.get(g)
                    .input() ? SRC_WEIGHT : SNK_WEIGHT))
                .thenComparing(g -> gateFlow[g])
                .thenComparing(g -> g));

        for (final int gate : order) {
            if (!support.contains(gate)) continue; // already dropped via a shrunken flow support
            final Set<Integer> trial = new HashSet<>(support);
            trial.remove(gate);
            final StageSolve solved = solveExternalsLp(ctx, floors, trial);
            if (solved != null) {
                support = solved.support;
            }
        }
        return support;
    }

    /**
     * LP over the gate structure with no binaries: externals outside {@code support} are closed
     * (null = all open), objective = weighted external quantity (source flow costs fractionally
     * more than sink flow, mirroring the stage-1 preference).
     */
    private static StageSolve solveExternalsLp(final Ctx ctx, final double[] floors, final Set<Integer> support) {
        final Handles h = ctx.buildModel(DEFAULT_BIG_M, false, floors);
        for (int p = 0; p < ctx.connectedPorts.size(); p++) {
            if (support != null && !support.contains(ctx.portGate[p])) {
                h.extVars[p].upper(0);
            } else {
                h.extVars[p].weight(
                    ctx.gates.get(ctx.portGate[p])
                        .input() ? SRC_WEIGHT : SNK_WEIGHT);
            }
        }
        final Optimisation.Result result = h.model.minimise();
        if (!isUsable(result)) return null;
        return StageSolve.from(ctx, h, result.getValue());
    }

    /** Stage 1 exact MILP: minimize weighted open-gate count under an upper-bound cut. */
    private static StageSolve solveStage1(final Ctx ctx, final double[] floors, final List<Set<Integer>> cuts,
        final Double upperBoundCost) {
        double bigM = DEFAULT_BIG_M;
        for (int growth = 0; growth <= MAX_M_GROWTHS; growth++) {
            final Handles h = ctx.buildModel(bigM, true, floors);
            h.model.options.time_abort = MILP_CERT_BUDGET_MILLIS;
            h.model.options.time_suffice = MILP_CERT_BUDGET_MILLIS;
            final Expression ub = upperBoundCost == null ? null : h.model.addExpression("ub_cut");
            for (int g = 0; g < ctx.gates.size(); g++) {
                final double weight = ctx.gates.get(g)
                    .input() ? SRC_WEIGHT : SNK_WEIGHT;
                h.gateVars[g].weight(weight);
                if (ub != null) {
                    ub.set(h.gateVars[g], weight);
                }
            }
            if (ub != null) {
                ub.upper(upperBoundCost + 0.5);
            }
            // Externals carry an epsilon cost so the solver anchors them at their minimal values:
            // costless externals can sit at arbitrary vertex values, which both presses the big-M
            // cap spuriously and pollutes the flow-derived gate support with junk flows. The
            // epsilon (<= 1e-9 * bigM = 1e-3 per external) can never flip a 1-unit gate decision.
            for (final Variable ext : h.extVars) {
                ext.weight(1e-9);
            }
            addNoGoodCuts(h, cuts);
            final Optimisation.Result result = h.model.minimise();
            if (!isUsable(result)) return null;
            if (pressesCap(h, bigM)) {
                bigM *= 10;
                continue;
            }
            // OPTIMAL or DISTINCT (unique optimum) both certify; FEASIBLE = timeout incumbent.
            return StageSolve.from(
                ctx,
                h,
                result.getValue(),
                result.getState()
                    .isOptimal());
        }
        return null;
    }

    /** Stage 2 on the uncertified path: fixed support, plain LP, minimize external quantity. */
    private static StageSolve solveStage2Fixed(final Ctx ctx, final double[] floors, final Set<Integer> support) {
        final Handles h = ctx.buildModel(DEFAULT_BIG_M, false, floors);
        for (int p = 0; p < ctx.connectedPorts.size(); p++) {
            if (support.contains(ctx.portGate[p])) {
                h.extVars[p].weight(1.0);
            } else {
                h.extVars[p].upper(0);
            }
        }
        final Optimisation.Result result = h.model.minimise();
        if (!isUsable(result)) return null;
        return StageSolve.from(ctx, h, result.getValue());
    }

    /** Stage 2: minimize total external quantity, weighted gate count capped at stage 1. */
    private static StageSolve solveStage2Cut(final Ctx ctx, final double[] floors, final double weightedCap,
        final List<Set<Integer>> cuts) {
        double bigM = DEFAULT_BIG_M;
        for (int growth = 0; growth <= MAX_M_GROWTHS; growth++) {
            final Handles h = ctx.buildModel(bigM, true, floors);
            final Expression cap = h.model.addExpression("count_cap");
            for (int g = 0; g < ctx.gates.size(); g++) {
                cap.set(
                    h.gateVars[g],
                    ctx.gates.get(g)
                        .input() ? SRC_WEIGHT : SNK_WEIGHT);
            }
            cap.upper(weightedCap + 0.5);
            for (final Variable ext : h.extVars) {
                ext.weight(1.0);
            }
            addNoGoodCuts(h, cuts);
            final Optimisation.Result result = h.model.minimise();
            if (!isUsable(result)) return null;
            if (pressesCap(h, bigM)) {
                bigM *= 10;
                continue;
            }
            return StageSolve.from(ctx, h, result.getValue());
        }
        return null;
    }

    /**
     * Stage 3: gates fixed to the given support (ports of open gates keep a free external under
     * the quantity cap, ports of closed gates are hard zero), minimize total internal flow. Also
     * the zero-gate fast path (empty support, zero cap).
     */
    private static StageSolve solveStage3(final Ctx ctx, final double[] floors, final Set<Integer> support,
        final double qtyCap) {
        final Handles h = ctx.buildModel(DEFAULT_BIG_M, false, floors);
        final Expression qty = support.isEmpty() ? null : h.model.addExpression("qty_cap");
        for (int p = 0; p < ctx.connectedPorts.size(); p++) {
            if (support.contains(ctx.portGate[p])) {
                qty.set(h.extVars[p], 1.0);
            } else {
                h.extVars[p].upper(0);
            }
        }
        if (qty != null) {
            qty.upper(qtyCap * (1 + QTY_EPS) + QTY_EPS);
        }
        for (final Variable f : h.flowVars) {
            f.weight(1.0);
        }
        final Optimisation.Result result = h.model.minimise();
        if (!isUsable(result)) return null;
        return StageSolve.from(ctx, h, result.getValue());
    }

    private static void addNoGoodCuts(final Handles h, final List<Set<Integer>> cuts) {
        int i = 0;
        for (final Set<Integer> cut : cuts) {
            final Expression e = h.model.addExpression("no_good_" + i++);
            for (int g = 0; g < h.gateVars.length; g++) {
                e.set(h.gateVars[g], cut.contains(g) ? -1.0 : 1.0);
            }
            e.lower(1.0 - cut.size());
        }
    }

    private static boolean isUsable(final Optimisation.Result result) {
        return result.getState()
            .isFeasible();
    }

    private static boolean pressesCap(final Handles h, final double bigM) {
        for (final Variable ext : h.extVars) {
            final Number v = ext.getValue();
            if (v != null && v.doubleValue() > 0.9 * bigM) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Model context
    // ---------------------------------------------------------------------------------------

    private record ConnectedPort(int machine, int portIndex, boolean input, double qtyPerCraft, List<Integer> edges) {}

    private record EdgeData(UUID id, int srcPort, int dstPort) {}

    /** One gate: an ingredient component in one direction, covering the listed ports. */
    private record Gate(boolean input, List<Integer> ports) {}

    private static final class MachineData {

        final Node node;
        final int durTicks;
        final double[] inQty;
        final double[] outQty;
        Double pinnedExtent; // crafts/s, null = free

        MachineData(final Node node) {
            this.node = node;
            final MachineConfig cfg = node.machineConfig;
            final var eff = cfg.computeEffect(node.properties, node.durationTicks);
            this.durTicks = Math.max(1, eff.durationTicks());
            final int tf = eff.throughputFactor();
            this.inQty = new double[node.inputs.size()];
            for (int i = 0; i < inQty.length; i++) {
                final var s = node.inputs.get(i);
                inQty[i] = Math.max(0, s.getAmount()) * s.getChance() * cfg.inputMultiplier(i) * tf;
            }
            this.outQty = new double[node.outputs.size()];
            for (int i = 0; i < outQty.length; i++) {
                final var s = node.outputs.get(i);
                outQty[i] = Math.max(0, s.getAmount()) * s.getChance() * cfg.outputMultiplier(i) * tf;
            }
        }

        double qty(final int portIndex, final boolean input) {
            return input ? inQty[portIndex] : outQty[portIndex];
        }
    }

    private static final class Ctx {

        final List<MachineData> machines = new ArrayList<>();
        final Map<UUID, Integer> machineIndex = new HashMap<>();
        final List<EdgeData> edges = new ArrayList<>();
        final List<ConnectedPort> connectedPorts = new ArrayList<>();
        final Map<Long, Integer> portLookup = new HashMap<>(); // (machine, port, input) -> index
        final List<Gate> gates = new ArrayList<>();
        int[] portGate; // connected port index -> gate index
        int[] portComponent; // connected port index -> ingredient component root
        boolean anyPin;
        final List<String> notes = new ArrayList<>();

        Ctx(final Graph graph, final Map<UUID, Double> extraExtentPins) {
            final List<Node> nodes = new ArrayList<>(graph.getNodes());
            nodes.sort(Comparator.comparing(n -> n.id));
            for (final Node node : nodes) {
                machineIndex.put(node.id, machines.size());
                machines.add(new MachineData(node));
            }

            final List<Edge> graphEdges = new ArrayList<>(graph.getEdges());
            graphEdges.sort(Comparator.comparing(e -> e.id));
            for (final Edge edge : graphEdges) {
                final Integer src = machineIndex.get(edge.sourceNodeId);
                final Integer dst = machineIndex.get(edge.targetNodeId);
                if (src == null || dst == null) continue;
                final int srcPort = port(src, edge.sourceOutputIndex, false);
                final int dstPort = port(dst, edge.targetInputIndex, true);
                final int e = edges.size();
                edges.add(new EdgeData(edge.id, srcPort, dstPort));
                connectedPorts.get(srcPort)
                    .edges()
                    .add(e);
                connectedPorts.get(dstPort)
                    .edges()
                    .add(e);
            }

            buildGates();
            applyPins(extraExtentPins);
        }

        /**
         * Ingredient identity is structural: connected components of ports under the drawn
         * edges. One SOURCE gate covers a component's input ports, one SINK gate its output
         * ports.
         */
        private void buildGates() {
            final int n = connectedPorts.size();
            final int[] root = new int[n];
            for (int i = 0; i < n; i++) {
                root[i] = i;
            }
            for (final EdgeData e : edges) {
                union(root, e.srcPort(), e.dstPort());
            }
            portGate = new int[n];
            portComponent = new int[n];
            final Map<Long, Integer> gateLookup = new HashMap<>();
            for (int p = 0; p < n; p++) {
                final boolean input = connectedPorts.get(p)
                    .input();
                portComponent[p] = find(root, p);
                final long key = ((long) portComponent[p] << 1) | (input ? 1 : 0);
                Integer gate = gateLookup.get(key);
                if (gate == null) {
                    gates.add(new Gate(input, new ArrayList<>()));
                    gate = gates.size() - 1;
                    gateLookup.put(key, gate);
                }
                gates.get(gate)
                    .ports()
                    .add(p);
                portGate[p] = gate;
            }
        }

        private static int find(final int[] root, final int i) {
            int r = i;
            while (root[r] != r) {
                r = root[r];
            }
            root[i] = r;
            return r;
        }

        private static void union(final int[] root, final int a, final int b) {
            root[find(root, a)] = find(root, b);
        }

        /**
         * Pins: explicit extents, then fixed machine counts. If nothing at all is pinned the
         * model is homogeneous (any solution scales), so anchor deterministically on the machine
         * with the largest configured count.
         */
        private void applyPins(final Map<UUID, Double> extraExtentPins) {
            for (final MachineData m : machines) {
                final Double extra = extraExtentPins.get(m.node.id);
                if (extra != null) {
                    m.pinnedExtent = extra;
                    anyPin = true;
                } else if (m.node.isMachineCountFixed()) {
                    m.pinnedExtent = extentOf(m, m.node.machineConfig.getMachineCount());
                    anyPin = true;
                }
            }
        }

        private static double extentOf(final MachineData m, final int machineCount) {
            return machineCount * (double) TICKS_PER_SECOND / m.durTicks;
        }

        private int port(final int machine, final int portIndex, final boolean input) {
            final long key = ((long) machine << 32) | ((long) portIndex << 1) | (input ? 1 : 0);
            return portLookup.computeIfAbsent(key, k -> {
                connectedPorts.add(
                    new ConnectedPort(
                        machine,
                        portIndex,
                        input,
                        machines.get(machine)
                            .qty(portIndex, input),
                        new ArrayList<>()));
                return connectedPorts.size() - 1;
            });
        }

        /**
         * Builds a fresh model (rebuilt per stage - mutating one model across stages is the
         * library-quirk trap class). Port rows are scaled by 1/max(1, qty) so coefficient
         * magnitudes stay near 1 across the 0.05-dust..20000-L quantity range.
         */
        Handles buildModel(final double bigM, final boolean withBinaries, final double[] floors) {
            final ExpressionsBasedModel model = new ExpressionsBasedModel();
            model.options.time_abort = STAGE_TIME_LIMIT_MILLIS;
            model.options.time_suffice = STAGE_TIME_LIMIT_MILLIS;
            model.options.mip_gap = 1e-6;

            final Variable[] extentVars = new Variable[machines.size()];
            for (int m = 0; m < machines.size(); m++) {
                final MachineData md = machines.get(m);
                final Variable v = model.addVariable("extent_" + m);
                if (md.pinnedExtent != null) {
                    v.lower(md.pinnedExtent)
                        .upper(md.pinnedExtent);
                } else {
                    v.lower(floors == null ? 0.0 : floors[m]);
                }
                extentVars[m] = v;
            }

            final Variable[] flowVars = new Variable[edges.size()];
            for (int e = 0; e < edges.size(); e++) {
                flowVars[e] = model.addVariable("flow_" + e)
                    .lower(0);
            }

            final Variable[] extVars = new Variable[connectedPorts.size()];
            for (int p = 0; p < connectedPorts.size(); p++) {
                extVars[p] = model.addVariable("ext_" + p)
                    .lower(0);
            }

            final Variable[] gateVars = new Variable[gates.size()];
            if (withBinaries) {
                for (int g = 0; g < gates.size(); g++) {
                    gateVars[g] = model.addVariable("y_" + g)
                        .binary();
                    final Expression link = model.addExpression("link_" + g);
                    for (final int p : gates.get(g)
                        .ports()) {
                        link.set(extVars[p], 1.0);
                    }
                    link.set(gateVars[g], -bigM);
                    link.upper(0);
                }
            }

            for (int p = 0; p < connectedPorts.size(); p++) {
                final ConnectedPort port = connectedPorts.get(p);
                final double scale = 1.0 / Math.max(1.0, port.qtyPerCraft());
                final Expression row = model.addExpression("port_" + p);
                for (final int e : port.edges()) {
                    row.set(flowVars[e], scale);
                }
                row.set(extVars[p], scale);
                row.set(extentVars[port.machine()], -port.qtyPerCraft() * scale);
                row.level(0);
            }

            return new Handles(model, extentVars, flowVars, extVars, gateVars);
        }

        /**
         * Stage-0 pass-2 floors: null when every unpinned machine already runs; otherwise a
         * uniform extent floor 1000x below the smallest observed running rate, so the floor can
         * never bind above a plausible natural rate.
         */
        double[] floorsFrom(final double[] extents) {
            boolean anyIdle = false;
            double minRunning = Double.MAX_VALUE;
            for (int m = 0; m < machines.size(); m++) {
                if (machines.get(m).pinnedExtent != null) continue;
                if (extents[m] <= USE_EPS_DETECT) {
                    anyIdle = true;
                } else {
                    minRunning = Math.min(minRunning, extents[m]);
                }
            }
            if (!anyIdle) return null;
            final double floor = (minRunning == Double.MAX_VALUE ? USE_EPS : minRunning) * 1e-3;
            final double[] floors = new double[machines.size()];
            Arrays.fill(floors, floor);
            return floors;
        }

        /** Weighted stage-1 cost of a gate support (sources 1025, sinks 1024). */
        double weightedCost(final Set<Integer> support) {
            double cost = 0;
            for (final int g : support) {
                cost += gates.get(g)
                    .input() ? SRC_WEIGHT : SNK_WEIGHT;
            }
            return cost;
        }

        /** The gate support (gate indices) carried by the given per-port external flows. */
        Set<Integer> gateSupport(final double[] externals) {
            final double[] gateFlow = new double[gates.size()];
            for (int p = 0; p < externals.length; p++) {
                gateFlow[portGate[p]] += externals[p];
            }
            final Set<Integer> support = new HashSet<>();
            for (int g = 0; g < gateFlow.length; g++) {
                if (gateFlow[g] > ZERO) support.add(g);
            }
            return support;
        }

        /** Ports whose externals carried flow, as public refs (for enumeration display). */
        Set<PortRef> flowPortRefs(final double[] externals) {
            final Set<PortRef> refs = new HashSet<>();
            for (int p = 0; p < externals.length; p++) {
                if (externals[p] <= ZERO) continue;
                final ConnectedPort port = connectedPorts.get(p);
                refs.add(new PortRef(machines.get(port.machine()).node.id, port.portIndex(), port.input()));
            }
            return refs;
        }

        /** Independent conservation check: every port row must hold to VALIDATE_TOL. */
        String validate(final Attempt attempt) {
            for (int p = 0; p < connectedPorts.size(); p++) {
                final ConnectedPort port = connectedPorts.get(p);
                double flows = 0;
                for (final int e : port.edges()) {
                    flows += attempt.flows[e];
                }
                final double rhs = attempt.extents[port.machine()] * port.qtyPerCraft();
                final double residual = flows + attempt.externals[p] - rhs;
                if (Math.abs(residual) > VALIDATE_TOL * Math.max(1.0, Math.abs(rhs))) {
                    final MachineData m = machines.get(port.machine());
                    return "port " + (port.input() ? "in" : "out")
                        + "["
                        + port.portIndex()
                        + "] of '"
                        + m.node.machineName
                        + "' residual "
                        + residual;
                }
            }
            return null;
        }

        Solution toSolution(final Attempt attempt, final boolean floorsUsed, final long wallMillis) {
            final Map<UUID, Double> extents = new LinkedHashMap<>();
            final Map<UUID, Double> counts = new LinkedHashMap<>();
            for (int m = 0; m < machines.size(); m++) {
                final MachineData md = machines.get(m);
                extents.put(md.node.id, attempt.extents[m]);
                counts.put(md.node.id, attempt.extents[m] * md.durTicks / TICKS_PER_SECOND);
            }

            final Map<UUID, Double> flows = new LinkedHashMap<>();
            for (int e = 0; e < edges.size(); e++) {
                flows.put(
                    edges.get(e)
                        .id(),
                    attempt.flows[e]);
            }

            final List<External> sources = new ArrayList<>();
            final List<External> sinks = new ArrayList<>();
            double externalQuantity = 0;
            for (int p = 0; p < connectedPorts.size(); p++) {
                final double ext = attempt.externals[p];
                if (ext <= ZERO) continue;
                final ConnectedPort port = connectedPorts.get(p);
                final External external = new External(
                    new PortRef(machines.get(port.machine()).node.id, port.portIndex(), port.input()),
                    ext);
                (port.input() ? sources : sinks).add(external);
                externalQuantity += ext;
            }

            final List<External> terminalIn = new ArrayList<>();
            final List<External> terminalOut = new ArrayList<>();
            for (int m = 0; m < machines.size(); m++) {
                final MachineData md = machines.get(m);
                collectTerminals(m, md, md.inQty.length, true, attempt, terminalIn);
                collectTerminals(m, md, md.outQty.length, false, attempt, terminalOut);
            }

            double internalFlow = 0;
            for (final double f : attempt.flows) {
                internalFlow += f;
            }

            final List<String> allNotes = new ArrayList<>(notes);
            allNotes.addAll(attempt.notes);
            allNotes.addAll(wiringDiagnostics(attempt, terminalIn));

            return new Solution(
                extents,
                counts,
                flows,
                sources,
                sinks,
                terminalIn,
                terminalOut,
                attempt.support.size(),
                externalQuantity,
                internalFlow,
                floorsUsed,
                wallMillis,
                List.copyOf(allNotes));
        }

        /**
         * Flags externals that look like missing edges rather than intent. The free-terminal
         * rule means an unwired input silently becomes "supplied from outside" - correct for
         * oil, wrong when the same ingredient is right there on the chart. Two smells: (1) a
         * terminal input whose ingredient the chart also produces (through drawn edges or
         * another terminal); (2) a gated source whose ingredient is produced in a DIFFERENT
         * edge-connected component (two unlinked islands of the same fluid). Same-component
         * gated sources are the normal deficit case (e.g. the loopGraph source) and stay quiet.
         */
        private List<String> wiringDiagnostics(final Attempt attempt, final List<External> terminalIn) {
            final List<String> result = new ArrayList<>();
            for (final External in : terminalIn) {
                final String match = findProduction(in, -1);
                if (match != null) {
                    result.add(
                        "'" + machineNameOf(in)
                            + "' imports "
                            + portNameOf(in)
                            + " externally, but the chart also produces it at '"
                            + match
                            + "' - missing an edge?");
                }
            }
            for (int p = 0; p < connectedPorts.size(); p++) {
                final ConnectedPort port = connectedPorts.get(p);
                if (!port.input() || attempt.externals[p] <= ZERO) continue;
                final External src = new External(
                    new PortRef(machines.get(port.machine()).node.id, port.portIndex(), port.input()),
                    attempt.externals[p]);
                final String match = findProduction(src, portComponent[p]);
                if (match != null) {
                    result.add(
                        "'" + machineNameOf(src)
                            + "' sources "
                            + portNameOf(src)
                            + " externally, but an unlinked part of the chart produces it at '"
                            + match
                            + "' - missing an edge?");
                }
            }
            return result;
        }

        /**
         * A machine name producing the same ingredient as {@code consumer}'s port, or null.
         * Checks terminal outputs and connected output ports; {@code excludeComponent} skips the
         * consumer's own component (-1 checks everything).
         */
        private String findProduction(final External consumer, final int excludeComponent) {
            final Port<?> want = portOf(consumer);
            for (int p = 0; p < connectedPorts.size(); p++) {
                final ConnectedPort port = connectedPorts.get(p);
                if (port.input() || portComponent[p] == excludeComponent) continue;
                final MachineData m = machines.get(port.machine());
                if (want.canConnect(m.node.outputs.get(port.portIndex()))) {
                    return m.node.machineName;
                }
            }
            for (int m = 0; m < machines.size(); m++) {
                final MachineData md = machines.get(m);
                for (int i = 0; i < md.outQty.length; i++) {
                    if (md.outQty[i] <= 0) continue;
                    final long key = ((long) m << 32) | ((long) i << 1);
                    if (portLookup.containsKey(key)) continue; // connected, handled above
                    if (want.canConnect(md.node.outputs.get(i))) {
                        return md.node.machineName;
                    }
                }
            }
            return null;
        }

        private Port<?> portOf(final External e) {
            final Node node = machines.get(
                machineIndex.get(
                    e.port()
                        .nodeId())).node;
            return (e.port()
                .input() ? node.inputs : node.outputs).get(
                    e.port()
                        .portIndex());
        }

        private String machineNameOf(final External e) {
            return machines.get(
                machineIndex.get(
                    e.port()
                        .nodeId())).node.machineName;
        }

        private String portNameOf(final External e) {
            return (e.port()
                .input() ? "input " : "output ") + e.port()
                    .portIndex();
        }

        private void collectTerminals(final int m, final MachineData md, final int portCount, final boolean input,
            final Attempt attempt, final List<External> out) {
            for (int i = 0; i < portCount; i++) {
                final double qty = md.qty(i, input);
                if (qty <= 0) continue;
                final long key = ((long) m << 32) | ((long) i << 1) | (input ? 1 : 0);
                if (portLookup.containsKey(key)) continue; // connected, not a terminal
                final double rate = attempt.extents[m] * qty;
                if (rate <= ZERO) continue;
                out.add(new External(new PortRef(md.node.id, i, input), rate));
            }
        }
    }

    private record Handles(ExpressionsBasedModel model, Variable[] extentVars, Variable[] flowVars, Variable[] extVars,
        Variable[] gateVars) {}

    /** Raw variable values of one stage's solve, with the flow-derived gate support. */
    private static final class StageSolve {

        final double[] extents;
        final double[] flows;
        final double[] externals;
        final Set<Integer> support;
        final double externalQuantity;
        final double internalFlow;
        final boolean provenOptimal;

        private StageSolve(final Ctx ctx, final double[] extents, final double[] flows, final double[] externals,
            final boolean provenOptimal) {
            this.extents = extents;
            this.flows = flows;
            this.externals = externals;
            this.provenOptimal = provenOptimal;
            this.support = ctx.gateSupport(externals);
            double qty = 0;
            for (final double e : externals) {
                qty += e;
            }
            this.externalQuantity = qty;
            double flowSum = 0;
            for (final double f : flows) {
                flowSum += f;
            }
            this.internalFlow = flowSum;
        }

        static StageSolve from(final Ctx ctx, final Handles h, final double objective) {
            return from(ctx, h, objective, true);
        }

        static StageSolve from(final Ctx ctx, final Handles h, final double objective, final boolean provenOptimal) {
            return new StageSolve(
                ctx,
                values(h.extentVars()),
                values(h.flowVars()),
                values(h.extVars()),
                provenOptimal);
        }

        private static double[] values(final Variable[] vars) {
            final double[] out = new double[vars.length];
            for (int i = 0; i < vars.length; i++) {
                final Number v = vars[i].getValue();
                out[i] = v == null ? 0 : Math.max(0, v.doubleValue());
            }
            return out;
        }
    }

    /** A completed stage-1..3 run: the stage-3 point plus its gate support. */
    private static final class Attempt {

        final double[] extents;
        final double[] flows;
        final double[] externals;
        final Set<Integer> support;
        final boolean certified;
        final List<String> notes;
        final String failure;

        private Attempt(final StageSolve s3, final Set<Integer> support, final boolean certified,
            final List<String> notes, final String failure) {
            this.extents = s3 == null ? null : s3.extents;
            this.flows = s3 == null ? null : s3.flows;
            this.externals = s3 == null ? null : s3.externals;
            this.support = support;
            this.certified = certified;
            this.notes = notes;
            this.failure = failure;
        }

        static Attempt of(final StageSolve s3, final Set<Integer> support, final boolean certified,
            final List<String> notes) {
            return new Attempt(s3, support, certified, notes, null);
        }

        static Attempt failed(final String reason) {
            return new Attempt(null, Set.of(), false, List.of(), reason);
        }
    }
}
