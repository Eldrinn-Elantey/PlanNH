package com.sbancuz.plannh.gui.summary;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.EnumValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.CycleButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.gtnewhorizon.gtnhlib.color.ColorResource;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.gui.CanvasWidget;
import com.sbancuz.plannh.gui.FlowchartFlow;
import com.sbancuz.plannh.gui.FlowchartList;
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
    private static final int PADDING = 4;
    private static final int CORNER_RADIUS = 2;
    private static final int SECTION_GAP = 4;
    private static final int TITLE_INSET_X = 4;
    private static final int BUTTON_GAP = 2;
    private static final int SCROLLBAR_GAP = 4;
    private static final int SCREEN_MARGIN = 10;
    private static final int MIN_VIEWPORT_H = 45;
    private static final int PANEL_CHROME = SummaryHeader.HEADER_H + 1 + SECTION_GAP * 4 + SCREEN_MARGIN;

    public SummaryWidget(final CanvasWidget canvas, final Summary data) {
        super(canvas, data);

        coverChildren();

        child(
            FlowchartFlow.col(this)
                .width(WIDTH)
                .padding(PADDING)
                .childPadding(SECTION_GAP)
                .coverChildrenHeight()
                .crossAxisAlignment(Alignment.CrossAxis.START)
                .collapseDisabledChild()
                .background(
                    new Rectangle().color(PlannhColors.SUMMARY_BG.getColor()),
                    new Rectangle().cornerRadius(CORNER_RADIUS)
                        .hollow(1)
                        .color(PlannhColors.SUMMARY_BORDER.getColor()))
                .child(
                    FlowchartFlow.row(this)
                        .fullWidth()
                        .height(SummaryHeader.HEADER_H)
                        .paddingLeft(TITLE_INSET_X)
                        .childPadding(PADDING)
                        .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                        .background(new Rectangle().color(PlannhColors.SUMMARY_TITLE_BG.getColor()))
                        .child(
                            new TextWidget<>(IKey.lang(Summary.Section.ALL.titleKey()))
                                .color(PlannhColors.TEXT_WHITE.getColor()))
                        .child(
                            FlowchartFlow.row(this)
                                .coverChildren()
                                .childPadding(BUTTON_GAP)
                                .collapseDisabledChild()
                                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                                .child(modeToggle(this, data))
                                .child(rateToggle(data))
                                .child(SummaryHeader.foldToggle(data, Summary.Section.ALL))))
                .child(
                    new Widget<>().fullWidth()
                        .height(1)
                        .background(new Rectangle().color(PlannhColors.SUMMARY_TITLE_LINE.getColor())))
                .child(
                    new FlowchartList().fullWidth()
                        .paddingRight(SCROLLBAR_GAP)
                        .crossAxisAlignment(Alignment.CrossAxis.START)
                        .scrollDirection(new VerticalScrollData())
                        .setEnabledIf(_ -> !data.isSummaryFold(Summary.Section.ALL))
                        .maxSize(
                            () -> Math.max(
                                MIN_VIEWPORT_H,
                                canvas.getArea().height / canvas.getGraph()
                                    .getZoom() - PANEL_CHROME))
                        .child(sec(Summary.Section.OUTPUTS, PlannhColors.SECTION_PRODUCT, PlannhColors.ACCENT_AMBER))
                        .child(sec(Summary.Section.INPUTS, PlannhColors.SECTION_INPUT, PlannhColors.ACCENT_GREEN2))
                        .child(sec(Summary.Section.PROPERTIES, PlannhColors.SECTION_OPS, PlannhColors.ACCENT_BLUE))
                        .child(sec(Summary.Section.CHOICES, PlannhColors.SECTION_CHOICE, PlannhColors.ACCENT_CYAN2))
                        .child(sec(Summary.Section.MACHINE_COUNTS, PlannhColors.SECTION_OPS, PlannhColors.ACCENT_BLUE))
                        .child(sec(Summary.Section.MESSAGES, PlannhColors.SECTION_WARN, PlannhColors.ACCENT_YELLOW))
                        .child(sec(Summary.Section.HELP, PlannhColors.SECTION_FLUID_OUT, PlannhColors.TEXT_LIGHT))));
    }

    private SummarySection sec(final Summary.Section section, final ColorResource accent, final ColorResource text) {
        return new SummarySection(this, section, accent.getColor(), text.getColor()).marginBottom(SECTION_GAP);
    }

    private static CycleButtonWidget rateToggle(final Summary data) {
        final CycleButtonWidget toggle = new CycleButtonWidget().size(SummaryHeader.HEADER_H, SummaryHeader.HEADER_H)
            .tooltipStatic(
                t -> t.addLine(IKey.lang("plannh.summary.rate.title"))
                    .addLine(IKey.lang("plannh.summary.mode.switch_hint")))
            .value(new EnumValue.Dynamic<>(Summary.RateUnit.class, data::rateUnit, data::rateUnit))
            .setEnabledIf(_ -> data.mode() == Summary.Mode.THROUGHPUT);
        for (final Summary.RateUnit unit : Summary.RateUnit.VALUES) {
            toggle.stateOverlay(unit, IKey.lang(unit.langKey));
        }
        return toggle;
    }

    private static CycleButtonWidget modeToggle(final FlowchartWidget<?, ?> panel, final Summary data) {
        return new CycleButtonWidget().size(SummaryHeader.HEADER_H, SummaryHeader.HEADER_H)
            .tooltipStatic(
                t -> t.addLine(IKey.lang("plannh.summary.mode.title"))
                    .addLine(IKey.lang("plannh.summary.mode.switch_hint")))
            .value(new EnumValue.Dynamic<>(Summary.Mode.class, data::mode, val -> {
                data.mode(val);
                data.recompute(
                    panel.getCanvas()
                        .getGraph());
            }))
            .stateOverlay(Summary.Mode.CYCLES, IKey.lang("plannh.summary.mode.cycles.short"))
            .stateOverlay(Summary.Mode.THROUGHPUT, IKey.lang("plannh.summary.mode.throughput.short"));
    }
}
