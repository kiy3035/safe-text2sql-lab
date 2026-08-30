package dev.safetext2sql.llm.prompt;

import dev.safetext2sql.llm.SqlGenerationRequest;
import java.util.StringJoiner;

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

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

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
