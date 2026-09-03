/*
 * 이 화면은 기존 REST API의 얇은 클라이언트일 뿐 보안 경계가 아니다.
 * 질문 검증, SQL AST 정책, DB 권한은 모두 서버가 다시 강제한다. 브라우저 코드를 수정하거나
 * API를 직접 호출해도 서버 정책을 우회할 수 없도록 기존 백엔드 흐름을 그대로 사용한다.
 */
const form = document.querySelector("#query-form");
const questionInput = document.querySelector("#question");
const characterCount = document.querySelector("#character-count");
const submitButton = document.querySelector("#submit-button");
const buttonLabel = submitButton.querySelector(".button-label");
const resultPanel = document.querySelector("#result-panel");
const resultMetadata = document.querySelector("#result-metadata");
const resultMessage = document.querySelector("#result-message");
const tableWrapper = document.querySelector("#table-wrapper");
const resultHead = document.querySelector("#result-head");
const resultBody = document.querySelector("#result-body");

const API_PATH = "/api/v1/text2sql/query";
const REQUEST_TIMEOUT_MS = 130_000;

questionInput.addEventListener("input", updateCharacterCount);

document.querySelectorAll("[data-question]").forEach((button) => {
    button.addEventListener("click", () => {
        questionInput.value = button.dataset.question;
        updateCharacterCount();
        questionInput.focus();
    });
});

form.addEventListener("submit", async (event) => {
    event.preventDefault();

    const question = questionInput.value.trim();
    if (!question) {
        showError("INVALID_QUESTION", "질문을 입력해 주세요.", 0);
        questionInput.focus();
        return;
    }

    setBusy(true);
    resetResult();

    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    try {
        const response = await fetch(API_PATH, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ question }),
            signal: controller.signal
        });

        const payload = await readJsonResponse(response);
        if (!response.ok) {
            showError(
                    payload.code ?? "REQUEST_FAILED",
                    errorDescription(payload.code),
                    payload.attemptCount ?? 0
            );
            return;
        }

        renderResult(payload);
    } catch (error) {
        if (error.name === "AbortError") {
            showError("CLIENT_TIMEOUT", "응답 대기 시간이 130초를 넘었습니다. Ollama와 서버 상태를 확인해 주세요.", 0);
        } else {
            showError("NETWORK_ERROR", "서버와 통신하지 못했습니다. Spring Boot가 실행 중인지 확인해 주세요.", 0);
        }
    } finally {
        window.clearTimeout(timeoutId);
        setBusy(false);
    }
});

/**
 * HTTP 본문이 JSON이라는 가정을 무조건 신뢰하지 않는다. 프록시나 예상하지 못한 서버 오류가
 * HTML을 반환해도 화면이 깨지지 않도록 안정적인 클라이언트 오류로 축약한다.
 */
async function readJsonResponse(response) {
    const text = await response.text();
    if (!text) {
        return {};
    }

    try {
        return JSON.parse(text);
    } catch (_ignored) {
        return { code: "INVALID_SERVER_RESPONSE", attemptCount: 0 };
    }
}

/**
 * 서버가 반환한 컬럼과 셀 값도 화면 관점에서는 신뢰할 수 없는 데이터다. innerHTML 문자열을
 * 만들지 않고 모든 값을 textContent로 넣어 HTML이나 스크립트로 해석될 가능성을 차단한다.
 */
