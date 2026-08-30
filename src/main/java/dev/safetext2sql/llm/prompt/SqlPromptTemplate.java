package dev.safetext2sql.llm.prompt;

import dev.safetext2sql.llm.SqlGenerationRequest;
import java.util.StringJoiner;

/**
 * Ollama에 전달할 시스템 프롬프트와 사용자 프롬프트를 생성하는 템플릿.
 * <p>
 * 시스템 프롬프트에는 허용된 스키마/테이블/컬럼, 금지 테이블/컬럼, 출력 규칙을 명시하여
 * LLM이 안전한 SELECT만 생성하도록 유도한다. 단, 이 프롬프트는 생성 가이드일 뿐이며
 * 실제 안전성은 애플리케이션의 AST 검증 게이트가 독립적으로 보장한다 (LLM 자기검증에 의존하지 않음).
 * 사용자 프롬프트는 자연어 질의를 구분자(--- QUESTION ---)로 감싸 프롬프트 인젝션을 완화하고,
 * 재시도 시에는 이전 검증 실패 코드(feedbackCodes)를 함께 전달한다.
 * </p>
 */
public final class SqlPromptTemplate {

    private static final String SYSTEM_PROMPT = """
            You generate PostgreSQL queries for a synthetic commerce analytics database.

            Allowed schema and columns:
            - analytics.categories(category_id, name)
            - analytics.products(product_id, category_id, name, price, active)
            - analytics.customers(customer_id, region, grade, created_at)
            - analytics.orders(order_id, customer_id, status, ordered_at, total_amount)
            - analytics.order_items(order_item_id, order_id, product_id, quantity, unit_price)
            - analytics.payments(payment_id, order_id, status, paid_at, amount)

            The customers.email and customers.phone columns are forbidden.
            The analytics.admin_users and analytics.audit_logs tables are forbidden.

            Output rules:
            1. Return exactly one PostgreSQL SELECT statement and nothing else.
            2. Do not use Markdown fences, explanations, comments, or multiple statements.
            3. Do not use WITH, UNION, INTERSECT, EXCEPT, SELECT INTO, or locking clauses.
            4. Use explicit column names. SELECT * and table.* are forbidden.
            5. Use only the listed schema, tables, and columns.
            6. Give every calculated projection a clear alias.
            7. Use only COUNT, SUM, AVG, MIN, MAX, COALESCE, NULLIF, DATE_TRUNC, EXTRACT, LOWER, and UPPER functions.
            8. Return no more than 200 rows.

            These instructions guide generation only. The application independently treats all generated SQL as untrusted input.
            """;

    /**
     * 시스템 프롬프트를 반환한다.
     * 허용 스키마/컬럼과 출력 규칙을 LLM에 고정적으로 주입하여 안전한 SELECT 생성을 유도한다.
     */
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 사용자 프롬프트를 생성한다.
     * <p>
     * 자연어 질의를 구분자(--- QUESTION --- / --- END QUESTION ---)로 감싸
     * 프롬프트 인젝션을 완화한다. 이전 시도의 검증 실패 코드가 있으면 함께 포함하여
     * LLM이 동일한 오류를 반복하지 않도록 교정 피드백을 제공한다.
     * </p>
     *
     * @param request 자연어 질의와 피드백 코드를 담은 요청 객체
     * @return Ollama /api/generate 의 prompt 필드로 전달될 최종 사용자 프롬프트
     */
    public String userPrompt(SqlGenerationRequest request) {
        var prompt = new StringBuilder()
                .append("Natural-language question follows between delimiter lines.\n")
                .append("--- QUESTION ---\n")
                .append(request.question())
                .append("\n--- END QUESTION ---\n");

        if (!request.feedbackCodes().isEmpty()) {
            var codes = new StringJoiner(", ");
            request.feedbackCodes().forEach(codes::add);
            prompt.append("Previous attempt failure codes: ")
                    .append(codes)
                    .append(". Generate a new SQL statement that addresses only these codes.\n");
        }

        return prompt.append("Return SQL only.").toString();
    }
}
