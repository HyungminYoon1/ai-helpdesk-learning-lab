import {createCustomerConfirmationClient} from "./customer-confirmation-ui.mjs";

export function attachCustomerConfirmations({fetchImpl, newRequestId, document}) {
    const el = (id) => document.querySelector(`#${id}`);
    const panel = el("customer-confirmations"), form = el("confirmation-form"), target = el("confirmation-target");
    const send = el("confirmation-send"), refresh = el("confirmation-refresh"), status = el("confirmation-status");
    let selectedTicket = null, busy = false, canConfirm = false, canReconfirm = false;
    const known = new Set();
    const labels = {loading: "확인 상태를 조회하고 있습니다.", posting: "선택한 고객 메시지까지 확인을 기록하고 있습니다.",
        "invalid-input": "고객 메시지를 선택하고 현재 담당 권한을 확인하세요.", "login-required": "로그인이 필요합니다.",
        forbidden: "현재 권한으로 확인할 수 없습니다.", "not-found": "확인 상태 조회 권한이 없습니다.", conflict: "기존 제출 또는 배정과 충돌합니다. 자동으로 대상이나 버전을 바꾸지 않습니다.",
        unavailable: "확인 상태를 읽지 못했습니다. 상태 조회를 다시 시도하세요.",
        "unknown-outcome": "저장 여부 미확인입니다. 같은 제출 재확인 버튼으로 결과를 확인하세요.",
        "unconfirmed-change": "미확인 제출의 대상을 바꿀 수 없습니다. 먼저 같은 제출을 재확인하세요.",
        "saved-refresh-failed": "확인 저장은 완료됐지만 현재 상태를 읽지 못했습니다. 저장 대신 상태 조회를 다시 시도하세요."};
    function buttons() {
        target.disabled = busy || canReconfirm || !canConfirm;
        send.disabled = busy || !canReconfirm && (!canConfirm || !target.value);
        send.textContent = canReconfirm ? "같은 제출 재확인" : "선택한 메시지까지 확인";
        refresh.disabled = busy;
    }
    const client = createCustomerConfirmationClient({fetchImpl, newRequestId, view: {
        setBusy(value) { busy = value; buttons(); },
        render(state) {
            canReconfirm = state.canReconfirm;
            if (state.kind === "cleared") { canConfirm = false; return; }
            status.textContent = labels[state.kind] ?? "현재 확인 상태를 조회했습니다.";
            if (state.kind === "current") {
                const data = state.currentConfirmation;
                canConfirm = data.canConfirm;
                el("confirmation-current").textContent = `현재 배정 v${data.assignmentVersion} / `
                    + (data.effectiveThroughMessageId === null ? "현재 배정의 확인 기록 없음" : `고객 메시지 #${data.effectiveThroughMessageId}까지 확인`)
                    + (data.latestCustomerMessageId === null ? " / 고객 메시지 없음" : ` / 최신 고객 메시지 #${data.latestCustomerMessageId}`)
                    + (data.needsConfirmation ? " / 확인 필요" : " / 추가 확인할 글 없음");
            } else if (state.kind !== "posting" && state.kind !== "unconfirmed-change" && state.kind !== "unknown-outcome") {
                canConfirm = false; el("confirmation-current").textContent = "현재 확인 상태 미조회";
            }
            if (state.confirmation) {
                const saved = state.confirmation;
                el("confirmation-result").textContent = `저장 확인: 배정 v${saved.assignmentVersion}, 고객 메시지 #${saved.throughMessageId}까지. 현재 배정 상태와 구분한 이력입니다.`;
                target.value = "";
            }
            el("confirmation-selection").textContent = state.selectedTarget === null ? "확인할 메시지를 직접 선택하세요."
                : `선택한 범위: 배정 v${state.selectedVersion}, 고객 메시지 #${state.selectedTarget}까지`;
            buttons();
        }
    }});
    target.addEventListener("change", () => {
        if (target.value) client.choose(Number(target.value));
        buttons();
    });
    form.addEventListener("submit", (event) => { event.preventDefault(); void client.confirm(); });
    refresh.addEventListener("click", () => { if (selectedTicket !== null) void client.read(selectedTicket); });
    function clear() {
        client.clear(); selectedTicket = null; known.clear(); panel.hidden = true; canConfirm = false; canReconfirm = false;
        target.replaceChildren(new Option("확인할 고객 메시지 선택", ""));
        el("confirmation-current").textContent = ""; el("confirmation-result").textContent = "";
        el("confirmation-selection").textContent = ""; status.textContent = "";
        buttons();
    }
    function observe(state) {
        if (state.kind === "selection-changed") { clear(); return; }
        if (state.kind === "loading" && state.append) return;
        if (state.kind !== "conversation") {
            if (["loading", "invalid-id", "login-required", "forbidden", "not-found", "invalid-response", "request-unavailable"].includes(state.kind)) {
                client.suspend(); panel.hidden = true;
            }
            return;
        }
        const data = state.conversation;
        if (!data.capabilities.canViewConfirmations) { clear(); return; }
        if (selectedTicket !== data.ticket.id) { clear(); selectedTicket = data.ticket.id; }
        panel.hidden = false;
        // Preserve already displayed targets on a same-Ticket refresh; no new target is auto-selected.
        for (const message of data.messages) {
            if (message.type === "OFFICIAL_REPLY" || known.has(message.id)) continue;
            known.add(message.id); target.add(new Option(`#${message.id} ${message.type === "INQUIRY" ? "고객 문의" : "고객 추가 문의"}`, String(message.id)));
        }
        void client.read(selectedTicket);
    }
    clear();
    return {observe, clear};
}
