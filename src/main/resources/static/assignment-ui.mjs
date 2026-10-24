const id = (value) => Number.isSafeInteger(value) && value > 0;
const version = (value) => Number.isSafeInteger(value) && value >= 0;
const object = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
export const isAssignment = (data, ticket) => object(data) && data.ticketId === ticket && id(data.serviceId)
    && version(data.version) && (data.assigneeAgentId === null ? data.version === 0 : id(data.assigneeAgentId) && data.version > 0)
    && ["canClaim", "canTransfer", "canReply"].every((key) => typeof data[key] === "boolean");
export function isSupportPage(data, after = 0, itemId = "id") {
    if (!object(data) || !Array.isArray(data.items) || data.items.length > 50 || typeof data.hasMore !== "boolean") return false;
    let last = after;
    for (const item of data.items) { if (!object(item) || !id(item[itemId]) || item[itemId] <= last) return false; last = item[itemId]; }
    return data.hasMore ? data.items.length > 0 && data.nextAfterId === last : data.nextAfterId === null;
}
const failed = (status) => ({kind: ({400: "invalid-input", 401: "login-required", 403: "forbidden", 404: "not-found", 409: "conflict"})[status] ?? "unavailable"});

export function createAssignmentClient({fetchImpl, view, newRequestId}) {
    let selected = null, generation = 0, readSequence = 0, assignment = null, posting = false, pending = null;
    async function get(url) {
        const response = await fetchImpl(url, {credentials: "same-origin", cache: "no-store"});
        if (!response.ok) throw {status: response.status};
        return response.json();
    }
    async function read(ticket) {
        if (selected !== ticket) { selected = ticket; generation++; pending = null; assignment = null; }
        const seq = ++readSequence, gen = generation;
        const current = () => gen === generation && seq === readSequence && selected === ticket;
        if (!id(ticket)) { assignment = null; view.render({kind: "invalid-input"}); return false; }
        view.render({kind: "loading"});
        try {
            const data = await get(`/api/tickets/${ticket}/assignment`);
            if (!current()) return false;
            if (!isAssignment(data, ticket)) throw {};
            assignment = data;
            view.render({kind: "assignment", assignment: data, pendingOperation: pending?.operation ?? null});
            return true;
        } catch (error) {
            if (current()) { assignment = null; view.render(failed(error.status)); }
            return false;
        }
    }
    async function change(operation, toAgentId = null, reason = null) {
        if (posting) return;
        if (!assignment || !["CLAIM", "TRANSFER"].includes(operation)
                || operation === "TRANSFER" && (!id(toAgentId) || typeof reason !== "string" || !reason.trim() || [...reason].length > 500)) {
            view.render({kind: "invalid-input"}); return;
        }
        if (pending && (pending.operation !== operation || pending.toAgentId !== toAgentId || pending.reason !== reason)) {
            view.render({kind: "unconfirmed-change"}); return;
        }
        if (!pending && !(operation === "CLAIM" ? assignment.canClaim : assignment.canTransfer)) {
            view.render({kind: "forbidden"}); return;
        }
        const ticket = selected, gen = generation;
        const current = () => selected === ticket && gen === generation;
        posting = true; view.setBusy(true);
        try {
            const csrf = await get("/api/csrf");
            if (!current()) return;
            if (!object(csrf) || typeof csrf.headerName !== "string" || !/^[A-Za-z0-9-]+$/.test(csrf.headerName)
                    || typeof csrf.token !== "string" || !csrf.token) throw {};
            if (!pending) {
                const key = newRequestId();
                if (typeof key !== "string" || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(key)) throw {};
                pending = {operation, toAgentId, reason, body: {clientRequestId: key, expectedAssignmentVersion: assignment.version}};
                if (operation === "TRANSFER") Object.assign(pending.body, {toAgentId, reason});
            }
            let response;
            try {
                response = await fetchImpl(`/api/tickets/${ticket}/assignment/${operation === "CLAIM" ? "claim" : "transfers"}`, {
                    method: "POST", credentials: "same-origin", headers: {"Content-Type": "application/json", [csrf.headerName]: csrf.token}, body: JSON.stringify(pending.body)
                });
            } catch { if (current()) view.render({kind: "unknown-outcome"}); return; }
            if (!current()) return;
            if (!response.ok) {
                // Even 409 may be a conflict with a previously committed submission. Keep exact fields.
                view.render(response.status >= 500 ? {kind: "unknown-outcome"} : failed(response.status)); return;
            }
            let result;
            try { result = await response.json(); } catch { view.render({kind: "unknown-outcome"}); return; }
            if (!current()) return;
            if (![200, 201].includes(response.status) || !object(result) || !isAssignment(result.assignment, ticket)
                    || !object(result.event) || !id(result.event.id) || result.event.ticketId !== ticket
                    || result.event.type !== operation || result.event.assignmentVersion !== pending.body.expectedAssignmentVersion + 1
                    || operation === "TRANSFER" && (result.event.toAgentId !== toAgentId || result.event.reason !== reason)) {
                view.render({kind: "unknown-outcome"}); return;
            }
            pending = null; assignment = result.assignment; readSequence++;
            view.render({kind: "change-saved", event: result.event, assignment: result.assignment});
            // Refresh failures never resend the successful POST.
            if (!await read(ticket) && current()) view.render({kind: "saved-refresh-failed"});
        } catch (error) { if (current()) view.render(failed(error.status)); }
        finally { posting = false; view.setBusy(false); }
    }
    function clear() { generation++; readSequence++; selected = null; pending = null; assignment = null; view.render({kind: "cleared"}); }
    return {read, change, clear};
}
