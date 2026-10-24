import {createAssignmentClient, isSupportPage} from "./assignment-ui.mjs";

const el = (name) => document.querySelector(`#${name}`);
const status = el("assignment-status"), service = el("support-service"), ticket = el("assignment-ticket-id");
const queue = el("assignment-queue"), agents = el("transfer-agent"), events = el("assignment-events");
let pageGeneration = 0, queueSequence = 0, detailSequence = 0, serviceSequence = 0;
let queueCursor = null, agentCursor = null, eventCursor = null, currentService = null;
const labels = {};
// UI text is Korean; response bodies, display names and reasons are never interpreted as HTML.
Object.assign(labels, {"loading": "배정을 조회하고 있습니다.", "login-required": "로그인이 필요합니다.",
    "forbidden": "현재 권한으로 변경할 수 없습니다.", "not-found": "조회할 수 있는 서비스 또는 문의가 없습니다.",
    "conflict": "현재 상태 또는 기존 제출과 충돌합니다. 자동 재전송하지 말고 배정을 확인하세요.",
    "unavailable": "요청을 완료하지 못했습니다.", "invalid-input": "입력이나 서버 응답을 확인하세요.",
    "unknown-outcome": "저장 여부 미확인입니다. 같은 대상과 사유로 재확인하세요.",
    "unconfirmed-change": "기존 제출과 대상 또는 사유가 다릅니다. 먼저 같은 제출을 확인하세요.",
    "change-saved": "변경이 저장됐습니다. 원래 변경 이력과 현재 담당자는 다를 수 있습니다.",
    "saved-refresh-failed": "변경 저장은 확인했지만 다시 조회하지 못했습니다. POST 대신 조회를 다시 시도하세요.",
    "cleared": "문의가 변경됐습니다. 다시 조회하세요."});

