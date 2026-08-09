package com.sbancuz.plannh.data.flowchart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sbancuz.plannh.data.flowchart.AutoBalancer.Alternative;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Alternatives;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.ChoiceKey;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.External;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Rank;
import com.sbancuz.plannh.data.flowchart.AutoBalancer.Solution;
import com.sbancuz.plannh.gui.GuiHelper;

/**
 * What the balancer's answer looks like to a reader, as data rather than as pixels. Everything the
 * canvas and the summary panel say about the chart's boundary is decided here and merely drawn
 * there, which leaves the widgets nothing to do but position and colour pre-built rows.
 *
 * <p>
 * Minecraft-free, so it runs under a plain JUnit. Rates go through {@link GuiHelper#formatRate}
 * rather than a formatter of its own: two authorities for how a rate is spelled is how a summary
 * and a node label start disagreeing about the same flow.
 */
public final class BalanceView {

    private BalanceView() {}

    /** Which side of the chart's boundary a flow sits on, and whether the solver asked for it. */
    public enum Kind {
        /**
         * A gated sink: surplus leaving through a wired port because nothing downstream wanted it
         * all. Deliberately NOT called voided - nothing destroys it, and a player pulls it out of
         * the bus like any other output. It differs from {@link #PRODUCT} only in leaving through a
         * port that has an edge on it, which is why it is worth pointing at and not worth alarming
         * anyone about.
         */
        EXCESS,
        /** A gated source: a shortfall the solver had to invent, injected at this port. */
        IMPORT,
        /** An unwired output: what the chart is for. */
        PRODUCT,
        /** An unwired input: what the chart runs on. */
        SUPPLY
    }

    /**
     * One flow crossing the chart's edge, named by the port it crosses at.
     *
     * @param label ready to draw, e.g. {@code "excess 5.00mB/s Beta Ingot"}.
     */
    public record Boundary(AutoBalancer.PortRef port, Kind kind, double ratePerSecond, String ingredient,
        String label) {}

    /**
     * One answer on offer.
     *
     * @param reason what it gives up against the default, empty for the default itself. A
     *               preference the user cannot read is one they cannot disagree with.
     * @param active whether this is the answer currently on screen.
     */
    public record Choice(ChoiceKey key, String label, String reason, boolean active) {}

    /**
     * The answers to ONE of the chart's independent questions: where a particular surplus goes, or
     * where a particular shortfall comes from.
     *
     * @param heading the ingredient the current answer uses, which is what names the decision.
     */
    public record Group(String heading, List<Choice> rows) {}

    /**
     * @param complete false when the search stopped on its budget or its cap. The caller has to say
     *                 so rather than present a truncated list as the whole truth.
     * @param rows     every row across every group, for callers that only need a flat list to click
     *                 through. Flattened once at construction: the panel reads it twice per frame,
     *                 to measure itself and again to draw.
     */
    public record Choices(List<Group> groups, boolean complete, List<String> notes, List<Choice> rows) {

        Choices(final List<Group> groups, final boolean complete, final List<String> notes) {
            this(groups, complete, notes, flatten(groups));
        }

        private static List<Choice> flatten(final List<Group> groups) {
            final List<Choice> all = new ArrayList<>();
            for (final Group group : groups) {
                all.addAll(group.rows());
            }
            return List.copyOf(all);
        }
    }

    /**
     * Everything crossing the chart's boundary. Reads the balance already on hand and runs no
     * solver of its own, but still builds a list and a label per flow, so callers drawing every
     * frame want {@link Graph#boundary()} rather than this.
     */
    public static List<Boundary> boundary(final Graph graph) {
        final Solution auto = graph.balance()
            .auto();
        if (auto == null) return List.of();
        final List<Boundary> out = new ArrayList<>();
        collect(graph, auto.gatedSinks(), Kind.EXCESS, "excess ", out);
        collect(graph, auto.gatedSources(), Kind.IMPORT, "add ", out);
        collect(graph, auto.terminalOutputs(), Kind.PRODUCT, "", out);
        collect(graph, auto.terminalInputs(), Kind.SUPPLY, "", out);
        return List.copyOf(out);
    }

    /**
     * The answers this chart could equally well have had, each marked with what it gives up and
     * which one is on screen. Rebuilds every row and group from the enumeration behind
     * {@link Graph#alternatives()}, so callers drawing every frame want {@link Graph#choices()}
     * rather than this. Row order within a decision is {@link #sortedRows}.
     */
    public static Choices choices(final Graph graph) {
        final Alternatives alternatives = graph.alternatives();
        // Grouped by the decision each option answers, in the order the solver emitted them, so a
        // chart posing two questions shows two short lists instead of one list of everything.
        final Map<AutoBalancer.PortRef, List<Alternative>> byDecision = new LinkedHashMap<>();
        final Map<AutoBalancer.PortRef, String> headings = new LinkedHashMap<>();
        for (final Alternative option : alternatives.options()) {
            byDecision.computeIfAbsent(option.replaces(), k -> new ArrayList<>())
                .add(option);
            if (option.isCurrent()) headings.put(option.replaces(), describe(graph, option));
        }
        final List<Group> groups = new ArrayList<>();
        for (final Map.Entry<AutoBalancer.PortRef, List<Alternative>> entry : byDecision.entrySet()) {
            // A decision with only its current answer under it is not a decision. Showing it would
            // put a heading above a row that repeats the heading, and imply a choice that is not
            // being offered.
            if (entry.getValue()
                .size() < 2) continue;
            final List<Choice> rows = new ArrayList<>();
            for (final Alternative option : sortedRows(entry.getValue())) {
                rows.add(new Choice(option.key(), describe(graph, option), reasonOf(option), option.isCurrent()));
            }
            groups.add(new Group(headings.getOrDefault(entry.getKey(), ""), List.copyOf(rows)));
        }
        return new Choices(List.copyOf(groups), alternatives.complete(), alternatives.notes());
    }

