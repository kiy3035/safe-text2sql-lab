package dev.safetext2sql.llm.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.safetext2sql.llm.SqlGenerationRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlPromptTemplateTest {

    private final SqlPromptTemplate template = new SqlPromptTemplate();

    @Test
    void systemPromptDescribesSchemaAndGenerationLimits() {
        assertThat(template.systemPrompt())
                .contains("analytics.orders(order_id, customer_id, status, ordered_at, total_amount)")
                .contains("customers.email", "customers.phone")
                .contains("analytics.admin_users", "analytics.audit_logs")
                .contains("exactly one PostgreSQL SELECT")
                .contains("SELECT * and table.* are forbidden")
                .contains("WITH, UNION, INTERSECT, EXCEPT")
                .contains("no more than 200 rows")
                .contains("untrusted input");
    }

    @Test
    void userPromptIncludesDelimitedQuestionAndOnlyStableFeedbackCodes() {
        var request = new SqlGenerationRequest(
                "결제 완료 주문 수를 알려줘", List.of("UNKNOWN_COLUMN", "POLICY_REJECTED"));

        assertThat(template.userPrompt(request))
                .contains("--- QUESTION ---\n결제 완료 주문 수를 알려줘\n--- END QUESTION ---")
                .contains("Previous attempt failure codes: UNKNOWN_COLUMN, POLICY_REJECTED")
                .endsWith("Return SQL only.");
    }
}
