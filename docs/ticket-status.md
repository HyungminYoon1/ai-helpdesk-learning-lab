# Ticket status changes and explicit reopening

Status: T01-T08 accepted on 2026-11-04. Technical contract for the next local implementation slice; runtime results are recorded after execution. No deployment, production migration or live AI is part of this work.

## Policy and ownership

Keep OPEN -> IN_PROGRESS -> RESOLVED and add explicit RESOLVED -> OPEN. Assignment, replies, confirmations and customer messages never implicitly change status. New START/RESOLVE commands require the current assignee and active service membership. A supervisor first claims/transfers the ticket to themselves. The requester may explicitly reopen their own ticket independently of staff membership; the current staff assignee may also reopen. Reopening preserves assignee/assignment version. No cancellation, duplicate/spam closure, automatic resolution, merge or deletion is provided.

RESOLVE records a nonblank reason, a related OFFICIAL_REPLY from this ticket, and the caller-selected latest customer Message ID. The current assignment must have an explicit confirmation through that message. A newer customer message committed before resolution rejects the old command. The server never promotes its selected range using another tab's newer confirmation. If resolution commits first, a later message remains stored and needs confirmation, without automatic reopening. A reference/confirmation is structural evidence, not proof of semantic correctness or actual reading.

## Storage and baseline

V1-V12 remain immutable. V13 adds tickets.status_version (nonnegative bigint, default 0) and append-only ticket_status_events. Zero is the version baseline at feature adoption, not fabricated historical events or proof of no earlier work. Existing titles/statuses/owners/assignments/messages/AI data remain unchanged.

Each event records ticket/actor/UUID, server-derived request scope (STAFF/REQUESTER), action, original expected status version, resulting version, actual assignment version, staff expected assignment version, from/to status, reason, optional resolution reply/customer references and server timestamp. Unique (ticket, actor, UUID) and (ticket, resulting status version) enforce distinct submissions and transitions. Same-ticket FKs protect referenced messages and positive assignment versions; assignment version 0 remains representable for unassigned legacy data. Service validates message kinds. Current status/version and event commit or roll back together.

All new commands lock the parent Ticket FOR UPDATE before event allocation. Membership authorization follows using existing FOR SHARE service checks. No network/AI work runs inside this transaction. A two-second lock wait maps to the existing safe 503 SUPPORT_BUSY contract. Storage/Commit failures use safe errors without raw payloads/SQL/credentials. A connection failure is not evidence of rollback.

Validate current read authority before submission lookup. An existing exact submission is returned before new-write assignee/version/transition checks, so an authorized former assignee can reconcile an old committed command. Compare scope/action/expected versions/reason/resolution references exactly; changing one conflicts. A replay never reapplies the transition or rewinds current state. Return the historical change separately from a fresh current-state snapshot. Revoked staff cannot replay through the staff route; requester reopening uses ownership, not staff permissions. Switching between requester/staff routes under one UUID conflicts.

## API and projection

Session authentication, CSRF on writes and no-store remain in effect. IDs are positive signed long integers; expected status version is 0..Long.MAX_VALUE-1, staff assignment version is positive and below Long.MAX_VALUE. UUID must be canonical. Reason is nonblank, unchanged in storage and <=500 Unicode code points. All JSON requests reject missing/extra/duplicate fields, wrong/coercible types, trailing JSON and bodies >32 KiB. Responses expose no credentials or login names.

Staff AGENT routes also require current active service read access:

- GET /api/tickets/{id}/status: status/statusVersion/assignmentVersion, latestCustomerMessageId, effectiveThroughMessageId and canStart/canResolve/canReopen. One statement reads the ticket/confirmation snapshot. GET/HEAD performs no status change.
- GET /api/tickets/{id}/status-events?afterId=0&limit=50: bounded ascending event-ID history (limit 1..100). Staff may see actor IDs and audit reasons within their service, not login credentials.
- POST /api/tickets/{id}/status-changes: fields action (START/RESOLVE/REOPEN), clientRequestId, expectedStatusVersion, expectedAssignmentVersion, reason. Only RESOLVE additionally requires relatedReplyMessageId and throughCustomerMessageId; those fields are forbidden for other actions.