function clearDetails() {
    detailSequence++; currentService = null; agents.replaceChildren(); events.replaceChildren();
    el("assignment-current").textContent = "문의 선택 대기";
    for (const name of ["assignment-claim", "assignment-transfer", "agents-more", "events-more"]) el(name).hidden = true;
}
async function get(url) {
    const response = await window.fetch(url, {credentials: "same-origin", cache: "no-store"});
    if (!response.ok) throw new Error("request unavailable");
    return response.json();
}
async function details(data, appendAgents = false, appendEvents = false) {
    const sequence = ++detailSequence, gen = pageGeneration;
    currentService = data.serviceId;
    try {
        const [members, history] = await Promise.all([
            appendEvents ? null : get(`/api/services/${data.serviceId}/agents?afterId=${appendAgents ? agentCursor : 0}&limit=50`),
            appendAgents ? null : get(`/api/tickets/${data.ticketId}/assignment-events?afterId=${appendEvents ? eventCursor : 0}&limit=50`)
        ]);
        if (sequence !== detailSequence || gen !== pageGeneration || Number(ticket.value) !== data.ticketId) return;
        if (members !== null && !isSupportPage(members, appendAgents ? agentCursor : 0, "agentId")
                || history !== null && !isSupportPage(history, appendEvents ? eventCursor : 0)) throw new Error("invalid page");
        const selectedAgent = agents.value;
        if (members !== null) {
        if (!appendAgents) agents.replaceChildren();
        for (const member of members.items) {
            if (typeof member.displayName !== "string" || !["AGENT", "SUPERVISOR"].includes(member.role)) throw new Error("invalid agent");
            if (member.agentId !== data.assigneeAgentId) agents.add(new Option(`${member.displayName} (#${member.agentId}, ${member.role})`, String(member.agentId)));
        }
        if ([...agents.options].some((option) => option.value === selectedAgent)) agents.value = selectedAgent;
        agentCursor = members.nextAfterId; el("agents-more").hidden = !members.hasMore;
        }
        if (history !== null) {
        if (!appendEvents) events.replaceChildren();
        for (const event of history.items) {
            if (event.ticketId !== data.ticketId || !["CLAIM", "TRANSFER"].includes(event.type) || typeof event.recordedAt !== "string") throw new Error("invalid event");
            const row = document.createElement("li");
            row.textContent = `v${event.assignmentVersion} ${event.type} / #${event.fromAgentId ?? "미배정"} → #${event.toAgentId} / ${event.recordedAt}${event.reason === null ? "" : ` / ${event.reason}`}`;
            events.append(row);
        }
        eventCursor = history.nextAfterId; el("events-more").hidden = !history.hasMore;
        }
    } catch { if (sequence === detailSequence && gen === pageGeneration) { agents.replaceChildren(); events.replaceChildren(); status.textContent = "배정은 조회했지만 직원 또는 이력을 읽지 못했습니다. 다시 조회하세요."; } }
}
let latestAssignment = null;
const client = createAssignmentClient({fetchImpl: window.fetch.bind(window), newRequestId: () => window.crypto.randomUUID(), view: {
    setBusy(value) { el("assignment-claim").disabled = value; el("transfer-send").disabled = value; },
    render(state) {
        status.textContent = labels[state.kind] ?? "현재 담당자를 조회했습니다.";
        if (["cleared", "login-required", "forbidden", "not-found", "unavailable", "invalid-input"].includes(state.kind)) { latestAssignment = null; clearDetails(); }
        if (!["assignment", "change-saved"].includes(state.kind)) return;
        const data = state.assignment; latestAssignment = data;
        el("assignment-current").textContent = `Ticket ${data.ticketId} / ${data.assigneeAgentId === null ? "미배정" : `담당자 #${data.assigneeAgentId}`} / 버전 ${data.version}`;
        el("assignment-claim").hidden = !data.canClaim && state.pendingOperation !== "CLAIM";
        el("assignment-transfer").hidden = !data.canTransfer && state.pendingOperation !== "TRANSFER";
        if (state.kind === "change-saved") { el("transfer-reason").value = ""; void loadQueue(); }
        void details(data);
    }
}});
async function loadServices() {
    const sequence = ++serviceSequence, gen = pageGeneration;
    try {
        const data = await get("/api/services");
        if (sequence !== serviceSequence || gen !== pageGeneration) return;
        if (!Array.isArray(data) || data.some((item) => !Number.isSafeInteger(item.id) || item.id <= 0 || typeof item.displayName !== "string")) throw new Error("invalid services");
        service.replaceChildren(...data.map((item) => new Option(item.displayName, String(item.id))));
        status.textContent = data.length ? "서비스를 선택해 업무를 조회하세요." : "활성 지원권한이 있는 서비스가 없습니다.";
        void loadQueue();
    } catch { if (sequence === serviceSequence && gen === pageGeneration) { service.replaceChildren(); queue.replaceChildren(); status.textContent = "서비스 조회 권한과 로그인을 확인하세요."; } }
}
async function loadQueue(append = false) {
    const sequence = ++queueSequence, gen = pageGeneration, selectedService = Number(service.value);
    const after = append ? queueCursor : 0;
    if (!selectedService) { queue.replaceChildren(); return; }
    try {
        const data = await get(`/api/services/${selectedService}/assignment-queue?view=${el("queue-view").value}&afterId=${after}&limit=50`);
        if (sequence !== queueSequence || gen !== pageGeneration) return;
        if (!isSupportPage(data, after)) throw new Error("invalid queue");
        if (!append) queue.replaceChildren();
        for (const entry of data.items) {
            const row = document.createElement("li"), button = document.createElement("button");
            button.type = "button"; button.textContent = `#${entry.id} ${entry.title} (${entry.status}) / ${entry.createdAt ?? "접수 시각 미확인"}`;
            button.addEventListener("click", () => { client.clear(); ticket.value = String(entry.id); el("transfer-reason").value = ""; void client.read(entry.id); });
            row.append(button); queue.append(row);
        }
        queueCursor = data.nextAfterId; el("queue-more").hidden = !data.hasMore;
    } catch { if (sequence === queueSequence && gen === pageGeneration) { queue.replaceChildren(); el("queue-more").hidden = true; status.textContent = "업무 목록을 읽지 못했습니다."; } }
}
ticket.addEventListener("input", () => { client.clear(); el("transfer-reason").value = ""; });
el("assignment-read").addEventListener("submit", (event) => { event.preventDefault(); void client.read(Number(ticket.value)); });
el("assignment-claim").addEventListener("click", () => { void client.change("CLAIM"); });
el("assignment-transfer").addEventListener("submit", (event) => { event.preventDefault(); void client.change("TRANSFER", Number(agents.value), el("transfer-reason").value); });
for (const name of ["support-service", "queue-view"]) el(name).addEventListener("change", () => { void loadQueue(); });
el("services-refresh").addEventListener("click", () => { void loadServices(); });
el("queue-refresh").addEventListener("click", () => { void loadQueue(); });
el("queue-more").addEventListener("click", () => { if (queueCursor !== null) void loadQueue(true); });
el("agents-more").addEventListener("click", () => { if (latestAssignment && currentService !== null && agentCursor !== null) void details(latestAssignment, true); });
el("events-more").addEventListener("click", () => { if (latestAssignment && eventCursor !== null) void details(latestAssignment, false, true); });
window.addEventListener("pagehide", () => { pageGeneration++; queueSequence++; serviceSequence++; client.clear(); queue.replaceChildren(); service.replaceChildren(); ticket.value = ""; el("transfer-reason").value = ""; });
void loadServices();
