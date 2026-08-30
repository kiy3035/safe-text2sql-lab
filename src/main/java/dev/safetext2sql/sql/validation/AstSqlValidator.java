package dev.safetext2sql.sql.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JsonAggregateFunction;
import net.sf.jsqlparser.expression.JsonFunction;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.NumericBind;
import net.sf.jsqlparser.expression.OracleHint;
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TranscodingFunction;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.expression.XMLSerializeExpr;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Fetch;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FunctionAllColumns;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;

import static dev.safetext2sql.sql.validation.AnalyticsSqlPolicy.normalize;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.AMBIGUOUS_COLUMN;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.COLUMN_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.CTE_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.DUPLICATE_PROJECTION;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.DUPLICATE_RELATION;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.EMPTY_SQL;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.FUNCTION_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.JOIN_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.LIMIT_EXCEEDED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.LOCKING_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.MULTIPLE_STATEMENTS;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.PARSE_REJECTED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.PROJECTION_ALIAS_REQUIRED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.QUERY_COMPLEXITY_EXCEEDED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.QUERY_TOO_LONG;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.SCHEMA_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.SELECT_INTO_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.SET_OPERATION_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.STATEMENT_NOT_SELECT;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.TABLE_NOT_ALLOWED;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.UNSUPPORTED_EXPRESSION;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.UNSUPPORTED_SELECT;
import static dev.safetext2sql.sql.validation.SqlRejectionReason.WILDCARD_NOT_ALLOWED;

/**
 * JSqlParser AST 기반 SQL 검증기 - 본 프로젝트의 보안 핵심 게이트.
 * <p>
 * 정규식·키워드 검사가 아닌 파싱된 AST 전체를 재귀적으로 순회하며 다음을 강제한다:
 * </p>
 * <ul>
 *   <li>단일 SELECT 문만 허용 (다중 문장, DDL/DML, CTE, UNION 등 거부)</li>
 *   <li>모든 테이블·컬럼·함수 참조를 Allowlist와 비교 (SELECT/JOIN/WHERE/GROUP BY/HAVING/ORDER BY/서브쿼리 전체)</li>
 *   <li>SELECT * / table.* / COUNT(*) 외 와일드카드 거부, 계산 컬럼은 별칭 필수</li>
 *   <li>스키마 한정 이름은 analytics만 허용, 대소문자·인용 식별자·별칭 우회 차단</li>
 *   <li>LIMIT/FETCH 행 수 상한(200), JOIN 개수(4), 서브쿼리 깊이(3), SQL 길이(10,000) 제한</li>
 *   <li>파싱 실패·미지원 구문·식별자 해석 불가 시 fail-closed (무조건 거부)</li>
 * </ul>
 * <p>
 * 검증에 성공한 경우에만 {@link ValidatedSelect}를 반환하며, 이 객체만이 실행 경계에서
 * {@code EntityManager.createNativeQuery(...)}로 전달될 수 있다.
 * </p>
 */
public final class AstSqlValidator implements SqlValidator {

    private final AnalyticsSqlPolicy policy;
    /** WHERE/HAVING/SELECT 등 모든 표현식을 재귀 검증하는 방문자. */
    private final SafeExpressionVisitor expressionVisitor = new SafeExpressionVisitor();

