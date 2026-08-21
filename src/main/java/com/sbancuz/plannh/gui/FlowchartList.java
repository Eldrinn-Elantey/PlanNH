package com.sbancuz.plannh.gui;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widgets.ListWidget;

/**
 * ListWidget only re-clamps the scroll offset on child churn; a resize that moves the visible
 * size (the zoom-dependent max, folded sections) must do the same or a bottom-anchored offset
 * renders past the panel.
 */
public final class FlowchartList extends ListWidget<IWidget, FlowchartList> {

    /**
     * This is to fix a bug where, when the scrollbar is at the bottom and you zoom out
     * the area becomes bigger than needed
     */
    @Override
    public boolean postLayoutWidgets() {
        final boolean done = super.postLayoutWidgets();
        getScrollData().clamp(getScrollArea());
        return done;
    }
}
