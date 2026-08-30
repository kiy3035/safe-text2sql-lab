package dev.safetext2sql.experiment.nl;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 문자열 대신 결과 의미를 비교하는 세 가지 모드를 검증한다. */
class ResultSetComparatorTest {

    private final ResultSetComparator comparator = new ResultSetComparator();

    @Test
    void scalarNormalizesJdbcNumericTypesButRejectsNonScalarShapes() {
        assertThat(comparator.matches(
                ResultComparisonMode.SCALAR,
                List.of(List.of(10L)),
                List.of(List.of(new BigDecimal("10.00")))
        )).isTrue();
        assertThat(comparator.matches(
                ResultComparisonMode.SCALAR,
                List.of(List.of(10L)),
                List.of(List.of(10L), List.of(10L))
        )).isFalse();
    }

    @Test
    void orderedRequiresTheSameRowOrder() {
        List<List<Object>> expected = List.of(List.of(1L), List.of(2L));
        List<List<Object>> reversed = List.of(List.of(2L), List.of(1L));

        assertThat(comparator.matches(ResultComparisonMode.ORDERED, expected, expected)).isTrue();
        assertThat(comparator.matches(ResultComparisonMode.ORDERED, expected, reversed)).isFalse();
    }

    @Test
    void unorderedIgnoresOrderButPreservesDuplicateCounts() {
        List<List<Object>> expected = List.of(List.of("A"), List.of("A"), List.of("B"));
        List<List<Object>> reordered = List.of(List.of("B"), List.of("A"), List.of("A"));
        List<List<Object>> missingDuplicate = List.of(List.of("A"), List.of("B"));

        assertThat(comparator.matches(ResultComparisonMode.UNORDERED, expected, reordered)).isTrue();
        assertThat(comparator.matches(ResultComparisonMode.UNORDERED, expected, missingDuplicate)).isFalse();
    }
}
