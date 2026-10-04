package io.github.tis199.gamecraft.engine.ai;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/** Difficulty-scaled choice policy for games whose full search tree is too large. */
public final class BotPolicy {
    private BotPolicy() { }

    public static <T> T choose(List<T> choices, String difficulty, ToDoubleFunction<T> score) {
        if (choices.isEmpty()) return null;
        String level = difficulty == null ? "medium" : difficulty.toLowerCase(java.util.Locale.ROOT);
        if (level.equals("easy")) {
            return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        }
        T best = choices.stream().max(Comparator.comparingDouble(score)).orElse(choices.get(0));
        if (level.equals("medium") && choices.size() > 1 && ThreadLocalRandom.current().nextDouble() < 0.35) {
            return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        }
        if (level.equals("hard") && choices.size() > 1 && ThreadLocalRandom.current().nextDouble() < 0.08) {
            return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        }
        return best;
    }
}
