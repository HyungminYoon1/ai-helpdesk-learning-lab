# Service-scoped ticket assignment contract

Status: accepted policies O1-O6, local implementation authorized 2026-10-24. Verification is recorded after execution, not inferred from this document. No push, Cloud or live AI execution is authorized by this request.

## Responsibility and access

One operating organization supports its own products through one Helpdesk. A support service is not an independent SaaS tenant. Active service members may read that service's tickets, conversations, AI results and assignment history. Only the current assignee may publish new replies/corrections. A service SUPERVISOR must transfer a ticket to themselves before replying. Customers still read only their own conversations; support revocation does not revoke that customer ownership.

An active AGENT claims an unassigned ticket for themselves. First committed claim wins. The current assignee or an active service SUPERVISOR transfers to another active member. Transfer takes effect at Commit without recipient acceptance. No automatic assignment/unassignment, first assignment to someone else, notification or credential-management endpoint is introduced. Deactivating membership preserves assignment and history; a supervisor explicitly transfers the remaining work.

## Provisioning and migration

Keep V1-V9 immutable. V10 adds services, memberships, ticket service/assignee/version, append-only assignment events, and nullable reply_assignment_version. Composite FKs keep references within the same service/ticket; UNIQUE submission and resulting-version keys prevent duplicates. Existing service/assignment values remain NULL/zero; never guess historical ownership or grant all AGENTs access. Legacy official replies keep NULL assignment versions.

Configure `helpdesk.support.receipt-service-code` explicitly for new receipts. The server selects that registered service; HTTP receipt bodies still accept only title/body. Missing/unregistered service fails closed before receipt writes. This slice does not provide an arbitrary customer-selectable service catalog or cross-application login integration.

Trusted startup provisioning uses `helpdesk.support.bootstrap.enabled=true`, `helpdesk.support.bootstrap.service-code`, `helpdesk.support.bootstrap.service-name`, and indexed `helpdesk.support.bootstrap.members[n].account-key`, `.display-name`, `.role` (AGENT/SUPERVISOR). Identities must already be explicitly bound. Grants are INSERT-only: restart never reactivates or changes an existing DB membership. No production default or historical ticket remapping is provided. Account keys are identifiers, not credentials or HTTP authority. Administrative SQL must be reviewed separately; no public grant/revoke API is added.

## Transactions and revocation

All assignment/reply writes lock the parent Ticket FOR UPDATE before allocating event/message IDs. Then lock referenced membership rows FOR SHARE in user-ID order, checking active/role from DB. Read operations use short transactions and a shared membership lock. Revocation updates a membership without subsequently acquiring Ticket locks: it waits for an already-authorized transaction, then future requests (including old Sessions) see inactive membership. This is transaction-order authorization, not cancellation of responses already in flight. No HTTP/AI operation runs inside these transactions.

State and event commit together. An event INSERT or Commit failure rolls both back; previously committed inquiry/reply/AI rows survive. Limit lock waits to 2 seconds (SQLSTATE 55P03 -> safe 503 SUPPORT_BUSY). Do not continue querying an aborted transaction. Other storage/Commit failures return safe 500 without SQL, names, bodies or credentials.

## API

Staff endpoints require Session AGENT; writes also require CSRF. Application rechecks DB identity and active service membership. All responses use no-store. Unknown/out-of-scope service/ticket is 404 without existence disclosure. Additional fields and coercible-but-wrong JSON types are rejected.

- GET /api/services: caller's active services, not a customer directory.
- GET /api/services/{id}/agents?afterId=0&limit=50: active member IDs/business display names/roles; no login names.
- GET /api/services/{id}/assignment-queue?view=UNASSIGNED|MINE&afterId=0&limit=50: bounded ID-ordered work list. Refresh from zero to discover newly committed tickets; this is not a durable dispatch cursor or FIFO guarantee.
- GET /api/tickets/{id}/assignment: current assignee/version and caller capabilities.
- GET /api/tickets/{id}/assignment-events?afterId=0&limit=50: ascending event IDs with bounded pages.
- POST /api/tickets/{id}/assignment/claim: `{clientRequestId, expectedAssignmentVersion}`.
- POST /api/tickets/{id}/assignment/transfers: `{clientRequestId, expectedAssignmentVersion, toAgentId, reason}`. Reason is nonblank, original length <=500 Unicode code points. Target is another active member of the same service.

