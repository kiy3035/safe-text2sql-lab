package dev.safetext2sql.experiment.index;

import com.fasterxml.jackson.databind.JsonNode;

/** PostgreSQL EXPLAIN JSON에서 비교에 필요한 계획 노드와 시간을 추출한다. */
final class PlanJsonParser {

    ParsedPlan parse(JsonNode rootArray) {
        JsonNode envelope = rootArray.get(0);
        JsonNode rootPlan = envelope.path("Plan");
        JsonNode relationPlan = findRelation(rootPlan, "orders");
        if (rootPlan.isMissingNode() || relationPlan == null) {
            throw new IllegalStateException("EXPLAIN JSON does not contain an orders plan node");
        }
        return new ParsedPlan(
                relationPlan.path("Node Type").asText("UNKNOWN"),
                relationPlan.path("Plan Rows").asDouble(),
                relationPlan.path("Actual Rows").asDouble(),
                rootPlan.path("Actual Rows").asDouble(),
                relationPlan.path("Rows Removed by Filter").asDouble(),
                relationPlan.path("Shared Hit Blocks").asLong(),
                relationPlan.path("Shared Read Blocks").asLong(),
                envelope.path("Planning Time").asDouble(),
                envelope.path("Execution Time").asDouble()
        );
    }

    private JsonNode findRelation(JsonNode node, String relationName) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (relationName.equals(node.path("Relation Name").asText())) {
            return node;
        }
        for (JsonNode child : node.path("Plans")) {
            JsonNode found = findRelation(child, relationName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    record ParsedPlan(
            String scanType,
            double plannerEstimatedRows,
            double actualRows,
            double resultRows,
            double rowsRemovedByFilter,
            long sharedHitBlocks,
            long sharedReadBlocks,
            double planningTimeMs,
            double executionTimeMs
    ) {
    }
}
