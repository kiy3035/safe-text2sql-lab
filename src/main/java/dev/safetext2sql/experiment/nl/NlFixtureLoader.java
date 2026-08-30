package dev.safetext2sql.experiment.nl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 저장소의 JSONL 질문·Golden SQL fixture를 엄격하게 읽는다.
 *
 * <p>질문은 정확히 50개여야 하며 ID 중복, 누락된 Golden SQL, 사용되지 않는 Golden SQL을
 * 모두 거부한다. fixture 일부가 빠진 상태에서 분모를 줄여 정확도가 높아지는 측정 오류를
 * 시작 전에 막기 위한 검증이다.</p>
 */
public final class NlFixtureLoader {

    private static final int REQUIRED_QUESTION_COUNT = 50;
    private static final Pattern QUESTION_ID = Pattern.compile("NL-\\d{3}");
    private static final Pattern GOLDEN_ID = Pattern.compile("G-\\d{3}");

    private final ObjectMapper objectMapper;

    public NlFixtureLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 두 JSONL 파일을 읽고 완전한 일대일 매핑을 반환한다.
     *
     * @throws IOException 파일을 읽거나 JSON을 해석할 수 없는 경우
     * @throws IllegalArgumentException 개수·ID·참조 무결성이 어긋난 경우
     */
    public NlFixtureSet load(Path questionsPath, Path goldenSqlPath) throws IOException {
        List<NlQuestion> questions = readJsonLines(questionsPath, NlQuestion.class);
        List<GoldenSql> goldenSql = readJsonLines(goldenSqlPath, GoldenSql.class);

        if (questions.size() != REQUIRED_QUESTION_COUNT) {
            throw new IllegalArgumentException("natural-language fixture must contain exactly 50 questions");
        }

        Map<String, NlQuestion> questionById = uniqueById(questions, NlQuestion::id, QUESTION_ID, "question");
        Map<String, GoldenSql> goldenById = uniqueById(goldenSql, GoldenSql::id, GOLDEN_ID, "golden SQL");
        if (questionById.size() != REQUIRED_QUESTION_COUNT || goldenById.size() != REQUIRED_QUESTION_COUNT) {
            throw new IllegalArgumentException("question and golden SQL fixtures must both contain 50 unique ids");
        }

        for (NlQuestion question : questions) {
            if (!goldenById.containsKey(question.goldenSqlId())) {
                throw new IllegalArgumentException("question references a missing golden SQL id");
            }
        }
        long referencedGoldenCount = questions.stream().map(NlQuestion::goldenSqlId).distinct().count();
        if (referencedGoldenCount != goldenById.size()) {
            throw new IllegalArgumentException("every golden SQL must be referenced exactly once");
        }
        return new NlFixtureSet(questions, goldenById);
    }

    private <T> List<T> readJsonLines(Path path, Class<T> type) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<T> values = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) {
                throw new IllegalArgumentException("blank JSONL lines are not allowed");
            }
            values.add(objectMapper.readValue(line, type));
        }
        return List.copyOf(values);
    }

    private <T> Map<String, T> uniqueById(
            List<T> values,
            java.util.function.Function<T, String> idExtractor,
            Pattern pattern,
            String fixtureName
    ) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T value : values) {
            String id = idExtractor.apply(value);
            if (!pattern.matcher(id).matches() || byId.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException(fixtureName + " ids must be unique and use the documented format");
            }
        }
        return byId;
    }
}
