package dev.safetext2sql.llm;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * LLM에 SQL 생성을 요청할 때 사용하는 불변 요청 객체.
 * <p>
 * {@code question}은 사용자의 자연어 질의 원문이며, {@code feedbackCodes}는 이전 시도에서
 * 검증기가 반환한 실패 코드({@link dev.safetext2sql.sql.validation.SqlRejectionReason}) 목록이다.
 * 재시도 시 이 코드들을 프롬프트에 포함해 LLM이 동일한 오류를 반복하지 않도록 유도한다.
 * 피드백 코드는 대문자·숫자·언더스코어만 허용하는 안정적인 형식([A-Z][A-Z0-9_]{0,63})으로 제한하여
 * 프롬프트 인젝션 위험을 줄인다.
 * </p>
 *
 * @param question      자연어 질의 (blank 불가)
 * @param feedbackCodes 이전 검증 실패 코드 목록 (최대 10개, 불변 복사본으로 보관)
 */
public record SqlGenerationRequest(String question, List<String> feedbackCodes) {

    /** 피드백 코드 형식: 대문자로 시작, 대문자/숫자/언더스코어만 허용, 최대 64자. */
    private static final Pattern FEEDBACK_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** 재시도 시 전달할 수 있는 피드백 코드 최대 개수. */
    private static final int MAX_FEEDBACK_CODES = 10;

    /**
     * Compact 생성자 - 레코드 불변식 검증.
     * <p>
     * question은 blank 불가, feedbackCodes는 null 불가·최대 10개·정규식 형식만 허용한다.
     * List.copyOf로 방어적 복사하여 외부 변경으로부터 불변성을 보장한다.
     * </p>
     */
    public SqlGenerationRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        Objects.requireNonNull(feedbackCodes, "feedbackCodes must not be null");
        if (feedbackCodes.size() > MAX_FEEDBACK_CODES) {
            throw new IllegalArgumentException("too many feedback codes");
        }
        feedbackCodes = List.copyOf(feedbackCodes);
        if (feedbackCodes.stream().anyMatch(code -> code == null || !FEEDBACK_CODE.matcher(code).matches())) {
            throw new IllegalArgumentException("feedback codes must use the stable code format");
        }
    }

    /**
     * 첫 시도용 요청을 생성하는 팩토리 메서드.
     * 피드백 코드 없이 자연어 질의만으로 요청을 만든다. 재시도가 아닌 최초 호출에서 사용한다.
     *
     * @param question 자연어 질의 원문
     * @return feedbackCodes가 빈 리스트인 요청 객체
     */
    public static SqlGenerationRequest firstAttempt(String question) {
        return new SqlGenerationRequest(question, List.of());
    }
}
