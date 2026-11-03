# Customer follow-up storage contract

Status: the first V11 slice is implemented and locally verified on 2026-11-03. Customer list/composer, confirmation records and state APIs are separate slices.

## Scope

Keep the existing Session, durable identity, service assignment and short transaction boundaries. Only the Ticket requester may write a customer follow-up. An AGENT who owns a Ticket uses their customer identity on this route; staff membership or assignment is not customer ownership. Handoff and staff revocation do not remove that ownership. Unknown owners are never inferred from usernames.

OPEN, IN_PROGRESS and RESOLVED accept follow-ups without changing status. Store original text. No reply/correction reference, automatic topic splitting, AI Job creation, Provider call, assignment change or original-message mutation occurs. Explicit resolution/reopening is a separate policy/API.

## HTTP and storage

- POST /api/tickets/{ticketId}/customer-messages requires USER/AGENT Session and CSRF. Accept exactly `body` and `clientRequestId`. Positive signed Long Ticket ID and canonical UUID; no author, type, correction, assignment or service fields.
- Read at most 32 KiB plus one byte before JSON tree creation. Exceeding the actual stream limit returns 413 REQUEST_TOO_LARGE regardless of Content-Length. Reject malformed JSON, duplicate properties, trailing JSON and coercible wrong types locally; do not change global Jackson settings.
- Reuse TicketMessage nonblank/2,000 stripped code-point measurement while preserving the original body.
- Lock the parent Ticket before checking DB requester ownership, submission lookup and Message ID allocation. No staff membership lock is needed to exercise customer ownership. Other owners and unconfirmed owners return 404; missing identity is 403.
- Insert CUSTOMER_FOLLOWUP with authenticated author ID/username and UUID. Correction and reply assignment version are NULL. V11 expands the V9/V10 checks without editing V1-V10 or backfilling historical ownership/assignment.
- Commit a new message before returning 201/no-store. An exact same Ticket/author/UUID/type/body replay returns 200 and adds no row. Changed body or type returns 409 FOLLOWUP_REQUEST_CONFLICT.
- The shared submission Unique spans official replies and customer follow-ups. Both routes now verify message kind; the replies route returns REPLY_REQUEST_CONFLICT for an existing customer submission, including the absent-version case that could resemble a legacy reply.
- Confirmed rollback cancels only new changes. Unknown Commit/HTTP acknowledgement is not rollback evidence; retain the original request for explicit reconciliation. Existing inquiry/reply/assignment and AI Job/Attempt/Suggestion/category rows remain independent.

The existing bounded conversation GET includes CUSTOMER_FOLLOWUP. Its decoder accepts the type without treating it as an official correction or saved-reply response. Text rendering labels customer inquiry/follow-up and staff reply without exposing login names or internal IDs. The customer follow-up composer and canFollowUp capability are not added by this storage slice.

## Implementation decisions

| Context and alternatives | Decision and reason | Files and follow-up |
|---|---|---|
| New message table vs existing conversation | Extend MessageType and V11 checks, reuse parent lock and submission key | Message/Service/JDBC/V11. Preserve legacy NULL reply version and original rows |
| Parse entire JSON before checking size vs bounded endpoint-local read | Controller delegates bounded conversion to CreateCustomerFollowupRequest; application owns business rules | Parser/controller. Actual-byte boundary, escaped emoji, malformed/duplicate/trailing input tests |
| Global AGENT permission vs requester ownership | Server checks stable requester ID, independent of current assignment/membership | Service/security. Other customer/staff denied; owner-after-revocation case |
| Same UUID/body assumed sufficient vs compare message kind | Compare both directions before accepting any replay | Service and Java/JS tests. Different endpoint kinds cannot become an accidental 200 |
| Regrant memberships at startup vs reset only synthetic test fixtures | Keep production insert-only bootstrap; restore test-owned fixture membership in BeforeEach | HTTP test isolation. First focused run exposed retained revocation between tests |

## Verification boundaries

Focused tests use an isolated PostgreSQL Testcontainer and MockMvc, not a live Browser or external AI. The HTTP suite covers ownership/CSRF, all three states, original text, 201/200/409, mixed staff/customer identity, actual stream/character bounds, concurrent same-key submission and rollback. A deferred foreign-key trigger forces failure at actual Commit, and a nonempty AI result/assignment/reply snapshot is checked after a forced post-insert failure. Provider/Worker are disabled and the Provider mock must have no interactions.

The migration test upgrades V10 data to V11 and checks original message/ticket/assignment rows, legacy reply versions, required follow-up fields and the shared key. JavaScript tests distinguish a valid follow-up in a conversation from an invalid saved-official-reply response. Current runtime probes expect V11; historical migration targets remain scoped to their own versions.

Final `mvnw -B -ntp verify` passed 1,095 Surefire tests and 2 packaged Failsafe tests, zero failures/errors/skips. This includes the 26-case follow-up HTTP suite and one V10-to-V11 migration case; focused runs overlap and are not added to this total. All 333 JavaScript tests and ESLint passed. The initial focused run had one test-fixture membership-isolation error, corrected before the full run without weakening production authorization.

These are local checks. No new live Browser experiment, remote CI, Image publication, AWS or real Provider call was run for this slice. A pre-V11 Image cannot safely read the new message kind; future deployment/rollback requires explicit compatibility checks.
