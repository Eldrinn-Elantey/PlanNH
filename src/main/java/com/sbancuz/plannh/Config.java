package com.sbancuz.plannh;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class Config {

    /** Dev diagnostic: log a headless repro of every arrow-routing recompute. */
    public static boolean debugRouteDump = false;

    /**
     * How long the balancer is allowed to look for a better answer, as a percentage of the tuned
     * default. One number rather than one per budget, because the budgets are not independent -
     * halving the time a solve gets and leaving the tie enumeration at five candidates only means
     * running out of time inside the enumeration.
     *
     * <p>
     * The ceiling is 200 and not more because AUTO solves from {@code draw()}: a chart that takes
     * its whole budget freezes the GUI for it, and 40 seconds is already past what anyone would
     * read as anything but a hang.
     */
    public static int solverEffortPercent = 100;

    public static final int SOLVER_EFFORT_MIN = 25;
    public static final int SOLVER_EFFORT_MAX = 200;

    /**
     * {@link #solverEffortPercent} as the solver reads it. Clamped rather than trusted: Forge
     * clamps what it parses out of the config file, but the field is public and nothing stops a
     * later caller from assigning to it, and an unclamped percentage multiplies a 20-second budget.
     */
    public static int solverEffort() {
        return Math.max(SOLVER_EFFORT_MIN, Math.min(SOLVER_EFFORT_MAX, solverEffortPercent));
    }

    public static void synchronizeConfiguration(final File configFile) {
        final Configuration configuration = new Configuration(configFile);

        debugRouteDump = configuration.getBoolean(
            "debugRouteDump",
            "debug",
            false,
            "Log a replayable dump of the arrow-routing input on every route recompute");

        solverEffortPercent = configuration.getInt(
            "solverEffortPercent",
            "solver",
            100,
            SOLVER_EFFORT_MIN,
            SOLVER_EFFORT_MAX,
            "How long AUTO balancing may spend looking for a better answer, as a percentage of the"
                + " default. Lower gives up sooner on big charts; higher makes them balance better"
                + " and the GUI pause longer, because the solve runs while the screen draws.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