Requester routes require the authenticated fixed requester identity, including when the same account also has AGENT:

- GET /api/tickets/{id}/customer-status: only status/statusVersion/canReopen. No assignment metadata, staff audit reasons, AI internals or permission inferred from request fields.
- POST /api/tickets/{id}/customer-status/reopen: exactly clientRequestId, expectedStatusVersion, reason. Scope/action are fixed by the endpoint; no staff assignment version is accepted. Store the actual assignment version without changing it.

New committed event returns 201; exact replay returns 200. Both contain change and current separately. Customer change projection includes only id/fromStatus/toStatus/statusVersion/reason/recordedAt for their own submission. Staff responses/history retain the complete audit fields. The current projection is a statement snapshot, not a future-state guarantee. No unsupported Location or notification delivery claim is introduced.

## Errors and safe recovery

- 400 INVALID_STATUS_REQUEST, INVALID_STATUS_TARGET; 413 REQUEST_TOO_LARGE.
- 401 authentication required; 403 filter/CSRF or STATUS_FORBIDDEN/NOT_ASSIGNEE; 404 STATUS_NOT_FOUND for missing/out-of-scope ticket. Ownership remains independent of employee access.
- 409 STATUS_REQUEST_CONFLICT for reused UUID with changed content/scope; STATUS_VERSION_CONFLICT or existing ASSIGNMENT_VERSION_CONFLICT; INVALID_STATUS_TRANSITION; NEW_CUSTOMER_MESSAGE; CUSTOMER_CONFIRMATION_REQUIRED; REQUESTER_UNCONFIRMED for new resolution of an unconfirmed requester.
- Existing membership/lock errors retain existing safe codes. Other unexpected storage errors are safe 500.

An unknown POST result retains the exact UUID/body/selected versions/references for explicit reconciliation. Known saved result plus failed GET retries only GET. A known rejected stale command requires refreshed state and a new explicit decision; do not silently edit/repost the old command. HTTP 200 or AI success does not indicate resolution. Show ticket processing, AI work and human review separately; customer projections hide internal errors/handoff reasons and promise no unagreed response time.

## Implementation decisions

| Context/options | Decision/rationale | Files and follow-up |
|---|---|---|
| One endpoint with caller-supplied role versus separate staff/requester commands | Separate endpoints and server-derived scope. Customers need no internal assignment counter, while staff drafts retain assignment fencing. Shared event UUID key compares route scope | Status Service/DTO/controllers/security. Test dual-role requester, revocation and cross-route UUID conflict |
| Separate current-state and history transactions versus atomic save | One parent-row-locked transaction with independent status version. Baseline 0 preserves historical facts; event/result version advances only on actual new transition | V13/domain/repository. Test upgrade preservation, rollback, deferred Commit failure and duplicate/competing writers |
| Different reason policies per action versus one explicit audit input | Require a <=500-code-point reason for each command. This implements T05's audit reason without implied user intent or extra status types | Request parser/Service/UI. Future UX may offer explicit starting-reason templates; no automatic closure |
| Application-only references versus same-ticket constraints | Keep existing Service kind checks and composite message FKs. A generated NULLIF(assignment_version,0) reference links positive assignment history and preserves baseline 0 | V13/JDBC adapter. Test invalid references and baseline requester reopening |

Planned validation: domain transitions; V12->V13 preservation/constraints; actual PostgreSQL HTTP authorization/parser/replay/Commit tests; both customer-message/resolution commit orders, handoff and revocation; Node/UI/representative Browser; appropriate full regression. Codex authors/runs these checks; they do not prove independent learner implementation or production readiness.

Local storage checkpoint, 2026-11-04: TicketTest 14 cases (including its existing cases) and one V12->V13 migration/storage test passed. The latter preserves existing row content, checks baseline zero/no fabricated events, same-ticket/identity/version constraints, requester assignment-zero reopening and rollback of an update whose event insert fails. Log: target/week12-status-storage.log (ignored). Service/HTTP/UI verification is still pending at this checkpoint.
