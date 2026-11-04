import { isTicket, measuredMessage } from "./ticket-ui.mjs";

const fields = (value, names) => value !== null && typeof value === "object" && !Array.isArray(value)
    && Object.keys(value).length === names.length && names.every((name) => Object.hasOwn(value, name));
const isId = (value) => Number.isSafeInteger(value) && value > 0;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isConversationMessage(message) {
    return fields(message, ["id", "type", "body", "createdAt", "correctsMessageId"])
        && isId(message.id) && ["INQUIRY", "OFFICIAL_REPLY", "CUSTOMER_FOLLOWUP"].includes(message.type)
        && typeof message.body === "string" && measuredMessage(message.body).length > 0
        && typeof message.createdAt === "string" && Number.isFinite(Date.parse(message.createdAt))
        && (message.correctsMessageId === null || isId(message.correctsMessageId)
            && message.correctsMessageId !== message.id && message.type === "OFFICIAL_REPLY");
}

export function isConversation(value, ticketId, afterId = 0) {
    if (!fields(value, ["ticket", "capabilities", "messages", "page"])
            || !isTicket(value.ticket) || value.ticket.id !== ticketId
            || !fields(value.capabilities, ["canReply", "canFollowUp", "canViewConfirmations", "expectedAssignmentVersion"])
            || typeof value.capabilities.canReply !== "boolean" || typeof value.capabilities.canFollowUp !== "boolean"
            || typeof value.capabilities.canViewConfirmations !== "boolean"
            || value.capabilities.canReply && !value.capabilities.canViewConfirmations
            || (value.capabilities.canReply ? !isId(value.capabilities.expectedAssignmentVersion) : value.capabilities.expectedAssignmentVersion !== null)
            || !Array.isArray(value.messages) || value.messages.length > 50
            || !fields(value.page, ["nextAfterId", "hasMore"]) || typeof value.page.hasMore !== "boolean") return false;
    let previous = afterId;
    if (!value.messages.every((message) => {
        if (!isConversationMessage(message) || message.id <= previous) return false;
        previous = message.id;
        return true;
    })) return false;
    return value.page.hasMore
        ? value.messages.length > 0 && value.page.nextAfterId === previous
        : value.page.nextAfterId === null;
}

function failure(status) {
    return { kind: ({400: "bad-request", 401: "login-required", 403: "forbidden", 404: "not-found", 409: "conflict"})[status]
        ?? "request-unavailable", status };
}

