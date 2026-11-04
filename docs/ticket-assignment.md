# Service-scoped ticket assignment contract

Status: design complete for the O1-O6 assignment/handoff slice, implementation locally verified on 2026-10-24. Documentation review/publication is separate from runtime changes, Cloud deployment or live AI. Production readiness is separate.

## Responsibility and access

One operating organization supports its own products through one Helpdesk. A support service is not an independent SaaS tenant. Active service members may read that service's tickets, conversations, AI results and assignment history. Only the current assignee may publish new replies/corrections. A service SUPERVISOR must transfer a ticket to themselves before replying. Customers still read only their own conversations; support revocation does not revoke that customer ownership.

An active AGENT claims an unassigned ticket for themselves. First committed claim wins. The current assignee or an active service SUPERVISOR transfers to another active member. Transfer takes effect at Commit without recipient acceptance. No automatic assignment/unassignment, first assignment to someone else, notification or credential-management endpoint is introduced. Deactivating membership preserves assignment and history; a supervisor explicitly transfers the remaining work.

## Provisioning and migration

Keep V1-V9 immutable. V10 adds services, memberships, ticket service/assignee/version, append-only assignment events, and nullable reply_assignment_version. Composite FKs keep references within the same service/ticket; UNIQUE submission and resulting-version keys prevent duplicates. Existing service/assignment values remain NULL/zero; never guess historical ownership or grant all AGENTs access. Legacy official replies keep NULL assignment versions.

Configure `helpdesk.support.receipt-service-code` explicitly for new receipts. The server selects that registered service; HTTP receipt bodies still accept only title/body. Missing/unregistered service fails closed before receipt writes. This slice does not provide an arbitrary customer-selectable service catalog or cross-application login integration.

Trusted startup provisioning uses `helpdesk.support.bootstrap.enabled=true`, `helpdesk.support.bootstrap.service-code`, `helpdesk.support.bootstrap.service-name`, and indexed `helpdesk.support.bootstrap.members[n].account-key`, `.display-name`, `.role` (AGENT/SUPERVISOR). Identities must already be explicitly bound. Grants are INSERT-only: restart never reactivates or changes an existing DB membership. No production default or historical ticket remapping is provided. Account keys are identifiers, not credentials or HTTP authority. Administrative SQL must be reviewed separately; no public grant/revoke API is added.

## Transactions and revocation

All assignment/reply writes lock the parent Ticket FOR UPDATE before allocating event/message IDs. Then lock referenced membership rows FOR SHARE in user-ID order, checking active/role from DB. Read operations use short transactions and a shared membership lock. Revocation updates a membership without subsequently acquiring Ticket locks: it waits for an already-authorized transaction, then future requests (including old Sessions) see inactive membership. This is transaction-order authorization, not cancellation of responses already in flight. No HTTP/AI operation runs inside these transactions.

State and event commit together. A rejected INSERT or confirmed failed Commit with rollback cancels both new changes; previously committed inquiry/reply/AI rows survive. Do not infer rollback from a connection loss or generic 5xx: Commit may have completed before its acknowledgement or the HTTP response was lost. Retain the original submission and reconcile against DB state in a separate authorized request. Limit lock waits to 2 seconds (SQLSTATE 55P03 -> safe 503 SUPPORT_BUSY). Do not continue querying an aborted transaction. Other storage/Commit failures return safe 500 without SQL, names, bodies or credentials.

An operation that already obtained the membership shared lock may finish before revocation obtains its conflicting lock and commits. If revocation gets the lock first, new staff authorization waits; after revocation commits it observes inactive membership and fails. A rolled-back revocation is not a completed revoke. Preserve the existing assignment/version/history; transfer remaining work explicitly. This does not cancel responses in flight or disable the entire login account.

## API

Staff endpoints require Session AGENT; writes also require CSRF. Application rechecks DB identity and active service membership. All responses use no-store. Unknown/out-of-scope service/ticket is 404 without existence disclosure. Additional fields and coercible-but-wrong JSON types are rejected.

