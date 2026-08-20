package com.sbancuz.plannh.gui.summary;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.EnumValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.CycleButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.gui.CanvasWidget;
import com.sbancuz.plannh.gui.FlowchartFlow;
import com.sbancuz.plannh.gui.FlowchartWidget;
import com.sbancuz.plannh.gui.PlannhColors;

/**
 * The summary panel: a draggable canvas widget that reads one chart's {@link Summary} - a title
 * bar with the master collapse (fold {@link Summary.Section#ALL}), then one {@link SummarySection} per
 * content bucket in reading order. It never re-sorts and never runs a solver; every row was
 * decided by {@link Summary#recompute}. When a newer solve moves the summary it just re-lays-out.
 */
public class SummaryWidget extends FlowchartWidget<SummaryWidget, Summary> {

    private static final int WIDTH = 200;
    private static final int TITLE_H = SummaryHeader.HEADER_H;
    private static final int SECTION_PADDING = 4;

    public SummaryWidget(final CanvasWidget canvas, final Summary data) {
        super(canvas, data);

        coverChildren();

        final Flow sections = FlowchartFlow.col(this)
            .fullWidth()
            .coverChildrenHeight()
            .collapseDisabledChild()
            .setEnabledIf(_ -> !data.isSummaryFold(Summary.Section.ALL))
            .child(
                new SummarySection(
                    this,
                    Summary.Section.OUTPUTS,
                    PlannhColors.SECTION_PRODUCT.getColor(),
                    PlannhColors.ACCENT_AMBER.getColor()))
            .child(
                new SummarySection(
                    this,
                    Summary.Section.INPUTS,
                    PlannhColors.SECTION_INPUT.getColor(),
                    PlannhColors.ACCENT_GREEN2.getColor()))
            .child(
                new SummarySection(
                    this,
                    Summary.Section.PROPERTIES,
                    PlannhColors.SECTION_OPS.getColor(),
                    PlannhColors.ACCENT_BLUE.getColor()))
            .child(
                new SummarySection(
                    this,
                    Summary.Section.CHOICES,
                    PlannhColors.SECTION_CHOICE.getColor(),
                    PlannhColors.ACCENT_CYAN2.getColor()))
            .child(
                new SummarySection(
                    this,
                    Summary.Section.MACHINE_COUNTS,
                    PlannhColors.SECTION_OPS.getColor(),
                    PlannhColors.ACCENT_BLUE.getColor()))
            .child(
                new SummarySection(
                    this,
                    Summary.Section.MESSAGES,
                    PlannhColors.SECTION_WARN.getColor(),
                    PlannhColors.ACCENT_YELLOW.getColor()))
            .child(
                new SummarySection(
                    this,
                    Summary.Section.HELP,
                    PlannhColors.SECTION_FLUID_OUT.getColor(),
                    PlannhColors.TEXT_LIGHT.getColor()));

        child(
            FlowchartFlow.col(this)
                .width(WIDTH)
                .padding(4)
                .childPadding(SECTION_PADDING)
                .coverChildrenHeight()
                .crossAxisAlignment(Alignment.CrossAxis.START)
                .collapseDisabledChild()
                .background(
                    new Rectangle().color(PlannhColors.SUMMARY_BG.getColor()),
                    new Rectangle().cornerRadius(2)
                        .hollow(1)
                        .color(PlannhColors.SUMMARY_BORDER.getColor()))
                .child(
                    FlowchartFlow.row(this)
                        .fullWidth()
                        .height(TITLE_H)
                        .childPadding(4)
                        .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                        .background(new Rectangle().color(PlannhColors.SUMMARY_TITLE_BG.getColor()))
                        .child(
                            new TextWidget<>(IKey.lang(Summary.Section.ALL.titleKey()))
                                .color(PlannhColors.TEXT_WHITE.getColor()))
                        .child(
                            FlowchartFlow.row(this)
                                .coverChildren()
                                .childPadding(2)
                                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                                .child(modeToggle(this, data))
                                .child(SummaryHeader.foldToggle(data, Summary.Section.ALL))))
                .child(
                    new Widget<>().fullWidth()
                        .height(1)
                        .background(new Rectangle().color(PlannhColors.SUMMARY_TITLE_LINE.getColor())))
                .child(sections));
    }

    private static CycleButtonWidget modeToggle(final FlowchartWidget<?, ?> panel, final Summary data) {
        final Plan plan = Plan.getInstance();
        return new CycleButtonWidget().size(64, TITLE_H)
            .tooltipStatic(t -> t.addLine(IKey.lang("plannh.summary.mode.title")))
            .value(new EnumValue.Dynamic<>(Summary.Mode.class, plan::getMode, val -> {
                plan.setMode(val);
                data.recompute(
                    panel.getCanvas()
                        .getGraph());
            }))
            .stateOverlay(Summary.Mode.CYCLES, IKey.lang("plannh.summary.mode.cycles"))
            .stateOverlay(Summary.Mode.THROUGHPUT, IKey.lang("plannh.summary.mode.throughput"));
    }
}
