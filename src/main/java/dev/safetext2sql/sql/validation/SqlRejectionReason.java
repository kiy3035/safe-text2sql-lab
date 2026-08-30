package dev.safetext2sql.sql.validation;

/**
 * SQL 검증 실패 사유를 나타내는 안정적인 코드 enum.
 * <p>
 * 예외 메시지에 원문 SQL을 포함하지 않는 대신, 이 enum 값을 사용해
 * 로그·재시도 프롬프트·실험 결과에 안전하게 기록한다.
 * 각 코드는 LLM 재시도 시 피드백 코드로 재사용될 수 있도록 대문자 스네이크 케이스로 정의된다.
 * </p>
 */
public enum SqlRejectionReason {
    /** 빈 문자열 또는 null 입력. */
    EMPTY_SQL,
    /** SQL 길이가 정책 상한(10,000자) 초과. */
    QUERY_TOO_LONG,
    /** JSqlParser 파싱 실패 또는 마크다운 펜스 등 비정상 형식. */
    PARSE_REJECTED,
    /** 세미콜론 등으로 구분된 다중 문장. */
    MULTIPLE_STATEMENTS,
    /** SELECT가 아닌 문장 (INSERT/UPDATE/DELETE/DDL 등). */
    STATEMENT_NOT_SELECT,
    /** CTE(WITH 절) 사용 - 복잡도 및 우회 경로 차단. */
    CTE_NOT_ALLOWED,
    /** UNION/INTERSECT/EXCEPT 등 집합 연산 사용. */
    SET_OPERATION_NOT_ALLOWED,
    /** FOR UPDATE/SHARE 등 잠금 구문 사용. */
    LOCKING_NOT_ALLOWED,
    /** SELECT INTO 사용. */
    SELECT_INTO_NOT_ALLOWED,
    /** 지원하지 않는 SELECT 변형 (LATERAL VIEW, TOP, QUALIFY 등). */
    UNSUPPORTED_SELECT,
    /** 지원하지 않는 표현식 (JDBC 파라미터, 변수 할당 등). */
    UNSUPPORTED_EXPRESSION,
    /** SELECT * 또는 table.* 와일드카드 사용. */
    WILDCARD_NOT_ALLOWED,
    /** 허용된 스키마(analytics) 이외의 스키마 참조. */
    SCHEMA_NOT_ALLOWED,
    /** 허용 목록에 없는 테이블 참조. */
    TABLE_NOT_ALLOWED,
    /** 허용 목록에 없는 컬럼 참조. */
    COLUMN_NOT_ALLOWED,
    /** 비한정 컬럼이 여러 테이블에 존재하여 모호한 경우. */
    AMBIGUOUS_COLUMN,
    /** FROM/JOIN에 동일 별칭·테이블명 중복. */
    DUPLICATE_RELATION,
    /** 계산된 프로젝션에 별칭(alias) 누락. */
    PROJECTION_ALIAS_REQUIRED,
    /** SELECT 절에 중복된 프로젝션 별칭. */
    DUPLICATE_PROJECTION,
    /** 허용 목록에 없는 함수 호출. */
    FUNCTION_NOT_ALLOWED,
    /** 허용되지 않는 JOIN 형태 (RIGHT/FULL/CROSS 등) 또는 ON/USING 누락. */
    JOIN_NOT_ALLOWED,
    /** LIMIT/FETCH 행 수가 상한(200) 초과 또는 비정상 형식. */
    LIMIT_EXCEEDED,
    /** JOIN 개수(4) 또는 서브쿼리 깊이(3) 등 복잡도 상한 초과. */
    QUERY_COMPLEXITY_EXCEEDED
}
