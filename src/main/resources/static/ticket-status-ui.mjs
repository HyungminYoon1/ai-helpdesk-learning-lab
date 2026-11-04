const fields = (v, names) => v !== null && typeof v === "object" && !Array.isArray(v)
    && Object.keys(v).length === names.length && names.every((n) => Object.hasOwn(v, n));
const id = (n) => Number.isSafeInteger(n) && n > 0;
const version = (n) => Number.isSafeInteger(n) && n >= 0;
const statuses = new Set(["OPEN", "IN_PROGRESS", "RESOLVED"]);
const actions = {START: ["OPEN", "IN_PROGRESS", "canStart"], RESOLVE: ["IN_PROGRESS", "RESOLVED", "canResolve"], REOPEN: ["RESOLVED", "OPEN", "canReopen"]};
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const codeStatus = new Map([["INVALID_STATUS_REQUEST", 400], ["INVALID_STATUS_TARGET", 400], ["NOT_ASSIGNEE", 403],
    ["STATUS_VERSION_CONFLICT", 409], ["ASSIGNMENT_VERSION_CONFLICT", 409], ["INVALID_STATUS_TRANSITION", 409],
    ["NEW_CUSTOMER_MESSAGE", 409], ["CUSTOMER_CONFIRMATION_REQUIRED", 409], ["REQUESTER_UNCONFIRMED", 409]]);
const failure = (s) => ({401: "login-required", 403: "forbidden", 404: "not-found"})[s] ?? "unavailable";

export function isTicketStatusCurrent(value, scope) {
    const names = scope === "STAFF" ? ["status", "statusVersion", "assignmentVersion", "latestCustomerMessageId",
        "effectiveThroughMessageId", "canStart", "canResolve", "canReopen"] : ["status", "statusVersion", "canReopen"];
    if (!fields(value, names) || !statuses.has(value.status) || !version(value.statusVersion) || typeof value.canReopen !== "boolean") return false;
    if (scope !== "STAFF") return value.canReopen === (value.status === "RESOLVED");
    return version(value.assignmentVersion) && ["canStart", "canResolve"].every((k) => typeof value[k] === "boolean")
        && (value.latestCustomerMessageId === null || id(value.latestCustomerMessageId))
        && (value.effectiveThroughMessageId === null || id(value.effectiveThroughMessageId))
        && (!value.canStart || value.status === "OPEN" && value.assignmentVersion > 0)
        && (!value.canReopen || value.status === "RESOLVED" && value.assignmentVersion > 0)
        && (!value.canResolve || value.status === "IN_PROGRESS" && value.assignmentVersion > 0
            && value.latestCustomerMessageId !== null && value.effectiveThroughMessageId >= value.latestCustomerMessageId);
}

