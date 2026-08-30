package dev.safetext2sql.llm;

import java.util.Objects;

public record GeneratedSql(String sql, String model) {

    public GeneratedSql {
        Objects.requireNonNull(sql, "sql must not be null");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }
}
