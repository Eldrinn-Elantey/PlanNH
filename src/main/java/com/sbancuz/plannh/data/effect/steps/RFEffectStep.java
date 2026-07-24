package com.sbancuz.plannh.data.effect.steps;

import com.sbancuz.plannh.data.properties.RecipeProperty;

public class RFEffectStep {

    public static final RecipeProperty<Integer> RF_COST = RecipeProperty.<Integer>builder("rf_cost", 0)
        .build();
}
