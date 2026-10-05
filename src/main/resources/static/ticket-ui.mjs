const TICKET_STATUSES = new Set([
    "OPEN",
    "IN_PROGRESS",
    "RESOLVED"
]);

export function isTicket(value) {
    return value !== null
        && typeof value === "object"
        && !Array.isArray(value)
        && Number.isSafeInteger(value.id)
        && value.id > 0
        && typeof value.title === "string"
        && value.title.trim().length > 0
        && TICKET_STATUSES.has(value.status);
}

function isCsrfInfo(value) {
    return value !== null
        && typeof value === "object"
        && !Array.isArray(value)
        && typeof value.headerName === "string"
        && /^[A-Za-z0-9-]+$/.test(value.headerName)
        && typeof value.token === "string"
        && value.token.length > 0;
}

// Java String.strip()/isBlank()의 edge whitespace와 같은 범위를 사용한다.
// JS trim()은 NBSP 등을 추가로 제거하므로 서버의 문자 수 계산과 섞지 않는다.
function measuredMessage(value) {
    return value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/gu, "");
}

export function createTicketClient({ fetchImpl, view }) {
    let latestReadRequestId = 0;
    let activeReadController = null;
    let creating = false;

    function showHttpFailure(response) {
        if (response.status === 401) {
            view.render({ kind: "login-required" });
        } else if (response.status === 403) {
            view.render({ kind: "forbidden" });
        } else if (response.status === 404) {
            view.render({ kind: "not-found" });
        } else if (response.status === 400) {
            view.render({ kind: "bad-request" });
        } else if (!response.ok) {
            view.render({ kind: "http-error", status: response.status });
        } else {
            return false;
        }

        return true;
    }

    async function readTicket(id) {
        if (creating) {
            return;
        }

        if (!Number.isSafeInteger(id) || id <= 0) {
            view.render({ kind: "invalid-id" });
            return;
        }

        activeReadController?.abort();
        const controller = new AbortController();
        activeReadController = controller;
        const requestId = ++latestReadRequestId;
        const isCurrent = () => requestId === latestReadRequestId;

        view.render({ kind: "loading" });

        try {
            let response;

            try {
                response = await fetchImpl(`/api/tickets/${id}`, {
                    credentials: "same-origin",
                    signal: controller.signal
                });
            } catch (error) {
                if (!isCurrent() || error?.name === "AbortError") {
                    return;
                }

                view.render({ kind: "request-unavailable" });
                return;
            }

            if (!isCurrent()) {
                return;
            }

            if (showHttpFailure(response)) {
                return;
            }

            let body;

            try {
                body = await response.json();
            } catch (error) {
                if (!isCurrent() || error?.name === "AbortError") {
                    return;
                }

                view.render({ kind: "invalid-response" });
                return;
            }

            if (!isCurrent()) {
                return;
            }

            if (!isTicket(body)) {
                view.render({ kind: "invalid-response" });
                return;
            }

            view.render({ kind: "success", ticket: body });
        } finally {
            if (activeReadController === controller) {
                activeReadController = null;
            }
        }
    }

    async function createTicket(title, messageBody) {
        if (creating) {
            return;
        }

        if (typeof title !== "string" || title.trim().length === 0) {
            view.render({ kind: "invalid-title" });
            return;
        }

        // undefined는 이전 in-memory 제목 전용 실험 호출에만 사용한다.
        // PostgreSQL은 body 누락을 서버에서도 거부한다.
        if (messageBody !== undefined) {
            if (typeof messageBody !== "string") {
                view.render({ kind: "invalid-body" });
                return;
            }
            const measured = measuredMessage(messageBody);
            if (measured.length === 0 || [...measured].length > 2000) {
                view.render({ kind: "invalid-body" });
                return;
            }
        }

        creating = true;
        activeReadController?.abort();
        activeReadController = null;
        latestReadRequestId++;
        view.render({ kind: "creating" });

        try {
            let csrfResponse;

            try {
                csrfResponse = await fetchImpl("/api/csrf", {
                    credentials: "same-origin"
                });
            } catch {
                view.render({ kind: "request-unavailable" });
                return;
            }

            if (showHttpFailure(csrfResponse)) {
                return;
            }

            let csrf;

            try {
                csrf = await csrfResponse.json();
            } catch {
                view.render({ kind: "invalid-response" });
                return;
            }

            if (!isCsrfInfo(csrf)) {
                view.render({ kind: "invalid-response" });
                return;
            }

            let response;

            try {
                response = await fetchImpl("/api/tickets", {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {
                        "Content-Type": "application/json",
                        [csrf.headerName]: csrf.token
                    },
                    body: JSON.stringify(messageBody === undefined
                        ? { title }
                        : { title, body: messageBody })
                });
            } catch {
                // The server may already have committed the POST.
                view.render({ kind: "unknown-outcome" });
                return;
            }

            if (showHttpFailure(response)) {
                return;
            }

            let body;

            try {
                body = await response.json();
            } catch {
                view.render({ kind: "invalid-response" });
                return;
            }

            if (!isTicket(body)) {
                view.render({ kind: "invalid-response" });
                return;
            }

            view.addTicket(body);
            view.render({ kind: "created", ticket: body });
        } finally {
            creating = false;
        }
    }

    return { readTicket, createTicket };
}
