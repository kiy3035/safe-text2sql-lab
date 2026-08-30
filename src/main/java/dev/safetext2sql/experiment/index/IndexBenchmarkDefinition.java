package dev.safetext2sql.experiment.index;

import java.util.List;

/**
 * 로컬 benchmark DB에만 적용할 후보 인덱스와 대표 조회 세 건이다.
 *
 * <p>DDL과 EXPLAIN 대상 SQL을 코드의 고정 상수로 관리해 사용자 입력이 관리 명령에 연결되지
 * 않게 한다. 일반 Text2SQL 검증 gate는 계속 EXPLAIN과 DDL을 거부한다.</p>
 */
public record IndexBenchmarkDefinition(
        String indexName,
        String createIndexSql,
        String dropIndexSql,
        List<IndexBenchmarkCase> cases
) {

    public IndexBenchmarkDefinition {
        cases = List.copyOf(cases);
    }

    /** 주문 상태와 기간 필터·정렬 패턴을 위한 대표 후보를 반환한다. */
    public static IndexBenchmarkDefinition defaultDefinition() {
        return new IndexBenchmarkDefinition(
                "idx_experiment_orders_status_ordered_at",
                "CREATE INDEX idx_experiment_orders_status_ordered_at "
                        + "ON analytics.orders(status, ordered_at)",
                "DROP INDEX IF EXISTS analytics.idx_experiment_orders_status_ordered_at",
                List.of(
                        new IndexBenchmarkCase(
                                "IDX-001",
                                "상태와 한 달 기간으로 주문 건수 집계",
                                "SELECT COUNT(order_id) FROM analytics.orders "
                                        + "WHERE status = 'PAID' "
                                        + "AND ordered_at >= TIMESTAMPTZ '2024-07-01 00:00:00+00' "
                                        + "AND ordered_at < TIMESTAMPTZ '2024-08-01 00:00:00+00'"
                        ),
                        new IndexBenchmarkCase(
                                "IDX-002",
                                "상태와 기간 필터 뒤 시간순 상위 100건 조회",
                                "SELECT order_id, ordered_at FROM analytics.orders "
                                        + "WHERE status = 'SHIPPED' "
                                        + "AND ordered_at >= TIMESTAMPTZ '2024-04-01 00:00:00+00' "
                                        + "AND ordered_at < TIMESTAMPTZ '2024-10-01 00:00:00+00' "
                                        + "ORDER BY ordered_at LIMIT 100"
                        ),
                        new IndexBenchmarkCase(
                                "IDX-003",
                                "상태와 반기 기간으로 주문 금액 합계",
                                "SELECT SUM(total_amount) FROM analytics.orders "
                                        + "WHERE status = 'CANCELLED' "
                                        + "AND ordered_at >= TIMESTAMPTZ '2024-01-01 00:00:00+00' "
                                        + "AND ordered_at < TIMESTAMPTZ '2024-07-01 00:00:00+00'"
                        )
                )
        );
    }
}
