package com.sbancuz.plannh.gui.summary;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.gui.FlowchartFlow;
import com.sbancuz.plannh.gui.FlowchartWidget;
import com.sbancuz.plannh.gui.PlannhColors;

final class MeasureRow extends FlowchartFlow {

    private static final float DISPLAY_NAME_RATIO = 2/3f;

    MeasureRow(final FlowchartWidget<?, ?> panel, final Summary.Line.Measure<?> measure, final String suffix) {
        super(GuiAxis.X, panel);
        final String raw = measure.displayAmount(measure.amount());
        final String amount = raw.isEmpty() ? "" : raw + suffix;
        fullWidth().coverChildrenHeight(SummaryBody.LINE_H)
            .paddingLeft(SummaryBody.TEXT_X)
            .padding(0, 2)
            .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
            .hoverBackground(new Rectangle().color(PlannhColors.SUMMARY_ROW_HOVER.getColor()))
            .child(
                new TextWidget<>(IKey.str(measure.displayName()))
                    .color(amount.isEmpty() ? PlannhColors.ACCENT_BLUE.getColor() : PlannhColors.TEXT_WHITE.getColor())
                    .textAlign(Alignment.CenterLeft).widthRel(DISPLAY_NAME_RATIO))
            .child(
                new TextWidget<>(IKey.str(amount)).color(PlannhColors.ACCENT_BLUE.getColor())
                    .textAlign(Alignment.CenterRight).widthRel(1 - DISPLAY_NAME_RATIO));
    }
}
