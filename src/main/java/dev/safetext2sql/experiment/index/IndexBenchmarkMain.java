package dev.safetext2sql.experiment.index;

import dev.safetext2sql.SafeText2SqlApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 웹 서버 없이 로컬 PostgreSQL 인덱스 실험만 실행하는 전용 진입점이다. */
public final class IndexBenchmarkMain {

    private IndexBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(SafeText2SqlApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            var command = context.getBean(IndexBenchmarkCommand.class);
            System.out.println("INDEX_EXPERIMENT_RESULT_DIR=" + command.run().toAbsolutePath());
        }
    }
}
