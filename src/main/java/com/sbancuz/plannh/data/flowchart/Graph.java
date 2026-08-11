package com.sbancuz.plannh.data.flowchart;

import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

public class Graph {

    // TODO make these use getters
    /**
     * Sorted, so the graph hands its contents back in id order and every consumer that needs a
     * reproducible answer gets one without sorting first - the solver, the serializer, the router
     * and the layout all read these directly.
     */
    public final SortedMap<UUID, Node> nodes = new TreeMap<>();
    public final SortedMap<UUID, Edge> edges = new TreeMap<>();
    public final SortedMap<UUID, Note> notes = new TreeMap<>();
    public final SortedMap<UUID, Group> groups = new TreeMap<>();

    @Getter
    @Setter
    private float zoom = 1f;
    @Getter
    @Setter
    private float panX;
    @Getter
    @Setter
    private float panY;
    @Getter
    @Setter
    private boolean snapToGrid;

    @Getter
    private Balancer.BalanceMode balanceMode = Balancer.BalanceMode.AUTO;
    @Getter
    private boolean opsMode;

    /**
     * Which of the equally-workable answers the user picked, or null for the solver's own. Applied
     * as a preference, never as a constraint: a key that no longer fits the chart is dropped with a
     * note rather than allowed to degrade it.
     */
    @Getter
    private AutoBalancer.ChoiceKey excessChoice;

    private Balancer.BalanceResult balance = null;
    private Summary summary = null;
    /**
     * The two display views, built on first ask after a solve rather than with it: the canvas wants
     * the boundary every frame and never the choices, the summary panel wants the reverse.
     */
    private List<BalanceView.Boundary> boundaryView = null;
    private BalanceView.Choices choicesView = null;

    private boolean dirty = true;

    public void markDirty() {
        dirty = true;
    }

    public void setExcessChoice(final AutoBalancer.ChoiceKey choice) {
        excessChoice = choice;
        markDirty();
    }

    public void setBalanceMode(final Balancer.BalanceMode mode) {
        balanceMode = mode;
        markDirty();
    }

    public void setOpsMode(final boolean opsMode) {
        this.opsMode = opsMode;
        markDirty();
    }

    public void removeNode(final UUID id) {
        nodes.remove(id);
        edges.values()
            .removeIf(e -> e.sourceNodeId.equals(id) || e.targetNodeId.equals(id));
        markDirty();
    }

    public Balancer.BalanceResult balance() {
        if (dirty) {
            balance = Balancer.balance(this, balanceMode, opsMode);
            summary = Summary.compute(balance, this, opsMode);
            boundaryView = null;
            choicesView = null;
            dirty = false;
        }
        return balance;
    }

    /**
     * Every answer that balances this chart as well as the one on screen. Produced by the same pass
     * that produced the balance, so asking costs nothing beyond the solve that already happened.
     */
    public AutoBalancer.Alternatives alternatives() {
        final AutoBalancer.Alternatives computed = balance().alternatives();
        return computed == null ? new AutoBalancer.Alternatives(null, List.of(), true, List.of()) : computed;
    }

    public Summary summary() {
        balance(); // ensure up-to-date
        return summary;
    }

    /**
     * Everything crossing the chart's boundary. Held from solve to solve because the canvas asks
     * once per frame and the answer only moves when the chart does.
     */
    public List<BalanceView.Boundary> boundary() {
        balance(); // drops a view built before the last edit
        if (boundaryView == null) boundaryView = BalanceView.boundary(this);
        return boundaryView;
    }

    /** The equally-workable answers, grouped by the question each one answers. Cached as above. */
    public BalanceView.Choices choices() {
        balance();
        if (choicesView == null) choicesView = BalanceView.choices(this);
        return choicesView;
    }

    public Collection<Node> getNodes() {
        return nodes.values();
    }

    public void addNode(final Node node) {
        nodes.put(node.id, node);
        markDirty();
    }

    public Collection<Edge> getEdges() {
        return edges.values();
    }

    public void addEdge(final Edge edge) {
        // A source-port/target-port pair carries at most one edge: re-wiring it replaces the
        // existing edge instead of stacking a duplicate.
        edges.values()
            .removeIf(
                e -> e.sourceNodeId.equals(edge.sourceNodeId) && e.sourceOutputIndex == edge.sourceOutputIndex
                    && e.targetNodeId.equals(edge.targetNodeId)
                    && e.targetInputIndex == edge.targetInputIndex);
        edges.put(edge.id, edge);
        markDirty();
    }

    public void removeEdge(final UUID id) {
        edges.remove(id);
        markDirty();
    }

    /**
     * First input port of {@code dst} that accepts {@code src}'s given output; -1 when none is
     * compatible.
     */
    public int findCompatibleInput(final Node src, final int srcOutIdx, final Node dst) {
        if (src == dst || srcOutIdx < 0 || srcOutIdx >= src.outputs.size()) return -1;
        final Port<?> out = src.outputs.get(srcOutIdx);
        for (int i = 0; i < dst.inputs.size(); i++) {
            if (out.canConnect(dst.inputs.get(i))) return i;
        }
        return -1;
    }

    public void removeGroup(final UUID id) {
        groups.remove(id);
    }

    public Collection<Group> getGroups() {
        return groups.values();
    }

    public Collection<Note> getNotes() {
        return notes.values();
    }
}
