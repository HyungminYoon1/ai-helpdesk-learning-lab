import {createConversationClient} from "./conversation-ui.mjs";

const readForm = document.querySelector("#conversation-read");
const idInput = document.querySelector("#conversation-ticket-id");
const replyForm = document.querySelector("#official-reply");
const bodyInput = document.querySelector("#reply-body");
const correctionInput = document.querySelector("#reply-correction");
const sendButton = document.querySelector("#reply-send");
const moreButton = document.querySelector("#conversation-more");
const status = document.querySelector("#conversation-status");
const heading = document.querySelector("#conversation-heading");
const list = document.querySelector("#conversation-messages");
let cursor = null;
const displayed = new Set();
const labels = {
    "loading": "대화 조회 중입니다.", "posting": "저장 후 즉시 고객에게 공개됩니다. 답변을 저장하고 있습니다.",
    "invalid-id": "올바른 Ticket ID를 입력하세요.", "bad-request": "본문과 정정 대상을 확인하세요. 본문은 앞뒤 공백 제외 2,000자 이내입니다.",
    "login-required": "로그인이 필요합니다.", "forbidden": "권한 또는 고정 ID 연결을 확인하세요.",
    "not-found": "조회할 수 있는 문의가 없습니다.", "conflict": "기존 제출과 충돌합니다. 대화 내역을 확인하세요.",
    "request-unavailable": "조회 또는 전송 준비에 실패했습니다. 연결을 확인하세요.",
    "unknown-outcome": "저장 여부를 확인하지 못했습니다. 내용을 바꾸지 말고 같은 제출로 다시 확인하세요.",
    "unconfirmed-submission": "이전 제출의 저장 여부가 미확인입니다. 기존 본문과 정정 대상으로 먼저 확인하세요.",
    "invalid-response": "서버 응답을 확인할 수 없습니다.",
    "selection-changed": "Ticket이 변경되었습니다. 대화를 다시 조회하세요.",
    "reply-saved": "공식 답변을 저장하고 고객에게 공개했습니다.",
    "reply-saved-refresh-failed": "답변 저장은 확인했지만 대화를 다시 읽지 못했습니다. 로그인과 조회 권한을 확인하세요."
};

function clearConversation() {
    list.replaceChildren();
    displayed.clear();
    heading.textContent = "대화 내역";
    replyForm.hidden = true;
    moreButton.hidden = true;
    cursor = null;
    correctionInput.replaceChildren(new Option("일반 추가 안내", ""));
}

const view = {
    setPosting(value) { sendButton.disabled = value; },
    render(state) {
        status.textContent = labels[state.kind] ?? "대화를 조회했습니다.";
        if (state.kind === "posting") sendButton.disabled = true;
        if (state.kind === "loading" && !state.append) clearConversation();
        if (["login-required", "forbidden", "not-found", "invalid-response", "invalid-id", "request-unavailable"].includes(state.kind)) clearConversation();
        if (state.kind === "reply-saved" || state.kind === "reply-saved-refresh-failed") {
            bodyInput.value = "";
            correctionInput.value = "";
        }
        if (state.kind !== "conversation") return;
        const data = state.conversation;
        if (!state.append) clearConversation();
        heading.textContent = `${data.ticket.id}: ${data.ticket.title} (${data.ticket.status})`;
        replyForm.hidden = !data.capabilities.canReply && !state.canReconfirm;
        for (const message of data.messages) {
            if (displayed.has(message.id)) continue;
            displayed.add(message.id);
            const item = document.createElement("li");
            const label = document.createElement("p");
            label.textContent = `#${message.id} ${message.type === "INQUIRY" ? "문의" : "공식 답변"} ${message.createdAt}`
                + (message.correctsMessageId === null ? "" : ` / #${message.correctsMessageId} 답변 정정`);
            const text = document.createElement("p");
            text.className = "message-body";
            text.textContent = message.body;
            item.append(label, text);
            list.append(item);
            if (message.type === "OFFICIAL_REPLY") correctionInput.add(new Option(`#${message.id} 공식 답변 정정`, String(message.id)));
        }
        cursor = data.page.nextAfterId;
        moreButton.hidden = !data.page.hasMore;
    }
};

const client = createConversationClient({fetchImpl: window.fetch.bind(window), view, newRequestId: () => window.crypto.randomUUID()});
let previousTicket = null;
idInput.addEventListener("input", () => {
    if (Number(idInput.value) !== previousTicket) {
        client.clear(); clearConversation(); bodyInput.value = "";
    }
});
readForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const id = Number(idInput.value);
    if (id !== previousTicket) { bodyInput.value = ""; correctionInput.value = ""; previousTicket = id; }
    void client.read(id);
});
moreButton.addEventListener("click", () => { if (cursor !== null) void client.read(Number(idInput.value), cursor); });
replyForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void client.reply(bodyInput.value, correctionInput.value === "" ? null : Number(correctionInput.value));
});

// A back/forward-cache restoration must not reveal the previous account's conversation.
window.addEventListener("pagehide", () => {
    client.clear();
    clearConversation();
    bodyInput.value = "";
    idInput.value = "";
    previousTicket = null;
});