    public AstSqlValidator(AnalyticsSqlPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * 신뢰할 수 없는 SQL 문자열을 검증하여 실행 가능한 {@link ValidatedSelect}로 변환한다.
     *
     * @param untrustedSql LLM이 생성한 원문 SQL (null/blank 불가)
     * @return 검증을 통과한 단일 SELECT 문
     * @throws SqlValidationException 정책 위반 또는 파싱 실패 시 (fail-closed)
     */
    @Override
    public ValidatedSelect validate(String untrustedSql) {
        String sql = normalizeInput(untrustedSql);
        Statements parsed = parse(sql);
        if (parsed.size() != 1) {
            reject(MULTIPLE_STATEMENTS);
        }

        Statement statement = parsed.get(0);
        if (!(statement instanceof Select)) {
            reject(STATEMENT_NOT_SELECT);
        }
        Select select = (Select) statement;

        List<String> projections = validateSelect(select, null, 0, true);
        return new ValidatedSelect(sql, projections);
    }

    /**
     * 입력 문자열을 정규화한다: trim, 마크다운 펜스(```sql ... ```) 제거, 길이 제한 검사.
     * 펜스 형식이 비정상이거나 중첩된 경우 파싱 거부로 처리하여 LLM의 장식적 출력을 차단한다.
     */
    private String normalizeInput(String untrustedSql) {
        if (untrustedSql == null || untrustedSql.isBlank()) {
            reject(EMPTY_SQL);
        }

        String sql = untrustedSql.trim();
        if (sql.startsWith("```")) {
            int headerEnd = sql.indexOf('\n');
            if (headerEnd < 0 || !sql.substring(3, headerEnd).trim().equalsIgnoreCase("sql") || !sql.endsWith("```")) {
                reject(PARSE_REJECTED);
            }
            sql = sql.substring(headerEnd + 1, sql.length() - 3).trim();
            if (sql.contains("```") || sql.isBlank()) {
                reject(PARSE_REJECTED);
            }
        }

        if (sql.length() > policy.maxSqlLength()) {
            reject(QUERY_TOO_LONG);
        }
        return sql;
    }

    /**
     * JSqlParser로 SQL을 파싱한다. 파싱 예외나 런타임 예외는 모두 PARSE_REJECTED로 변환하여 fail-closed 처리한다.
     */
    private Statements parse(String sql) {
        try {
            return CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException | RuntimeException exception) {
            reject(PARSE_REJECTED);
            throw new AssertionError("unreachable", exception);
        }
    }

    /**
     * SELECT 문 전체를 검증한다. 서브쿼리 깊이 제한, CTE/집합연산/잠금 구문 거부 후
     * PlainSelect 또는 괄호로 감싼 서브쿼리로 분기하여 재귀 검증한다.
     */
    private List<String> validateSelect(Select select, Scope outerScope, int depth, boolean requireNamedProjection) {
        if (depth > policy.maxSubqueryDepth()) {
            reject(QUERY_COMPLEXITY_EXCEEDED);
        }
        validateCommonSelectClauses(select);

        if (select instanceof SetOperationList) {
            reject(SET_OPERATION_NOT_ALLOWED);
        }
        if (select instanceof Values) {
            reject(UNSUPPORTED_SELECT);
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            if (parenthesedSelect.getPivot() != null
                    || parenthesedSelect.getUnPivot() != null
                    || parenthesedSelect.getSampleClause() != null
                    || (parenthesedSelect.getOrderByElements() != null && !parenthesedSelect.getOrderByElements().isEmpty())
                    || parenthesedSelect.getLimit() != null
                    || parenthesedSelect.getOffset() != null
                    || parenthesedSelect.getFetch() != null
                    || parenthesedSelect.getSelect() == null) {
                reject(UNSUPPORTED_SELECT);
            }
            return validateSelect(parenthesedSelect.getSelect(), outerScope, depth, requireNamedProjection);
        }
        if (!(select instanceof PlainSelect)) {
            reject(UNSUPPORTED_SELECT);
        }
        PlainSelect plainSelect = (PlainSelect) select;
        return validatePlainSelect(plainSelect, outerScope, depth, requireNamedProjection);
    }

    /**
     * 모든 SELECT 변형에 공통으로 적용되는 절을 검증한다: CTE, 잠금, LIMIT/FETCH/OFFSET.
     */
    private void validateCommonSelectClauses(Select select) {
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            reject(CTE_NOT_ALLOWED);
        }
        if (select.getForClause() != null
                || select.getForMode() != null
                || select.getForUpdateTable() != null
                || select.getWait() != null
                || select.isNoWait()
                || select.isSkipLocked()) {
            reject(LOCKING_NOT_ALLOWED);
        }
        if (select.getLimitBy() != null
                || select.getIsolation() != null
                || select.isOracleSiblings()
                || select.getPivot() != null
                || select.getUnPivot() != null) {
            reject(UNSUPPORTED_SELECT);
        }
        validateLimit(select.getLimit());
        validateFetch(select.getFetch());
        if (select.getOffset() != null) {
            Expression offset = select.getOffset().getOffset();
            if (!(offset instanceof LongValue value) || value.getValue() < 0 || select.getOffset().getOffsetParam() != null) {
                reject(UNSUPPORTED_SELECT);
            }
        }
    }

