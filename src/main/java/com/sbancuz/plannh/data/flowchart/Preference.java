package com.sbancuz.plannh.data.flowchart;

import java.util.List;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.Variable;

import com.sbancuz.plannh.data.flowchart.AutoBalancer.Rank;
import com.sbancuz.plannh.data.flowchart.FlowModel.Handles;
import com.sbancuz.plannh.data.flowchart.FlowModel.StageSolve;

/**
 * One rule for choosing between answers that every rule before it found equally good. A chart
 * normally has many balanced solutions and no fact that separates them, so AUTO ranks them by an
 * ordered list of preferences, each optimized subject to the optima of the ones before it. A
 * preference is fully described by which family of model variables carries its cost and what each
 * of those is worth: making it an objective, reading it off a solved point and holding it while a
 * later preference breaks the remaining ties all follow from that pair.
 *
 * <p>
 * {@link #ORDER} is the single authority for the sequence - the solve chains its caps in it,
 * {@link AutoBalancer} names the first entry that separates a rejected answer, and the choices
 * panel sorts by the same index. Adding a heuristic is one entry here.
 */
record Preference(String name, Family family, Weight weight, Rank whenWorse, Rank whenBetter) {

    /** Which family of model variables carries a preference's cost. */
    enum Family {
        /** One binary per gate; only present in a model built with binaries. */
        GATES,
        /** One per connected port: what crosses the chart's boundary there. */
        EXTERNALS,
        /** One per drawn edge. */
        FLOWS
    }

    @FunctionalInterface
    interface Weight {

        double of(FlowModel model, int index);
    }

    /**
     * AUTO's answer, in the order it prefers things. Only the first two are decided
     * combinatorially - they choose WHICH gates open, which needs binaries - and
     * {@link #gateWeights} folds them into one objective. The rest are plain LPs over the support
     * that search settles on.
     */
    static final List<Preference> ORDER = List.of(
        new Preference("fewest gates", Family.GATES, (model, gate) -> 1.0, null, null),
        new Preference(
            "fewest imports",
            Family.GATES,
            (model, gate) -> model.gates.get(gate)
                .input() ? 1.0 : 0.0,
            Rank.IMPORTS_INSTEAD,
            null),
        new Preference("least excess", Family.EXTERNALS, FlowModel::externalWeight, Rank.VOIDS_MORE, null),
        new Preference("least internal flow", Family.FLOWS, (model, edge) -> 1.0, Rank.MOVES_MORE, Rank.MOVES_LESS));

    /**
     * Smallest unit the packed gate objective may use. The packing only needs to exceed the gate
     * count to be lexicographic, but ojAlgo does not solve a small objective range as reliably as a
     * large one - the corpus stops changing somewhere between 256 and 512, and this keeps an octave
     * beyond that measured edge.
     */
    private static final double WEIGHT_FLOOR = 1024.0;

    /**
     * The direction tilt as a multiplier on a quantity objective rather than on gate binaries: a
     * source costs fractionally more than a sink, in the same proportion the packed gate weights
     * use. One rule, so the gate search and the LP that seeds it cannot drift apart.
     */
    static double importTilt(final FlowModel model, final int gate) {
        return 1.0 + ORDER.get(1)
            .weight()
            .of(model, gate) / WEIGHT_FLOOR;
    }

    /** How many leading preferences choose WHICH gates open, and so need the gate binaries. */
    private static final int GATE_LEVELS = 2;

    /** The preferences applied as plain LPs once the gate search has settled the support. */
    static List<Preference> refinements() {
        return ORDER.subList(GATE_LEVELS, ORDER.size());
    }

    /**
     * One weight per gate, packing every {@link Family#GATES} preference into a single objective
     * that is lexicographic by construction: no level can charge more than 1 per gate and there are
     * only {@code gates} of them, so each level outweighs everything after it. Derived from the gate
     * count rather than chosen, so a chart big enough cannot make one level stop dominating the
     * next.
     */
    static double[] gateWeights(final FlowModel model) {
        final List<Preference> levels = ORDER.subList(0, GATE_LEVELS);
        final double unit = Math.max(WEIGHT_FLOOR, model.gates.size() + 1.0);
        final double[] weights = new double[model.gates.size()];
        for (int gate = 0; gate < weights.length; gate++) {
            double scale = Math.pow(unit, levels.size() - 1.0);
            for (final Preference level : levels) {
                weights[gate] += level.weight.of(model, gate) * scale;
                scale /= unit;
            }
        }
        return weights;
    }

    /** What this preference costs at a solved point. */
    double measure(final FlowModel model, final StageSolve solve) {
        return measure(model, solve.support, solve.externals, solve.flows);
    }

    /** As above, for a point held as bare arrays rather than as a solve. */
    double measure(final FlowModel model, final Set<Integer> support, final double[] externals, final double[] flows) {
        return switch (family) {
            case GATES -> costOf(model, support);
            case EXTERNALS -> weighted(model, externals);
            case FLOWS -> weighted(model, flows);
        };
    }

    /** What this preference costs for a gate support, without needing a solved point. */
    double costOf(final FlowModel model, final Set<Integer> support) {
        return costOf(support, gate -> weight.of(model, gate));
    }

    /**
     * What a gate support costs under any per-gate weighting. One preference's own weight and the
     * packed lexicographic weight of {@link #gateWeights} are the same sum over different numbers,
     * so they share the sum rather than each keeping a copy of it to drift from.
     */
    static double costOf(final Set<Integer> support, final IntToDoubleFunction weightOf) {
        double total = 0;
        for (final int gate : support) {
            total += weightOf.applyAsDouble(gate);
        }
        return total;
    }

    /** Makes this preference the objective of a fresh model. */
    void objective(final FlowModel model, final Handles handles) {
        final Variable[] vars = variables(handles);
        for (int i = 0; i < vars.length; i++) {
            vars[i].weight(weight.of(model, i));
        }
    }

    /**
     * Holds this preference at an optimum a previous stage reached, so a later one can only choose
     * between answers that are still equally good by this one.
     *
     * @param slack relative headroom, because an LP reproduces its own optimum only to within its
     *              feasibility tolerance and a cap set exactly at it can come back infeasible.
     */
    void hold(final FlowModel model, final Handles handles, final double optimum, final double slack) {
        final Expression cap = handles.model()
            .addExpression("hold_" + name.replace(' ', '_'));
        final Variable[] vars = variables(handles);
        for (int i = 0; i < vars.length; i++) {
            cap.set(vars[i], weight.of(model, i));
        }
        cap.upper(optimum * (1 + slack) + slack);
    }

    private Variable[] variables(final Handles handles) {
        return switch (family) {
            case GATES -> handles.gateVars();
            case EXTERNALS -> handles.extVars();
            case FLOWS -> handles.flowVars();
        };
    }

    private double weighted(final FlowModel model, final double[] values) {
        double total = 0;
        for (int i = 0; i < values.length; i++) {
            total += values[i] * weight.of(model, i);
        }
        return total;
    }
}
