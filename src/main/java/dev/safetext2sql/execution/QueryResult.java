package dev.safetext2sql.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Native Query 결과를 API에서 직렬화할 수 있는 행·열 구조로 표현한다.
 *
 * <p>SQL 결과에는 {@code null}이 정상적으로 포함될 수 있어 {@link List#copyOf} 대신
 * null을 허용하는 방어적 복사를 사용한다. 각 행의 값 개수는 검증된 projection 개수와
 * 같아야 하므로 구조가 어긋나면 실행기 내부 오류로 처리한다.</p>
 *
 * @param columns 검증된 SELECT의 결과 컬럼명
 * @param rows 최대 행 상한이 적용된 결과
 * @param truncated 실제 결과가 행 상한을 초과했는지 여부
 */
public record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {

    public QueryResult {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }

        Objects.requireNonNull(rows, "rows");
        List<List<Object>> copiedRows = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            Objects.requireNonNull(row, "row");
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("row width must match columns");
            }
            copiedRows.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(copiedRows);
    }
}
