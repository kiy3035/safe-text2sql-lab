package dev.safetext2sql.llm;

/**
 * 자연어 질의를 SQL로 변환하는 LLM 연동 추상화.
 * <p>
 * 실제 구현체는 두 가지로 분리된다:
 * </p>
 * <ul>
 *   <li>{@link dev.safetext2sql.llm.ollama.OllamaSqlGenerator} - Ollama 로컬 LLM HTTP 구현체 (실험용)</li>
 *   <li>Scripted/Fake 구현체 (testFixtures) - 단위·통합 테스트에서 결정적 동작을 제공하는 가짜 구현체</li>
 * </ul>
 * <p>
 * 이 인터페이스 뒤에 구현을 숨김으로써, 테스트에서는 WireMock/Fake로 재현 가능한 검증을 수행하고
 * 실제 정확도 측정에서는 Ollama로 교체할 수 있다. 반환된 {@link GeneratedSql}은
 * 아직 신뢰할 수 없는 문자열이므로 반드시 검증 게이트를 통과해야 한다.
 * </p>
 */
@FunctionalInterface
public interface SqlGenerator {

    /**
     * 주어진 자연어 요청을 LLM에 전달하여 SQL을 생성한다.
     *
     * @param request 자연어 질의와 이전 실패 피드백 코드를 담은 요청
     * @return LLM이 반환한 원문 SQL과 모델 정보 (검증 전)
     */
    GeneratedSql generate(SqlGenerationRequest request);
}
