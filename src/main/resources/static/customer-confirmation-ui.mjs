const fields = (value, names) => value !== null && typeof value === "object" && !Array.isArray(value)
    && Object.keys(value).length === names.length && names.every((name) => Object.hasOwn(value, name));
const id = (value) => Number.isSafeInteger(value) && value > 0;
const uuid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const failed = (status) => ({kind: ({400: "invalid-input", 401: "login-required", 403: "forbidden", 404: "not-found", 409: "conflict"})[status] ?? "unavailable"});

export function isConfirmationState(data) {
    if (!fields(data, ["assignmentVersion", "effectiveThroughMessageId", "latestCustomerMessageId", "needsConfirmation", "canConfirm"])
            || !Number.isSafeInteger(data.assignmentVersion) || data.assignmentVersion < 0
            || data.latestCustomerMessageId !== null && !id(data.latestCustomerMessageId)
            || data.effectiveThroughMessageId !== null && (!id(data.effectiveThroughMessageId) || data.assignmentVersion === 0
                || data.latestCustomerMessageId === null || data.effectiveThroughMessageId > data.latestCustomerMessageId)
            || typeof data.needsConfirmation !== "boolean" || typeof data.canConfirm !== "boolean") return false;
    return data.needsConfirmation === (data.latestCustomerMessageId !== null
        && (data.effectiveThroughMessageId === null || data.effectiveThroughMessageId < data.latestCustomerMessageId))
        && (!data.canConfirm || data.assignmentVersion > 0 && data.latestCustomerMessageId !== null);
}

function isChange(data, pending) {
    if (!fields(data, ["confirmation", "currentConfirmation"]) || !isConfirmationState(data.currentConfirmation)) return false;
    const saved = data.confirmation;
    return fields(saved, ["id", "assignmentVersion", "throughMessageId", "recordedAt"]) && id(saved.id)
        && saved.assignmentVersion === pending.expectedAssignmentVersion && saved.throughMessageId === pending.throughMessageId
        && typeof saved.recordedAt === "string" && Number.isFinite(Date.parse(saved.recordedAt));
}

// Stores an explicit selection and its assignment version; refresh never expands the target.
export function createCustomerConfirmationClient({fetchImpl, view, newRequestId}) {
    let selected = null, generation = 0, readSequence = 0, currentState = null, target = null, pending = null, posting = false;
    const render = (state) => view.render({...state, selectedTarget: target?.throughMessageId ?? null,
        selectedVersion: target?.expectedAssignmentVersion ?? null, canReconfirm: pending !== null});
    async function read(ticket) {
        if (selected !== ticket) { generation++; selected = ticket; currentState = null; target = null; pending = null; }
        const sequence = ++readSequence, gen = generation;
        const current = () => selected === ticket && gen === generation && sequence === readSequence;
        if (!id(ticket)) { currentState = null; render({kind: "invalid-input"}); return false; }
        render({kind: "loading"});
        try {
            const response = await fetchImpl(`/api/tickets/${ticket}/customer-message-confirmations/current`, {credentials: "same-origin", cache: "no-store"});
            if (!current()) return false;
            if (!response.ok) { currentState = null; render(failed(response.status)); return false; }
            const data = await response.json();
            if (!current()) return false;
            if (!isConfirmationState(data)) throw {};
            currentState = data; render({kind: "current", currentConfirmation: data}); return true;
        } catch { if (current()) { currentState = null; render({kind: "unavailable"}); } return false; }
    }
    function choose(throughMessageId) {
        if (posting) return;
        if (pending) { render({kind: "unconfirmed-change"}); return; }
        if (!id(throughMessageId) || !currentState?.canConfirm) { render({kind: "invalid-input"}); return; }
        target = {throughMessageId, expectedAssignmentVersion: currentState.assignmentVersion};
        render({kind: "current", currentConfirmation: currentState});
    }
    async function confirm() {
        if (posting) return;
        if (!id(selected) || !pending && (!currentState?.canConfirm || target === null)) { render({kind: "invalid-input"}); return; }
        const ticket = selected, gen = generation;
        const current = () => selected === ticket && generation === gen;
        posting = true; readSequence++; view.setBusy(true); render({kind: "posting"});
        try {
            let csrf;
            try {
                const response = await fetchImpl("/api/csrf", {credentials: "same-origin", cache: "no-store"});
                if (!current()) return;
                if (!response.ok) { render(failed(response.status)); return; }
                csrf = await response.json();
            } catch { if (current()) render({kind: "unavailable"}); return; }
            if (!current()) return;
            if (!fields(csrf, ["headerName", "token"]) || typeof csrf.headerName !== "string" || !/^[A-Za-z0-9-]+$/.test(csrf.headerName)
                    || typeof csrf.token !== "string" || !csrf.token) { render({kind: "invalid-input"}); return; }
            if (!pending) {
                const key = newRequestId();
                if (typeof key !== "string" || !uuid.test(key)) { render({kind: "invalid-input"}); return; }
                pending = {clientRequestId: key, ...target};
            }
            let response, saved;
            try {
                response = await fetchImpl(`/api/tickets/${ticket}/customer-message-confirmations`, {
                    method: "POST", credentials: "same-origin", headers: {"Content-Type": "application/json", [csrf.headerName]: csrf.token},
                    body: JSON.stringify(pending)
                });
            } catch { if (current()) render({kind: "unknown-outcome"}); return; }
            if (!current()) return;
            // Preserve exact submission even on 409 or 5xx; never replace its version or target.
            if (!response.ok) { render(response.status >= 500 ? {kind: "unknown-outcome"} : failed(response.status)); return; }
            try { saved = await response.json(); } catch { if (current()) render({kind: "unknown-outcome"}); return; }
            if (!current()) return;
            if (![200, 201].includes(response.status) || !isChange(saved, pending)) { render({kind: "unknown-outcome"}); return; }
            const confirmation = saved.confirmation;
            pending = null; target = null; currentState = null; readSequence++;
            render({kind: "saved", confirmation});
            // The historical saved result must not overwrite a newer read. Re-read current state by GET.
            if (!await read(ticket) && current()) render({kind: "saved-refresh-failed", confirmation});
        } finally { posting = false; view.setBusy(false); }
    }
    function suspend() { readSequence++; currentState = null; }
    function clear() { generation++; readSequence++; selected = null; currentState = null; target = null; pending = null; render({kind: "cleared"}); }
    return {read, choose, confirm, suspend, clear};
}
