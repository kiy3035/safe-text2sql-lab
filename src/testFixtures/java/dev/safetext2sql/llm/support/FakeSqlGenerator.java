package dev.safetext2sql.llm.support;

import dev.safetext2sql.llm.GeneratedSql;
import dev.safetext2sql.llm.SqlGenerationRequest;
import dev.safetext2sql.llm.SqlGenerator;
import java.util.ArrayList;
import java.util.List;

public final class FakeSqlGenerator implements SqlGenerator {

    private final GeneratedSql response;
    private final List<SqlGenerationRequest> requests = new ArrayList<>();

    public FakeSqlGenerator(GeneratedSql response) {
        this.response = response;
    }

    @Override
    public GeneratedSql generate(SqlGenerationRequest request) {
        requests.add(request);
        return response;
    }

    public List<SqlGenerationRequest> requests() {
        return List.copyOf(requests);
    }
}