- GET /api/services: caller's active services, not a customer directory.
- GET /api/services/{id}/agents?afterId=0&limit=50: active member IDs/business display names/roles; no login names.
- GET /api/services/{id}/assignment-queue?view=UNASSIGNED|MINE&afterId=0&limit=50: bounded ID-ordered work list. Refresh from zero to discover newly committed tickets; this is not a durable dispatch cursor or FIFO guarantee.
- GET /api/tickets/{id}/assignment: current assignee/version and caller capabilities.
- GET /api/tickets/{id}/assignment-events?afterId=0&limit=50: ascending event IDs with bounded pages.
- POST /api/tickets/{id}/assignment/claim: `{clientRequestId, expectedAssignmentVersion}`.
- POST /api/tickets/{id}/assignment/transfers: `{clientRequestId, expectedAssignmentVersion, toAgentId, reason}`. Reason is nonblank, original length <=500 Unicode code points. Target is another active member of the same service.

IDs are positive signed 64-bit integers; an assignment expected version is nonnegative and less than Long.MAX_VALUE; canonical UUID string required. Browser clients reject numbers outside their safe integer range. Page limit 1..100. New committed event is 201; exact actor/ticket/UUID/operation/version/target/reason replay is 200. Return the original event separately from current assignment, since a later transfer may already exist. Same UUID with changed fields is 409 ASSIGNMENT_REQUEST_CONFLICT. Check current service read permission before replay, but replay before current owner/version checks. Replay does not restore new-write permission.

