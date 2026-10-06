const JOB_STATUSES = new Set(["PENDING", "RUNNING", "FAILED", "SUCCEEDED", "ABSTAINED"]);
const FAILURE_CODES = new Set([
    "PROVIDER_REFUSED", "PROVIDER_RATE_LIMITED", "PROVIDER_OUTCOME_UNKNOWN",
    "OUTPUT_INVALID", "OUTPUT_REQUIRED_FIELD_MISSING", "OUTPUT_REPAIR_LIMIT_EXHAUSTED",
    "GENERATION_LIMIT_EXHAUSTED", "JOB_PROCESSING_DEADLINE_EXCEEDED",
    "RESULT_STORAGE_RETRY_EXHAUSTED", "ADAPTER_CONFIGURATION_ERROR"
]);
const CATEGORIES = new Set(["ACCOUNT", "BILLING", "TECHNICAL", "OTHER", "UNDETERMINED"]);
const PRIORITIES = new Set(["NORMAL", "HIGH", "UNDETERMINED"]);

function hasFields(value, fields) {
    return value !== null
        && typeof value === "object"
        && !Array.isArray(value)
        && Object.keys(value).length === fields.length
        && fields.every((field) => Object.hasOwn(value, field));
}

function isId(value) {
    return Number.isSafeInteger(value) && value > 0;
}

export function isAiSuggestionQuery(value, ticketId) {
    if (!hasFields(value, ["ticketId", "job", "suggestion"])
            || !isId(value.ticketId) || value.ticketId !== ticketId) {
        return false;
    }

    if (value.job === null) {
        return value.suggestion === null;
    }

    const job = value.job;
    if (!hasFields(job, ["id", "status", "failureCode"])
            || !isId(job.id) || !JOB_STATUSES.has(job.status)) {
        return false;
    }
    if (job.status === "FAILED"
            ? !FAILURE_CODES.has(job.failureCode)
            : job.failureCode !== null) {
        return false;
    }
    if (job.status !== "SUCCEEDED") {
        return value.suggestion === null;
    }

    const suggestion = value.suggestion;
    return hasFields(suggestion, ["id", "summary", "categories", "priority", "reviewStatus"])
        && isId(suggestion.id)
        && typeof suggestion.summary === "string"
        && suggestion.summary.trim().length > 0
        && Array.isArray(suggestion.categories)
        && suggestion.categories.length > 0
        && suggestion.categories.every((category) => CATEGORIES.has(category))
        && new Set(suggestion.categories).size === suggestion.categories.length
        && PRIORITIES.has(suggestion.priority)
        && suggestion.reviewStatus === "PENDING_REVIEW";
}

function httpFailure(response) {
    const kinds = {
        400: "bad-request",
        401: "login-required",
        403: "forbidden",
        404: "not-found"
    };
    return { kind: kinds[response.status] ?? "query-error", status: response.status };
}

export function createAiSuggestionClient({ fetchImpl, view }) {
    let latestRequestId = 0;
    let activeController = null;

    async function readSuggestion(ticketId) {
        // 입력이 잘못됐을 때도 이전 조회를 무효화한다.
        activeController?.abort();
        activeController = null;
        const requestId = ++latestRequestId;
        const isCurrent = () => requestId === latestRequestId;

        if (!isId(ticketId)) {
            view.render({ kind: "invalid-id" });
            return;
        }

        const controller = new AbortController();
        activeController = controller;
        view.render({ kind: "loading" });

        try {
            let response;
            try {
                response = await fetchImpl(`/api/tickets/${ticketId}/ai-suggestion`, {
                    method: "GET",
                    credentials: "same-origin",
                    cache: "no-store",
                    signal: controller.signal
                });
            } catch (error) {
                if (isCurrent() && error?.name !== "AbortError") {
                    view.render({ kind: "request-unavailable" });
                }
                return;
            }

            if (!isCurrent()) {
                return;
            }
            if (!response.ok) {
                view.render(httpFailure(response));
                return;
            }

            let body;
            try {
                body = await response.json();
            } catch (error) {
                if (isCurrent() && error?.name !== "AbortError") {
                    view.render({ kind: "invalid-response" });
                }
                return;
            }

            if (!isCurrent()) {
                return;
            }
            if (!isAiSuggestionQuery(body, ticketId)) {
                view.render({ kind: "invalid-response" });
                return;
            }

            // HTTP 조회 성공과 AI 작업 결과는 별도로 판단한다.
            view.render({
                kind: body.job === null ? "no-job" : body.job.status.toLowerCase(),
                result: body
            });
        } finally {
            if (activeController === controller) {
                activeController = null;
            }
        }
    }

    // 생성·재시도·주기적 Polling은 이 읽기 Client의 책임이 아니다.
    return { readSuggestion };
}

const MESSAGES = {
    "loading": "AI 작업 상태를 조회 중입니다.",
    "invalid-id": "올바른 Ticket ID를 입력하세요.",
    "login-required": "로그인이 필요합니다.",
    "forbidden": "AI 제안 조회에는 AGENT 권한이 필요합니다.",
    "not-found": "Ticket을 찾을 수 없습니다.",
    "bad-request": "조회 요청을 확인하세요.",
    "request-unavailable": "조회 응답을 읽지 못했습니다. 연결 또는 Browser 정책을 확인하세요.",
    "invalid-response": "AI 상태 조회 응답 형식이 올바르지 않습니다.",
    "no-job": "등록된 AI 작업이 없습니다.",
    "pending": "AI 처리 대기 중입니다.",
    "running": "AI 처리 중입니다.",
    "failed": "AI 처리가 실패했습니다. 접수한 문의는 유지됩니다.",
    "abstained": "AI가 제안 생성을 보류했습니다.",
    "succeeded": "AI 제안 생성 완료·담당자 검토 대기"
};

export function createAiSuggestionView({
    statusElement, resultElement, suggestionElement,
    ticketIdElement, jobStatusElement, failureCodeElement,
    summaryElement, categoriesElement, priorityElement, reviewStatusElement
}) {
    const fields = [ticketIdElement, jobStatusElement, failureCodeElement,
        summaryElement, categoriesElement, priorityElement, reviewStatusElement];

    return {
        render(state) {
            // 새 요청·오류·제안 부재에 이전 요약이나 실패 코드가 남지 않게 한다.
            for (const field of fields) {
                field.textContent = "";
            }
            resultElement.hidden = true;
            suggestionElement.hidden = true;
            statusElement.textContent = state.kind === "query-error"
                ? `HTTP ${state.status}: AI 상태 조회에 실패했습니다.`
                : MESSAGES[state.kind];

            if (state.result === undefined) {
                return;
            }

            const { ticketId, job, suggestion } = state.result;
            ticketIdElement.textContent = String(ticketId);
            jobStatusElement.textContent = job === null ? "미등록" : job.status;
            failureCodeElement.textContent = job?.failureCode ?? "없음";
            resultElement.hidden = false;

            if (state.kind === "succeeded") {
                summaryElement.textContent = suggestion.summary;
                categoriesElement.textContent = suggestion.categories.join(", ");
                priorityElement.textContent = suggestion.priority;
                reviewStatusElement.textContent = "PENDING_REVIEW — 담당자 검토 대기";
                suggestionElement.hidden = false;
            }
        }
    };
}
