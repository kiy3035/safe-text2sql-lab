package dev.safetext2sql.experiment.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Aggregate 아래에 중첩된 실제 orders scan 노드와 실행 시간을 추출하는지 검증한다. */
class PlanJsonParserTest {

    @Test
    void extractsNestedRelationMetricsFromPostgresqlExplainJson() throws Exception {
        String json = """
                [{
                  "Plan": {
                    "Node Type": "Aggregate",
                    "Actual Rows": 1,
                    "Plans": [{
                      "Node Type": "Bitmap Heap Scan",
                      "Relation Name": "orders",
                      "Plan Rows": 123,
                      "Actual Rows": 120,
                      "Rows Removed by Filter": 3,
                      "Shared Hit Blocks": 15,
                      "Shared Read Blocks": 2
                    }]
                  },
                  "Planning Time": 0.25,
                  "Execution Time": 1.75
                }]
                """;

        PlanJsonParser.ParsedPlan parsed = new PlanJsonParser().parse(new ObjectMapper().readTree(json));

        assertThat(parsed.scanType()).isEqualTo("Bitmap Heap Scan");
        assertThat(parsed.plannerEstimatedRows()).isEqualTo(123);
        assertThat(parsed.actualRows()).isEqualTo(120);
        assertThat(parsed.resultRows()).isEqualTo(1);
        assertThat(parsed.rowsRemovedByFilter()).isEqualTo(3);
        assertThat(parsed.sharedHitBlocks()).isEqualTo(15);
        assertThat(parsed.sharedReadBlocks()).isEqualTo(2);
        assertThat(parsed.planningTimeMs()).isEqualTo(0.25);
        assertThat(parsed.executionTimeMs()).isEqualTo(1.75);
    }
}
