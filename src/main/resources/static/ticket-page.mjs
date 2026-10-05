import { createTicketClient } from "./ticket-ui.mjs";

const createForm = document.querySelector("#create-form");
const readForm = document.querySelector("#read-form");
const titleInput = document.querySelector("#ticket-title-input");
const bodyInput = document.querySelector("#ticket-body-input");
const idInput = document.querySelector("#ticket-id-input");
const createButton = document.querySelector("#create-button");
const readButton = document.querySelector("#read-button");
const statusElement = document.querySelector("#ui-status");
const detailElement = document.querySelector("#ticket-detail");
const ticketIdElement = document.querySelector("#ticket-id");
const ticketTitleElement = document.querySelector("#ticket-title");
const ticketStatusElement = document.querySelector("#ticket-status");
const ticketList = document.querySelector("#ticket-list");

const messages = {
    "loading": "Ticket 조회 중입니다.",
    "creating": "Ticket 생성 중입니다.",
    "invalid-id": "올바른 Ticket ID를 입력하세요.",
    "invalid-title": "공백이 아닌 제목을 입력하세요.",
    "invalid-body": "공백이 아닌 본문을 앞뒤 공백 제외 2,000자 이내로 입력하세요.",
    "login-required": "로그인이 필요합니다.",
    "forbidden": "이 작업에 대한 권한이 없습니다.",
    "not-found": "Ticket을 찾을 수 없습니다.",
    "bad-request": "요청 입력을 확인하세요.",
    "request-unavailable": "응답을 읽지 못했습니다. 연결 또는 Browser 정책을 확인하세요.",
    "unknown-outcome": "생성 결과를 확인할 수 없습니다. 자동 재시도하지 마세요.",
    "invalid-response": "Server 응답 형식이 올바르지 않습니다."
};

const view = {
    render(state) {
        createButton.disabled = state.kind === "creating";
        readButton.disabled = state.kind === "creating";

        if (state.kind === "success" || state.kind === "created") {
            statusElement.textContent = state.kind === "created"
                ? "Ticket을 생성했습니다."
                : "Ticket을 조회했습니다.";
            ticketIdElement.textContent = String(state.ticket.id);
            ticketTitleElement.textContent = state.ticket.title;
            ticketStatusElement.textContent = state.ticket.status;
            detailElement.hidden = false;
            return;
        }

        detailElement.hidden = true;
        statusElement.textContent = state.kind === "http-error"
            ? `HTTP ${state.status} 요청 오류입니다.`
            : messages[state.kind];
    },

    addTicket(ticket) {
        const item = document.createElement("li");
        const title = document.createElement("span");
        title.textContent = ticket.title;

        const button = document.createElement("button");
        button.type = "button";
        button.dataset.action = "open";
        button.dataset.ticketId = String(ticket.id);

        const buttonLabel = document.createElement("span");
        buttonLabel.textContent = "상세 보기";
        button.append(buttonLabel);

        item.append(title, document.createTextNode(" "), button);
        ticketList.append(item);
    }
};

const client = createTicketClient({
    fetchImpl: window.fetch.bind(window),
    view
});

createForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void client.createTicket(titleInput.value, bodyInput.value);
});

readForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void client.readTicket(Number(idInput.value));
});

ticketList.addEventListener("click", (event) => {
    if (!(event.target instanceof Element)) {
        return;
    }

    const button = event.target.closest("[data-action='open']");

    if (button === null || !ticketList.contains(button)) {
        return;
    }

    void client.readTicket(Number(button.dataset.ticketId));
});