    /**
     * PlainSelect(가장 일반적인 SELECT 형태)를 검증한다.
     * FROM/JOIN 스코프를 구성한 뒤, 프로젝션 → WHERE → GROUP BY → HAVING → ORDER BY 순으로
     * 모든 식별자를 Allowlist와 대조한다. JOIN 조건은 스코프 구성 후에 별도로 검증한다.
     */
    private List<String> validatePlainSelect(
            PlainSelect select,
            Scope outerScope,
            int depth,
            boolean requireNamedProjection
    ) {
        rejectUnsupportedPlainSelectFeatures(select);
        if (select.getFromItem() == null || select.getSelectItems() == null || select.getSelectItems().isEmpty()) {
            reject(UNSUPPORTED_SELECT);
        }

        Scope scope = new Scope(outerScope);
        registerFromItem(select.getFromItem(), scope, depth);
        List<Join> joins = select.getJoins() == null ? List.of() : select.getJoins();
        if (joins.size() > policy.maxJoins()) {
            reject(QUERY_COMPLEXITY_EXCEEDED);
        }
        for (Join join : joins) {
            validateJoinShape(join);
            registerFromItem(join.getRightItem(), scope, depth);
        }
        for (Join join : joins) {
            validateJoinConditions(join, scope, depth);
        }

        List<String> projections = validateProjections(select.getSelectItems(), scope, depth, requireNamedProjection);
        scope.setProjectionAliases(projections);

        validateExpression(select.getWhere(), new ExpressionContext(scope, depth, false));
        validateGroupBy(select.getGroupBy(), scope, depth);
        validateExpression(select.getHaving(), new ExpressionContext(scope, depth, false));
        validateOrderBy(select.getOrderByElements(), scope, depth);
        return projections;
    }

    /**
     * PlainSelect에서 허용하지 않는 DB 고유 기능들을 일괄 거부한다.
     * SELECT INTO, LATERAL VIEW, TOP, QUALIFY, Oracle Hint 등 합성 DB에서 불필요하거나 우회 경로가 될 수 있는 구문을 모두 차단한다.
     */
    private void rejectUnsupportedPlainSelectFeatures(PlainSelect select) {
        if ((select.getIntoTables() != null && !select.getIntoTables().isEmpty()) || select.getIntoTempTable() != null) {
            reject(SELECT_INTO_NOT_ALLOWED);
        }
        if ((select.getLateralViews() != null && !select.getLateralViews().isEmpty())
                || select.isUsingFinal()
                || select.isUsingOnly()
                || select.isUseWithNoLog()
                || select.getSampleClause() != null
                || select.getOptimizeFor() != null
                || select.getTop() != null
                || select.getSkip() != null
                || select.getMySqlHintStraightJoin()
                || select.getFirst() != null
                || select.getBigQuerySelectQualifier() != null
                || select.getQualify() != null
                || select.getOracleHierarchical() != null
                || select.getPreferringClause() != null
                || select.getOracleHint() != null
                || select.getForXmlPath() != null
                || select.getKsqlWindow() != null
                || select.isEmitChanges()
                || (select.getWindowDefinitions() != null && !select.getWindowDefinitions().isEmpty())
                || select.getMySqlSqlCalcFoundRows()
                || select.getMySqlSqlCacheFlag() != null) {
            reject(UNSUPPORTED_SELECT);
        }
        if (select.getDistinct() != null
                && (select.getDistinct().isUseUnique()
                || (select.getDistinct().getOnSelectItems() != null
                && !select.getDistinct().getOnSelectItems().isEmpty()))) {
            reject(UNSUPPORTED_SELECT);
        }
    }

