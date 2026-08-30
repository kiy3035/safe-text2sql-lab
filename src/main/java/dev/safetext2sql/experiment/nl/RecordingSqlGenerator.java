package dev.safetext2sql.experiment.nl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.safetext2sql.llm.GeneratedSql;
import dev.safetext2sql.llm.SqlGenerationRequest;
import dev.safetext2sql.llm.SqlGenerator;

/**
 * 실제 generator 동작을 바꾸지 않고 호출 번호와 성공적으로 반환된 SQL을 기록하는 장식자다.
 * 실패 예외는 그대로 전달하므로 제품 재시도 정책에 영향을 주지 않는다.
 */
final class RecordingSqlGenerator implements SqlGenerator {

    private final SqlGenerator delegate;
    private final List<GeneratedSqlAttempt> generated = new ArrayList<>();
    private int invocationCount;

    RecordingSqlGenerator(SqlGenerator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public GeneratedSql generate(SqlGenerationRequest request) {
        int attempt = ++invocationCount;
        GeneratedSql result = delegate.generate(request);
        generated.add(new GeneratedSqlAttempt(attempt, result.model(), result.sql()));
        return result;
    }

    int invocationCount() {
        return invocationCount;
    }

    List<GeneratedSqlAttempt> generated() {
        return List.copyOf(generated);
    }
}
