package com.sbancuz.plannh.gui.summary;

import javax.annotation.Nonnull;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.data.flowchart.Summary.Line;
import com.sbancuz.plannh.data.flowchart.balancer.Severity;
import com.sbancuz.plannh.gui.FlowchartFlow;
import com.sbancuz.plannh.gui.FlowchartWidget;
import com.sbancuz.plannh.gui.GuiHelper;
import com.sbancuz.plannh.gui.PlannhColors;

/**
 * The rows of one section as a vertical list of real MUI2 widgets, one per {@link Line}: hover,
 * tooltips and clicks ride MUI2's own hit-testing instead of row math. When the summary recomputes
 * the rows are swapped out wholesale (the same child-subtree swap {@code DynamicSyncedWidget} does),
 * which lets the layout engine re-sizes the panel through its ordinary dirty chain. The body covers
 * its children so the section always grows to exactly its rows.
 */
class SummaryBody extends FlowchartFlow {

    private static final int LINE_H = 12;
    private static final int TEXT_X = 8;

    private final Summary data;
    private final Graph graph;
    private final Summary.Section section;
    private long rowsBuiltAt = Long.MIN_VALUE;
    private Summary.Mode rowsMode = null;

    SummaryBody(final FlowchartWidget<?, ?> panel, final Summary data, final Graph graph,
        final Summary.Section section) {
        super(GuiAxis.Y, panel);
        this.data = data;
        this.graph = graph;
        this.section = section;
        this.rowsMode = data.computedMode();

        fullWidth().coverChildrenHeight()
            .setEnabledIf(_ -> !data.isSummaryFold(section));

        rebuildRows();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        // Reload both when the chart moves and when the throughput/cycles mode changes (which
        // re-derives the rows but not the graph version).
        final Summary.Mode mode = data.computedMode();
        if (rowsBuiltAt != data.calculatedAt() || rowsMode != mode) {
            rowsBuiltAt = data.calculatedAt();
            rowsMode = mode;
            rebuildRows();
        }
    }

    /** Swap the whole row list for the summary's current lines; child churn re-sizes the panel. */
    private void rebuildRows() {
        removeAll();
        for (final Line<?> line : data.lines(section)) child(row(line));
        scheduleResize();
    }

    private Widget<?> row(final Line<?> line) {
        final FlowchartWidget<?, ?> panel = getFlowchartParent();
        return switch (line) {
            case Line.Measure<?> measure -> new MeasureRow(panel, measure, rowSuffix());
            case Line.Message message -> new TextRow(panel, IKey.str(message.displayName()),
                severityColor(message.note().severity()));
            case Line.Text(String key) -> new TextRow(panel, IKey.lang(key), PlannhColors.SUMMARY_TEXT.getColor());
            case Line.Heading heading -> new TextRow(panel, IKey.str(heading.displayName()),
                PlannhColors.SUMMARY_TEXT_MUTED.getColor());
            case Line.Choice choice -> new ChoiceRow(panel, graph, choice);
            case Line.Totals totals -> new TotalsRow(panel, totals, rowsMode);
        };
    }

    private static int severityColor(final Severity severity) {
        return switch (severity) {
            case ERROR -> PlannhColors.ACCENT_RED.getColor();
            case WARN -> PlannhColors.ACCENT_AMBER.getColor();
            case INFO -> PlannhColors.ACCENT_BLUE.getColor();
        };
    }

    private String rowSuffix() {
        if (section == Summary.Section.MACHINE_COUNTS) return " x";
        return rowsMode == Summary.Mode.THROUGHPUT ? "/s" : " x";
    }

    private static final class TextRow extends FlowchartFlow {

        TextRow(final FlowchartWidget<?, ?> panel, final IKey text, final int color) {
            super(GuiAxis.X, panel);
            fullWidth().coverChildrenHeight(LINE_H)
                .paddingLeft(TEXT_X)
                .child(
                    new TextWidget<>(text).color(color)
                        .textAlign(Alignment.CenterLeft)
                        .fullWidth());
        }
    }

    private static final class MeasureRow extends FlowchartFlow {

        MeasureRow(final FlowchartWidget<?, ?> panel, final Line.Measure<?> measure, final String suffix) {
            super(GuiAxis.X, panel);
            final String raw = measure.displayAmount(measure.amount());
            final String amount = raw.isEmpty() ? "" : raw + suffix;
            fullWidth().coverChildrenHeight(LINE_H)
                .paddingLeft(TEXT_X)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .child(
                    new TextWidget<>(IKey.str(measure.displayName()))
                        .color(
                            amount.isEmpty() ? PlannhColors.ACCENT_BLUE.getColor() : PlannhColors.TEXT_WHITE.getColor())
                        .textAlign(Alignment.CenterLeft));
            if (!amount.isEmpty()) {
                child(
                    new TextWidget<>(IKey.str(amount)).color(PlannhColors.ACCENT_BLUE.getColor())
                        .textAlign(Alignment.CenterRight));
            }
        }
    }

    /**
     * The chart-wide totals: total operations plus the time one full cycle takes. The model only
     * supplies the numbers; the sentence is built and localized here, in the panel, in the shape
     * the current mode wants (cycles: "Time: 240t (12.0s/cycle)", throughput: "Cycle: 12.0s").
     */
    private static final class TotalsRow extends FlowchartFlow {

        TotalsRow(final FlowchartWidget<?, ?> panel, final Line.Totals totals, final Summary.Mode mode) {
            super(GuiAxis.X, panel);
            final String ops = GuiHelper.formatCount(totals.operations());
            final String sec = String.format("%.2f", (double) totals.durationTicks() / GuiHelper.TICKS_PER_SECOND);
            final IKey text = mode == Summary.Mode.THROUGHPUT ? IKey.lang("plannh.summary.totals.throughput", ops, sec)
                : IKey.lang("plannh.summary.totals.cycles", ops, totals.durationTicks(), sec);
            fullWidth().coverChildrenHeight(LINE_H)
                .paddingLeft(TEXT_X)
                .child(
                    new TextWidget<>(text).color(PlannhColors.ACCENT_BLUE.getColor())
                        .textAlign(Alignment.CenterLeft)
                        .fullWidth());
        }
    }

    /** A clickable choice row: the active answer is lead-marked; the reason it gives up on hover. */
    private static final class ChoiceRow extends FlowchartFlow implements Interactable {

        private final Graph graph;
        private final Line.Choice choice;

        ChoiceRow(final FlowchartWidget<?, ?> panel, final Graph graph, final Line.Choice choice) {
            super(GuiAxis.X, panel);
            this.graph = graph;
            this.choice = choice;

            fullWidth().coverChildrenHeight(LINE_H)
                .hoverBackground(new Rectangle().color(0x22FFFFFF))
                .child(
                    new TextWidget<>(IKey.str((choice.active() ? "> " : "  ") + choice.displayName()))
                        .paddingLeft(TEXT_X)
                        .color(
                            choice.active() ? PlannhColors.ACCENT_CYAN2.getColor()
                                : PlannhColors.SUMMARY_TEXT_MUTED.getColor())
                        .textAlign(Alignment.CenterLeft)
                        .fullWidth());

            if (choice.reason() != null) {
                tooltip(
                    new RichTooltip().add(
                        choice.reason()
                            .render()));
            }
        }

        @Override
        public @Nonnull Result onMousePressed(final int mouseButton) {
            if (mouseButton != 0) return Result.IGNORE;
            PlanAPI.recordEdit(graph, () -> graph.setExcessChoice(choice.key()));
            return Result.SUCCESS;
        }
    }
}
