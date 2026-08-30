package dev.safetext2sql.experiment.nl;

/** 난이도별 정답 수와 정확도다. */
public record DifficultyAccuracy(int total, int correct, double accuracy) {
}
