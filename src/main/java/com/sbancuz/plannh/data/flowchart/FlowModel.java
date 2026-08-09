package com.sbancuz.plannh.data.flowchart;

import static com.sbancuz.plannh.data.flowchart.AutoBalancer.MIN_MODEL_MILLIS;
import static com.sbancuz.plannh.data.flowchart.AutoBalancer.MISSING_EDGE;
import static com.sbancuz.plannh.data.flowchart.AutoBalancer.Severity.INFO;
import static com.sbancuz.plannh.data.flowchart.AutoBalancer.Severity.WARN;
import static com.sbancuz.plannh.data.flowchart.AutoBalancer.TIE_REL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Variable;
import org.ojalgo.optimisation.integer.IntegerStrategy;
import org.ojalgo.type.context.NumberContext;

import com.sbancuz.plannh.Config;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Alternative;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Attempt;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Budget;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.ChoiceKey;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.External;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.PortRef;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Rank;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Solution;

/**
 * The chart as the solver sees it: machines in recipe-extent form, ports interned per machine and
 * direction, gates over the connected components of ports, and the conservation rows tying them
 * together. Independent of which answer gets preferred - every variant of the solver builds this
 * same model and differs only in what it optimizes over it.
 */
final class FlowModel {

    /** Flows below this count as zero when deriving gate support. Relative - see zeroTolerance. */
    private static final double ZERO = 1e-6;
    /**
     * Solver dust: what a variable holds when the solver meant zero. Orders of magnitude below
     * {@link #ZERO}, because the two answer different questions - {@link FlowModel#gateSupport} asks "is
     * this worth reporting", {@link FlowModel#carryingGates} asks "would closing this gate break the
     * solution", and a gate carrying 1e-9 of the chart's scale really would break it.
     */
    private static final double DUST = 1e-11;
    /**
     * Residual tolerance for the independent conservation check, scaled by each row's largest
     * coefficient so it means the same thing on a row measured in items and one in millibuckets.
     */
    private static final double VALIDATE_TOL = 1e-6;
    private static final int TICKS_PER_SECOND = 20;
    /** Fallback pass-2 floor (crafts/s) when no machine ran at all. */
    private static final double USE_EPS = 1e-4;
    /** A machine "runs" if its crafts/s exceeds this. */
    private static final double USE_EPS_DETECT = 1e-7;
    /** Ceiling on any ONE model's time, scaled by {@link AutoBalancer#effort}. */
    private static final long STAGE_TIME_LIMIT_MILLIS = 15_000;

    final List<MachineData> machines = new ArrayList<>();
    final Map<UUID, Integer> machineIndex = new HashMap<>();
    final List<EdgeData> edges = new ArrayList<>();
    final List<ConnectedPort> connectedPorts = new ArrayList<>();
    final Map<Long, Integer> portLookup = new HashMap<>(); // (machine, port, input) -> index
    final List<Gate> gates = new ArrayList<>();
    int[] portGate; // connected port index -> gate index
    int[] portComponent; // connected port index -> ingredient component root
    boolean anyPin;
    Budget budget;
    /**
     * Why the last model this solve rejected produced nothing usable. Recorded where the rejection
     * happens, because by the time a stage reports failure the result that would explain it is gone -
     * and ojAlgo spells an infeasible model and a search that gave up the same way.
     */
    String rejection = "found no solution";
    /** Fixed once the gates are known, and read per gate while building every MILP. */
    private double[] gateWeights;
    final List<String> notes = new ArrayList<>();

