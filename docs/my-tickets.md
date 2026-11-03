# My Tickets query

The postgres profile exposes `GET /api/my/tickets?limit=50&cursor=...`. USER/AGENT Authentication must carry a linked durable account key. The server resolves that key to requester_user_id; neither staff membership nor a caller-supplied user/service ID grants list access. A revoked staff member can still list their own customer Tickets while their login remains valid.

Return `items: [{ticketId,title,status,lastActivityAt}]` and `page: {nextCursor,hasMore}` with no-store. Empty results are 200. Anonymous access is 401, unlinked identity is 403. Reject unknown or duplicate query parameters, invalid limits outside 1..100 and malformed cursors with 400 INVALID_REQUEST. The Controller only converts query/response data and delegates to a read-only application Service and repository.

Activity is the created_at of the last public Message by ID, including INQUIRY, CUSTOMER_FOLLOWUP and OFFICIAL_REPLY. It is not MAX(created_at), assignment history or the last Ticket update. An owned legacy Ticket without messages remains visible with NULL activity. Order by activity DESC NULLS LAST, then Ticket ID DESC. Fetch limit+1 to determine hasMore.

The opaque unpadded Base64url cursor contains exactly v=1, lastActivityAt (ISO Instant or NULL) and a positive signed Long ticketId. Bound input to 1,024 ASCII characters before decoding, reject duplicate JSON properties/trailing JSON and retain microsecond precision. A cursor defines only a page boundary, never authorization. The requester predicate applies to every query. All SQL arguments are bound; cursor contents never become SQL text.

Each query has its own PostgreSQL statement snapshot. A Ticket that receives a new message between pages can move above the old cursor. Refresh from the beginning to discover it; the UI deduplicates Ticket IDs. This is a changing activity list, not a fixed cross-page snapshot or complete real-time feed.

## Decisions and verification

| Context/options | Decision and rationale | Files and follow-up |
|---|---|---|
| Store another activity field or derive from public conversation | Read latest public Message by ID in a lateral join; avoid an independent activity write that could drift | MyTicketsRepository/JDBC. Existing requester and message indexes retained; no unmeasured performance claim |
| Offset or activity cursor | Versioned bounded cursor with explicit NULL branch and ID tie breaker | MyTicketsQuery and application Service. Equal timestamps, NULL activity and moving Tickets tested |
| Staff service list or customer list | Always requester identity, including staff accounts | Security/Controller/Service. Cross-customer, unlinked identity and revoked staff cases tested |

Local verification uses isolated PostgreSQL Testcontainers and MockMvc, with AI Worker/Provider disabled. The 27 cases cover identity/read-only behavior, strict query/cursor validation, equal timestamps/NULL pages, latest-Message semantics and refresh after movement. MockMvc retains a mapped HEAD response body; that test checks authorization and unchanged data, not on-wire body suppression. Browser integration is a subsequent check. No schema migration, remote CI, AWS or real AI call is required for this query.
