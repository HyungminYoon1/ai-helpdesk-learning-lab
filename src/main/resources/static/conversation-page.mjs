import {createConversationClient} from "./conversation-ui.mjs";
import {attachCustomerConfirmations} from "./customer-confirmation-page.mjs";

const readForm = document.querySelector("#conversation-read");
const idInput = document.querySelector("#conversation-ticket-id");
const replyForm = document.querySelector("#official-reply");
const followupForm = document.querySelector("#customer-followup");
const followupBody = document.querySelector("#followup-body");
const followupSend = document.querySelector("#followup-send");
const bodyInput = document.querySelector("#reply-body");
const correctionInput = document.querySelector("#reply-correction");
const sendButton = document.querySelector("#reply-send");
const draftStatus = document.querySelector("#reply-draft-status");
const reviewReplyButton = document.querySelector("#reply-review");
const moreButton = document.querySelector("#conversation-more");
const status = document.querySelector("#conversation-status");
const heading = document.querySelector("#conversation-heading");
const list = document.querySelector("#conversation-messages");
let cursor = null;
let posting = false, replyReviewRequired = false, canReconfirmReply = false, canReviewReply = false;
const displayed = new Set();
const confirmations = attachCustomerConfirmations({fetchImpl: window.fetch.bind(window),
    newRequestId: () => window.crypto.randomUUID(), document});
const labels = {
    "loading": "대화 조회 중입니다.", "posting": "저장 후 즉시 고객에게 공개됩니다. 답변을 저장하고 있습니다.",
    "invalid-id": "올바른 Ticket ID를 입력하세요.", "bad-request": "본문과 정정 대상을 확인하세요. 본문은 앞뒤 공백 제외 2,000자 이내입니다.",
    "login-required": "로그인이 필요합니다.", "forbidden": "권한 또는 고정 ID 연결을 확인하세요.",
    "not-found": "조회할 수 있는 문의가 없습니다.", "conflict": "기존 제출과 충돌합니다. 대화 내역을 확인하세요.",
    "request-unavailable": "조회 또는 전송 준비에 실패했습니다. 연결을 확인하세요.",
    "unknown-outcome": "저장 여부를 확인하지 못했습니다. 내용을 바꾸지 말고 같은 제출로 다시 확인하세요.",
    "unconfirmed-submission": "이전 제출의 저장 여부가 미확인입니다. 기존 작성 종류와 본문, 정정 대상으로 먼저 확인하세요.",
    "reply-version-conflict": "담당 배정이 바뀌어 답변이 거부됐습니다. 기존 초안을 버리고 현재 배정을 조회한 뒤 다시 작성하세요.",
    "reply-review-required": "이전 배정에서 시작한 초안입니다. 배정을 자동 변경하지 않습니다. 새로 작성할지 확인하세요.",
    "reply-draft-reset": "기존 답변 초안을 비웠습니다. 현재 담당 배정을 조회합니다. 아직 새 답변은 저장하지 않았습니다.",
    "invalid-response": "서버 응답을 확인할 수 없습니다.",
    "selection-changed": "Ticket이 변경되었습니다. 대화를 다시 조회하세요.",
    "reply-saved": "공식 답변을 저장하고 고객에게 공개했습니다.",
    "reply-saved-refresh-failed": "답변 저장은 확인했지만 대화를 다시 읽지 못했습니다. 로그인과 조회 권한을 확인하세요.",
    "followup-saved": "추가 문의를 저장했습니다. Ticket의 해결 상태는 변경되지 않았습니다.",
    "followup-saved-refresh-failed": "추가 문의 저장은 확인했지만 대화를 읽지 못했습니다. 다시 저장하지 말고 대화를 조회하세요."
};

function clearConversation() {
    list.replaceChildren();
    displayed.clear();
    heading.textContent = "대화 내역";
    replyForm.hidden = true;
    followupForm.hidden = true;
    moreButton.hidden = true;
    cursor = null;
}

function resetReplyDraft(clearOptions = false) {
    bodyInput.value = "";
    if (clearOptions) correctionInput.replaceChildren(new Option("일반 추가 안내", ""));
    correctionInput.value = "";
}

function replyControls() {
    sendButton.disabled = posting || replyReviewRequired && !canReconfirmReply;
    bodyInput.readOnly = posting || replyReviewRequired || canReconfirmReply;
    correctionInput.disabled = posting || replyReviewRequired || canReconfirmReply;
    reviewReplyButton.hidden = !canReviewReply;
    reviewReplyButton.disabled = posting;
}

