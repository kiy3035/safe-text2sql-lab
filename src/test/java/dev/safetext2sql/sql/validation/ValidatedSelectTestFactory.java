package dev.safetext2sql.sql.validation;

import java.util.List;

/**
 * 실행기 자원 제한을 검증할 때만 사용하는 테스트 전용 {@link ValidatedSelect} 팩터리다.
 *
 * <p>운영 코드의 생성자는 계속 패키지 전용으로 유지된다. 따라서 애플리케이션은 이 클래스를
 * 참조할 수 없고, 신뢰할 수 없는 SQL을 검증 없이 실행 객체로 감싸는 우회 경로도 생기지 않는다.</p>
 */
public final class ValidatedSelectTestFactory {

    private ValidatedSelectTestFactory() {
    }

    /** 테스트할 SQL과 예상 결과 컬럼 하나를 검증 완료 값으로 감싼다. */
    public static ValidatedSelect create(String sql, String projectedColumn) {
        return new ValidatedSelect(sql, List.of(projectedColumn), false);
    }
}