export function createConversationClient({fetchImpl, view, newRequestId}) {
    let selected = null, selection = 0, latestRead = 0, readController = null;
    let canReply = false, canFollowUp = false, posting = false, pending = null, pendingKind = null, uncertain = false;
    let draftVersion = null, currentReplyVersion = null, replyVersionRejected = false;
    const needsReplyReview = () => replyVersionRejected
        || draftVersion !== null && currentReplyVersion !== null && draftVersion !== currentReplyVersion;
    const render = (state) => view.render({...state, replyDraftVersion: draftVersion, replyCurrentVersion: currentReplyVersion,
        replyReviewRequired: needsReplyReview(), canReconfirm: pendingKind === "reply" && !replyVersionRejected,
        canReviewReply: needsReplyReview() && (pending === null || replyVersionRejected)});

    async function read(ticketId, afterId = 0) {
        readController?.abort();
        const request = ++latestRead;
        if (selected !== ticketId) { selected = ticketId; selection++; pending = null; pendingKind = null; uncertain = false; canReply = false; canFollowUp = false; draftVersion = null; currentReplyVersion = null; replyVersionRejected = false; }
        const current = () => request === latestRead && selected === ticketId;
        if (!isId(ticketId) || !Number.isSafeInteger(afterId) || afterId < 0) {
            canReply = false; canFollowUp = false;
            render({kind: "invalid-id"});
            return false;
        }
        readController = new AbortController();
        render({kind: "loading", append: afterId > 0});
        try {
            const response = await fetchImpl(`/api/tickets/${ticketId}/messages?afterId=${afterId}&limit=50`, {
                method: "GET", credentials: "same-origin", cache: "no-store", signal: readController.signal
            });
            if (!current()) return false;
            if (!response.ok) { canReply = false; canFollowUp = false; render(failure(response.status)); return false; }
            const body = await response.json();
            if (!current()) return false;
            if (!isConversation(body, ticketId, afterId)) {
                canReply = false; canFollowUp = false; render({kind: "invalid-response"}); return false;
            }
            canReply = body.capabilities.canReply;
            canFollowUp = body.capabilities.canFollowUp;
            currentReplyVersion = body.capabilities.expectedAssignmentVersion;
            if (canReply && draftVersion === null) draftVersion = body.capabilities.expectedAssignmentVersion;
            render({kind: "conversation", conversation: body, append: afterId > 0,
                canReconfirmFollowup: pendingKind === "followup"});
            return true;
        } catch (error) {
            if (current() && error?.name !== "AbortError") { canReply = false; canFollowUp = false; render({kind: "request-unavailable"}); }
            return false;
        }
    }

    async function submit(kind, body, correction = null) {
        if (posting) return;
        if (pending && pendingKind !== kind) { render({kind: "unconfirmed-submission"}); return; }
        if (kind === "reply" && (replyVersionRejected || pending === null && needsReplyReview())) {
            render({kind: "reply-review-required"}); return;
        }
        if (!isId(selected) || !(kind === "reply" ? canReply : canFollowUp) && pending === null) { render({kind: "forbidden"}); return; }
        if (typeof body !== "string" || measuredMessage(body).length === 0
                || [...measuredMessage(body)].length > 2000 || correction !== null && !isId(correction)) {
            render({kind: "bad-request"}); return;
        }
        if (pending && (pending.body !== body || kind === "reply" && pending.correctsMessageId !== correction)) {
            render({kind: "unconfirmed-submission"}); return;
        }
        posting = true;
        const ticketId = selected, captured = selection;
        const current = () => selected === ticketId && captured === selection;
        render({kind: "posting", submissionKind: kind});
        try {
            let csrfResponse, csrf;
            try {
                csrfResponse = await fetchImpl("/api/csrf", {credentials: "same-origin", cache: "no-store"});
                if (!current()) return;
                if (!csrfResponse.ok) { render(failure(csrfResponse.status)); return; }
                csrf = await csrfResponse.json();
            } catch { if (current()) render({kind: "request-unavailable"}); return; }
            if (!current()) return;
            if (typeof csrf?.headerName !== "string" || !/^[A-Za-z0-9-]+$/.test(csrf.headerName)
                    || typeof csrf.token !== "string" || csrf.token.length === 0) {
                render({kind: "invalid-response"}); return;
            }
            if (!pending) {
                const id = newRequestId();
                if (typeof id !== "string" || !uuid.test(id)) { render({kind: "invalid-response"}); return; }
                pending = kind === "reply"
                    ? {body, clientRequestId: id, correctsMessageId: correction, expectedAssignmentVersion: draftVersion}
                    : {body, clientRequestId: id};
                pendingKind = kind;
            }
            let response;
            try {
                response = await fetchImpl(`/api/tickets/${ticketId}/${kind === "reply" ? "replies" : "customer-messages"}`, {
                    method: "POST", credentials: "same-origin", headers: {"Content-Type": "application/json", [csrf.headerName]: csrf.token},
                    body: JSON.stringify(pending)
                });
            } catch { if (current()) { uncertain = true; render({kind: "unknown-outcome"}); } return; }
            if (!current()) return;
            if (!response.ok) {
                if (kind === "reply" && response.status === 409) {
                    let problem;
                    try { problem = await response.json(); } catch { /* Keep ambiguous submissions unchanged. */ }
                    if (!current()) return;
                    if (problem !== null && typeof problem === "object" && !Array.isArray(problem)
                            && Object.hasOwn(problem, "code") && problem.code === "ASSIGNMENT_VERSION_CONFLICT") {
                        replyVersionRejected = true; currentReplyVersion = null; canReply = false;
                        latestRead++; readController?.abort();
                        render({kind: "reply-version-conflict"}); return;
                    }
                }
                // A 5xx may have happened after Commit. Keep the original submission for explicit verification.
                if (response.status >= 500 || response.status === 409) uncertain = true;
                if (!uncertain && response.status >= 400 && response.status < 500) { pending = null; pendingKind = null; }
                render(response.status >= 500 ? {kind: "unknown-outcome"} : failure(response.status));
                return;
            }
            let message;
            try { message = await response.json(); }
            catch { if (current()) { uncertain = true; render({kind: "unknown-outcome"}); } return; }
            if (!current()) return;
            if (![200, 201].includes(response.status) || !isConversationMessage(message)
                    || message.type !== (kind === "reply" ? "OFFICIAL_REPLY" : "CUSTOMER_FOLLOWUP") || message.body !== pending.body
                    || message.correctsMessageId !== (kind === "reply" ? pending.correctsMessageId : null)) {
                uncertain = true; render({kind: "unknown-outcome"}); return;
            }
            pending = null;
            pendingKind = null;
            uncertain = false;
            if (kind === "reply") draftVersion = null;
            const refreshed = await read(ticketId);
            if (current()) render({kind: `${kind}-saved${refreshed ? "" : "-refresh-failed"}`});
        } finally {
            posting = false;
            view.setPosting(false);
        }
    }

    async function reviewReply() {
        if (posting || !isId(selected) || !needsReplyReview() || pending !== null && !replyVersionRejected) return false;
        const ticketId = selected;
        pending = null; pendingKind = null; uncertain = false;
        draftVersion = null; currentReplyVersion = null; replyVersionRejected = false; canReply = false; canFollowUp = false;
        render({kind: "reply-draft-reset"});
        return read(ticketId);
    }

    function clear() {
        readController?.abort();
        latestRead++; selection++; selected = null; pending = null; pendingKind = null; uncertain = false; canReply = false; canFollowUp = false; draftVersion = null; currentReplyVersion = null; replyVersionRejected = false;
        render({kind: "selection-changed"});
    }

    return {read, reply: (body, correction = null) => submit("reply", body, correction),
        followUp: (body) => submit("followup", body), reviewReply, clear};
}