    /**
     * FROM 절의 단일 항목을 스코프에 등록한다. 테이블이면 컬럼 Allowlist를 확인하고,
     * 서브쿼리이면 재귀 검증 후 그 프로젝션 별칭을 새로운 릴레이션으로 등록한다.
     */
    private void registerFromItem(FromItem fromItem, Scope scope, int depth) {
        if (fromItem instanceof Table table) {
            registerTable(table, scope);
            return;
        }
        if (fromItem instanceof ParenthesedSelect subquery) {
            if (subquery.getAlias() == null || subquery.getAlias().getUnquotedName() == null) {
                reject(PROJECTION_ALIAS_REQUIRED);
            }
            validateSimpleAlias(subquery.getAlias());
            List<String> projections = validateSelect(subquery, null, depth + 1, true);
            scope.addRelation(normalize(subquery.getAlias().getUnquotedName()), new Relation(Set.copyOf(projections)));
            return;
        }
        reject(UNSUPPORTED_SELECT);
    }

    /**
     * 테이블 참조를 검증하고 스코프에 등록한다.
     * 카탈로그/DB명, 3파트 이상 이름, PIVOT 등 비정상 형태를 거부하고, 스키마·테이블 Allowlist를 확인한 뒤
     * 별칭이 있으면 별칭으로, 없으면 테이블명으로 릴레이션을 등록한다.
     */
    private void registerTable(Table table, Scope scope) {
        if (hasText(table.getCatalogName())
                || hasText(table.getDatabaseName())
                || table.getNameParts().size() > 2
                || table.getPivot() != null
                || table.getUnPivot() != null
                || table.getIndexHint() != null
                || table.getSqlServerHints() != null
                || table.getSampleClause() != null) {
            reject(UNSUPPORTED_SELECT);
        }

        String schema = normalize(table.getUnquotedSchemaName());
        if (schema != null && !schema.equals(policy.allowedSchema())) {
            reject(SCHEMA_NOT_ALLOWED);
        }
        String tableName = normalize(table.getUnquotedName());
        Set<String> columns = policy.columnsFor(tableName);
        if (columns == null) {
            reject(TABLE_NOT_ALLOWED);
        }
        String relationName = table.getAlias() == null
                ? tableName
                : normalize(table.getAlias().getUnquotedName());
        if (table.getAlias() != null) {
            validateSimpleAlias(table.getAlias());
        }
        if (relationName == null || relationName.isBlank()) {
            reject(UNSUPPORTED_SELECT);
        }
        scope.addRelation(relationName, new Relation(columns));
    }

    /**
     * JOIN 형태를 검증한다.
     * INNER/LEFT OUTER + ON/USING 조합만 허용하고, RIGHT/FULL/CROSS/NATURAL/SEMI/APPLY 등
     * 복잡하거나 우회 가능한 JOIN은 모두 거부한다. 단순 콤마 JOIN(simple)도 거부.
     */
    private void validateJoinShape(Join join) {
        if (join == null
                || join.getRightItem() == null
                || join.isSimple()
                || join.isStraight()
                || join.isApply()
                || join.isSemi()
                || join.isRight()
                || join.isNatural()
                || join.isFull()
                || join.isCross()
                || join.isGlobal()
                || join.isWindowJoin()
                || join.getJoinHint() != null
                || (join.isOuter() && !join.isLeft())) {
            reject(JOIN_NOT_ALLOWED);
        }
    }

