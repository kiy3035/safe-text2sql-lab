package dev.safetext2sql.experiment.nl;

import java.util.List;
import java.util.Map;

/** 질문 50건과 일대일로 연결된 Golden SQL 묶음이다. */
public record NlFixtureSet(List<NlQuestion> questions, Map<String, GoldenSql> goldenSqlById) {

    public NlFixtureSet {
        questions = List.copyOf(questions);
        goldenSqlById = Map.copyOf(goldenSqlById);
    }

    /** 존재가 검증된 Golden SQL을 질문 순서대로 반환한다. */
    public GoldenSql goldenSqlFor(NlQuestion question) {
        GoldenSql goldenSql = goldenSqlById.get(question.goldenSqlId());
        if (goldenSql == null) {
            throw new IllegalStateException("validated fixture lost golden SQL mapping");
        }
        return goldenSql;
    }
}
