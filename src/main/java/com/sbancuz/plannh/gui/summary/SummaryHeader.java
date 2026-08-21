package com.sbancuz.plannh.gui.summary;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.CycleButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.data.flowchart.Summary.Line;
import com.sbancuz.plannh.data.flowchart.balancer.Note;
import com.sbancuz.plannh.gui.FlowchartFlow;
import com.sbancuz.plannh.gui.FlowchartWidget;
import com.sbancuz.plannh.gui.PlannhColors;

/**
 * The title row of one summary section: a section-colored highlight bar, the section's name (with
 * a row count for the content sections), and a fold toggle wired straight into the {@link
 * Summary}'s fold bitmask. The CHOICES header carries the budget notes as a hover tooltip.
 */
class SummaryHeader extends ParentWidget<SummaryHeader> {

    public static final int HEADER_H = 18;
    private static final int ACCENT_W = 3;

    protected SummaryHeader(final FlowchartWidget<?, ?> panel, final Summary data, final Summary.Section section,
        final int accent, final int titleTextColor) {
        fullWidth().height(HEADER_H)
            .background(new Rectangle().color(PlannhColors.SUMMARY_HEADER_BG.getColor()));

        child(
            FlowchartFlow.row(panel)
                .fullWidth()
                .fullHeight()
                .childPadding(4)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                .child(
                    FlowchartFlow.row(panel)
                        .coverChildren()
                        .childPadding(4)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                        .child(accentStrip(accent))
                        .child(new TextWidget<>(headerTitle(section, data)).color(titleTextColor)))
                .child(foldToggle(data, section)));

        child(
            new Widget<>().fullWidth()
                .height(1)
                .background(new Rectangle().color(PlannhColors.SUMMARY_SEPARATOR.getColor())));

        if (section == Summary.Section.CHOICES) {
            tooltipDynamic(tooltip -> {
                for (final Note note : data.choiceNotes()) {
                    tooltip.add(note.render())
                        .newLine();
                }
            });
        }
    }

    static CycleButtonWidget foldToggle(final Summary data, final Summary.Section section) {
        return new CycleButtonWidget().stateCount(2)
            .size(HEADER_H, HEADER_H)
            .stateOverlay(true, IKey.str("V"))
            .stateOverlay(false, IKey.str("^"))
            .value(new BoolValue.Dynamic(() -> data.isSummaryFold(section), val -> data.setSummaryFold(section, val)));
    }

    private static IKey headerTitle(final Summary.Section section, final Summary data) {
        final IKey title = IKey.lang(section.titleKey());
        if (section == Summary.Section.HELP) return title;
        return IKey.dynamicKey(() -> IKey.comp(title, IKey.str(" (" + count(section, data) + ")")));
    }

    private static int count(final Summary.Section section, final Summary data) {
        if (section == Summary.Section.CHOICES) {
            return (int) data.lines(section)
                .stream()
                .filter(Line.Choice.class::isInstance)
                .count();
        }
        return data.lineCount(section);
    }

    private static Widget<?> accentStrip(final int color) {
        return new Widget<>().size(ACCENT_W, HEADER_H)
            .background(new Rectangle().color(color));
    }
}
