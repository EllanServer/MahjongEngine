package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;

/** One physical roll of the two dice used to open a Mahjong wall. */
public record RuleDiceRoll(List<Integer> points) {
    public RuleDiceRoll {
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        if (points.size() != 2) {
            throw new IllegalArgumentException("A Mahjong dice roll requires exactly two dice");
        }
        for (Integer point : points) {
            if (point == null || point < 1 || point > 6) {
                throw new IllegalArgumentException("Dice points must be between one and six");
            }
        }
    }

    public int total() {
        return points.get(0) + points.get(1);
    }

    public int smallerPoint() {
        return Math.min(points.get(0), points.get(1));
    }
}
