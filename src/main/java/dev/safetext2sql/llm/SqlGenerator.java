package dev.safetext2sql.llm;

@FunctionalInterface
public interface SqlGenerator {

    GeneratedSql generate(SqlGenerationRequest request);
}
