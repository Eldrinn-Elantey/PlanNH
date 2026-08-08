package com.sbancuz.plannh.data.flowchart;

import java.util.ArrayList;
import java.util.Collection;
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
import org.ojalgo.optimisation.integer.IntegerStrategy;
import org.ojalgo.type.context.NumberContext;

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

    /**
     * How far above a chart's own scale the big-M gate links sit. Relative, because the model is
     * homogeneous: a fixed 1e6 is generous on a chart moving thousands of litres and absurd on one
     * moving hundredths of an item per second, and an absurd M is what kills branch-and-bound - the
     * relaxation reads {@code y >= ext/M} as zero, the root bound says nothing, and stage 1 silently
     * stops certifying below some scale. mk1 pinned at 1/100th of its rate used to answer with a
     * different gate than mk1 pinned at its rate, for exactly that reason. Under-estimates are
     * caught by {@link #pressesCap} and grown.
     */
    private static final double BIG_M_FACTOR = 1e3;
    private static final int MAX_M_GROWTHS = 3;
    private static final long STAGE_TIME_LIMIT_MILLIS = 15_000;
    /**
     * Ceiling on the WHOLE solve, not one model. A pass is a deletion filter, a certification MILP,
     * up to {@link #MAX_TIED_SUPPORTS} stage-2 MILPs each retried up to {@link #MAX_M_GROWTHS} times,
     * and a stage-3 LP per candidate - and {@link #solve} runs a second pass whenever stage-0 floors
     * bite. At 15s each and no total, that is minutes of frozen GUI, because the solve runs from
     * draw(). Everything optional (certification, tie enumeration) is skipped once this is spent,
     * and the answer already in hand is returned with a note.
     */
    private static final long SOLVE_BUDGET_MILLIS = 20_000;
    /**
     * Floor on any one model's time limit, however little of {@link #SOLVE_BUDGET_MILLIS} is left.
     * A solver given a millisecond does not decline to answer, it aborts and hands back whatever
     * point it was holding - which is the one outcome worth avoiding, since a stage's cap and
     * support are built on that point.
     */
    private static final long MIN_MODEL_MILLIS = 250;
    /**
     * Budget for the exact stage-1 MILP once the LP deletion filter has already produced a
     * minimal support. Big-M count minimization gives branch-and-bound no usable root bound, so
     * large charts cannot be certified in any budget - proofs that land do so within 150ms,
     * anything longer only lengthens the freeze for the same answer.
     *
     * <p>
     * TODO: make this an iteration or node count rather than a wall clock. Measuring in
     * milliseconds means the answer depends on how busy the machine is: the filter's support is
     * minimal but not always minimum, so a certification that closes on an idle box and times out
     * on a loaded one returns two different gate counts for the same chart. palladium_line answers
     * 9 or 10 gates for exactly this reason, which is why
     * GroundTruthTest#solutionsScaleWithTheirPins asserts a spread there instead of equality. A
     * deterministic budget would let it assert equality again and would make a solve reproducible
     * between two machines, which a wall clock never can.
     */
    private static final long MILP_CERT_BUDGET_MILLIS = 500;
    /** Flows below this count as zero when deriving gate support. Relative - see zeroTolerance. */
    private static final double ZERO = 1e-6;
    /**
     * Solver dust: what a variable holds when the solver meant zero. Orders of magnitude below
     * {@link #ZERO}, because the two answer different questions - {@link Ctx#gateSupport} asks "is
     * this worth reporting", {@link Ctx#carryingGates} asks "would closing this gate break the
     * solution", and a gate carrying 1e-9 of the chart's scale really would break it.
     */
    private static final double DUST = 1e-11;
    /**
     * Relative tolerance for "two solves found the SAME optimum". The model is homogeneous -
     * scaling every pin scales every objective - so an absolute epsilon would call two genuinely
     * different optima tied on a chart measured in millibuckets, and two identical ones distinct on
     * a chart measured in dust. Decades above the LP's own objective agreement (feasibility is
     * NumberContext.of(12, 10)) and decades below any difference a player would notice.
     */
    private static final double TIE_REL = 1e-6;
    /** A machine "runs" if its crafts/s exceeds this. */
    private static final double USE_EPS_DETECT = 1e-7;
    /** Fallback pass-2 floor (crafts/s) when no machine ran at all. */
    private static final double USE_EPS = 1e-4;
    /** Relative slack on the stage-2 quantity cap in stage 3. */
    private static final double QTY_EPS = 1e-7;
    /**
     * Residual tolerance for the independent conservation check, scaled by each row's largest
     * coefficient so it means the same thing on a row measured in items and one in millibuckets.
     */
    private static final double VALIDATE_TOL = 1e-6;
    /**
     * The direction tilt: a sink costs fractionally less than a source, so a solve with the choice
     * discards the excess rather than importing an intermediate. Also the base unit of
     * {@link Ctx#gateWeight}, where only the ratio to the gate count matters.
     */
    private static final double SNK_WEIGHT = 1024.0;
    private static final double SRC_WEIGHT = 1025.0;
    private static final int TICKS_PER_SECOND = 20;
    private static final int MAX_TIED_SUPPORTS = 5;
    /**
     * Wall budget for the WHOLE alternatives search, not one solve inside it. The user asked "what
     * else could this be", not "recompute everything"; over budget the list is returned marked
     * incomplete rather than grown.
     */
    private static final long ALT_BUDGET_MILLIS = 750;
    /**
     * The share of {@link #ALT_BUDGET_MILLIS} the breadth search may spend before the rest is
     * reserved for turning what it found into answers. Without the split, a chart with a thousand
     * candidate swaps spends the entire budget deciding which are feasible and then has nothing
     * left to evaluate any of them with - it comes back having looked everywhere and found
     * nothing, which is the worst of both.
     */
    private static final long ALT_SEARCH_SHARE_PERCENT = 50;
    /**
     * Cap on gate swaps tried. The neighbourhood is |support| x |gates|, and each try costs one
     * stage-2 LP - the expensive part, stage 3 and canonicalization, is paid only by the shortlist
     * that survives. That is what makes a cap this size affordable; whatever is still left untried
     * is reported, because a silently truncated list reads as "there is nothing else".
     */
    private static final int MAX_ALT_SWAPS = 1024;
    /** How many answers the list is allowed to carry before it stops being a list and starts being noise. */
    private static final int MAX_ALT_OPTIONS = 8;
    /** Display order: the default, then what beats it, then what ties it, then what it gave up. */
    private static final List<Rank> RANK_ORDER = List
        .of(Rank.DEFAULT, Rank.MOVES_LESS, Rank.EQUALLY_VALID, Rank.IMPORTS_INSTEAD, Rank.MOVES_MORE, Rank.VOIDS_MORE);

    private AutoBalancer() {}

    /**
     * The failure reason when no machine is pinned. Not an error: an unpinned chart is just
     * wiring (the gtnh-flow contract) - the model is homogeneous and every solution scales
     * freely, so any numbers would be an invented anchor the user never asked for.
     */
    public static final String NO_PIN = "nothing is pinned - fix a machine count to ask for a balance";

    /** Suffix of every wiring-diagnostic note; tests key on it. */
    public static final String MISSING_EDGE = "missing an edge?";

    /** A port on a specific machine. {@code input} distinguishes the two port lists. */
    public record PortRef(UUID nodeId, int portIndex, boolean input) {

        /**
         * Canonical order, matching the node order {@link Ctx} builds machines in. Gives everything
         * derived from a port a stable spelling, which is what lets a tie be broken by the chart
         * itself rather than by whatever order branch-and-bound happened to enumerate.
         */
        public static final Comparator<PortRef> ORDER = Comparator.comparing(PortRef::nodeId)
            .thenComparing(PortRef::input)
            .thenComparingInt(PortRef::portIndex);
    }

    /**
     * The identity of one answer: the anchor port of every open gate, sorted.
     *
     * <p>
     * Gate indices are rebuilt from scratch on every solve - UUID-sorted nodes, then edges, then
     * connected components - so nothing derived from them survives a save and reload. A port,
     * however, names itself the same way in every rebuild. The anchor is the smallest
     * {@link PortRef} among ALL of a gate's ports, never only the ones carrying flow, so the key
     * does not move when the solver redistributes a dump between two ports of one gate. Gates
     * partition the connected ports, so distinct supports always give distinct keys.
     */
    public record ChoiceKey(List<PortRef> gateAnchors) implements Comparable<ChoiceKey> {

        public static ChoiceKey of(final Collection<PortRef> anchors) {
            final List<PortRef> sorted = new ArrayList<>(anchors);
            sorted.sort(PortRef.ORDER);
            return new ChoiceKey(List.copyOf(sorted));
        }

        @Override
        public int compareTo(final ChoiceKey other) {
            final int n = Math.min(gateAnchors.size(), other.gateAnchors.size());
            for (int i = 0; i < n; i++) {
                final int cmp = PortRef.ORDER.compare(gateAnchors.get(i), other.gateAnchors.get(i));
                if (cmp != 0) return cmp;
            }
            return Integer.compare(gateAnchors.size(), other.gateAnchors.size());
        }
    }

    /**
     * Why an answer is not the default. Everything past the gate count is a preference rather than
     * a fact - the sink-over-source tilt is a constant somebody chose, and "least material moved" is
     * a taste - so the reason travels with the option and gets shown, instead of quietly deciding
     * on the user's behalf.
     */
    public enum Rank {
        /** What the solver returned. */
        DEFAULT,
        /** Indistinguishable on every objective; only node ordering separated the two. */
        EQUALLY_VALID,
        /** Lost on the {@link #SRC_WEIGHT}/{@link #SNK_WEIGHT} tilt: it imports where the default voids. */
        IMPORTS_INSTEAD,
        /**
         * Lost on stage 2: it leans on the outside more. "More" is each port's flow divided by that port's
         * own per-craft quantity, so it compares fractions of a craft wasted rather than items
         * against litres - but the crafts belong to different machines, which is a real comparison
         * and still a debatable one. Shown, not hidden, for exactly that reason.
         */
        VOIDS_MORE,
        /** Lost on stage 3: same gates and same excess, but more material moved internally. */
        MOVES_MORE,
        /**
         * Same gates and same excess, and it moves LESS material than the default. Reachable
         * because the default's tie enumeration is capped at {@link #MAX_TIED_SUPPORTS} supports
         * and a one-gate swap can step outside what it reached. Listed rather than swallowed: the
         * honest thing is to show that the default was not the last word.
         */
        MOVES_LESS
    }

    /**
     * One answer the user may pick.
     *
     * <p>
     * A chart with several open gates poses several independent questions, and an option answers
     * exactly one of them: {@code replaces} names the decision it belongs to (the gate of the
     * current answer it would displace) and {@code opens} the gate it would use instead. Grouping
     * by {@code replaces} is what keeps a seven-row list from reading as one undifferentiated soup
     * when it is really two questions with three and four answers.
     *
     * @param key       the WHOLE support this option implies - what gets stored when it is picked.
     * @param externals the flows at {@code opens} only, i.e. what actually differs. Labelling from
     *                  the full support would repeat the parts every option shares.
     */
    public record Alternative(ChoiceKey key, PortRef replaces, PortRef opens, List<External> externals, Rank rank) {

        /** True when this option keeps the answer that is already on screen for its decision. */
        public boolean isCurrent() {
            return rank == Rank.DEFAULT;
        }
    }

    /**
     * Every answer worth showing for one chart, default first.
     *
     * <p>
     * {@code complete} is false when the search stopped on its budget or its swap cap rather than on
     * exhaustion: the UI has to say "at least these", because claiming a complete list it never
     * proved is the one thing a solver may not do.
     */
    public record Alternatives(ChoiceKey chosen, List<Alternative> options, boolean complete, List<String> notes) {}

    /**
     * A wall-clock ceiling shared by every model in one solve. Passed rather than held statically so
     * that separate entry points can budget independently.
     */
    private record Budget(long deadlineMillis) {

        static Budget of(final long millis) {
            return new Budget(System.currentTimeMillis() + millis);
        }

        long remaining() {
            return Math.max(0, deadlineMillis - System.currentTimeMillis());
        }

        boolean expired() {
            return remaining() <= 0;
        }
    }

    /** An external attached to a port, in ingredient units per second. */
    public record External(PortRef port, double ratePerSecond) {}

    public record Solution(Map<UUID, Double> extentsPerSecond, Map<UUID, Double> machineCounts,
        Map<UUID, Double> edgeFlowsPerSecond, List<External> gatedSources, List<External> gatedSinks,
        List<External> terminalInputs, List<External> terminalOutputs, int openGates, double externalQuantity,
        double totalInternalFlow, boolean floorsUsed, long wallMillis, List<String> notes, ChoiceKey key) {}

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
        return solve(graph, extraExtentPins, null);
    }

    /**
     * @param choice one of the answers {@link #alternatives} offered, or null for the solver's own.
     *               Held to the same bar the list is drawn by - equal gate count, no worse excess -
     *               so anything the user was offered can actually be picked, and anything else is
     *               refused with a note rather than quietly degrading the chart. A choice that
     *               only loses on a heuristic (it imports where the default voids, or it moves more
     *               material) is honoured: those were preferences, not facts.
     */
    public static Result solve(final Graph graph, final Map<UUID, Double> extraExtentPins, final ChoiceKey choice) {
        return answer(graph, extraExtentPins, choice, false).result();
    }

    /** A solved chart and, optionally, the other answers it could have had. */
    public record Answer(Result result, Alternatives alternatives) {}

    /**
     * Solve and enumerate in ONE pass.
     *
     * <p>
     * The two used to be separate entry points, which meant the panel paid for the whole
     * lexicographic pipeline twice over - once to draw the chart and again to ask what else it
     * could have been. On the largest corpus chart that was a second of duplicated work for an
     * answer already sitting in memory, and it is the reason the choices list used to hide behind
     * a click.
     */
    public static Answer solveWithAlternatives(final Graph graph, final Map<UUID, Double> extraExtentPins,
        final ChoiceKey choice) {
        return answer(graph, extraExtentPins, choice, true);
    }

    private static Answer answer(final Graph graph, final Map<UUID, Double> extraExtentPins, final ChoiceKey choice,
        final boolean withAlternatives) {
        final long start = System.currentTimeMillis();
        final Alternatives none = new Alternatives(null, List.of(), true, List.of());
        final Ctx ctx = new Ctx(graph, extraExtentPins, Budget.of(SOLVE_BUDGET_MILLIS));
        if (ctx.machines.isEmpty()) {
            return new Answer(Result.fail("empty graph"), none);
        }
        if (!ctx.anyPin) {
            return new Answer(Result.fail(NO_PIN), none);
        }

        final Run run = runBothPasses(ctx);
        if (run.failure != null) {
            return new Answer(Result.fail(run.failure), none);
        }

        Attempt attempt = run.attempt;
        if (choice != null) {
            attempt = applyChoice(ctx, run, choice);
        }

        final String residualError = ctx.validate(attempt);
        if (residualError != null) {
            return new Answer(Result.fail("solution failed independent validation: " + residualError), none);
        }

        final Result result = Result.ok(ctx.toSolution(attempt, run.floorsUsed, System.currentTimeMillis() - start));
        return new Answer(result, withAlternatives ? enumerate(ctx, run, attempt) : none);
    }

    /** The floor-free pass and, when it left machines idle, the floored one that replaces it. */
    private record Run(Attempt attempt, double[] floors, boolean floorsUsed, String failure) {

        static Run failed(final String reason) {
            return new Run(null, null, false, reason);
        }
    }

    private static Run runBothPasses(final Ctx ctx) {
        // Pass 1 is floor-free. A floor picked before a solution exists is not scale-free: set it
        // above a machine's natural rate and it forces excess through that machine, which then
        // needs an external to absorb it. It also drags conservation rows down to floor magnitude,
        // where a solver's arithmetic is a large fraction of the row.
        final Attempt first = runStages(ctx, null);
        if (first.failure != null) {
            final String conflict = diagnosePins(ctx);
            return Run.failed(conflict == null ? first.failure : first.failure + " - " + conflict);
        }

        // Stage 0: every machine wired to a pin runs, with the floor taken from pass 1's own
        // smallest running rate so it cannot bind above a rate this chart already achieves. Not
        // negotiable - a chart that cannot run its machines is reported as unbalanceable rather
        // than answered with dead machines in it.
        final double[] floors = ctx.stageZeroFloors(first.extents);
        if (floors == null) {
            return new Run(first, null, false, null);
        }
        final Attempt floored = runStages(ctx, floors);
        if (floored.failure != null) {
            return Run.failed(ctx.idleCount(first.extents) + " machines cannot run: " + floored.failure);
        }
        return new Run(floored, floors, true, null);
    }

    // ---------------------------------------------------------------------------------------
    // Alternatives
    // ---------------------------------------------------------------------------------------

    /**
     * Every answer worth showing for this chart, the solver's own first.
     *
     * <p>
     * Deliberately not part of {@link #solve}: that runs from the GUI's draw path behind a dirty
     * flag, and this costs an LP per candidate swap, which is only worth paying when somebody is
     * actually looking at the choices.
     *
     * <p>
     * The bar for being listed is NOT DOMINATED, not TIED. Past the gate count, every rule that
     * narrows the field is a preference: the sink-over-source tilt is a constant somebody picked,
     * and "least material moved" is a taste. So anything with the same gate count that is no worse
     * on excess gets shown, carrying the reason it is not the default - a heuristic the user cannot
     * see is a heuristic the user cannot disagree with.
     */
    public static Alternatives alternatives(final Graph graph, final Map<UUID, Double> extraExtentPins) {
        return solveWithAlternatives(graph, extraExtentPins, graph.getExcessChoice()).alternatives();
    }

    /** The answers around {@code attempt}, using the context that already solved it. */
    private static Alternatives enumerate(final Ctx ctx, final Run run, final Attempt attempt) {
        final Set<Integer> incumbent = attempt.support;
        final ChoiceKey chosen = ctx.keyOf(incumbent);
        final List<Alternative> options = new ArrayList<>();
        // One "this is what it does now" row per open gate, so every question in the list has its
        // current answer sitting at the top of its own group rather than one row standing for all
        // of them at once.
        for (final int gate : sorted(incumbent)) {
            options.add(ctx.asAlternative(attempt.externals, incumbent, gate, gate, Rank.DEFAULT));
        }
        final List<String> notes = new ArrayList<>();

        // Nothing else could open, so nothing else could differ.
        if (incumbent.isEmpty() || ctx.gates.size() == incumbent.size()) {
            return new Alternatives(chosen, List.copyOf(options), true, List.copyOf(notes));
        }

        final double flowStar = sum(attempt.flows);
        final double qtyStar = ctx.normalizedQuantity(attempt.externals);
        final double costStar = ctx.weightedCost(incumbent);
        final double tol = TIE_REL * Math
            .max(Math.max(flowStar, qtyStar), ctx.solutionScale(attempt.extents, attempt.flows, attempt.externals));

        // One gate swapped at a time. Equal gate count by construction, so stage 1's optimum holds
        // and no MILP is needed; either direction, because the whole point is to surface the
        // source/sink tilt rather than let it delete a candidate before anyone sees it.
        final Budget whole = Budget.of(ALT_BUDGET_MILLIS);
        ctx.budget = Budget.of(ALT_BUDGET_MILLIS * ALT_SEARCH_SHARE_PERCENT / 100);
        final Set<ChoiceKey> seen = new HashSet<>();
        seen.add(chosen);
        // Two passes, because the neighbourhood is large and most of it is not feasible. A stage-2
        // LP alone answers "does this support work at all, and how much does it lean on the outside"
        // for one solve; only the shortlist that survives pays for stage 3 and canonicalization,
        // which is what actually makes an option displayable. Evaluating all three up front spent
        // two thirds of the budget on candidates that were about to be discarded.
        final List<Swap> shortlist = new ArrayList<>();
        int evaluated = 0;
        int skipped = 0;
        for (final int out : sorted(incumbent)) {
            for (int in = 0; in < ctx.gates.size(); in++) {
                if (incumbent.contains(in)) continue;
                if (evaluated >= MAX_ALT_SWAPS || ctx.budget.expired()) {
                    skipped++;
                    continue;
                }
                evaluated++;
                final Set<Integer> trial = new HashSet<>(incumbent);
                trial.remove(out);
                trial.add(in);
                final StageSolve s2 = solveStage2Fixed(ctx, run.floors, trial);
                if (s2 == null || s2.support.size() != incumbent.size()) continue;
                if (!seen.add(ctx.keyOf(s2.support))) continue;
                shortlist.add(new Swap(out, in, s2));
            }
        }

        // Whatever the search did not use now belongs to the evaluation.
        ctx.budget = whole;

        // Cheapest on the outside world first, so a budget that runs out takes the least
        // interesting answers with it rather than an arbitrary slice.
        shortlist.sort(
            Comparator.<Swap, Double>comparing(c -> c.solve().externalQuantity)
                .thenComparing(c -> ctx.keyOf(c.solve().support)));

        // Round-robin over the decisions rather than straight down the sorted list. A chart asking
        // two questions where one happens to have six cheap answers would otherwise spend the whole
        // option budget on that one and leave the other showing a heading with nothing under it -
        // which reads as "no alternatives here" when the truth is "nobody looked".
        final List<Swap> fair = interleaveByDecision(shortlist);
        for (final Swap candidate : fair) {
            if (options.size() >= MAX_ALT_OPTIONS || ctx.budget.expired()) {
                skipped++;
                continue;
            }
            final StageSolve witness = candidate.solve();
            final Set<Integer> open = ctx.carryingGates(witness.externals, witness.support);
            final StageSolve s3 = solveStage3(ctx, run.floors, open, witness.externalQuantity);
            if (s3 == null) continue;
            final StageSolve alt = canonicalize(ctx, run.floors, open, s3);
            if (alt.support.size() != incumbent.size()) continue;
            options.add(
                ctx.asAlternative(
                    alt.externals,
                    alt.support,
                    candidate.out(),
                    candidate.in(),
                    rankOf(ctx, alt, qtyStar, flowStar, costStar, tol)));
        }
        // Default first, then the ones that cost nothing to prefer, then by how much they give up.
        // Ties inside a rank go to the lower key, so the same chart always lists in the same order.
        options.sort(
            Comparator.comparing((final Alternative o) -> o.replaces(), PortRef.ORDER)
                .thenComparingInt(o -> RANK_ORDER.indexOf(o.rank()))
                .thenComparing(Alternative::key));
        boolean complete = skipped == 0;
        if (options.size() > MAX_ALT_OPTIONS) {
            notes.add("showing the closest " + MAX_ALT_OPTIONS + " of " + options.size() + " workable answers");
            options.subList(MAX_ALT_OPTIONS, options.size())
                .clear();
            complete = false;
        }
        if (skipped > 0) {
            notes.add(
                "stopped after " + evaluated
                    + " combinations, so there may be more than the ones listed ("
                    + skipped
                    + " not tried)");
        }
        return new Alternatives(chosen, List.copyOf(options), complete, List.copyOf(notes));
    }

    /** A candidate support reached by closing one gate and opening another, with its witness. */
    private record Swap(int out, int in, StageSolve solve) {}

    /** The same candidates, dealt out one per decision per round, best-first within each. */
    private static List<Swap> interleaveByDecision(final List<Swap> sorted) {
        final Map<Integer, List<Swap>> byDecision = new LinkedHashMap<>();
        for (final Swap swap : sorted) {
            byDecision.computeIfAbsent(swap.out(), k -> new ArrayList<>())
                .add(swap);
        }
        final List<Swap> out = new ArrayList<>(sorted.size());
        for (int round = 0; out.size() < sorted.size(); round++) {
            for (final List<Swap> queue : byDecision.values()) {
                if (round < queue.size()) out.add(queue.get(round));
            }
        }
        return out;
    }

    /**
     * Why {@code alt} is not the default: the EARLIEST stage at which the two diverged, which is
     * the one that actually decided it. mk1's alternative both imports and moves less material, and
     * reporting the flow would hide the thing that really chose - the source/sink tilt, applied at
     * stage 1, before flow is ever looked at.
     */
    private static Rank rankOf(final Ctx ctx, final StageSolve alt, final double qtyStar, final double flowStar,
        final double costStar, final double tol) {
        if (ctx.weightedCost(alt.support) > costStar + 0.5) return Rank.IMPORTS_INSTEAD;
        if (alt.externalQuantity > qtyStar + tol) return Rank.VOIDS_MORE;
        final double flow = alt.internalFlow;
        if (flow > flowStar + tol) return Rank.MOVES_MORE;
        if (flow < flowStar - tol) return Rank.MOVES_LESS;
        return Rank.EQUALLY_VALID;
    }

    /** The full stage-2 -> stage-3 -> canonical point over one fixed gate support, or null. */
    private static StageSolve evaluateSupport(final Ctx ctx, final double[] floors, final Set<Integer> open) {
        final StageSolve s2 = solveStage2Fixed(ctx, floors, open);
        if (s2 == null) return null;
        final Set<Integer> carrying = ctx.carryingGates(s2.externals, s2.support);
        final StageSolve s3 = solveStage3(ctx, floors, carrying, s2.externalQuantity);
        if (s3 == null) return null;
        return canonicalize(ctx, floors, carrying, s3);
    }

    /** Applies a stored choice, or explains in a note why it could not be. */
    private static Attempt applyChoice(final Ctx ctx, final Run run, final ChoiceKey choice) {
        final Set<Integer> target = ctx.resolve(choice);
        if (target == null) {
            ctx.notes.add("the saved excess choice no longer fits this chart - showing the solver's own answer");
            return run.attempt;
        }
        if (target.equals(run.attempt.support)) return run.attempt;

        final StageSolve alt = evaluateSupport(ctx, run.floors, target);
        if (alt == null || alt.support.size() != run.attempt.support.size()) {
            // Gate count is the one bar a choice may not fall below: it is a real optimum, where
            // everything after it is a preference the user is entitled to disagree with.
            ctx.notes.add("the saved excess choice needs more gates than the solver's answer, so it was dropped");
            return run.attempt;
        }
        // Deliberately no note when the pick leans on the outside more than the default would have:
        // that is what choosing a listed alternative MEANS. The list said as much before it was
        // picked and the row still says it, so repeating it here reads as "something went wrong"
        // over an answer the user selected on purpose.
        return Attempt.of(alt, alt.support, run.attempt.certified, run.attempt.notes);
    }

    private static double sum(final double[] values) {
        double total = 0;
        for (final double v : values) {
            total += v;
        }
        return total;
    }

    /** Gate indices in a stable order, so the swap search is reproducible. */
    private static List<Integer> sorted(final Set<Integer> gates) {
        final List<Integer> out = new ArrayList<>(gates);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Stage pipeline
    // ---------------------------------------------------------------------------------------

    /**
     * Which pins the chart cannot satisfy at once, or null when the pins are not the problem.
     *
     * <p>
     * A floor-free stage 1 fails only when the most permissive model there is - every gate open,
     * every external free, every unconnected port a free terminal - is still infeasible. With
     * nonnegative externals on every connected port, the only thing left that can conflict is the
     * pins: two fixed counts on machines that feed each other at a ratio their counts do not
     * honour. So drop them weakest first (fixed counts before targets, mirroring the ranking in
     * {@link Ctx#applyPins}) until the LP closes, and name the ones that had to go. Reporting "no
     * feasible support" for that is technically true and practically useless.
     */
    private static String diagnosePins(final Ctx ctx) {
        final List<MachineData> pinned = new ArrayList<>();
        for (final MachineData m : ctx.machines) {
            if (m.pinnedExtent != null) pinned.add(m);
        }
        if (pinned.size() < 2) return null;
        pinned.sort(Comparator.comparingInt(m -> PIN_STRENGTH.indexOf(m.pinKind)));

        final Map<MachineData, Double> saved = new LinkedHashMap<>();
        final List<String> dropped = new ArrayList<>();
        try {
            for (final MachineData m : pinned) {
                if (solveExternalsLp(ctx, null, null) != null) break;
                if (saved.size() == pinned.size() - 1) return null; // one pin left: not a conflict
                saved.put(m, m.pinnedExtent);
                m.pinnedExtent = null;
                dropped.add("'" + m.node.machineName + "' (" + m.pinKind + ")");
            }
        } finally {
            saved.forEach((m, extent) -> m.pinnedExtent = extent);
        }
        if (dropped.isEmpty()) return null;
        return "these pins cannot hold together, and dropping them makes the chart solvable: "
            + String.join(", ", dropped);
    }

    /** Pin kinds weakest first, the order {@link #diagnosePins} sacrifices them in. */
    private static final List<String> PIN_STRENGTH = List.of("fixed machine count", "target rate", "extent pin");

    /** One full lexicographic run (stages 1-3) under the given floors (null = floor-free). */
    private static Attempt runStages(final Ctx ctx, final double[] floors) {
        // Fast path: if the chart balances with every gate closed, stages 1-2 are trivially
        // optimal (0 gates, 0 external quantity) and this LP IS stage 3.
        final StageSolve fast = solveStage3(ctx, floors, Set.of(), 0.0);
        if (fast != null) {
            return Attempt.of(canonicalize(ctx, floors, Set.of(), fast), Set.of(), true, List.of());
        }

        // Stage 1 runs twice: an LP deletion filter that always produces a minimal support fast,
        // then a short exact-MILP slice that certifies (or beats) it when the chart is small
        // enough for branch-and-bound. Large charts keep the filter answer, uncertified.
        final StageSolve filter = deletionFilter(ctx, floors);
        if (filter == null) {
            return Attempt.failed("stage 1 (gate count) found no feasible support");
        }
        // Everything with a binary in it is sized from the filter's own solution: it is the first
        // point that exists, and every later stage lives at the same scale.
        final double scale = ctx.solutionScale(filter.extents, filter.flows, filter.externals);
        final Set<Integer> filterSupport = filter.support;
        Set<Integer> s1Support = filterSupport;
        StageSolve s1Witness = filter;
        boolean certified = false;
        // Certification is optional: the filter's support is already minimal, and proving it so is
        // the first thing to drop when the solve's budget is gone.
        final StageSolve milp = ctx.budget.expired() ? null
            : solveStage1(ctx, floors, ctx.weightedCost(filterSupport), scale);
        if (milp != null && ctx.weightedCost(milp.support) <= ctx.weightedCost(filterSupport) + 0.5) {
            s1Support = milp.support;
            s1Witness = milp;
            certified = milp.provenOptimal;
        }
        final List<String> notes = certified ? List.of()
            : List.of(
                "gate count " + s1Support.size() + " is minimal but not certified optimal (exact search over budget)");
        // The cap must cover what stage 1 actually used, not what its support reports: a gate
        // carrying flow too small to register still costs its weight, and capping below it leaves
        // stage 2 infeasible on a chart stage 1 has just solved.
        final double weightedCap = ctx.weightedCost(ctx.carryingGates(s1Witness.externals, s1Support));

        final Set<Integer> s1Carrying = ctx.carryingGates(s1Witness.externals, s1Support);
        StageSolve s2 = certified ? solveStage2Cut(ctx, floors, weightedCap, List.of(), scale)
            : solveStage2Fixed(ctx, floors, s1Carrying);
        if (s2 == null && certified) {
            // The free search over minimal-count supports did not close. Holding the gates to the
            // ones stage 1 used makes this an LP that stage 1's solution already satisfies, so it
            // cannot be infeasible; the quantity it finds is never better than the free search
            // would have found, and the stage-3 tie enumeration below is skipped accordingly.
            s2 = solveStage2Fixed(ctx, floors, s1Carrying);
            certified = false;
        }
        if (s2 == null) {
            return Attempt.failed("stage 2 (external quantity) found no solution within budget");
        }

        // Ties at (count, quantity) are resolved by stage 3's objective: re-run stage 3 for each
        // tied stage-2 support and keep the least internal flow. This is what makes loopGraph
        // deterministically pick the diluted-acid source over the sulfuric one. Only meaningful
        // on the certified path (the filter path has no optimality frontier to enumerate).
        //
        // Each candidate opens the gates ITS OWN witness carries. Feeding s2's externals to another
        // candidate's support unions the two, which makes every later candidate a relaxation of the
        // first: it can then win on internal flow using gates from both supports, above the count
        // stage 1 proved optimal, and report only its own support's size for them.
        final List<StageSolve> candidates = certified && !ctx.budget.expired()
            ? tiedSupports(ctx, floors, weightedCap, s2, scale)
            : List.of(s2);
        StageSolve best = null;
        Set<Integer> bestSupport = null;
        for (final StageSolve cand : candidates) {
            final Set<Integer> open = ctx.carryingGates(cand.externals, cand.support);
            StageSolve s3 = solveStage3(ctx, floors, open, s2.externalQuantity);
            if (s3 == null) continue;
            s3 = canonicalize(ctx, floors, open, s3);
            // Least internal flow wins; ties go to the lower ChoiceKey rather than to whichever
            // candidate branch-and-bound happened to enumerate first. On a chart whose two answers
            // are mirror images that order is the ONLY thing separating them, and it must not depend
            // on the solver's internals or on an ojAlgo upgrade.
            if (best == null) {
                best = s3;
                bestSupport = s3.support;
                continue;
            }
            final double tol = ctx.tieTolerance(best.internalFlow, best);
            final boolean better = s3.internalFlow < best.internalFlow - tol;
            final boolean tiedButLower = Math.abs(s3.internalFlow - best.internalFlow) <= tol && ctx.keyOf(s3.support)
                .compareTo(ctx.keyOf(bestSupport)) < 0;
            if (better || tiedButLower) {
                // The support the returned point actually carries, not the candidate that led to
                // it: those differ whenever stage 3 leaves one of the candidate's gates unused, and
                // it is the former that Solution.openGates has to agree with.
                best = s3;
                bestSupport = s3.support;
            }
        }
        if (best == null) {
            return Attempt.failed("stage 3 (internal flow) found no solution within budget");
        }
        return Attempt.of(best, bestSupport, certified, notes);
    }

    /**
     * The stage-2 witness plus any other witness tied with it at the same (weighted gate count,
     * external quantity). Each is returned whole: a support without the externals that produced it
     * cannot say which of its gates actually carry flow.
     */
    private static List<StageSolve> tiedSupports(final Ctx ctx, final double[] floors, final double weightedCap,
        final StageSolve s2, final double scale) {
        final List<StageSolve> candidates = new ArrayList<>();
        candidates.add(s2);
        final List<Set<Integer>> cuts = new ArrayList<>();
        cuts.add(s2.support);
        final Set<Set<Integer>> seen = new HashSet<>();
        seen.add(s2.support);
        while (candidates.size() < MAX_TIED_SUPPORTS && !ctx.budget.expired()) {
            final StageSolve next = solveStage2Cut(ctx, floors, weightedCap, cuts, scale);
            if (next == null || next.externalQuantity > s2.externalQuantity + ctx.tieTolerance(s2.externalQuantity, s2)
                || ctx.weightedCost(next.support) > weightedCap + 0.5) {
                break;
            }
            // The no-good cuts are written over the binaries, but y_g = 1 with zero flow satisfies
            // the one-directional big-M link: whenever the cap leaves a gate spare, the solver can
            // dodge a cut by opening one and hand back a support it has already given us. Dedupe on
            // the flow-derived support and stop on a repeat, so the loop provably makes progress.
            if (!seen.add(next.support)) break;
            candidates.add(next);
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
    private static StageSolve deletionFilter(final Ctx ctx, final double[] floors) {
        final StageSolve lp0 = solveExternalsLp(ctx, floors, null);
        if (lp0 == null) return null;
        StageSolve best = lp0;
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
                best = solved;
            }
        }
        return best;
    }

    /**
     * LP over the gate structure with no binaries: externals outside {@code support} are closed
     * (null = all open), objective = weighted external quantity (source flow costs fractionally
     * more than sink flow, mirroring the stage-1 preference).
     *
     * <p>
     * Raw quantity here, deliberately, where stage 2 measures externals in crafts: what this LP
     * produces is a starting SUPPORT for the deletion filter, not a quantity anyone reads, and the
     * filter can only shrink that support one gate at a time. Raw quantity makes a wide, thin
     * support expensive and so is the better proxy for "few gates"; normalized, palladium_line
     * opens 16 gates where 9 suffice, because spreading excess across many high-throughput fluid
     * ports costs almost nothing per craft.
     */
    private static StageSolve solveExternalsLp(final Ctx ctx, final double[] floors, final Set<Integer> support) {
        final Handles h = ctx.buildModel(0, false, floors);
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
        return StageSolve.from(ctx, h);
    }

    /**
     * Stage 1 exact MILP: minimize weighted open-gate count under an upper-bound cut.
     *
     * @param scale the chart's own magnitude, from a solve that already succeeded - the big-M links
     *              are sized from it rather than from a constant, so the search behaves the same way
     *              on a chart pinned at one rate and the same chart pinned at a hundredth of it.
     */
    private static StageSolve solveStage1(final Ctx ctx, final double[] floors, final Double upperBoundCost,
        final double scale) {
        double bigM = BIG_M_FACTOR * scale;
        for (int growth = 0; growth <= MAX_M_GROWTHS; growth++) {
            final Handles h = ctx.buildModel(bigM, true, floors);
            final long certLimit = Math
                .max(MIN_MODEL_MILLIS, Math.min(MILP_CERT_BUDGET_MILLIS, ctx.budget.remaining()));
            h.model.options.time_abort = certLimit;
            h.model.options.time_suffice = certLimit;
            final Expression ub = upperBoundCost == null ? null : h.model.addExpression("ub_cut");
            for (int g = 0; g < ctx.gates.size(); g++) {
                final double weight = ctx.gateWeight(g);
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
            // cap spuriously and pollutes the flow-derived gate support with junk flows. Sized
            // against bigM rather than fixed, so that one external at its cap costs a thousandth of
            // a gate no matter what the chart is measured in, and can never flip a 1-unit decision.
            final double anchor = 1e-3 / bigM;
            for (final Variable ext : h.extVars) {
                ext.weight(anchor);
            }
            final long solveStart = System.currentTimeMillis();
            final Optimisation.Result result = h.model.minimise();
            final boolean withinBudget = System.currentTimeMillis() - solveStart < certLimit;
            if (!isUsable(result)) return null;
            if (pressesCap(h, bigM)) {
                bigM *= 10;
                continue;
            }
            // OPTIMAL or DISTINCT (unique optimum) both certify; FEASIBLE = timeout incumbent. The
            // wall check is belt and braces: a state that says OPTIMAL because the search stopped on
            // time_suffice rather than on a closed gap would make every downstream stage - the cap,
            // the tie enumeration - run on a proof that was never obtained.
            return StageSolve.from(
                ctx,
                h,
                withinBudget && result.getState()
                    .isOptimal());
        }
        return null;
    }

    /** Stage 2 on the uncertified path: fixed support, plain LP, minimize external quantity. */
    private static StageSolve solveStage2Fixed(final Ctx ctx, final double[] floors, final Set<Integer> open) {
        final Handles h = ctx.buildModel(0, false, floors);
        for (int p = 0; p < ctx.connectedPorts.size(); p++) {
            if (open.contains(ctx.portGate[p])) {
                h.extVars[p].weight(ctx.externalWeight(p));
            } else {
                h.extVars[p].upper(0);
            }
        }
        final Optimisation.Result result = h.model.minimise();
        if (!isUsable(result)) return null;
        return StageSolve.from(ctx, h);
    }

    /** Stage 2: minimize total external quantity, weighted gate count capped at stage 1. */
    private static StageSolve solveStage2Cut(final Ctx ctx, final double[] floors, final double weightedCap,
        final List<Set<Integer>> cuts, final double scale) {
        double bigM = BIG_M_FACTOR * scale;
        for (int growth = 0; growth <= MAX_M_GROWTHS; growth++) {
            final Handles h = ctx.buildModel(bigM, true, floors);
            final Expression cap = h.model.addExpression("count_cap");
            for (int g = 0; g < ctx.gates.size(); g++) {
                cap.set(h.gateVars[g], ctx.gateWeight(g));
            }
            cap.upper(weightedCap + 0.5);
            for (int p = 0; p < ctx.connectedPorts.size(); p++) {
                h.extVars[p].weight(ctx.externalWeight(p));
            }
            addNoGoodCuts(h, cuts);
            final Optimisation.Result result = h.model.minimise();
            if (!isUsable(result)) return null;
            if (pressesCap(h, bigM)) {
                bigM *= 10;
                continue;
            }
            return StageSolve.from(ctx, h);
        }
        return null;
    }

    /**
     * Stage 3: gates fixed to the given support (ports of open gates keep a free external under
     * the quantity cap, ports of closed gates are hard zero), minimize total internal flow. Also
     * the zero-gate fast path (empty support, zero cap).
     */
    private static StageSolve solveStage3(final Ctx ctx, final double[] floors, final Set<Integer> open,
        final double qtyCap) {
        final Handles h = ctx.buildModel(0, false, floors);
        final Expression qty = open.isEmpty() ? null : h.model.addExpression("qty_cap");
        for (int p = 0; p < ctx.connectedPorts.size(); p++) {
            if (qty != null && open.contains(ctx.portGate[p])) {
                // Same units as stage 2's objective, or the cap would not mean what stage 2 proved.
                qty.set(h.extVars[p], ctx.externalWeight(p));
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
        return StageSolve.from(ctx, h);
    }

    /**
     * Stage 3b: the one canonical point among stage 3's optima.
     *
     * <p>
     * Stage 3 fixes how much flows and how much is voided, but not always WHERE: two ports of one
     * gate can split a dump differently, and two parallel edges can carry a demand in any
     * proportion, on a solution the solver considers finished. Capping both totals at what stage 3
     * already achieved and then minimizing a rank-weighted sum can only redistribute inside that
     * optimum - no objective moves - but it pushes mass onto the lowest-ranked port and edge, and
     * so gives the same chart the same numbers on every solve. Without it the displayed rates, and
     * any choice keyed off them, drift between runs and across a save and reload.
     *
     * <p>
     * Ranks are dimensionless multipliers in {@code [1, 2]} taken from the canonical build order
     * (UUID-sorted nodes, then UUID-sorted edges), so this stays scale-free and coefficients stay
     * near 1.
     */
    private static StageSolve canonicalize(final Ctx ctx, final double[] floors, final Set<Integer> open,
        final StageSolve s3) {
        final Handles h = ctx.buildModel(0, false, floors);
        final Expression qty = open.isEmpty() ? null : h.model.addExpression("qty_cap");
        final int ports = ctx.connectedPorts.size();
        for (int p = 0; p < ports; p++) {
            if (qty != null && open.contains(ctx.portGate[p])) {
                qty.set(h.extVars[p], ctx.externalWeight(p));
                h.extVars[p].weight(rank(p, ports) * ctx.externalWeight(p));
            } else {
                h.extVars[p].upper(0);
            }
        }
        if (qty != null) {
            qty.upper(s3.externalQuantity * (1 + QTY_EPS) + QTY_EPS);
        }
        final Expression flowCap = h.model.addExpression("flow_cap");
        for (int e = 0; e < h.flowVars.length; e++) {
            flowCap.set(h.flowVars[e], 1.0);
            h.flowVars[e].weight(rank(e, h.flowVars.length));
        }
        flowCap.upper(s3.internalFlow * (1 + QTY_EPS) + QTY_EPS * Math.max(1.0, s3.internalFlow));
        final Optimisation.Result result = h.model.minimise();
        if (!isUsable(result)) return s3; // the uncanonical point is still a correct answer
        final StageSolve canonical = StageSolve.from(ctx, h);
        return canonical == null ? s3 : canonical;
    }

    /** Position {@code i} of {@code n} mapped into {@code [1, 2]}: a tie-break, not a cost. */
    private static double rank(final int i, final int n) {
        return n <= 1 ? 1.0 : 1.0 + (double) i / (n - 1);
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
        /** Where {@link Ctx#applyPins} took the pin from, for the conflicting-pin report. */
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
        Budget budget;
        final List<String> notes = new ArrayList<>();

        Ctx(final Graph graph, final Map<UUID, Double> extraExtentPins, final Budget budget) {
            this.budget = budget;
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
         * Says so when a node carries targets it cannot all hit. Parallel outputs share one extent,
         * so the largest target sets it and every other one is overproduced - correct, but silent,
         * and a user who typed a number and got a bigger one deserves to be told which number the
         * chart is actually holding to.
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
                        "'" + m.node.machineName
                            + "' output "
                            + t.getKey()
                            + " overshoots its target: "
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
            final long limit = Math.max(MIN_MODEL_MILLIS, Math.min(STAGE_TIME_LIMIT_MILLIS, budget.remaining()));
            model.options.time_abort = limit;
            model.options.time_suffice = limit;
            model.options.integer(IntegerStrategy.DEFAULT.withGapTolerance(NumberContext.of(12, 8)));
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

        /**
         * Stage-1 cost of one gate. A source costs exactly one more than a sink, so equal counts
         * prefer "discard the excess" over "import an intermediate", and the unit has to exceed the
         * gate count so that one extra gate always outweighs every direction preference in the chart
         * put together. At a flat {@link #SNK_WEIGHT}/{@link #SRC_WEIGHT} that stops holding at 1024
         * gates, where 1025 sinks cost exactly what 1024 sources do and the count silently stops
         * dominating. Growing the unit only past that point leaves every chart small enough to have
         * been correct already at exactly its old weights.
         */
        double gateWeight(final int gate) {
            final double unit = Math.max(SNK_WEIGHT, 2.0 * gates.size());
            return gates.get(gate)
                .input() ? unit + 1 : unit;
        }

        /**
         * Objective weight for one connected port's external: its rate measured in crafts of the
         * machine that carries it, rather than in that ingredient's own units.
         *
         * <p>
         * Summing raw external rates adds items/s to millibuckets/s in one number, so "least
         * excess" comes out meaning "least excess that happens to be counted in small units": a
         * chart with the choice voids 2.97/s of an item byproduct rather than 148/s of a fluid one,
         * purely because litres outnumber items, and it will burn several times the raw input to do
         * it. Divided through by the per-craft quantity, both readings are the same number - the
         * unused extent of the machine the slack sits on - which is what they always were.
         *
         * <p>
         * Deliberately NOT the {@code 1/max(1, qty)} the conservation rows use: that clamp is there
         * to condition the constraint matrix and belongs there, but in an objective it silently
         * stops normalizing every sub-unity port, leaving a 0.05-per-craft chanced dust weighted
         * twenty times too heavily.
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

        /** Weighted stage-1 cost of a gate support. */
        double weightedCost(final Set<Integer> support) {
            double cost = 0;
            for (final int g : support) {
                cost += gateWeight(g);
            }
            return cost;
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
         * Every gate the given solution puts any flow through, however little - the set that must
         * stay open for that solution to remain feasible. {@link #gateSupport} is the reporting
         * view of the same data and deliberately ignores negligible flows.
         *
         * <p>
         * The floor is {@link #DUST}, not {@link #ZERO}: a closed port carries solver noise, and
         * counting that as a gate inflates the stage-2 cap by a whole gate's weight, handing stage 2
         * permission to open one more gate than stage 1 proved it needed.
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
         */
        private List<String> wiringDiagnostics(final Attempt attempt, final double tol,
            final List<External> terminalIn) {
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
                final String match = findProduction(src, portComponent[p]);
                if (match != null) {
                    result.add(
                        "'" + machineNameOf(src)
                            + "' sources "
                            + portNameOf(src)
                            + " externally, but an unlinked part of the chart produces it at '"
                            + match
                            + "' - "
                            + MISSING_EDGE);
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
            // Stage 2's objective value, so in crafts rather than in mixed ingredient units - this
            // is what stage 3's cap and the tie tests are compared against. The raw per-ingredient
            // rates a reader wants are rebuilt in Ctx#toSolution.
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

        static StageSolve from(final Ctx ctx, final Handles h) {
            return from(ctx, h, true);
        }

        /**
         * The point this model actually holds, or null when it does not conserve.
         *
         * <p>
         * Every stage is checked, not just the answer: a solve that ran out of time can report a
         * feasible state over a point that breaks its own rows, and the caps, supports and tie
         * comparisons that later stages build on it are then all derived from a fiction. Every
         * caller already handles null as "this stage found nothing", so a lying solver degrades into
         * the fallback path instead of into a wrong chart.
         */
        static StageSolve from(final Ctx ctx, final Handles h, final boolean provenOptimal) {
            final double[] extents = values(h.extentVars());
            final double[] flows = values(h.flowVars());
            final double[] externals = values(h.extVars());
            if (ctx.validate(extents, flows, externals) != null) return null;
            return new StageSolve(ctx, extents, flows, externals, provenOptimal);
        }

        /**
         * Read as solved. Clamping negatives to zero here would hide the one thing worth knowing
         * about a solver that returned a negative flow - {@link Ctx#validate} rejects a solution
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
            this(
                s3 == null ? null : s3.extents,
                s3 == null ? null : s3.flows,
                s3 == null ? null : s3.externals,
                support,
                certified,
                notes,
                failure);
        }

        private Attempt(final double[] extents, final double[] flows, final double[] externals,
            final Set<Integer> support, final boolean certified, final List<String> notes, final String failure) {
            this.extents = extents;
            this.flows = flows;
            this.externals = externals;
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
