import {createTicketStatusClient} from "./ticket-status-ui.mjs";

export function attachTicketStatus({fetchImpl, newRequestId, document, requesterView = false}) {
    const el = (name) => document.querySelector(`#status-${name}`);
    const panel = el("panel"), form = el("form"), action = el("action"), reason = el("reason"), reply = el("reply"), through = el("through");
    let selected = null, selectedScope = null;
    const known = new Set();
    const labels = {loading: "현재 처리 상태를 읽고 있습니다.", current: "현재 처리 상태를 조회했습니다.",
        draft: "선택 당시 버전으로 작성 중입니다.", posting: "처리 기록을 저장하고 있습니다.",
        "invalid-input": "처리 종류, 사유와 필요한 근거를 확인하세요. 사유는 500자 이내입니다.",
        "login-required": "로그인이 필요합니다.", forbidden: "현재 처리 권한을 확인하세요.", "not-found": "조회할 수 있는 문의가 없습니다.",
        unavailable: "현재 상태를 읽지 못했습니다. 상태 조회를 다시 시도하세요.",
        "unknown-outcome": "저장 여부 미확인입니다. 내용을 바꾸지 말고 같은 제출을 재확인하세요.",
        rejected: "현재 담당 배정, 처리 상태 또는 고객 글이 달라 요청이 거부됐습니다. 대화를 확인하고 다시 선택하세요.",
        reset: "거부된 제출이나 오래된 초안을 비웠습니다. 현재 상태를 조회한 뒤 직접 다시 선택하세요.",
        saved: "처리 기록 저장을 확인했습니다. 현재 상태는 별도로 조회합니다.",
        "saved-refresh-failed": "처리 기록 저장은 확인했지만 현재 상태 조회에 실패했습니다. 저장 대신 상태 조회를 다시 시도하세요."};
    function emptyFields() { action.value = ""; reason.value = ""; reply.value = ""; through.value = ""; }
    function values() { return {action: action.value, reason: reason.value,
        relatedReplyMessageId: reply.value ? Number(reply.value) : null, throughCustomerMessageId: through.value ? Number(through.value) : null}; }
    const client = createTicketStatusClient({fetchImpl, newRequestId, view: {render(state) {
        if (["cleared", "hidden"].includes(state.kind)) { panel.hidden = true; return; }
        panel.hidden = false;
        el("message").textContent = labels[state.kind] ?? "현재 상태를 확인하세요.";
        const data = state.current;
        el("current").textContent = data ? `현재 문의 상태: ${data.status} / 상태 v${data.statusVersion}`
            + (state.scope === "STAFF" ? ` / 배정 v${data.assignmentVersion}` : "") : "현재 처리 상태 미조회";
        el("saved").textContent = state.saved ? `저장된 처리 #${state.saved.id}: ${state.saved.fromStatus} → ${state.saved.toStatus}, 상태 v${state.saved.statusVersion}. 현재 상태와 구분한 이력입니다.` : "";
        el("draft").textContent = state.draft ? `작성 기준: 상태 v${state.draft.expectedStatusVersion}`
            + (state.scope === "STAFF" ? ` / 배정 v${state.draft.expectedAssignmentVersion}` : "") : "처리 종류와 사유를 직접 선택하세요.";
        if (["saved", "reset"].includes(state.kind)) emptyFields();
        action.hidden = state.scope !== "STAFF"; el("action-label").hidden = action.hidden;
        action.required = !action.hidden;
        const resolving = state.scope === "STAFF" && (state.draft?.action ?? action.value) === "RESOLVE";
        el("resolution").hidden = !resolving; reply.required = resolving; through.required = resolving;
        for (const option of action.options) if (option.value) option.disabled = data?.[({START: "canStart", RESOLVE: "canResolve", REOPEN: "canReopen"})[option.value]] !== true;
        const locked = state.locked || state.canReset || data === null;
        action.disabled = locked; reason.readOnly = locked; reply.disabled = locked; through.disabled = locked;
        el("send").disabled = !state.canSubmit;
        el("send").textContent = state.canReconfirm ? "같은 처리 제출 재확인" : state.scope === "REQUESTER" ? "문의 재개 요청" : "선택한 처리 저장";
        el("refresh").disabled = state.busy; el("reset").hidden = !state.canReset; el("reset").disabled = state.busy;
    }}});
    for (const input of [action, reason, reply, through]) input.addEventListener(input === reason ? "input" : "change", () => client.edit(values()));
    form.addEventListener("submit", (event) => {event.preventDefault();client.edit(values());void client.submit();});
    el("refresh").addEventListener("click", () => {void client.refresh();});
    el("reset").addEventListener("click", () => {void client.resetRejected();});
    function clear() {
        client.clear();selected = null;selectedScope = null;known.clear();emptyFields();
        reply.replaceChildren(new Option("관련 공식 답변 선택", ""));through.replaceChildren(new Option("직접 확인한 마지막 고객 글 선택", ""));
        el("current").textContent = "";el("saved").textContent = "";el("draft").textContent = "";el("message").textContent = "";
    }
    function observe(state) {
        if (state.kind === "selection-changed") {clear();return;}
        if (state.kind !== "conversation") {
            if (["invalid-id", "login-required", "forbidden", "not-found", "invalid-response", "request-unavailable"].includes(state.kind)
                    || state.kind === "loading" && !state.append) client.suspend();
            return;
        }
        const data = state.conversation;
        const scope = requesterView ? data.capabilities.canFollowUp ? "REQUESTER" : null
            : data.capabilities.canViewConfirmations ? "STAFF" : data.capabilities.canFollowUp ? "REQUESTER" : null;
        if (scope === null) {clear();return;}
        if (selected !== data.ticket.id || scope !== selectedScope) {clear();selected = data.ticket.id;selectedScope = scope;}
        for (const message of data.messages) if (!known.has(message.id)) {
            known.add(message.id);
            if (message.type === "OFFICIAL_REPLY") reply.add(new Option(`#${message.id} 공식 답변`, String(message.id)));
            else through.add(new Option(`#${message.id} 고객 글`, String(message.id)));
        }
        void client.open(selected, scope);
    }
    return {observe, clear};
}
