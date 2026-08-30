package dev.safetext2sql.api;

import dev.safetext2sql.workflow.Text2SqlQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자연어 질문을 안전 실행 파이프라인에 전달하는 REST API다.
 *
 * <p>컨트롤러는 생성 SQL을 직접 다루지 않으며 서비스 결과에서 컬럼·행·시도 메타데이터만
 * 응답한다. 실패 응답은 {@link Text2SqlExceptionHandler}가 원문 없이 변환한다.</p>
 */
@RestController
@RequestMapping(path = "/api/v1/text2sql", produces = MediaType.APPLICATION_JSON_VALUE)
public final class Text2SqlQueryController {

    private final Text2SqlQueryService queryService;

    public Text2SqlQueryController(Text2SqlQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Text2SqlQueryResponse query(@RequestBody Text2SqlQueryRequest request) {
        return Text2SqlQueryResponse.from(queryService.query(request.question()));
    }
}
