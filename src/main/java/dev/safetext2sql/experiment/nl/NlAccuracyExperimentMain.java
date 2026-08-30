package dev.safetext2sql.experiment.nl;

import dev.safetext2sql.SafeText2SqlApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 웹 서버를 열지 않고 자연어 정확도 실험만 실행하는 전용 진입점이다.
 * DB와 Ollama 설정은 일반 애플리케이션과 같은 환경 변수를 사용한다.
 */
public final class NlAccuracyExperimentMain {

    private NlAccuracyExperimentMain() {
    }

    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(SafeText2SqlApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            var command = context.getBean(NlAccuracyExperimentCommand.class);
            System.out.println("NL_EXPERIMENT_RESULT_DIR=" + command.run().toAbsolutePath());
        }
    }
}