The 2026-11-04 queue projection adds latestCustomerMessageId/At, effectiveThroughMessageId, needsConfirmation and canConfirm without changing its pagination or excluding RESOLVED tickets. Progress belongs to the current assignment; GET does not record confirmation. The exact fields, active-membership read capability and local focused verification are recorded in [customer-message confirmations](customer-message-confirmations.md#staff-queue-projection-2026-11-04).

New operations reject stale versions (409 ASSIGNMENT_VERSION_CONFLICT), assigned claims (409 ASSIGNMENT_NOT_UNASSIGNED), unassigned transfers (409 ASSIGNMENT_REQUIRED), unauthorized transfer (403 ASSIGNMENT_FORBIDDEN), invalid/inactive/out-of-service targets (400 INVALID_ASSIGNEE). Do not auto-retry with a new UUID or silently refresh expected version.

## Official reply revision and read paths

The earlier all-AGENT reply/read policy in official-replies.md is superseded by this contract. New reply requests require positive `expectedAssignmentVersion`; store it with the reply. Existing UUID/body/correction/version replays are allowed only after current read authorization. Already-saved legacy NULL-version replies accept only absent/null expected version; adding a version conflicts. Missing version for a new reply or an existing nonlegacy result is 400. Changed version with same UUID is 409 REPLY_REQUEST_CONFLICT. A->B->A does not revive old drafts. New non-owner writes are 403 NOT_ASSIGNEE. Requester-unconfirmed restriction remains.

Conversation capabilities return canReply and expectedAssignmentVersion (null for customers/nonassignees). Public messages expose no internal actor or assignment IDs. Existing Ticket and AI-result GET/HEAD also enforce service membership, so revocation cannot be bypassed through old endpoints. Non-postgres learning fixtures retain their isolated legacy behavior; this is not a production fallback.

UI keeps exact pending UUID/body/target/version when outcome is unknown, ignores stale reads after selection change, uses textContent, clears sensitive views on pagehide, and never stores drafts/credentials in persistent browser storage. Viewing an old event never overwrites the current assignment. Client-side capability checks never replace server checks.

## Explicit reply-draft review, 2026-11-04

The conversation client pins the first reply-capable assignment version until a saved reply or explicit fresh start. A later GET may expose a newer current version, including A-to-B-to-A, but must not rebind the old draft. An unsent draft with a known newer version now blocks new POSTs before CSRF/UUID allocation and offers an explicit discard-and-read action. It clears the body/correction choice, performs GET only, and requires the user to write again. A failed or unauthorized GET cannot enable a new reply.

A submitted draft can use that action only after the reply endpoint returns both HTTP 409 and ASSIGNMENT_VERSION_CONFLICT. The reply service checks current read access and the actor/Ticket/UUID's saved result before checking a new write's assignment version. This endpoint-specific order supports the narrow recovery condition; it does not make every 409 evidence of non-storage. Ordinary refresh retains the rejected body/target and old version until the user deliberately discards that draft.

Unknown reply outcomes retain the exact UUID/body/correction/version even when current GET shows a newer assignment. They expose only same-submission reconciliation, never this discard action. Other 409s, missing/unreadable codes or a pending customer follow-up also block draft reset. During reconciliation the original body/correction are read-only; a conversation refresh preserves a previously selected correction even if its target is outside the reloaded first page. A successful replay clears the draft. Ticket navigation/pagehide keeps the documented memory-only lifetime and does not cancel committed messages.

The review button belongs to official replies, independently of the customer-message confirmation widget. Both still rely on server authorization/Commit checks. No service, schema, permission, account lifecycle, retention, AI or cloud policy changes accompany this UI slice.

## Implementation decisions

| Context / alternatives | Decision and reason | Files / follow-up |
|---|---|---|
| Per-app deployment vs pooled external SaaS vs one organization's services | Service-scoped access within existing layers and DB. Neither resource duplication nor a tenant-isolation claim | V10, support package. Independent organizations remain future design |
| Default auto-grants vs public access management vs explicit provisioning | Explicit server receipt route and insert-only trusted bootstrap, preserving revocation on restart | Support bootstrap/configuration and tests. Legacy mapping and full account lifecycle remain administrative work |
| UI-only owner check vs short row locks | Ticket first, ordered membership shared locks, same-TX event+state and bounded waits; revocation serializes on membership | Assignment/reply/access services and JDBC repository. No throughput improvement claimed |
| Reject all old clients vs silently fill reply version | Legacy saved-result-only replay; new writes must supply expected version. Never invent historical assignment | Reply parser/service/repository and UI. Client/server must be released together |
| Only guard new endpoints vs close old read bypasses | Recheck service scope on ticket/conversation/AI reads; keep customer own-message rights separate | Scoped query service/security tests. No credential database or Session replacement |
| Documentation conflated rejected Commit with an unobserved outcome | Distinguish confirmed rollback from lost acknowledgement; preserve exact submission rather than assume failure or retry under a new UUID | Assignment/reply UI already retains unknown submissions. Documentation clarified; direct DB Commit-ACK fault injection remains unperformed |
| Old reply drafts stay pinned after refresh; page re-entry loses submission context | Choose explicit discard/GET/blank writing only for an unsent stale draft or a typed version rejection. Reject automatic version replacement and broad error-based reset | conversation client/page/HTML, Node tests and ReplyDraftReviewBrowserExperiment. Preserve uncertain replies and correction selections; no persistent draft recovery or server changes |

## Design closure and deferred work

The 2026-11-03 [customer follow-up storage slice](customer-followups.md) preserves O1-O6 and distinguishes requester ownership from staff membership. Customer follow-up UI/list, explicit confirmation, state APIs and production account lifecycle remain separate work. The V10 verification below is historical evidence, not a new V11 run.

The schema, request/response contracts, authorization, lock order, replay, failure boundaries and minimal UI are settled for this slice. Receipt routing currently selects one explicitly configured service; multi-product authentication/routing and legacy service mapping are not silently completed. Configured authentication is not production account lifecycle management. Customer follow-ups, state APIs, notifications, retention/deletion, private internal-ticket ACLs and SaaS remain separate decisions.

After confirmed POST success, retry a failed GET, not the POST. On an unknown POST retain the same UUID/version/body/target/reason for explicit reconciliation; a matching stored result returns 200, while no stored result still requires current eligibility/version checks. Clearing the in-memory submission on navigation limits recovery; persistent cross-session submission recovery is not implemented. An ignored late response never rolls back DB changes.

Design review covered composite constraints, short transactions and consistent locking. No throughput or authorization latency was measured; an index definition is not an observed query plan. Do not weaken revocation semantics with a TTL-only permission cache. Commit-acknowledgement fault injection and load measurements remain follow-up verification. The documentation closeout does not rerun or enlarge the historical test counts below.

Verification must distinguish DB constraints, HTTP tests, concurrency/Commit rollback, synthetic Browser behavior, and full regression. Preserve original title/body/requester/author/correction and Job input/attempts/results across assignment tests; Provider remains disabled. This feature alone is not production readiness.

## Verification 2026-10-24

Local `mvnw -B -ntp verify` passed 1,068 Surefire tests and 2 packaged Failsafe tests with zero failures/errors/skips. Node passed 331 tests; ESLint passed. Focused runs overlap with full regression and are not added to that total.

After final test hardening, `-Dtest=ConversationBrowserExperiment,TicketAssignmentHttpIntegrationTest test` passed the 25-case assignment suite plus one explicit Browser experiment again. This includes an actual retained MockHttpSession after membership revocation and a nonempty previously stored AI suggestion/category/attempt snapshot across successful handoff and rolled-back handoff. The Browser screenshot from this final run was inspected; test artifacts remain ignored under output/playwright.

The actual local Browser experiment used isolated USER A/B, AGENT A/B and a service supervisor against a PostgreSQL Testcontainer. It verified receipt, claim, reply/correction, handoff, supervisor takeover before replying, customer isolation/relogin and text-only rendering. Database checks found five messages (one inquiry, four official replies), three assignment events and reply assignment versions 1/1/2/3. Provider/Worker were disabled and real Provider calls were zero. An assignment screenshot was inspected. This does not test production credentials or real AI.

Regression initially exposed old test fixtures without durable staff identity/service settings and expectations for the formerly shared TicketController. Fixtures now explicitly provision synthetic membership and test the postgres-specific scoped query controller; no production authorization bypass was added. The old internal read-only AI query still exists, but its HTTP wrapper now holds a short membership shared lock and verifies that business rows remain unchanged. SQLSTATE 55P03 is mapped safely without exposing raw SQL.

The runtime test probe now expects V10 and its explicit synthetic receipt service; historic measurements are not rerun or relabeled. Existing tests that deliberately use pre-V10 data remain distinct from new V10 migration/storage checks. At this local verification stage, no push, remote CI, image publish, Cloud resource or real-secret changes were performed. Subsequent publication and exact-commit remote CI results are separate evidence.

## Verification of reply draft review, 2026-11-04

Node passed 399 cases (19 new and one revised stale-draft expectation relative to 380); ESLint and the new PowerShell script parser passed. The focused reply-client/panel file contains 41 cases, included in that total. They cover explicit review without POST/UUID allocation, typed versus ambiguous rejections, unknown reply/follow-up preservation, failed review GETs, in-flight/late responses, correction selection across a refreshed page and retained correction choices after saving.

ReplyDraftReviewBrowserExperiment uses a fresh PostgreSQL Testcontainer, one synthetic AGENT Session/CSRF and existing transactional services for fixture handoffs. It pins a draft in assignment 1, commits A-to-B-to-A to assignment 3 and observes the actual reply endpoint's 409. Refresh preserves its old body/correction; explicit review performs GET and leaves a blank composer. A new reply then commits with a different UUID/version 3. A later correction also commits in version 3, but its 201 is deliberately blocked from reaching the page. After another fixture round trip commits assignment 5, the Browser keeps the unknown correction's original body/target/UUID/version and explicitly receives 200 for that same saved Message.

The four upstream POST statuses are 409/201/201/200. PostgreSQL contains exactly two added official replies, both stored under assignment 3; the correction still references the original official reply. The inquiry and first reply, original Ticket title/status/requester/service, first assignment event and existing Job/Attempt/Suggestion/category/membership data are preserved. The four intended handoffs advance current assignment to 5/A, no confirmation rows appear, and Provider interactions stay zero. All four UI screenshots were inspected. This uses fixture service calls, not B's Browser login or an administrative production endpoint.

The initial combined run passed the three existing confirmation Browser cases but failed the new reply case; a narrow diagnostic rerun also failed. A post-save draft cleanup incorrectly removed the loaded correction options. A failing Node regression reproduced it; cleanup now clears only the current choice after save and retains the candidate list. The final combined run passed all four explicit Browser/PostgreSQL cases with zero failures/errors/skips and BUILD SUCCESS. Earlier failures are not relabeled as successful runs.

```powershell
.\mvnw.cmd -B -ntp '-Dtest=ReplyDraftReviewBrowserExperiment,CustomerConfirmationReselectionBrowserExperiment,CustomerConfirmationRecoveryBrowserExperiment,CustomerConfirmationAuthorityBrowserExperiment' test
```

Ignored logs are target/week12-reply-review-all-node.log, target/week12-reply-review-eslint.log and target/week12-reply-review-browsers-final.log. The new experiment is opt-in, outside default *Test execution. The previous full Java 1,173+2 and long Conversation Browser were not rerun, and the pre-existing full-suite JVM shutdown issue remains open. There is no new Push/CI, image, cloud, real-key or external AI evidence in this local slice.