function renderResult(payload) {
    const columns = Array.isArray(payload.columns) ? payload.columns : [];
    const rows = Array.isArray(payload.rows) ? payload.rows : [];

    if (columns.length === 0) {
        showError("INVALID_SERVER_RESPONSE", "서버 응답에 결과 컬럼이 없습니다.", payload.attemptCount ?? 0);
        return;
    }

    resultPanel.hidden = false;
    resultMessage.hidden = true;
    tableWrapper.hidden = false;

    const headerRow = document.createElement("tr");
    columns.forEach((column) => {
        const cell = document.createElement("th");
        cell.scope = "col";
        cell.textContent = String(column);
        headerRow.appendChild(cell);
    });
    resultHead.replaceChildren(headerRow);

    const rowElements = rows.map((row) => {
        const rowElement = document.createElement("tr");
        const cells = Array.isArray(row) ? row : [row];
        columns.forEach((_column, index) => {
            const cell = document.createElement("td");
            cell.textContent = formatCell(cells[index]);
            rowElement.appendChild(cell);
        });
        return rowElement;
    });
    resultBody.replaceChildren(...rowElements);

    if (rows.length === 0) {
        const emptyRow = document.createElement("tr");
        const emptyCell = document.createElement("td");
        emptyCell.colSpan = columns.length;
        emptyCell.textContent = "조건에 맞는 결과가 없습니다.";
        emptyRow.appendChild(emptyCell);
        resultBody.replaceChildren(emptyRow);
    }

    renderMetadata(payload.attemptCount ?? 0, payload.elapsedMs ?? 0, payload.truncated === true);
    resultPanel.scrollIntoView({ behavior: "smooth", block: "start" });
}

function showError(code, description, attemptCount) {
    resultPanel.hidden = false;
    tableWrapper.hidden = true;
    resultHead.replaceChildren();
    resultBody.replaceChildren();
    resultMetadata.replaceChildren();

    const title = document.createElement("strong");
    title.textContent = code;
    const detail = document.createElement("span");
    detail.textContent = description;
    resultMessage.replaceChildren(title, detail);
    resultMessage.hidden = false;

    if (attemptCount > 0) {
        renderMetadata(attemptCount, null, false);
    }
    resultPanel.scrollIntoView({ behavior: "smooth", block: "start" });
}

function renderMetadata(attemptCount, elapsedMs, truncated) {
    const items = [`LLM 호출 ${attemptCount}회`];
    if (elapsedMs !== null) {
        items.push(`총 ${Number(elapsedMs).toLocaleString("ko-KR")}ms`);
    }
    if (truncated) {
        items.push("최대 행 수로 잘림");
    }

    const badges = items.map((item) => {
        const badge = document.createElement("span");
        badge.textContent = item;
        return badge;
    });
    resultMetadata.replaceChildren(...badges);
}

function formatCell(value) {
    if (value === null || value === undefined) {
        return "NULL";
    }
    if (typeof value === "object") {
        return JSON.stringify(value);
    }
    return String(value);
}

function setBusy(busy) {
    submitButton.disabled = busy;
    submitButton.classList.toggle("is-loading", busy);
    buttonLabel.textContent = busy ? "검증하고 있습니다" : "안전하게 조회하기";
    questionInput.readOnly = busy;
}

function resetResult() {
    resultPanel.hidden = true;
    resultMessage.hidden = true;
    tableWrapper.hidden = true;
    resultMetadata.replaceChildren();
    resultHead.replaceChildren();
    resultBody.replaceChildren();
}

function updateCharacterCount() {
    characterCount.textContent = `${questionInput.value.length} / 1000`;
}

function errorDescription(code) {
    switch (code) {
        case "INVALID_QUESTION":
            return "질문이 비어 있거나 허용 길이를 넘었습니다.";
        case "SQL_GENERATION_FAILED":
            return "세 번 안에 검증 가능한 조회문을 만들지 못했습니다. 질문을 더 구체적으로 바꿔 보세요.";
        case "LLM_REQUEST_REJECTED":
            return "Ollama가 요청을 거부했습니다. 모델 설정을 확인해 주세요.";
        case "LLM_UNAVAILABLE":
            return "Ollama에 연결할 수 없습니다. 로컬 앱과 모델 상태를 확인해 주세요.";
        case "DB_TIMEOUT":
            return "조회가 허용 시간을 초과해 중단됐습니다.";
        case "DB_PERMISSION_DENIED":
            return "읽기 전용 계정에서 허용되지 않은 조회입니다.";
        case "DB_UNAVAILABLE":
            return "PostgreSQL에 연결할 수 없습니다.";
        default:
            return "요청을 처리하지 못했습니다. 서버 로그와 실행 상태를 확인해 주세요.";
    }
}
