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
        && value.token.length > 0
        && (value.receiptContext === undefined || value.receiptContext === null
            || (typeof value.receiptContext === "string" && value.receiptContext.length > 0));
}

// Java String.strip()/isBlank()의 edge whitespace와 같은 범위를 사용한다.
// JS trim()은 NBSP 등을 추가로 제거하므로 서버의 문자 수 계산과 섞지 않는다.
export function measuredMessage(value) {
    return value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/gu, "");
}

export function createTicketClient({
    fetchImpl,
    view,
    newRequestId = () => globalThis.crypto?.randomUUID?.()
}) {
    let latestReadRequestId = 0;
    let activeReadController = null;
    let creating = false;
    let pendingReceipt = null;
    let receiptLookupRequired = false;

    function httpFailure(response) {
        if (response.status === 401) {
            return { kind: "login-required" };
        }
        if (response.status === 403) {
            return { kind: "forbidden" };
        }
        if (response.status === 404) {
            return { kind: "not-found" };
        }
        if (response.status === 400) {
            return { kind: "bad-request" };
        }
        return response.ok ? null : { kind: "http-error", status: response.status };
    }

    function startReceiptRequest(kind) {
        creating = true;
        activeReadController?.abort();
        activeReadController = null;
        latestReadRequestId++;
        view.render({ kind });
    }

    function showUnknownReceipt() {
        view.render({ kind: "unknown-outcome", canConfirm: pendingReceipt !== null });
    }

    function requireReceiptLookup(kind = "receipt-check-list") {
        pendingReceipt = null;
        receiptLookupRequired = true;
        view.render({ kind });
    }

    async function loadCsrf() {
        let response;
        try {
            response = await fetchImpl("/api/csrf", { credentials: "same-origin", cache: "no-store" });
        } catch {
            return { failure: { kind: "request-unavailable" } };
        }
        const failure = httpFailure(response);
        if (failure) {
            return { failure };
        }

        try {
            const csrf = await response.json();
            if (isCsrfInfo(csrf)) {
                return { csrf };
            }
        } catch {
            // Without a usable CSRF response, the POST has not been sent.
        }
        return { failure: { kind: "invalid-response" } };
    }

    async function sendReceipt(payload, csrf, confirming) {
        let response;
        try {
            response = await fetchImpl("/api/tickets", {
                method: "POST",
                credentials: "same-origin",
                headers: {
                    "Content-Type": "application/json",
                    [csrf.headerName]: csrf.token
                },
                body: JSON.stringify(payload)
            });
        } catch {
            if (!receiptLookupRequired) {
                showUnknownReceipt();
            }
            return;
        }

        if (receiptLookupRequired) {
            return;
        }
        const failure = httpFailure(response);
        if (failure) {
            if (pendingReceipt && response.status === 409) {
                requireReceiptLookup("receipt-conflict");
            } else if (confirming && (response.status === 401 || response.status === 403)) {
                requireReceiptLookup();
            } else if (confirming || (pendingReceipt && response.status >= 500)) {
                showUnknownReceipt();
            } else {
                pendingReceipt = null;
                view.render(failure);
            }
            return;
        }

        let ticket;
        try {
            ticket = await response.json();
        } catch {
            // A response body that cannot be read leaves the result unclear.
        }

        if (receiptLookupRequired) {
            return;
        }
        if (!isTicket(ticket) || (pendingReceipt && response.status !== 201 && response.status !== 200)) {
            if (pendingReceipt) {
                showUnknownReceipt();
            } else {
                view.render({ kind: "invalid-response" });
            }
            return;
        }

        pendingReceipt = null;
        view.addTicket(ticket);
        view.render({ kind: response.status === 200 ? "receipt-confirmed" : "created", ticket });
    }

    async function readTicket(id) {
        if (creating || pendingReceipt || receiptLookupRequired) {
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

            const failure = httpFailure(response);
            if (failure) {
                view.render(failure);
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
        if (creating || pendingReceipt || receiptLookupRequired) {
            return;
        }

        if (typeof title !== "string" || title.trim().length === 0) {
            view.render({ kind: "invalid-title" });
            return;
        }

        // The old in-memory example allows a title-only request.
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

        startReceiptRequest("creating");
        try {
            const { csrf, failure } = await loadCsrf();
            if (failure) {
                view.render({ ...failure, beforeSubmission: true });
                return;
            }

            const payload = messageBody === undefined ? { title } : { title, body: messageBody };
            if (csrf.receiptContext) {
                const requestId = newRequestId();
                if (typeof requestId !== "string" || requestId.length === 0) {
                    view.render({ kind: "receipt-id-unavailable" });
                    return;
                }
                payload.clientRequestId = requestId;
                pendingReceipt = { payload, csrf };
            }
            await sendReceipt(payload, csrf, false);
        } finally {
            creating = false;
        }
    }

    async function confirmReceipt() {
        if (creating || !pendingReceipt) {
            return;
        }
        const receipt = pendingReceipt;
        startReceiptRequest("confirming-receipt");

        try {
            const { csrf, failure } = await loadCsrf();
            if (receipt !== pendingReceipt) {
                return;
            }
            if (failure) {
                if (failure.kind === "login-required" || failure.kind === "forbidden") {
                    requireReceiptLookup();
                } else {
                    showUnknownReceipt();
                }
                return;
            }
            if (csrf.receiptContext !== receipt.csrf.receiptContext) {
                requireReceiptLookup();
                return;
            }
            // The original token also protects against a session switch after the GET.
            await sendReceipt(receipt.payload, receipt.csrf, true);
        } finally {
            creating = false;
        }
    }

    function resetReceiptAfterPageRestore() {
        if (pendingReceipt) {
            requireReceiptLookup();
        }
    }

    return { readTicket, createTicket, confirmReceipt, resetReceiptAfterPageRestore };
}
