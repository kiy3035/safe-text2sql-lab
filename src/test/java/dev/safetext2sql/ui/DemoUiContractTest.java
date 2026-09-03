package dev.safetext2sql.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot가 제공할 정적 데모 화면의 보안·연결 계약을 검증한다.
 *
 * <p>브라우저의 시각적·상호작용 검증은 실제 애플리케이션으로 별도 수행한다. 이 테스트는 배포
 * 산출물에서 폼이나 API 경로가 빠지는 회귀, 외부 CDN 의존성 추가, 신뢰할 수 없는 결과를
 * {@code innerHTML}로 렌더링하는 변경을 빠르게 차단한다.</p>
 */
class DemoUiContractTest {

    @Test
    void pageContainsTheQuestionFormAndOnlyLocalAssets() throws IOException {
        String html = resource("static/index.html");

        assertThat(html)
                .contains("id=\"query-form\" novalidate")
                .contains("id=\"question\"")
                .contains("id=\"result-panel\"")
                .contains("href=\"/styles.css\"")
                .contains("src=\"/app.js\"")
                .doesNotContain("http://", "https://");
    }

    @Test
    void scriptUsesTheExistingApiAndTextOnlyDomRendering() throws IOException {
        String script = resource("static/app.js");

        assertThat(script)
                .contains("/api/v1/text2sql/query")
                .contains("textContent")
                .contains("JSON.stringify({ question })")
                .doesNotContain(".innerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write");
    }

    @Test
    void stylesheetProvidesResponsiveAndReducedMotionModes() throws IOException {
        String stylesheet = resource("static/styles.css");

        assertThat(stylesheet)
                .contains("@media (max-width: 680px)")
                .contains("@media (prefers-reduced-motion: reduce)");
    }

    /**
     * 테스트 classpath에 포함된 실제 정적 파일을 읽는다. 소스 경로를 직접 읽지 않으므로 Gradle의
     * processResources 결과에서 파일이 누락되는 문제까지 함께 발견할 수 있다.
     */
    private String resource(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing classpath resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
