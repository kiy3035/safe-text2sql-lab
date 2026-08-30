package dev.safetext2sql.llm.support;

import dev.safetext2sql.llm.GeneratedSql;
import dev.safetext2sql.llm.SqlGenerationRequest;
import dev.safetext2sql.llm.SqlGenerator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ScriptedSqlGenerator implements SqlGenerator {

    private static final String SCRIPTED_MODEL = "scripted-test-model";

    private final Deque<Step> remainingSteps;
    private final List<SqlGenerationRequest> requests = new ArrayList<>();

    private ScriptedSqlGenerator(List<Step> steps) {
        this.remainingSteps = new ArrayDeque<>(steps);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public GeneratedSql generate(SqlGenerationRequest request) {
        requests.add(request);
        var step = remainingSteps.pollFirst();
        if (step == null) {
            throw new IllegalStateException("No scripted SQL generation step remains");
        }
        return step.execute();
    }

    public List<SqlGenerationRequest> requests() {
        return List.copyOf(requests);
    }

    public int remainingStepCount() {
        return remainingSteps.size();
    }

    @FunctionalInterface
    private interface Step {
        GeneratedSql execute();
    }

    public static final class Builder {

        private final List<Step> steps = new ArrayList<>();

        public Builder thenReturn(String sql) {
            steps.add(() -> new GeneratedSql(sql, SCRIPTED_MODEL));
            return this;
        }

        public Builder thenThrow(RuntimeException exception) {
            steps.add(() -> {
                throw exception;
            });
            return this;
        }

        public ScriptedSqlGenerator build() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("At least one scripted step is required");
            }
            return new ScriptedSqlGenerator(steps);
        }
    }
}
