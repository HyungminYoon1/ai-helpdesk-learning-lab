import {isTicket} from "./ticket-ui.mjs";

const fields = (v, names) => v !== null && typeof v === "object" && !Array.isArray(v)
    && Object.keys(v).length === names.length && names.every((name) => Object.hasOwn(v, name));
const isCursor = (v) => typeof v === "string" && /^[A-Za-z0-9_-]{1,1024}$/.test(v);

export function isMyTicketsPage(value) {
    if (!fields(value, ["items", "page"]) || !Array.isArray(value.items) || value.items.length > 50
            || !fields(value.page, ["nextCursor", "hasMore"]) || typeof value.page.hasMore !== "boolean") return false;
    const ids = new Set();
    if (!value.items.every((item) => {
        if (!fields(item, ["ticketId", "title", "status", "lastActivityAt"])
                || !isTicket({id: item.ticketId, title: item.title, status: item.status}) || ids.has(item.ticketId)
                || !(item.lastActivityAt === null || typeof item.lastActivityAt === "string" && Number.isFinite(Date.parse(item.lastActivityAt)))) return false;
        ids.add(item.ticketId);
        return true;
    })) return false;
    return value.page.hasMore ? value.items.length > 0 && isCursor(value.page.nextCursor) : value.page.nextCursor === null;
}

export function createMyTicketsClient({fetchImpl, view}) {
    let version = 0, controller = null, cursor = null, items = [], loading = false;
    async function read(append = false) {
        if (append && (loading || cursor === null)) return;
        controller?.abort();
        const request = ++version;
        const boundary = append ? cursor : null;
        if (!append) { items = []; cursor = null; }
        controller = new AbortController();
        loading = true;
        view.render({kind: "loading", append});
        try {
            const response = await fetchImpl(`/api/my/tickets?limit=50${boundary === null ? "" : `&cursor=${encodeURIComponent(boundary)}`}`, {
                method: "GET", credentials: "same-origin", cache: "no-store", signal: controller.signal
            });
            if (request !== version) return;
            if (!response.ok) {
                if ([401, 403].includes(response.status)) { items = []; cursor = null; }
                view.render({kind: ({401: "login-required", 403: "forbidden"})[response.status] ?? "unavailable"});
                return;
            }
            const body = await response.json();
            if (request !== version) return;
            if (!isMyTicketsPage(body) || body.page.hasMore && body.page.nextCursor === boundary) {
                view.render({kind: "invalid-response"}); return;
            }
            // A changing activity list can repeat a Ticket across pages. Keep one copy.
            const merged = new Map(items.map((item) => [item.ticketId, item]));
            for (const item of body.items) merged.set(item.ticketId, item);
            items = [...merged.values()]; cursor = body.page.nextCursor;
            view.render({kind: "tickets", items, hasMore: body.page.hasMore});
        } catch (error) {
            if (request === version && error?.name !== "AbortError") view.render({kind: "unavailable"});
        } finally {
            if (request === version) { loading = false; view.setLoading(false); }
        }
    }
    function clear() {
        controller?.abort(); version++; cursor = null; items = []; loading = false;
        view.setLoading(false); view.render({kind: "cleared"});
    }
    return {refresh: () => read(), more: () => read(true), clear};
}
