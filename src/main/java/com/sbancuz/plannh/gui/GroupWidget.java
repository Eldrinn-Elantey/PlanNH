package com.sbancuz.plannh.gui;

import static com.sbancuz.plannh.data.flowchart.Group.GROUP_MIN_W;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.DynamicDrawable;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.Color;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ColorPickerDialog;
import com.cleanroommc.modularui.widgets.CycleButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Group;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;

import lombok.Getter;

public final class GroupWidget extends GroupableWidget<GroupWidget, Group> {

    @Getter
    private final GroupAreaWidget areaWidget;

    public GroupWidget(CanvasWidget canvas, Group data) {
        super(canvas, data);
        areaWidget = new GroupAreaWidget(this);

        coverChildren(GROUP_MIN_W, 0);

        background(
            new DynamicDrawable(
                () -> new Rectangle().hollow(2)
                    .color(data.getColor())));

        data.getChildren()
            .values()
            .forEach(subData -> {
                GroupableWidget<?, ?> widget = GroupableWidget.getFlowchartWidgetFromData(canvas, subData);
                widget.dataContainer = data.getChildren();
                areaWidget.child(widget);
            });

        IPanelHandler colorPicker = IPanelHandler
            .simple(canvas.getPanel(), (_, _) -> new ColorPickerDialog(data::setColor, data.getColor(), true), true);

        Flow mainColumn = FlowchartFlow.column()
            .coverChildren(GROUP_MIN_W, 0)
            .collapseDisabledChild();

        Flow topRow = FlowchartFlow.row(this)
            .coverChildrenHeight()
            .fullWidth()
            .background(new DynamicDrawable(() -> new Rectangle().color(data.getColor())));

        topRow.child(new HeaderTextWidget(this, data::getColor));

        Flow buttonRow = FlowchartFlow.row(this)
            .coverChildren()
            .childPadding(2)
            .reverseLayout();

        buttonRow.child(new CloseButtonWidget(this))
            .child(new ToggleButton().value(new BoolValue.Dynamic(data::isMachineSharing, val -> {
                data.setMachineSharing(val);
                resolve();
            }))
                .overlay(
                    IKey.str("MS")
                        .color(Color.WHITE.main))
                .addTooltipLine(IKey.lang("plannh.gui.group.machine_sharing")))
            .child(capacityButton("+", 1))
            .child(capacityButton("-", -1))
            .child(new ToggleButton().value(new BoolValue.Dynamic(data::isCoverChildren, val -> {
                data.setCoverChildren(val);
                if (val) canvas.fitGroupToChildren(data);
                areaWidget.configureCoverChildren();
            }))
                .overlay(
                    IKey.str("CC")
                        .color(Color.WHITE.main))
                .addTooltipLine("Toggle Cover Children"))
            .child(
                new ButtonWidget<>().overlay(GuiTextures.COLOR_WHEEL)
                    .onMousePressed(_ -> {
                        if (!colorPicker.isPanelOpen()) colorPicker.openPanel();
                        else colorPicker.closePanel();
                        return true;
                    }))
            .child(
                new CycleButtonWidget().stateCount(2)
                    .stateOverlay(true, IKey.str("^"))
                    .stateOverlay(false, IKey.str("V"))
                    .value(new BoolValue.Dynamic(data::isCollapsed, val -> {
                        boolean was = data.isCollapsed();
                        data.setCollapsed(val);
                        scheduleResize();
                        canvas.setGroupNodesVisible(data.getId(), !val);
                        if (was && !val) canvas.rebuildNodeWidgets();
                    })));

        topRow.child(loadLabel());
        topRow.child(buttonRow);

        mainColumn.child(topRow);
        mainColumn.child(areaWidget);

        child(mainColumn);
    }

    /**
     * Steps the pool's capacity, floored at zero, which is the group's "as many machines as it
     * takes" and the state every chart starts in. The capacity is a solve input, so a step is an
     * edit like any other: recorded for undo and version-bumped so the chart re-balances under it.
     */
    private ButtonWidget<?> capacityButton(final String label, final int step) {
        return new ButtonWidget<>().overlay(
            IKey.str(label)
                .color(Color.WHITE.main))
            .onMousePressed(_ -> {
                PlanAPI.recordEdit(canvas.getGraph(), () -> {
                    getData().setMachineCapacity(Math.max(0, getData().getMachineCapacity() + step));
                    resolve();
                });
                return true;
            })
            .addTooltipLine(IKey.lang("plannh.gui.group.machine_capacity"));
    }

