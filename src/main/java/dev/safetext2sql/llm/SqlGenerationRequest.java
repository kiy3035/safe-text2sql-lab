package dev.safetext2sql.llm;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record SqlGenerationRequest(String question, List<String> feedbackCodes) {

    private static final Pattern FEEDBACK_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int MAX_FEEDBACK_CODES = 10;

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

    public static SqlGenerationRequest firstAttempt(String question) {
        return new SqlGenerationRequest(question, List.of());
    }
}
