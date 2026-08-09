package com.sbancuz.plannh.data.flowchart;

import static com.sbancuz.plannh.gui.GroupWidget2.GROUP_MIN_H;
import static com.sbancuz.plannh.gui.GroupWidget2.GROUP_MIN_W;

import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.utils.Color;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Group extends GraphData {

    private static Random colorRandom = new Random(12345);

    private int width = GROUP_MIN_W;
    private int height = GROUP_MIN_H;
    private int color = getRandomColor();
    private boolean collapsed;
    private boolean clampNodes;
    private boolean coverChildren;
    /**
     * Sorted for the same reason the graph's own maps are, and for one more: Gson builds a
     * SortedMap field as a TreeMap but a plain Map keyed on anything but String as an
     * insertion-ordered LinkedTreeMap, so a group used to iterate one way when built and another
     * way after a save and reload.
     */
    @NotNull
    private final SortedMap<UUID, GraphData> children = new TreeMap<>();

    public Group() {
        super(UUID.randomUUID());
    }

    @Override
    public String getType() {
        return "group";
    }

    private int getRandomColor() {
        return Color.argb(colorRandom.nextFloat(), colorRandom.nextFloat(), colorRandom.nextFloat(), 0.5f);
    }
}