    /**
     * JOIN 조건을 검증한다. ON 표현식은 재귀 검증, USING 컬럼은 두 릴레이션 이상에 존재하는 컬럼만 허용한다.
     * ON과 USING이 모두 없으면 무조건 CROSS JOIN이므로 거부한다.
     */
    private void validateJoinConditions(Join join, Scope scope, int depth) {
        if (join.getOnExpressions() != null) {
            join.getOnExpressions().forEach(expression ->
                    validateExpression(expression, new ExpressionContext(scope, depth, false)));
        }
        if (join.getUsingColumns() != null) {
            for (Column column : join.getUsingColumns()) {
                String name = normalize(column.getUnquotedColumnName());
                if (column.getTable() != null || scope.relationCountContaining(name) < 2) {
                    reject(COLUMN_NOT_ALLOWED);
                }
            }
        }
        boolean hasOn = join.getOnExpressions() != null && !join.getOnExpressions().isEmpty();
        boolean hasUsing = join.getUsingColumns() != null && !join.getUsingColumns().isEmpty();
        if (!hasOn && !hasUsing) {
            reject(JOIN_NOT_ALLOWED);
        }
    }

    /**
     * SELECT 절 프로젝션을 검증한다.
     * 와일드카드 거부, 각 표현식 재귀 검증, 계산 컬럼은 별칭 필수, 중복 별칭 거부 규칙을 적용한다.
     * 반환된 프로젝션 별칭 목록은 ORDER BY에서 별칭 참조를 허용하는 데 사용된다.
     */
    private List<String> validateProjections(
            List<SelectItem<?>> selectItems,
            Scope scope,
            int depth,
            boolean requireNamedProjection
    ) {
        List<String> projections = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (SelectItem<?> item : selectItems) {
            Expression expression = item.getExpression();
            if (item.getAlias() != null) {
                validateSimpleAlias(item.getAlias());
            }
            if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                reject(WILDCARD_NOT_ALLOWED);
            }
            validateExpression(expression, new ExpressionContext(scope, depth, false));

            String name = item.getAlias() == null ? directColumnName(expression) : normalize(item.getUnquotedAliasName());
            if (requireNamedProjection && (name == null || name.isBlank())) {
                reject(PROJECTION_ALIAS_REQUIRED);
            }
            if (name != null && !unique.add(name)) {
                reject(DUPLICATE_PROJECTION);
            }
            if (name != null) {
                projections.add(name);
            }
        }
        return List.copyOf(projections);
    }

    /** 표현식이 단순 컬럼 참조이면 그 컬럼명을 반환하고, 아니면 null (별칭 필수 판정에 사용). */
    private String directColumnName(Expression expression) {
        return expression instanceof Column column ? normalize(column.getUnquotedColumnName()) : null;
    }

    /** GROUP BY 절을 검증한다. ROLLUP, GROUPING SETS 등 비표준 grouping은 거부한다. */
    private void validateGroupBy(GroupByElement groupBy, Scope scope, int depth) {
        if (groupBy == null) {
            return;
        }
        if (groupBy.isMysqlWithRollup() || (groupBy.getGroupingSets() != null && !groupBy.getGroupingSets().isEmpty())) {
            reject(UNSUPPORTED_SELECT);
        }
        ExpressionList<?> expressions = groupBy.getGroupByExpressionList();
        if (expressions != null) {
            expressions.forEach(expression -> validateExpression(expression, new ExpressionContext(scope, depth, false)));
        }
    }

    /** ORDER BY 절을 검증한다. 각 정렬 표현식을 재귀 검증하며, ORDER BY에서는 프로젝션 별칭 참조를 허용한다. */
    private void validateOrderBy(List<OrderByElement> elements, Scope scope, int depth) {
        if (elements == null) {
            return;
        }
        for (OrderByElement element : elements) {
            if (element.isMysqlWithRollup()) {
                reject(UNSUPPORTED_SELECT);
            }
            validateExpression(element.getExpression(), new ExpressionContext(scope, depth, true));
        }
    }

    /**
     * LIMIT 절을 검증한다. LIMIT ALL/NULL, BY 표현식 거부, rowCount는 0~maxRows(200) 범위의 LongValue만 허용한다.
     */
    private void validateLimit(Limit limit) {
        if (limit == null) {
            return;
        }
        if (limit.getByExpressions() != null && !limit.getByExpressions().isEmpty()) {
            reject(LIMIT_EXCEEDED);
        }
        // LIMIT ALL/NULL과 파라미터는 LongValue가 아니므로 아래 단일 검사에서 함께 거절된다.
        Expression rowCount = limit.getRowCount();
        if (!(rowCount instanceof LongValue value) || value.getValue() < 0 || value.getValue() > policy.maxRows()) {
            reject(LIMIT_EXCEEDED);
        }
        if (limit.getOffset() != null
                && (!(limit.getOffset() instanceof LongValue value) || value.getValue() < 0)) {
            reject(UNSUPPORTED_SELECT);
        }
    }

    /** FETCH 절을 검증한다. 행 수가 정수 리터럴이며 0~maxRows 범위일 때만 허용한다. */
    private void validateFetch(Fetch fetch) {
        if (fetch == null) {
            return;
        }
        // JSqlParser 5.3에서는 행 수를 expression으로 제공한다. JDBC 파라미터나 계산식은
        // 실행 시점에 상한이 달라질 수 있으므로 값이 고정된 LongValue만 허용한다.
        Expression rowCount = fetch.getExpression();
        if (!(rowCount instanceof LongValue value)
                || value.getValue() < 0
                || value.getValue() > policy.maxRows()) {
            reject(LIMIT_EXCEEDED);
        }
    }

    /** 단일 표현식을 방문자 패턴으로 재귀 검증한다. null이면 무시한다. */
    private void validateExpression(Expression expression, ExpressionContext context) {
        if (expression != null) {
            expression.accept(expressionVisitor, context);
        }
    }

    /**
     * 컬럼 참조를 검증한다. 한정/비한정 여부에 따라 스코프에서 해석하며,
     * ORDER BY에서는 프로젝션 별칭 참조를 허용한다. 스키마·테이블·컬럼 모두 Allowlist 대조.
     */
    private void validateColumn(Column column, ExpressionContext context) {
        if (column.getArrayConstructor() != null || hasText(column.getCatalogName())) {
            reject(UNSUPPORTED_EXPRESSION);
        }
        String columnName = normalize(column.getUnquotedColumnName());
        if (columnName == null || columnName.isBlank()) {
            reject(COLUMN_NOT_ALLOWED);
        }

        Table qualifier = column.getTable();
        if (qualifier == null || qualifier.getName() == null || qualifier.getName().isBlank()) {
            if (context.allowProjectionAliases() && context.scope().projectionAliases.contains(columnName)) {
                return;
            }
            context.scope().resolveUnqualified(columnName);
            return;
        }

        if (hasText(qualifier.getCatalogName()) || hasText(qualifier.getDatabaseName())) {
            reject(SCHEMA_NOT_ALLOWED);
        }
        String schema = normalize(qualifier.getUnquotedSchemaName());
        if (schema != null && !schema.equals(policy.allowedSchema())) {
            reject(SCHEMA_NOT_ALLOWED);
        }
        context.scope().resolveQualified(normalize(qualifier.getUnquotedName()), columnName);
    }

    /**
     * 함수 호출을 검증한다. 단일 이름 + Allowlist에 포함된 함수만 허용하며,
     * COUNT(*) 형태의 AllColumns는 count에만 예외적으로 허용한다.
     */
    private void validateFunction(Function function, ExpressionContext context) {
        List<String> multipartName = function.getMultipartName();
        if (multipartName == null || multipartName.size() != 1 || !policy.allowsFunction(function.getName())) {
            reject(FUNCTION_NOT_ALLOWED);
        }
        boolean countAll = normalize(function.getName()).equals("count")
                && function.getParameters() != null
                && function.getParameters().size() == 1
                && function.getParameters().get(0) instanceof AllColumns;
        if (function.isAllColumns() && !normalize(function.getName()).equals("count")) {
            reject(WILDCARD_NOT_ALLOWED);
        }
        if (function.getNamedParameters() != null
                || function.getAttribute() != null
                || function.getKeep() != null
                || function.getLimit() != null
                || function.getHavingClause() != null
                || function.getOrderByElements() != null
                || function.getOnOverflowTruncate() != null
                || function.getExtraKeyword() != null) {
            reject(UNSUPPORTED_EXPRESSION);
        }
        if (function.getParameters() != null && !countAll) {
            function.getParameters().forEach(expression -> validateExpression(expression, context));
        }
    }

    /** 지정된 사유로 검증을 즉시 중단하고 fail-closed 예외를 던진다. */
    private static void reject(SqlRejectionReason reason) {
        throw new SqlValidationException(reason);
    }

    /** 문자열이 null이 아니고 blank가 아닌지 확인한다. */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 별칭이 단순 별칭인지 검증한다. 컬럼 리스트를 포함한 별칭(AliasColumns)은 거부한다. */
    private static void validateSimpleAlias(net.sf.jsqlparser.expression.Alias alias) {
        if (alias.getAliasColumns() != null && !alias.getAliasColumns().isEmpty()) {
            reject(UNSUPPORTED_SELECT);
        }
    }

    /**
     * 모든 표현식 노드를 방문해 허용되지 않은 구문을 차단하는 방문자.
     * Column/Function은 별도 검증 메서드로 위임하고, 서브쿼리(ParenthesedSelect)는 재귀 검증한다.
     * JDBC 파라미터, 변수, 분석 함수 등 비허용 노드는 즉시 거부한다.
     */
    private final class SafeExpressionVisitor extends ExpressionVisitorAdapter<Void> {

        @Override
        public <S> Void visit(Column column, S rawContext) {
            validateColumn(column, context(rawContext));
            return null;
        }

        @Override
        public <S> Void visit(Function function, S rawContext) {
            validateFunction(function, context(rawContext));
            return null;
        }

        @Override
        public <S> Void visit(ExtractExpression expression, S rawContext) {
            if (!policy.allowsFunction("extract")) {
                reject(FUNCTION_NOT_ALLOWED);
            }
            validateExpression(expression.getExpression(), context(rawContext));
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S rawContext) {
            ExpressionContext context = context(rawContext);
            validateSelect(select, context.scope(), context.depth() + 1, false);
            return null;
        }

        @Override
        public <S> Void visit(Select select, S rawContext) {
            ExpressionContext context = context(rawContext);
            validateSelect(select, context.scope(), context.depth() + 1, false);
            return null;
        }

        @Override
        public <S> Void visit(AllColumns columns, S context) {
            reject(WILDCARD_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(AllTableColumns columns, S context) {
            reject(WILDCARD_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(FunctionAllColumns columns, S context) {
            reject(WILDCARD_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(JdbcParameter parameter, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        @Override
        public <S> Void visit(JdbcNamedParameter parameter, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        @Override
        public <S> Void visit(UserVariable variable, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        @Override
        public <S> Void visit(NumericBind bind, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        @Override
        public <S> Void visit(NextValExpression expression, S context) {
            reject(FUNCTION_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(TimeKeyExpression expression, S context) {
            reject(FUNCTION_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(AnalyticExpression expression, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        @Override
        public <S> Void visit(JsonFunction expression, S context) {
            reject(FUNCTION_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(JsonAggregateFunction expression, S context) {
            reject(FUNCTION_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(TrimFunction expression, S context) {
            reject(FUNCTION_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(TranscodingFunction expression, S context) {
            reject(FUNCTION_NOT_ALLOWED);
            return null;
        }

        @Override
        public <S> Void visit(VariableAssignment expression, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        @Override
        public <S> Void visit(OracleHint expression, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        @Override
        public <S> Void visit(XMLSerializeExpr expression, S context) {
            reject(UNSUPPORTED_EXPRESSION);
            return null;
        }

        private ExpressionContext context(Object rawContext) {
            if (!(rawContext instanceof ExpressionContext)) {
                reject(UNSUPPORTED_EXPRESSION);
            }
            return (ExpressionContext) rawContext;
        }
    }

    /** 표현식 검증 시 함께 전달되는 컨텍스트: 현재 스코프, 깊이, 프로젝션 별칭 허용 여부. */
    private record ExpressionContext(Scope scope, int depth, boolean allowProjectionAliases) {
    }

    /** 스코프 내에서 테이블·서브쿼리를 나타내는 릴레이션: 이름과 허용 컬럼 집합. */
    private record Relation(Set<String> columns) {
        private Relation {
            columns = Set.copyOf(columns);
        }
    }

    /**
     * FROM/JOIN으로 구성된 현재 SELECT 레벨의 가시 범위를 관리한다.
     * 부모 스코프를 체인으로 연결하여 상관 서브쿼리의 바깥 참조를 해석한다.
     * 컬럼 해석 시 단일 매칭이면 성공, 다중이면 AMBIGUOUS, 없으면 부모로 위임 후 최종 거부.
     */
    private static final class Scope {

        private final Scope parent;
        private final Map<String, Relation> relations = new HashMap<>();
        private Set<String> projectionAliases = Set.of();

        private Scope(Scope parent) {
            this.parent = parent;
        }

        /** 릴레이션을 스코프에 등록한다. 동일 이름이 이미 있으면 중복으로 거부한다. */
        private void addRelation(String name, Relation relation) {
            if (relations.putIfAbsent(name, relation) != null) {
                reject(DUPLICATE_RELATION);
            }
        }

        /** 프로젝션 별칭 집합을 저장한다. ORDER BY에서 별칭 참조 허용 여부를 판단하는 데 사용된다. */
        private void setProjectionAliases(List<String> aliases) {
            projectionAliases = Set.copyOf(aliases);
        }

        /** 한정된 컬럼(qualifier.column)을 해석한다. 현재 스코프에 없으면 부모 스코프로 위임한다. */
        private void resolveQualified(String qualifier, String column) {
            Relation relation = relations.get(qualifier);
            if (relation != null) {
                if (!relation.columns().contains(column)) {
                    reject(COLUMN_NOT_ALLOWED);
                }
                return;
            }
            if (parent != null) {
                parent.resolveQualified(qualifier, column);
                return;
            }
            reject(COLUMN_NOT_ALLOWED);
        }

        /**
         * 비한정 컬럼을 해석한다. 현재 스코프의 릴레이션 중 해당 컬럼을 가진 곳이 정확히 1곳이면 성공,
         * 2곳 이상이면 모호성(AMBIGUOUS)으로 거부, 0곳이면 부모 스코프로 위임 후 최종적으로 거부한다.
         */
        private void resolveUnqualified(String column) {
            long matches = relations.values().stream().filter(relation -> relation.columns().contains(column)).count();
            if (matches == 1) {
                return;
            }
            if (matches > 1) {
                reject(AMBIGUOUS_COLUMN);
            }
            if (parent != null) {
                parent.resolveUnqualified(column);
                return;
            }
            reject(COLUMN_NOT_ALLOWED);
        }

        /** 해당 컬럼을 포함하는 릴레이션 개수를 반환한다 (USING 검증에 사용). */
        private long relationCountContaining(String column) {
            return relations.values().stream().filter(relation -> relation.columns().contains(column)).count();
        }
    }
}
