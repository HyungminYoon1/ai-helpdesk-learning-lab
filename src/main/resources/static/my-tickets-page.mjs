import {createMyTicketsClient} from "./my-tickets-ui.mjs";

const list = document.querySelector("#my-tickets");
const status = document.querySelector("#my-tickets-status");
const refresh = document.querySelector("#my-tickets-refresh");
const more = document.querySelector("#my-tickets-more");
const client = createMyTicketsClient({fetchImpl: window.fetch.bind(window), view: {
    setLoading(value) { more.disabled = value; },
    render(state) {
        if (state.kind === "loading") {
            status.textContent = "내 문의를 읽고 있습니다."; more.disabled = true;
            if (!state.append) { list.replaceChildren(); more.hidden = true; }
            return;
        }
        if (["cleared", "login-required", "forbidden"].includes(state.kind)) { list.replaceChildren(); more.hidden = true; }
        if (state.kind !== "tickets") {
            status.textContent = ({"login-required": "로그인이 필요합니다.", forbidden: "계정의 조회 권한을 확인하세요.",
                "invalid-response": "목록 응답을 확인할 수 없습니다.", cleared: "목록을 새로 조회하세요."})[state.kind]
                ?? "목록을 읽지 못했습니다. 새로고침하거나 다음 목록 읽기를 다시 시도하세요.";
            return;
        }
        list.replaceChildren();
        for (const ticket of state.items) {
            const item = document.createElement("li"), link = document.createElement("a"), detail = document.createElement("p");
            link.href = `/conversation.html?ticketId=${ticket.ticketId}`;
            link.textContent = `#${ticket.ticketId} ${ticket.title}`;
            detail.textContent = `${ticket.status} / 최근 대화: ${ticket.lastActivityAt ?? "기록 없음"}`;
            item.append(link, detail); list.append(item);
        }
        status.textContent = state.items.length === 0 ? "등록한 문의가 없습니다." : `내 문의 ${state.items.length}건을 읽었습니다.`;
        more.hidden = !state.hasMore;
    }
}});
refresh.addEventListener("click", () => { void client.refresh(); });
more.addEventListener("click", () => { void client.more(); });
window.addEventListener("pagehide", () => client.clear());
void client.refresh();