    /** Marks the chart for a fresh solve; a pool's capacity and membership are model inputs. */
    private void resolve() {
        canvas.getGraph()
            .markDirty();
    }

    /**
     * The group's machine load: for every machine type inside the frame, the counts of its nodes
     * summed. A node's count is already machine-time - a recipe filling a third of a machine reads
     * 0.33 - so the sum is what one type has to be able to run at once if the group's recipes share
     * hardware. Nothing here constrains the solve; the group is the player's own statement that
     * these recipes run on the same machines, and the header just adds up what the nodes already
     * say. Sorted by load so the busiest type leads, name breaking ties.
     */
    private List<Map.Entry<String, Double>> machineLoad() {
        if (!getData().isMachineSharing()) return List.of();
        final Graph graph = canvas.getGraph();
        final BalanceResult balance = graph.balance();
        if (balance == null) return List.of();
        final Map<String, Double> byMachine = new HashMap<>();
        for (final UUID nodeId : getData().getNodeIds()) {
            final Node node = graph.nodes.get(nodeId);
            if (node == null) continue;
            final Balancer.NodeBalance nb = balance.nodeBalances()
                .get(nodeId);
            if (nb == null || nb.operations() <= 0) continue;
            byMachine.merge(node.machineName, nb.operations(), Double::sum);
        }
        final List<Map.Entry<String, Double>> out = new ArrayList<>(byMachine.entrySet());
        out.sort(
            Map.Entry.<String, Double>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        return out;
    }

    /**
     * The load next to the group's name: the count alone for a single machine type, a type count
     * when there are several. Machine names are as long as GregTech feels like, and the row already
     * holds the name field and the buttons, so the header carries the number and the names live in
     * the tooltip, which is pinned below the row - the default position sits beside the widget and
     * trims every line to whatever screen width is left there. Empty when the group does not claim
     * machine sharing or holds no solved nodes, which collapses the widget away.
     */
    private TextWidget<?> loadLabel() {
        return new TextWidget<>(IKey.dynamic(() -> {
            final List<Map.Entry<String, Double>> load = machineLoad();
            if (load.isEmpty()) return "";
            final int capacity = getData().getMachineCapacity();
            final String used = load.size() == 1 ? "×" + GuiHelper.formatCount(
                load.get(0)
                    .getValue())
                : StatCollector.translateToLocalFormatted("plannh.gui.group.machine_types", load.size());
            return capacity > 0 ? used + " / " + capacity : used;
        })).color(PlannhColors.SUMMARY_TEXT_MUTED.getColor())
            .textAlign(Alignment.CenterRight)
            .tooltipPos(RichTooltip.Pos.BELOW)
            .tooltipAutoUpdate(true)
            .tooltipDynamic(tooltip -> {
                for (final Map.Entry<String, Double> entry : machineLoad()) {
                    tooltip.add("×" + GuiHelper.formatCount(entry.getValue()) + " " + entry.getKey())
                        .newLine();
                }
            });
    }

    @Override
    public void removeFromGraph() {
        getChildren().stream()
            .filter(w -> w instanceof GroupableWidget<?, ?>)
            .map(w -> (GroupableWidget<?, ?>) w)
            .forEach(GroupableWidget::removeFromGraph);
        super.removeFromGraph();
    }

    @Override
    protected Map<UUID, Group> getDefaultContainer() {
        final Map<UUID, Group> out = new HashMap<>();
        for (final Group g : canvas.getGraph()
            .getGroups()) out.put(g.getId(), g);
        return out;
    }

    public int getMouseGroupX() {
        ModularGuiContext context = getContext();

        return getScreen().getPanelManager()
            .getAllHoveredWidgetsList(false)
            .stream()
            .filter(locatedWidget -> locatedWidget.getElement() == areaWidget)
            .findFirst()
            .orElseThrow()
            .getTransformationMatrix()
            .unTransformX(context.getAbsMouseX(), context.getAbsMouseY());
    }

    public int getMouseGroupY() {
        ModularGuiContext context = getContext();

        return getScreen().getPanelManager()
            .getAllHoveredWidgetsList(false)
            .stream()
            .filter(locatedWidget -> locatedWidget.getElement() == areaWidget)
            .findFirst()
            .orElseThrow()
            .getTransformationMatrix()
            .unTransformY(context.getAbsMouseX(), context.getAbsMouseY());
    }
}