// Draft versions are captured on explicit editing; reads never rewrite a pending command.
export function createTicketStatusClient({fetchImpl, view, newRequestId}) {
    let ticket = null, scope = null, generation = 0, readSequence = 0, current = null, draft = null, pending = null;
    let saved = null, posting = false, reading = false, rejected = false, suspended = false, lastKind = "cleared";
    const stale = () => draft !== null && current !== null && (draft.expectedStatusVersion !== current.statusVersion
        || scope === "STAFF" && draft.expectedAssignmentVersion !== current.assignmentVersion);
    const validDraft = () => draft !== null && Object.hasOwn(actions, draft.action) && typeof draft.reason === "string"
        && draft.reason.trim().length > 0 && [...draft.reason].length <= 500
        && (draft.action !== "RESOLVE" || id(draft.relatedReplyMessageId) && id(draft.throughCustomerMessageId));
    const canNew = () => current !== null && validDraft() && !stale() && current[actions[draft.action][2]] === true;
    function render(kind) {
        lastKind = kind;
        view.render({kind: suspended ? "hidden" : kind, scope, current, draft: draft && {...draft}, saved,
            busy: posting || reading, locked: posting || reading || pending !== null,
            canSubmit: !posting && !reading && !rejected && (pending !== null || canNew()),
            canReset: !posting && !reading && (rejected || pending === null && stale()), canReconfirm: pending !== null && !rejected});
    }
    function clear() {
        generation++; readSequence++; ticket = null; scope = null; current = null; draft = null; pending = null;
        saved = null; posting = false; reading = false; rejected = false; suspended = false; render("cleared");
    }
    async function open(nextTicket, nextScope) {
        if (!id(nextTicket) || !["STAFF", "REQUESTER"].includes(nextScope)) { clear(); return false; }
        if (nextTicket !== ticket || scope !== nextScope) { clear(); ticket = nextTicket; scope = nextScope; }
        suspended = false;
        return refresh();
    }
    async function refresh() {
        if (!id(ticket)) return false;
        const selected = ticket, selectedScope = scope, gen = generation, sequence = ++readSequence;
        const active = () => ticket === selected && scope === selectedScope && generation === gen && sequence === readSequence;
        reading = true; render("loading");
        try {
            const response = await fetchImpl(`/api/tickets/${selected}/${selectedScope === "STAFF" ? "status" : "customer-status"}`,
                {credentials: "same-origin", cache: "no-store"});
            if (!active()) return false;
            if (!response.ok) { current = null; reading = false; render(failure(response.status)); return false; }
            const data = await response.json();
            if (!active()) return false;
            if (!isTicketStatusCurrent(data, selectedScope)) throw new Error("invalid status response");
            current = data; reading = false; render("current"); return true;
        } catch { if (active()) { current = null; reading = false; render("unavailable"); } return false; }
    }
    function edit(values) {
        if (posting || reading || pending !== null || current === null || stale()) return false;
        if (!draft) draft = {expectedStatusVersion: current.statusVersion,
            ...(scope === "STAFF" ? {expectedAssignmentVersion: current.assignmentVersion} : {})};
        Object.assign(draft, {action: scope === "REQUESTER" ? "REOPEN" : values.action, reason: values.reason,
            relatedReplyMessageId: values.relatedReplyMessageId, throughCustomerMessageId: values.throughCustomerMessageId});
        render("draft"); return true;
    }
    function validChange(data, command, selectedScope) {
        const c = data?.change, action = selectedScope === "STAFF" ? command.action : "REOPEN";
        if (!c || !id(c.id) || c.fromStatus !== actions[action][0] || c.toStatus !== actions[action][1]
                || c.statusVersion !== command.expectedStatusVersion + 1 || c.reason !== command.reason
                || typeof c.recordedAt !== "string" || !Number.isFinite(Date.parse(c.recordedAt))
                || !isTicketStatusCurrent(data.current, selectedScope) || data.current.statusVersion < c.statusVersion) return false;
        if (selectedScope === "REQUESTER") return fields(c, ["id", "fromStatus", "toStatus", "statusVersion", "reason", "recordedAt"]);
        return c.ticketId === ticket && c.clientRequestId === command.clientRequestId && c.requestScope === "STAFF"
            && c.action === command.action && c.expectedStatusVersion === command.expectedStatusVersion
            && c.expectedAssignmentVersion === command.expectedAssignmentVersion && c.assignmentVersion === command.expectedAssignmentVersion
            && (c.relatedReplyMessageId ?? null) === (command.relatedReplyMessageId ?? null)
            && (c.throughCustomerMessageId ?? null) === (command.throughCustomerMessageId ?? null);
    }
    async function submit() {
        if (posting || reading || rejected || !pending && !canNew()) { render("invalid-input"); return false; }
        const selected = ticket, selectedScope = scope, gen = generation;
        const active = () => selected === ticket && selectedScope === scope && gen === generation;
        posting = true; readSequence++; render("posting");
        try {
            let csrf;
            try {
                const response = await fetchImpl("/api/csrf", {credentials: "same-origin", cache: "no-store"});
                if (!active()) return false;
                if (!response.ok) { render(failure(response.status)); return false; }
                csrf = await response.json();
            } catch { if (active()) render("unavailable"); return false; }
            if (!active()) return false;
            if (!fields(csrf, ["headerName", "token"]) || typeof csrf.headerName !== "string" || !/^[A-Za-z0-9-]+$/.test(csrf.headerName)
                    || typeof csrf.token !== "string" || !csrf.token) { render("invalid-input"); return false; }
            if (!pending) {
                if (!canNew()) { render("invalid-input"); return false; }
                const key = newRequestId();
                if (typeof key !== "string" || !uuid.test(key)) { render("invalid-input"); return false; }
                pending = {clientRequestId: key, expectedStatusVersion: draft.expectedStatusVersion, reason: draft.reason};
                if (selectedScope === "STAFF") Object.assign(pending, {action: draft.action, expectedAssignmentVersion: draft.expectedAssignmentVersion});
                if (draft.action === "RESOLVE") Object.assign(pending, {relatedReplyMessageId: draft.relatedReplyMessageId, throughCustomerMessageId: draft.throughCustomerMessageId});
            }
            let response;
            try {
                response = await fetchImpl(`/api/tickets/${selected}/${selectedScope === "STAFF" ? "status-changes" : "customer-status/reopen"}`,
                    {method: "POST", credentials: "same-origin", headers: {"Content-Type": "application/json", [csrf.headerName]: csrf.token}, body: JSON.stringify(pending)});
            } catch { if (active()) render("unknown-outcome"); return false; }
            if (!active()) return false;
            if (!response.ok) {
                let problem;
                try { problem = await response.json(); } catch { /* Unreadable error keeps the original command. */ }
                if (!active()) return false;
                if (problem && Object.hasOwn(problem, "code") && codeStatus.get(problem.code) === response.status) {
                    rejected = true; render("rejected");
                } else { if ([401, 403, 404].includes(response.status)) current = null; render("unknown-outcome"); }
                return false;
            }
            let data;
            try { data = await response.json(); } catch { if (active()) render("unknown-outcome"); return false; }
            if (!active()) return false;
            if (![200, 201].includes(response.status) || !validChange(data, pending, selectedScope)) { render("unknown-outcome"); return false; }
            saved = data.change; pending = null; draft = null; rejected = false; current = null; render("saved");
            if (!await refresh() && active() && current === null && !reading) render("saved-refresh-failed");
            return true;
        } finally { if (active()) { posting = false; render(lastKind); } }
    }
    async function resetRejected() {
        if (posting || reading || !rejected && (pending !== null || !stale())) return false;
        draft = null; pending = null; rejected = false; saved = null; current = null; render("reset");
        return refresh();
    }
    function suspend() { readSequence++; reading = false; current = null; suspended = true; render("hidden"); }
    return {open, refresh, edit, submit, resetRejected, suspend, clear};
}
