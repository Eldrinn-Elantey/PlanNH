package com.sbancuz.plannh.data.flowchart;

import java.util.Random;
import java.util.Set;
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

    public static final int GROUP_MIN_W = 300;
    public static final int GROUP_MIN_H = 200;

    private static Random colorRandom = new Random(12345);

    private int width = GROUP_MIN_W;
    private int height = GROUP_MIN_H;
    private int color = getRandomColor();
    private boolean collapsed;
    private boolean clampNodes;
    private boolean coverChildren;
    /**
     * The player's statement that the recipes framed here run on the same machines, so their
     * machine counts add up per type in the header. An old chart reads back as false, which is the
     * behaviour it already had.
     */
    private boolean machineSharing;
    /**
     * How many machines the shared pool is allowed to be, or 0 for as many as it takes. This is
     * the only part of a sharing group the solver reads: a positive capacity caps the group's
     * summed machine time, so the chart is balanced to fit the hardware that exists instead of
     * being measured after the fact. Ignored while {@link #machineSharing} is off.
     */
    private int machineCapacity;
    /**
     * Sorted for the same reason the graph's own maps are, and for one more: Gson builds a
     * SortedMap field as a TreeMap but a Map keyed on anything but String as an insertion-ordered
     * LinkedTreeMap, so the declared type here is what makes a reloaded group iterate like a
     * built one.
     */
    @NotNull
    private final SortedMap<UUID, GraphData> children = new TreeMap<>();
    @NotNull
    private final Set<UUID> nodeIds = new java.util.HashSet<>();

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