    /**
     * Reading order for one decision's answers: the one on screen first, because it is what the
     * chart is currently doing and every other row is read against it. The rest are what could
     * replace it, sorted to be compared rather than to argue - deliberately NOT the solver's
     * preference order - with what they would bring in ahead of what they would let go of, each
     * ascending by how much crosses.
     */
    private static List<Alternative> sortedRows(final List<Alternative> options) {
        final List<Alternative> sorted = new ArrayList<>(options);
        sorted.sort(
            Comparator.comparing((final Alternative o) -> !o.isCurrent())
                .thenComparing(o -> !isImport(o))
                .thenComparingDouble(BalanceView::crossingRate)
                .thenComparing(Alternative::key));
        return sorted;
    }

    /** Whether this answer brings the ingredient in, as opposed to letting a surplus out. */
    private static boolean isImport(final Alternative option) {
        return !option.externals()
            .isEmpty() && option.externals()
                .get(0)
                .port()
                .input();
    }

    /** How much crosses the boundary under this answer, summed over the ports it uses. */
    private static double crossingRate(final Alternative option) {
        double rate = 0;
        for (final External e : option.externals()) {
            rate += e.ratePerSecond();
        }
        return rate;
    }

    /** Whether there is anything to choose between - cheap enough to ask every frame. */
    public static boolean hasChoices(final Graph graph) {
        final Solution auto = graph.balance()
            .auto();
        return auto != null && auto.openGates() > 0;
    }

    private static void collect(final Graph graph, final List<External> externals, final Kind kind, final String verb,
        final List<Boundary> out) {
        for (final External e : externals) {
            final String ingredient = ingredientOf(graph, e);
            if (ingredient == null) continue;
            out.add(
                new Boundary(
                    e.port(),
                    kind,
                    e.ratePerSecond(),
                    ingredient,
                    verb + rateOf(graph, e) + " " + ingredient));
        }
    }

    /** "excess 5.00/s Beta Ingot" - what this option does at the one port that distinguishes it. */
    private static String describe(final Graph graph, final Alternative option) {
        // One gate can cross at several ports of the same ingredient; "75/s water, 90/s water" is
        // two ports, not two decisions, so it reads as one number.
        final Map<String, double[]> merged = new LinkedHashMap<>();
        External sample = null;
        for (final External e : option.externals()) {
            final String ingredient = ingredientOf(graph, e);
            if (ingredient == null) continue;
            if (sample == null) sample = e;
            merged.computeIfAbsent(ingredient, k -> new double[1])[0] += e.ratePerSecond();
        }
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, double[]> entry : merged.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(
                option.externals()
                    .get(0)
                    .port()
                    .input() ? "add " : "excess ")
                .append(rateOf(graph, sample, entry.getValue()[0]))
                .append(' ')
                .append(entry.getKey());
        }
        return sb.length() == 0 ? "nothing crosses the boundary" : sb.toString();
    }

    /**
     * A rate spelled the way the summary panel spells it, units and all: the ingredient's own
     * formatter decides whether 530 reads as "530" or "530mB", and a chip that disagrees with the
     * summary about the same flow is worse than a chip with no units at all.
     */
    private static String rateOf(final Graph graph, final External external) {
        return rateOf(graph, external, external.ratePerSecond());
    }

    /** As above, but for a rate summed over several ports that share one ingredient. */
    private static String rateOf(final Graph graph, final External at, final double rate) {
        final Port<?> port = at == null ? null : portOf(graph, at);
        return (port == null ? GuiHelper.formatRate((float) rate)
            : port.getType()
                .formatAmount((float) rate))
            + "/s";
    }

    /**
     * The ingredient at an external's port, or null when the port no longer exists - a recipe can
     * lose a slot between a solve and a redraw, and a missing name is a row to skip rather than a
     * crash.
     */
    private static String ingredientOf(final Graph graph, final External external) {
        final Port<?> port = portOf(graph, external);
        return port == null ? null : port.getDisplayName();
    }

    private static Port<?> portOf(final Graph graph, final External external) {
        final Node node = graph.nodes.get(
            external.port()
                .nodeId());
        if (node == null) return null;
        final List<Port<?>> ports = external.port()
            .input() ? node.inputs : node.outputs;
        final int index = external.port()
            .portIndex();
        return index < 0 || index >= ports.size() ? null : ports.get(index);
    }

    /**
     * The one sentence explaining why an answer is not the default. {@link Rank#VOIDS_MORE} needs
     * two sentences of its own: throwing more away and importing more are both "more external
     * quantity" to the solver, but calling an import "voids more" is wrong in front of a user.
     */
    public static String reasonOf(final Alternative option) {
        if (option.rank() != Rank.VOIDS_MORE) return reasonOf(option.rank());
        boolean voids = false;
        boolean imports = false;
        for (final External e : option.externals()) {
            if (e.port()
                .input()) {
                imports = true;
            } else {
                voids = true;
            }
        }
        if (voids && imports) return "crosses the boundary more";
        return imports ? "imports more" : "leaves more excess";
    }

    /** The direction-blind wording, for a rank with no externals to look at. */
    public static String reasonOf(final Rank rank) {
        return switch (rank) {
            case DEFAULT -> "";
            case EQUALLY_VALID -> "equally valid";
            case IMPORTS_INSTEAD -> "imports instead of leaving a surplus";
            case MOVES_MORE -> "moves more material";
            case MOVES_LESS -> "moves less material";
            case VOIDS_MORE -> "leans on the outside more";
        };
    }
}
