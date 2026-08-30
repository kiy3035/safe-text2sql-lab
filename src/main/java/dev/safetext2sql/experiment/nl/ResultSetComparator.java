package dev.safetext2sql.experiment.nl;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 생성 SQL과 Golden SQL의 결과값을 비교한다.
 *
 * <p>JDBC 드라이버가 같은 숫자를 {@code Long}, {@code BigInteger}, {@code BigDecimal} 등으로
 * 반환할 수 있으므로 모든 숫자는 scale을 제거한 {@link BigDecimal}로 정규화한다. UNORDERED도
 * 단순 집합으로 바꾸지 않고 행별 출현 횟수를 비교해 중복 행 손실을 정답으로 오인하지 않는다.</p>
 */
public final class ResultSetComparator {

    /** 컬럼 이름이나 SQL 문자열이 아니라 행의 값과 지정된 순서 의미만 비교한다. */
    public boolean matches(
            ResultComparisonMode mode,
            List<List<Object>> expectedRows,
            List<List<Object>> actualRows
    ) {
        Objects.requireNonNull(mode, "mode");
        List<List<Object>> expected = normalizeRows(expectedRows);
        List<List<Object>> actual = normalizeRows(actualRows);

        return switch (mode) {
            case SCALAR -> isScalar(expected) && isScalar(actual)
                    && expected.getFirst().getFirst().equals(actual.getFirst().getFirst());
            case ORDERED -> expected.equals(actual);
            case UNORDERED -> frequencies(expected).equals(frequencies(actual));
        };
    }

    private boolean isScalar(List<List<Object>> rows) {
        return rows.size() == 1 && rows.getFirst().size() == 1;
    }

    private List<List<Object>> normalizeRows(List<List<Object>> rows) {
        Objects.requireNonNull(rows, "rows");
        List<List<Object>> normalized = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> normalizedRow = new ArrayList<>(row.size());
            for (Object value : row) {
                normalizedRow.add(normalizeValue(value));
            }
            normalized.add(List.copyOf(normalizedRow));
        }
        return List.copyOf(normalized);
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return NullValue.INSTANCE;
        }
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
            return decimal.signum() == 0 ? BigDecimal.ZERO : decimal;
        }
        if (value instanceof TemporalAccessor
                || value instanceof java.util.Date
                || value instanceof Character
                || value instanceof Enum<?>) {
            return value.toString();
        }
        return value;
    }

    private Map<List<Object>, Integer> frequencies(List<List<Object>> rows) {
        Map<List<Object>, Integer> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.merge(row, 1, Integer::sum));
        return counts;
    }

    /** null을 Map key 안에서도 값 문자열과 구분해 표현하는 내부 표식이다. */
    private enum NullValue {
        INSTANCE
    }
}
