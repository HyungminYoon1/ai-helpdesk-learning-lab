# Customer follow-up storage contract

Status: V11 customer follow-ups/list/composer and the V12 explicit-confirmation backend are implemented on 2026-11-03. Confirmation UI/queue integration and state APIs remain separate slices. Storage, Browser and subsequent backend verification are recorded separately below.

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

The bounded conversation GET includes CUSTOMER_FOLLOWUP. Its decoder accepts the type without treating it as an official correction or saved-reply response. Text rendering labels customer inquiry/follow-up and staff reply without exposing login names or internal IDs. The initial storage commit 645b3df did not add a composer; the next slice below connects it.

## Customer list and composer

`/my-tickets.html` uses the [requester-scoped activity query](./my-tickets.md), retains results only in memory, appends pages with Ticket ID deduplication and refreshes from the beginning on request. It ignores stale responses and clears data on pagehide. A fixed same-origin link selects `/conversation.html?ticketId=...`; unsafe/duplicate query IDs are rejected by the page, and every backend read still verifies access.

Conversation capabilities now include `canFollowUp`, computed from stable requester identity independently of `canReply`. These are UI hints; every write rechecks current server authority. Server and the strict JS decoder change together. A mixed-role account can see both forms when it owns and currently handles the Ticket. Losing staff membership does not remove customer ownership.

The customer form sends only body/clientRequestId using Session/CSRF. It has no correction or assignment fields. Shared posting coordination prevents simultaneous form submissions; an unknown submission retains its kind as well as exact payload/UUID, so switching forms cannot reuse it for a different command. A confirmed save followed by failed GET clears the saved draft and asks for a read, not a new POST. Reply drafts retain their captured assignment version across unrelated customer saves.

Both forms render original text with textContent and clear memory on Ticket changes/pagehide. They do not resolve/reopen Tickets or invoke AI. The customer composer and AI page explicitly say the summary/classification/priority describe the initial inquiry and do not incorporate later conversation. General account lifecycle, cross-navigation draft recovery and automatic refresh remain outside this slice.

New decisions: derive canFollowUp from requester ownership rather than staff capability; keep pending submission kind outside the transmitted DTO; reuse the existing conversation client instead of a second independent POST coordinator. These choices affect Conversation Service/Controller, conversation UI/page and Node tests. Review server/client compatibility together at deployment; no old Image compatibility is implied.

## Implementation decisions

| Context and alternatives | Decision and reason | Files and follow-up |
|---|---|---|
| New message table vs existing conversation | Extend MessageType and V11 checks, reuse parent lock and submission key | Message/Service/JDBC/V11. Preserve legacy NULL reply version and original rows |
| Parse entire JSON before checking size vs bounded endpoint-local read | Controller delegates bounded conversion to CreateCustomerFollowupRequest; application owns business rules | Parser/controller. Actual-byte boundary, escaped emoji, malformed/duplicate/trailing input tests |
| Global AGENT permission vs requester ownership | Server checks stable requester ID, independent of current assignment/membership | Service/security. Other customer/staff denied; owner-after-revocation case |
| Same UUID/body assumed sufficient vs compare message kind | Compare both directions before accepting any replay | Service and Java/JS tests. Different endpoint kinds cannot become an accidental 200 |
| Regrant memberships at startup vs reset only synthetic test fixtures | Keep production insert-only bootstrap; restore test-owned fixture membership in BeforeEach | HTTP test isolation. First focused run exposed retained revocation between tests |

## Initial storage verification (645b3df)

Focused tests use an isolated PostgreSQL Testcontainer and MockMvc, not a live Browser or external AI. The HTTP suite covers ownership/CSRF, all three states, original text, 201/200/409, mixed staff/customer identity, actual stream/character bounds, concurrent same-key submission and rollback. A deferred foreign-key trigger forces failure at actual Commit, and a nonempty AI result/assignment/reply snapshot is checked after a forced post-insert failure. Provider/Worker are disabled and the Provider mock must have no interactions.

The migration test upgrades V10 data to V11 and checks original message/ticket/assignment rows, legacy reply versions, required follow-up fields and the shared key. JavaScript tests distinguish a valid follow-up in a conversation from an invalid saved-official-reply response. At this slice runtime probes expected V11; the confirmation backend later updates that expectation to V12. Historical migration targets remain scoped to their own versions.

Final `mvnw -B -ntp verify` passed 1,095 Surefire tests and 2 packaged Failsafe tests, zero failures/errors/skips. This includes the 26-case follow-up HTTP suite and one V10-to-V11 migration case; focused runs overlap and are not added to this total. All 333 JavaScript tests and ESLint passed. The initial focused run had one test-fixture membership-isolation error, corrected before the full run without weakening production authorization.

These are local checks. No new live Browser experiment, remote CI, Image publication, AWS or real Provider call was run for this slice. A pre-V11 Image cannot safely read the new message kind; future deployment/rollback requires explicit compatibility checks.

## Subsequent list/composer verification

MyTicketsHttpIntegrationTest passed 27 PostgreSQL/MockMvc cases. The updated conversation HTTP suites passed 50 cases, and the explicit ConversationBrowserExperiment passed separately using synthetic USER/AGENT accounts, a random local password, an isolated browser Session and PostgreSQL. No real credentials or Provider keys are used. The Browser runner is excluded from the default `*Test` suite.

The Browser checked an empty other-customer list, the owner's one-Ticket list, real HEAD body suppression, list-to-conversation navigation, customer form/CSRF, new message type, original text and text-only rendering. JDBC assertions verified six Messages (original, four official replies, one customer follow-up), authenticated author and UUID, OPEN status, one original PENDING Job, no Attempt/reservation/suggestion and unchanged assignment history/version values. Provider mock interactions were zero. Its screenshots stay in ignored output/playwright directories.

Final full regression passed 1,122 Surefire tests and 2 packaged Failsafe tests, zero failures/errors/skips, BUILD SUCCESS. All 345 Node tests and ESLint passed. Node scenarios cover nullable/microsecond activity, moving-page deduplication, stale reads, unknown POST kind/key preservation and saved-then-GET-failed recovery. Focused tests overlap with the full count; the explicit Browser experiment is separate. These simulations do not replace Browser or DB Commit tests.

Surefire reported a fork-JVM exit timeout after tests in this final run. The initial storage log did not contain that exact message; earlier general shutdown/Hikari notes do not establish identical behavior or cause. Test outcomes and packaging passed, but clean test-process shutdown remains an unresolved follow-up. No remote CI/Push, Image, Cloud or real AI call was performed.

## Explicit confirmation backend

The [V12 confirmation contract and verification](./customer-message-confirmations.md) adds current-assignee confirmation records/API with assignment-scoped MAX, separate replay/current state and no implicit action on GET. Its focused 43 cases and full Java 1,165+2 passed locally. Staff queue/confirmation buttons and their Browser flow are still pending. This backend creates no new Message or AI work and does not change customer ownership, Ticket state, message content or original suggestions.