const view = {
    setPosting(value) { posting = value; replyControls(); followupSend.disabled = value; },
    render(state) {
        confirmations.observe(state);
        replyReviewRequired = state.replyReviewRequired ?? false;
        canReconfirmReply = state.canReconfirm ?? false;
        canReviewReply = state.canReviewReply ?? false;
        draftStatus.textContent = replyReviewRequired
            ? canReconfirmReply ? "담당 배정이 바뀌어도 저장 결과가 미확인인 제출은 먼저 원래 정보로 재확인하세요."
                : "배정이 바뀐 초안을 보관하고 있습니다. 일반 대화 조회로 초안의 배정 버전을 바꾸지 않습니다."
            : "";
        status.textContent = labels[state.kind] ?? "대화를 조회했습니다.";
        if (state.kind === "posting") {
            posting = true; followupSend.disabled = true;
            if (state.submissionKind === "followup") status.textContent = "추가 문의를 저장하고 있습니다.";
        }
        replyControls();
        if (state.kind === "loading" && !state.append) clearConversation();
        if (["login-required", "forbidden", "not-found", "invalid-response", "invalid-id", "request-unavailable"].includes(state.kind)) clearConversation();
        if (["reply-saved", "reply-saved-refresh-failed", "reply-draft-reset", "selection-changed"].includes(state.kind)) {
            resetReplyDraft(state.kind === "reply-draft-reset" || state.kind === "selection-changed");
        }
        if (state.kind === "followup-saved" || state.kind === "followup-saved-refresh-failed") followupBody.value = "";
        if (state.kind !== "conversation") return;
        const data = state.conversation;
        if (!state.append) clearConversation();
        const selectedCorrection = correctionInput.value;
        if (!state.append) correctionInput.replaceChildren(new Option("일반 추가 안내", ""));
        heading.textContent = `${data.ticket.id}: ${data.ticket.title} (${data.ticket.status})`;
        replyForm.hidden = !data.capabilities.canReply && !state.canReconfirm && !state.canReviewReply;
        followupForm.hidden = !data.capabilities.canFollowUp && !state.canReconfirmFollowup;
        for (const message of data.messages) {
            if (displayed.has(message.id)) continue;
            displayed.add(message.id);
            const item = document.createElement("li");
            const label = document.createElement("p");
            const kind = message.type === "OFFICIAL_REPLY" ? "담당자 공식 답변"
                : message.type === "CUSTOMER_FOLLOWUP" ? "고객 추가 문의" : "고객 문의";
            label.textContent = `#${message.id} ${kind} ${message.createdAt}`
                + (message.correctsMessageId === null ? "" : ` / #${message.correctsMessageId} 답변 정정`);
            const text = document.createElement("p");
            text.className = "message-body";
            text.textContent = message.body;
            item.append(label, text);
            list.append(item);
            if (message.type === "OFFICIAL_REPLY" && ![...correctionInput.options].some((option) => option.value === String(message.id))) {
                correctionInput.add(new Option(`#${message.id} 공식 답변 정정`, String(message.id)));
            }
        }
        if (/^[1-9][0-9]*$/.test(selectedCorrection) && Number.isSafeInteger(Number(selectedCorrection))
                && ![...correctionInput.options].some((option) => option.value === selectedCorrection)) {
            correctionInput.add(new Option(`#${selectedCorrection} 공식 답변 정정`, selectedCorrection));
        }
        correctionInput.value = selectedCorrection;
        cursor = data.page.nextAfterId;
        moreButton.hidden = !data.page.hasMore;
    }
};

const client = createConversationClient({fetchImpl: window.fetch.bind(window), view, newRequestId: () => window.crypto.randomUUID()});
let previousTicket = null;
idInput.addEventListener("input", () => {
    if (Number(idInput.value) !== previousTicket) {
        client.clear(); clearConversation(); bodyInput.value = ""; followupBody.value = "";
    }
});
readForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const id = Number(idInput.value);
    if (id !== previousTicket) { bodyInput.value = ""; followupBody.value = ""; correctionInput.value = ""; previousTicket = id; }
    void client.read(id);
});
moreButton.addEventListener("click", () => { if (cursor !== null) void client.read(Number(idInput.value), cursor); });
replyForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void client.reply(bodyInput.value, correctionInput.value === "" ? null : Number(correctionInput.value));
});
reviewReplyButton.addEventListener("click", () => { void client.reviewReply(); });
followupForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void client.followUp(followupBody.value);
});

if (window.location?.search) {
    const ids = new window.URLSearchParams(window.location.search).getAll("ticketId");
    if (ids.length === 1 && /^[1-9][0-9]*$/.test(ids[0]) && Number.isSafeInteger(Number(ids[0]))) {
        previousTicket = Number(ids[0]); idInput.value = ids[0];
        void client.read(previousTicket);
    } else view.render({kind: "invalid-id"});
}

// A back/forward-cache restoration must not reveal the previous account's conversation.
window.addEventListener("pagehide", () => {
    client.clear();
    confirmations.clear();
    clearConversation();
    bodyInput.value = "";
    followupBody.value = "";
    idInput.value = "";
    previousTicket = null;
});