IDs are positive signed 64-bit integers; version is a nonnegative integer; canonical UUID string required. Page limit 1..100. New committed event is 201; exact actor/ticket/UUID/operation/version/target/reason replay is 200. Return the original event separately from current assignment, since a later transfer may already exist. Same UUID with changed fields is 409 ASSIGNMENT_REQUEST_CONFLICT. Check current service read permission before replay, but replay before current owner/version checks. Replay does not restore new-write permission.

New operations reject stale versions (409 ASSIGNMENT_VERSION_CONFLICT), assigned claims (409 ASSIGNMENT_NOT_UNASSIGNED), unassigned transfers (409 ASSIGNMENT_REQUIRED), unauthorized transfer (403 ASSIGNMENT_FORBIDDEN), invalid/inactive/out-of-service targets (400 INVALID_ASSIGNEE). Do not auto-retry with a new UUID or silently refresh expected version.

## Official reply revision and read paths

The earlier all-AGENT reply/read policy in official-replies.md is superseded by this contract. New reply requests require positive `expectedAssignmentVersion`; store it with the reply. Existing UUID/body/correction/version replays are allowed only after current read authorization. Already-saved legacy NULL-version replies accept only absent/null expected version; adding a version conflicts. Missing version for a new reply or an existing nonlegacy result is 400. Changed version with same UUID is 409 REPLY_REQUEST_CONFLICT. A->B->A does not revive old drafts. New non-owner writes are 403 NOT_ASSIGNEE. Requester-unconfirmed restriction remains.

Conversation capabilities return canReply and expectedAssignmentVersion (null for customers/nonassignees). Public messages expose no internal actor or assignment IDs. Existing Ticket and AI-result GET/HEAD also enforce service membership, so revocation cannot be bypassed through old endpoints. Non-postgres learning fixtures retain their isolated legacy behavior; this is not a production fallback.

UI keeps exact pending UUID/body/target/version when outcome is unknown, ignores stale reads after selection change, uses textContent, clears sensitive views on pagehide, and never stores drafts/credentials in persistent browser storage. Viewing an old event never overwrites the current assignment. Client-side capability checks never replace server checks.

## Implementation decisions

| Context / alternatives | Decision and reason | Files / follow-up |
|---|---|---|
| Per-app deployment vs pooled external SaaS vs one organization's services | Service-scoped access within existing layers and DB. Neither resource duplication nor a tenant-isolation claim | V10, support package. Independent organizations remain future design |
| Default auto-grants vs public access management vs explicit provisioning | Explicit server receipt route and insert-only trusted bootstrap, preserving revocation on restart | Support bootstrap/configuration and tests. Legacy mapping and full account lifecycle remain administrative work |
| UI-only owner check vs short row locks | Ticket first, ordered membership shared locks, same-TX event+state and bounded waits; revocation serializes on membership | Assignment/reply/access services and JDBC repository. No throughput improvement claimed |
| Reject all old clients vs silently fill reply version | Legacy saved-result-only replay; new writes must supply expected version. Never invent historical assignment | Reply parser/service/repository and UI. Client/server must be released together |
| Only guard new endpoints vs close old read bypasses | Recheck service scope on ticket/conversation/AI reads; keep customer own-message rights separate | Scoped query service/security tests. No credential database or Session replacement |

Verification must distinguish DB constraints, HTTP tests, concurrency/Commit rollback, synthetic Browser behavior, and full regression. Preserve original title/body/requester/author/correction and Job input/attempts/results across assignment tests; Provider remains disabled. This feature alone is not production readiness.
