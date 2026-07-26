package com.sbancuz.plannh.data.effect.steps;

import com.sbancuz.plannh.data.properties.RecipeProperty;
import com.sbancuz.plannh.data.properties.SummaryProperty;

public class CoFHCompat {

    public static final RecipeProperty<Integer> RF_COST = SummaryProperty.<Integer>builder("rf_cost", 0)
        .build();

    public static final RecipeProperty<Integer> RF_PER_T = SummaryProperty.<Integer>builder("rf_cost", 0)
        .build();
}
