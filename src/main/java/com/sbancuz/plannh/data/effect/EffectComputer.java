package com.sbancuz.plannh.data.effect;

import java.util.Map;

import com.sbancuz.plannh.data.RecipeContext;

@FunctionalInterface
public interface EffectComputer {

    EffectResult compute(Map<String, Object> settings, RecipeContext ctx);

    default EffectComputer andThen(EffectStep step) {
        return (s, ctx) -> step.apply(this.compute(s, ctx), s, ctx);
    }
}
