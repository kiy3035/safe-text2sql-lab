package dev.safetext2sql.experiment.nl;

/**
 * 생성 SQL과 Golden SQL의 결과를 어떤 의미로 비교할지 정의한다.
 *
 * <p>SQL 문자열은 같은 결과를 만드는 여러 표현이 가능하므로 비교 대상에서 제외한다.
 * 단일 값은 {@link #SCALAR}, 순위·시간순 결과는 {@link #ORDERED}, 순서가 의미 없는 집합은
 * 중복 개수까지 보존하는 {@link #UNORDERED}를 사용한다.</p>
 */
public enum ResultComparisonMode {
    SCALAR,
    ORDERED,
    UNORDERED
}