    FlowModel(final Graph graph, final Map<UUID, Double> extraExtentPins, final Budget budget) {
        this.budget = budget;
        // Graph hands nodes and edges back in id order, so nothing is sorted here.
        for (final Node node : graph.getNodes()) {
            machineIndex.put(node.id, machines.size());
            machines.add(new MachineData(node));
        }

        for (final Edge edge : graph.getEdges()) {
            final Integer src = machineIndex.get(edge.sourceNodeId);
            final Integer dst = machineIndex.get(edge.targetNodeId);
            if (src == null || dst == null) continue;
            // Edges keep the port indices they were saved with, while port lists are rebuilt
            // from the live recipe handler and can shrink (a settings change, or a recipe that
            // lost an output between modpack versions). Drop the dangling edge rather than
            // indexing past the node's ports.
            if (!machines.get(src)
                .hasPort(edge.sourceOutputIndex, false)
                || !machines.get(dst)
                    .hasPort(edge.targetInputIndex, true)) {
                continue;
            }
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
        gateWeights = Preference.gateWeights(this);
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
     * Pins, strongest first: explicit extents, target output rates, fixed machine counts. A
     * target beats a fixed count on the same machine because it names the thing the user
     * actually wants - the rate - where the count is only a means to it.
     */
    private void applyPins(final Map<UUID, Double> extraExtentPins) {
        for (final MachineData m : machines) {
            final Double extra = extraExtentPins.get(m.node.id);
            final Double target = targetExtent(m);
            if (extra != null) {
                m.pinnedExtent = extra;
                m.pinKind = "extent pin";
                anyPin = true;
            } else if (target != null) {
                m.pinnedExtent = target;
                m.pinKind = "target rate";
                anyPin = true;
                noteOvershotTargets(m, target);
            } else if (m.node.isMachineCountFixed()) {
                m.pinnedExtent = extentOf(m, m.node.machineConfig.getMachineCount());
                m.pinKind = "fixed machine count";
                anyPin = true;
            }
        }
    }

    /**
     * The extent implied by a node's target output rates: rate divided by per-craft quantity,
     * largest target winning (parallel outputs share one extent, so only the tightest can be
     * hit exactly). Targets on ports that no longer exist or produce nothing are skipped the
     * same way stale edges are.
     */
    private static Double targetExtent(final MachineData m) {
        Double extent = null;
        for (final Map.Entry<Integer, Double> t : m.node.targetOutputRates.entrySet()) {
            if (t.getValue() == null || t.getValue() <= 0) continue;
            if (!m.hasPort(t.getKey(), false)) continue;
            final double qty = m.qty(t.getKey(), false);
            if (qty <= 0) continue;
            final double e = t.getValue() / qty;
            if (extent == null || e > extent) extent = e;
        }
        return extent;
    }

    /**
     * Says so when a node carries targets it cannot all hit. Parallel outputs share one extent, so
     * the largest target sets it and the rest are overproduced - correct, but silent unless the
     * chart names the target it is actually holding to.
     */
    private void noteOvershotTargets(final MachineData m, final double chosenExtent) {
        for (final Map.Entry<Integer, Double> t : m.node.targetOutputRates.entrySet()) {
            if (t.getValue() == null || t.getValue() <= 0) continue;
            if (!m.hasPort(t.getKey(), false)) continue;
            final double qty = m.qty(t.getKey(), false);
            if (qty <= 0) continue;
            final double actual = chosenExtent * qty;
            if (actual > t.getValue() * (1 + TIE_REL)) {
                notes.add(
                    WARN.tag() + "'"
                        + m.node.machineName
                        + "' overshoots its "
                        + m.node.outputs.get(t.getKey())
                            .getDisplayName()
                        + " target: "
                        + actual
                        + "/s produced for a target of "
                        + t.getValue()
                        + "/s, because another output on the same machine asks for more");
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
        // Never longer than what the whole solve has left: a per-model limit alone bounds one
        // stage, and a solve is a dozen of them. The floor matters as much as the ceiling - a
        // model handed a millisecond aborts into whatever point it happens to be holding, which
        // is worse than not solving it at all. The optional stages check the budget and skip
        // themselves; the ones that are left get a workable slice even if that overshoots.
        final long limit = Math
            .max(MIN_MODEL_MILLIS, Math.min(AutoBalancer.effort(STAGE_TIME_LIMIT_MILLIS), budget.remaining()));
        model.options.time_abort = limit;
        model.options.time_suffice = limit;
        // One branch-and-bound worker, not one per core: the parallel search shares a node counter
        // between threads, so which nodes are processed before a node budget runs out depends on how
        // the threads interleaved, and the same chart answers differently on two machines. A single
        // worker makes the node order a property of the model, and costs nothing measurable - these
        // MILPs close in tens of nodes, where the time goes on building them.
        model.options.integer(
            IntegerStrategy.DEFAULT.withGapTolerance(NumberContext.of(12, 8))
                .withParallelism(() -> 1));
        // The conservation rows are what the whole answer rests on, so the solver is held to a
        // tighter feasibility context than its default: the independent validation downstream
        // rejects residuals this would otherwise leave behind.
        model.options.feasibility = NumberContext.of(12, 10);

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
     * Stage-0 pass-2 floors: null when every unpinned machine already runs; otherwise an
     * extent floor 1000x below pass 1's smallest running rate, so it can never bind above a
     * rate this chart already achieves. Machines in a component with no pin are exempt:
     * nothing anchors their scale, so forcing them to run would invent quantities the user
     * never asked for.
     */
    double[] stageZeroFloors(final double[] extents) {
        final int[] root = new int[machines.size()];
        for (int m = 0; m < root.length; m++) {
            root[m] = m;
        }
        for (final EdgeData e : edges) {
            union(
                root,
                connectedPorts.get(e.srcPort())
                    .machine(),
                connectedPorts.get(e.dstPort())
                    .machine());
        }

        final Set<Integer> pinnedComponents = new HashSet<>();
        for (int m = 0; m < machines.size(); m++) {
            if (machines.get(m).pinnedExtent != null) pinnedComponents.add(find(root, m));
        }
        if (pinnedComponents.isEmpty()) return null;

        boolean anyIdle = false;
        double minRunning = Double.MAX_VALUE;
        for (int m = 0; m < machines.size(); m++) {
            if (machines.get(m).pinnedExtent != null || !pinnedComponents.contains(find(root, m))) continue;
            if (extents[m] <= USE_EPS_DETECT) anyIdle = true;
            else minRunning = Math.min(minRunning, extents[m]);
        }
        if (!anyIdle) return null;

        final double floor = (minRunning == Double.MAX_VALUE ? USE_EPS : minRunning) * 1e-3;
        final double[] floors = new double[machines.size()];
        for (int m = 0; m < machines.size(); m++) {
            if (machines.get(m).pinnedExtent == null && pinnedComponents.contains(find(root, m))) {
                floors[m] = floor;
            }
        }
        return floors;
    }

    /** Machines the solution leaves at zero, ignoring any the user pinned there. */
    int idleCount(final double[] extents) {
        int idle = 0;
        for (int m = 0; m < machines.size(); m++) {
            if (machines.get(m).pinnedExtent == null && extents[m] <= USE_EPS_DETECT) idle++;
        }
        return idle;
    }

    /** The lexicographic gate cost every combinatorial preference contributes to. */
    double gateWeight(final int gate) {
        return gateWeights[gate];
    }

    /**
     * Objective weight for one connected port's external: its rate in crafts of the machine that
     * carries it, not in the ingredient's own units. Summing raw rates adds items/s to
     * millibuckets/s, so "least excess" would mean "least excess that happens to be counted in
     * small units" and a chart would burn raw input to void an item byproduct instead of a fluid
     * one. NOT the {@code 1/max(1, qty)} the conservation rows use: that clamp conditions the
     * constraint matrix, but in an objective it stops normalizing every sub-unity port and weights
     * a 0.05-per-craft chanced dust twenty times too heavily.
     */
    double externalWeight(final int port) {
        final double qty = connectedPorts.get(port)
            .qtyPerCraft();
        // A zero-quantity port's row already pins its external to zero, so the weight is
        // arbitrary; 1.0 keeps it out of the way.
        return qty > 0 ? 1.0 / qty : 1.0;
    }

    /** A port's stable name, independent of every index that is rebuilt per solve. */
    PortRef refOf(final int port) {
        final ConnectedPort p = connectedPorts.get(port);
        return new PortRef(machines.get(p.machine()).node.id, p.portIndex(), p.input());
    }

    /** The gate's canonical anchor: its smallest port under {@link PortRef#ORDER}. */
    PortRef anchorOf(final int gate) {
        PortRef best = null;
        for (final int p : gates.get(gate)
            .ports()) {
            final PortRef ref = refOf(p);
            if (best == null || PortRef.ORDER.compare(ref, best) < 0) best = ref;
        }
        return best;
    }

    /** Stage 2's objective value for a set of externals: excess measured in crafts. */
    double normalizedQuantity(final double[] externals) {
        double qty = 0;
        for (int p = 0; p < externals.length; p++) {
            qty += externals[p] * externalWeight(p);
        }
        return qty;
    }

    /**
     * One answer packaged for the UI, carrying only the flows at the gate it opens - the part
     * that distinguishes it from every other answer to the same question.
     */
    Alternative asAlternative(final double[] externals, final Set<Integer> support, final int replaced,
        final int opened, final Rank rank) {
        final double tol = DUST * scaleOf(externals);
        final List<External> flows = new ArrayList<>();
        for (final int p : gates.get(opened)
            .ports()) {
            if (externals[p] <= tol) continue;
            flows.add(new External(refOf(p), externals[p]));
        }
        return new Alternative(keyOf(support), anchorOf(replaced), anchorOf(opened), List.copyOf(flows), rank);
    }

    ChoiceKey keyOf(final Set<Integer> support) {
        final List<PortRef> anchors = new ArrayList<>(support.size());
        for (final int g : support) {
            anchors.add(anchorOf(g));
        }
        return ChoiceKey.of(anchors);
    }

    /**
     * The gates a stored key names on this chart, or null when it no longer names a support.
     * An exact port hit is the normal case; when the anchor has moved the ingredient is matched
     * instead, by the same {@code canConnect} the wiring diagnostics use, and only a unique
     * match counts. A key that cannot be resolved is dropped rather than approximated - the
     * solver's own answer is always a safe thing to fall back to.
     */
    Set<Integer> resolve(final ChoiceKey choice) {
        final Set<Integer> support = new HashSet<>();
        for (final PortRef anchor : choice.gateAnchors()) {
            final Integer machine = machineIndex.get(anchor.nodeId());
            if (machine == null) return null;
            final long key = ((long) machine << 32) | ((long) anchor.portIndex() << 1) | (anchor.input() ? 1 : 0);
            final Integer port = portLookup.get(key);
            // Not a ternary: mixing int and Integer in one makes javac unbox both arms, so a
            // null from the ingredient fallback becomes an NPE instead of "no match".
            Integer gate = null;
            if (port != null) {
                gate = portGate[port];
            } else {
                gate = resolveByIngredient(anchor);
            }
            if (gate == null) return null;
            // Two anchors landing on one gate means the key no longer describes the support it
            // was written for: an edge has merged two ingredient components since it was stored.
            if (!support.add(gate)) return null;
        }
        return support;
    }

    private Integer resolveByIngredient(final PortRef anchor) {
        final Node node = machines.get(machineIndex.get(anchor.nodeId())).node;
        final List<Port<?>> ports = anchor.input() ? node.inputs : node.outputs;
        if (anchor.portIndex() < 0 || anchor.portIndex() >= ports.size()) return null;
        final Port<?> want = ports.get(anchor.portIndex());
        Integer found = null;
        for (int p = 0; p < connectedPorts.size(); p++) {
            final ConnectedPort cp = connectedPorts.get(p);
            if (cp.input() != anchor.input()) continue;
            final Node other = machines.get(cp.machine()).node;
            final Port<?> candidate = (cp.input() ? other.inputs : other.outputs).get(cp.portIndex());
            if (!want.canConnect(candidate)) continue;
            if (found != null && found != portGate[p]) return null; // ambiguous, so no answer
            found = portGate[p];
        }
        return found;
    }

    /** Weighted cost of a gate support under the combinatorial preferences. */
    double weightedCost(final Set<Integer> support) {
        return Preference.costOf(support, this::gateWeight);
    }

    /**
     * What counts as no flow, for one solution. The model is homogeneous - scaling every pin
     * scales every flow - so "negligible" only means anything next to the other flows in the
     * same solution. An absolute floor reads a small chart's real externals as noise, drops
     * them from the support, and leaves stage 2 pinning those ports to zero: infeasible, and
     * reported as a budget timeout.
     */
    private static double zeroTolerance(final double[] values) {
        return ZERO * scaleOf(values);
    }

    /** The largest magnitude in a vector, never zero, so it can be divided into safely. */
    private static double scaleOf(final double[] values) {
        double max = 0;
        for (final double v : values) {
            max = Math.max(max, Math.abs(v));
        }
        return Math.max(max, Double.MIN_NORMAL);
    }

    /**
     * The largest rate this solution moves anywhere: every edge flow, every external, and every
     * port's own {@code extent x perCraftQty} throughput, so that terminals - which are not
     * variables - are covered too. The model is homogeneous, so this is the only yardstick
     * against which "negligible" or "the same optimum" can mean anything.
     */
    double solutionScale(final double[] extents, final double[] flows, final double[] externals) {
        double max = Math.max(scaleOf(flows), scaleOf(externals));
        for (int m = 0; m < machines.size(); m++) {
            final MachineData md = machines.get(m);
            for (final double q : md.inQty) {
                max = Math.max(max, extents[m] * q);
            }
            for (final double q : md.outQty) {
                max = Math.max(max, extents[m] * q);
            }
        }
        return Math.max(max, Double.MIN_NORMAL);
    }

    /**
     * How close two solves of the same objective have to be to count as the same optimum.
     * Taking the objective's own magnitude as well as the solution scale keeps this meaningful
     * for a sum over many edges, and handles an optimum of exactly zero without a special case.
     */
    double tieTolerance(final double objective, final StageSolve s) {
        return TIE_REL * Math.max(Math.abs(objective), solutionScale(s.extents, s.flows, s.externals));
    }

    /**
     * Every gate the given solution puts any flow through, however little - the set that must stay
     * open for it to remain feasible, where {@link #gateSupport} is the reporting view that ignores
     * negligible flows. The floor is {@link #DUST} and not {@link #ZERO} because a closed port
     * carries solver noise, and counting that as a gate inflates the stage-2 cap by a whole gate's
     * weight.
     */
    Set<Integer> carryingGates(final double[] externals, final Set<Integer> fallback) {
        if (externals == null) return fallback;
        final Set<Integer> carrying = new HashSet<>(fallback);
        final double dust = DUST * scaleOf(externals);
        for (int p = 0; p < externals.length; p++) {
            if (externals[p] > dust) carrying.add(portGate[p]);
        }
        return carrying;
    }

    /** The gate support (gate indices) carried by the given per-port external flows. */
    Set<Integer> gateSupport(final double[] externals) {
        final double[] gateFlow = new double[gates.size()];
        for (int p = 0; p < externals.length; p++) {
            gateFlow[portGate[p]] += externals[p];
        }
        final double tol = zeroTolerance(gateFlow);
        final Set<Integer> support = new HashSet<>();
        for (int g = 0; g < gateFlow.length; g++) {
            if (gateFlow[g] > tol) support.add(g);
        }
        return support;
    }

    /**
     * Independent conservation check: every port row must hold to VALIDATE_TOL, and no variable
     * may have broken its own lower bound. Extents, flows and externals are all rates of real
     * material; a negative one is a solver failure wearing a feasible status code.
     */
    String validate(final Attempt attempt) {
        return validate(attempt.extents, attempt.flows, attempt.externals);
    }

    String validate(final double[] extents, final double[] flowValues, final double[] externals) {
        final double floor = -DUST * solutionScale(extents, flowValues, externals);
        for (final double extent : extents) {
            if (extent < floor) return "negative extent " + extent;
        }
        for (final double flow : flowValues) {
            if (flow < floor) return "negative edge flow " + flow;
        }
        for (final double ext : externals) {
            if (ext < floor) return "negative external " + ext;
        }
        for (int p = 0; p < connectedPorts.size(); p++) {
            final ConnectedPort port = connectedPorts.get(p);
            double flows = 0;
            for (final int e : port.edges()) {
                flows += flowValues[e];
            }
            final double rhs = extents[port.machine()] * port.qtyPerCraft();
            final double residual = flows + externals[p] - rhs;
            // Scaled by the row's largest coefficient, which is what makes the tolerance mean
            // the same thing on a row measured in single items and one measured in thousands of
            // millibuckets. Scaling by the right-hand side instead makes the check arbitrarily
            // strict on any port whose own throughput is small.
            final double scale = Math.max(1.0, port.qtyPerCraft());
            if (Math.abs(residual) / scale > VALIDATE_TOL) {
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
        // Solver dust, judged against this solution's own scale - an absolute floor reads a
        // chart measured in items as all noise and one measured in millibuckets as all signal.
        // Deliberately {@link #DUST} and not {@link #ZERO}: these lists are what a reader
        // reconstructs conservation from, so dropping a real external here is a wrong answer,
        // while reporting a negligible one only produces a row the GUI rounds away anyway.
        final double tol = DUST * solutionScale(attempt.extents, attempt.flows, attempt.externals);
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
            if (ext <= tol) continue;
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
            collectTerminals(m, md, md.inQty.length, true, attempt, tol, terminalIn);
            collectTerminals(m, md, md.outQty.length, false, attempt, tol, terminalOut);
        }

        double internalFlow = 0;
        for (final double f : attempt.flows) {
            internalFlow += f;
        }

        final List<String> allNotes = new ArrayList<>(notes);
        allNotes.addAll(attempt.notes);
        allNotes.addAll(wiringDiagnostics(attempt, tol, terminalIn));

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
            List.copyOf(allNotes),
            keyOf(attempt.support));
    }

    /**
     * Flags externals that look like missing edges rather than intent. The free-terminal
     * rule means an unwired input silently becomes "supplied from outside" - correct for
     * oil, wrong when the same ingredient is right there on the chart. Two smells: (1) a
     * terminal input whose ingredient the chart also produces (through drawn edges or
     * another terminal); (2) a gated source whose ingredient is produced in a DIFFERENT
     * edge-connected component (two unlinked islands of the same fluid). Same-component
     * gated sources are the normal deficit case (e.g. the loopGraph source) and stay quiet.
     *
     * <p>
     * Severities differ because the two smells do: taking an ingredient from outside while the
     * chart also makes some is routine (nobody wires every input), so smell 1 is an observation.
     * Two unlinked islands of the SAME ingredient is a chart that does not describe one factory,
     * so smell 2 is a warning. Ingredients the pack gives away are filtered out of both -
     * see {@link Config#isFreeIngredient}.
     */
    private List<String> wiringDiagnostics(final Attempt attempt, final double tol, final List<External> terminalIn) {
        // A set: the same ingredient imported at two ports of the same machine is one wiring
        // mistake to the reader, and the message that describes it is identical either way.
        final Set<String> result = new LinkedHashSet<>();
        for (final External in : terminalIn) {
            final String ingredient = ingredientNameOf(in);
            if (Config.isFreeIngredient(ingredient)) continue;
            final String match = findProduction(in, -1);
            if (match != null) {
                result.add(
                    INFO.tag() + "'"
                        + machineNameOf(in)
                        + "' imports "
                        + ingredient
                        + " externally, but is produced at '"
                        + match
                        + "' - "
                        + MISSING_EDGE);
            }
        }
        for (int p = 0; p < connectedPorts.size(); p++) {
            final ConnectedPort port = connectedPorts.get(p);
            if (!port.input() || attempt.externals[p] <= tol) continue;
            final External src = new External(
                new PortRef(machines.get(port.machine()).node.id, port.portIndex(), port.input()),
                attempt.externals[p]);
            final String ingredient = ingredientNameOf(src);
            if (Config.isFreeIngredient(ingredient)) continue;
            final String match = findProduction(src, portComponent[p]);
            if (match != null) {
                result.add(
                    WARN.tag() + "'"
                        + machineNameOf(src)
                        + "' sources "
                        + ingredient
                        + " externally, but an unlinked part of the chart produces it at '"
                        + match
                        + "' - "
                        + MISSING_EDGE);
            }
        }
        return List.copyOf(result);
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

    /** The ingredient at a port, named the way the rest of the GUI names it. */
    private String ingredientNameOf(final External e) {
        return portOf(e).getDisplayName();
    }

    private void collectTerminals(final int m, final MachineData md, final int portCount, final boolean input,
        final Attempt attempt, final double tol, final List<External> out) {
        for (int i = 0; i < portCount; i++) {
            final double qty = md.qty(i, input);
            if (qty <= 0) continue;
            final long key = ((long) m << 32) | ((long) i << 1) | (input ? 1 : 0);
            if (portLookup.containsKey(key)) continue; // connected, not a terminal
            final double rate = attempt.extents[m] * qty;
            if (rate <= tol) continue;
            out.add(new External(new PortRef(md.node.id, i, input), rate));
        }
    }

    record ConnectedPort(int machine, int portIndex, boolean input, double qtyPerCraft, List<Integer> edges) {}

    record EdgeData(UUID id, int srcPort, int dstPort) {}

    /** One gate: an ingredient component in one direction, covering the listed ports. */
    record Gate(boolean input, List<Integer> ports) {}

    static final class MachineData {

        final Node node;
        final int durTicks;
        final double[] inQty;
        final double[] outQty;
        Double pinnedExtent; // crafts/s, null = free
        /** Where {@link FlowModel#applyPins} took the pin from, for the conflicting-pin report. */
        String pinKind;

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

        boolean hasPort(final int portIndex, final boolean input) {
            return portIndex >= 0 && portIndex < (input ? inQty.length : outQty.length);
        }
    }

    record Handles(ExpressionsBasedModel model, Variable[] extentVars, Variable[] flowVars, Variable[] extVars,
        Variable[] gateVars) {}

    /** Raw variable values of one stage's solve, with the flow-derived gate support. */
    static final class StageSolve {

        final double[] extents;
        final double[] flows;
        final double[] externals;
        final Set<Integer> support;
        final double externalQuantity;
        final double internalFlow;
        final boolean provenOptimal;

        private StageSolve(final FlowModel ctx, final double[] extents, final double[] flows, final double[] externals,
            final boolean provenOptimal) {
            this.extents = extents;
            this.flows = flows;
            this.externals = externals;
            this.provenOptimal = provenOptimal;
            this.support = ctx.gateSupport(externals);
            // Stage 2's objective value, so in crafts rather than in mixed ingredient units - this
            // is what stage 3's cap and the tie tests are compared against. The raw per-ingredient
            // rates a reader wants are rebuilt in FlowModel#toSolution.
            double qty = 0;
            for (int p = 0; p < externals.length; p++) {
                qty += externals[p] * ctx.externalWeight(p);
            }
            this.externalQuantity = qty;
            double flowSum = 0;
            for (final double f : flows) {
                flowSum += f;
            }
            this.internalFlow = flowSum;
        }

        static StageSolve from(final FlowModel ctx, final Handles h) {
            return from(ctx, h, true);
        }

        /**
         * The point this model actually holds, or null when it does not conserve. Every stage is
         * checked and not just the answer: a solve that ran out of time can report a feasible state
         * over a point that breaks its own rows, and every later cap and support is built on it.
         * Callers already treat null as "this stage found nothing".
         */
        static StageSolve from(final FlowModel ctx, final Handles h, final boolean provenOptimal) {
            final double[] extents = values(h.extentVars());
            final double[] flows = values(h.flowVars());
            final double[] externals = values(h.extVars());
            final String residual = ctx.validate(extents, flows, externals);
            if (residual != null) {
                ctx.rejection = "returned a point that does not conserve: " + residual;
                return null;
            }
            return new StageSolve(ctx, extents, flows, externals, provenOptimal);
        }

        /**
         * Read as solved. Clamping negatives to zero here would hide the one thing worth knowing
         * about a solver that returned a negative flow - {@link FlowModel#validate} rejects a solution
         * whose variables broke their own lower bounds, and it cannot do that on numbers already
         * repaired on the way out.
         */
        private static double[] values(final Variable[] vars) {
            final double[] out = new double[vars.length];
            for (int i = 0; i < vars.length; i++) {
                final Number v = vars[i].getValue();
                out[i] = v == null ? 0 : v.doubleValue();
            }
            return out;
        }
    }
}
