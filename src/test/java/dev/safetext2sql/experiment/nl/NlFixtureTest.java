package dev.safetext2sql.experiment.nl;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.sql.validation.AnalyticsSqlPolicy;
import dev.safetext2sql.sql.validation.AstSqlValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 자연어 50건과 Golden SQL이 개수·분포·AST 정책을 계속 만족하는지 검증한다. */
class NlFixtureTest {

    private final NlFixtureLoader loader = new NlFixtureLoader(new ObjectMapper());

    @Test
    void loadsExactlyFiftyOneToOneQuestionAndGoldenSqlPairs() throws Exception {
        NlFixtureSet fixtures = loadFixtures();

        assertThat(fixtures.questions()).hasSize(50);
        assertThat(fixtures.goldenSqlById()).hasSize(50);
        assertThat(fixtures.questions()).extracting(NlQuestion::id).doesNotHaveDuplicates();
        assertThat(fixtures.questions()).extracting(NlQuestion::goldenSqlId).doesNotHaveDuplicates();
    }

    @Test
    void coversEveryComparisonModeAndDifficulty() throws Exception {
        NlFixtureSet fixtures = loadFixtures();

        assertThat(fixtures.questions()).extracting(NlQuestion::comparison)
                .contains(ResultComparisonMode.values());
        assertThat(fixtures.questions()).extracting(NlQuestion::difficulty)
                .contains(QuestionDifficulty.values());
        assertThat(fixtures.questions().stream()
                        .filter(question -> question.difficulty() == QuestionDifficulty.HARD))
                .hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void everyGoldenSqlPassesTheSameProductionAstPolicy() throws Exception {
        NlFixtureSet fixtures = loadFixtures();
        var validator = new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy());

        for (NlQuestion question : fixtures.questions()) {
            GoldenSql goldenSql = fixtures.goldenSqlFor(question);
            assertThatCode(() -> validator.validate(goldenSql.sql()))
                    .as("%s -> %s", question.id(), goldenSql.id())
                    .doesNotThrowAnyException();
        }
    }

    private NlFixtureSet loadFixtures() throws Exception {
        return loader.load(
                Path.of("experiments", "nl-questions.jsonl"),
                Path.of("experiments", "golden-sql.jsonl")
        );
    }
}
