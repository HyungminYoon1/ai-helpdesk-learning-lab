# Official replies and conversation contract

Status: implementation started on 2026-10-23. The agreed P1-P8 policies are implemented incrementally; this document is not evidence that tests have passed.

## Scope and decisions

Keep Controller / Application / Domain / Repository responsibilities and the existing Session login. USER reads their own conversation. AGENT reads all conversations and writes replies. A committed reply is immediately visible to the requester. Replies never change Ticket status, including RESOLVED. Corrections append an OFFICIAL_REPLY referencing an existing official reply on the same Ticket. They never overwrite history. Neither replies nor reads create AI Jobs or invoke a Provider.

### Durable identity and Session binding

Store an internal BIGINT identity and an explicitly supplied UUID account key. A server-side configuration or an explicit administrative provisioning operation binds the current login name to that key. Never infer historical ownership from matching names. V9 leaves legacy ownership NULL; AGENT can read it but cannot publish a reply until ownership is independently established. There is no public ownership/provisioning endpoint.

The configured login Principal carries the account key captured at authentication, not an ID looked up by username on each request. This prevents a still-live Session from adopting a different identity when a name is reused. Renaming an account retains its key; replacing it with a different person requires a new key. Existing Session/Password verification stays in InMemoryUserDetailsManager. No signup, credential database or account-management UI is added.

Do not assign the same account key to two configured accounts. Startup rejects that configuration with a fixed error instead of silently merging their ownership. The identity-aware manager returns a fresh Principal per login: erasing credentials after authentication must not erase the stored credential needed for the next login. This small adapter retains the existing authentication storage rather than introducing a new credential system.

Optional properties `helpdesk.local.user.account-key`, `helpdesk.local.agent.account-key`, and their `helpdesk.deployment.*` equivalents explicitly enable identity-aware Principals for those accounts. Keys have no authentication authority by themselves and are never accepted in an HTTP body. A missing binding does not invent a user or fall back to a username. New receipt/reply operations fail closed; existing AGENT-only legacy reads remain possible. Bootstrap runs only against the configured postgres profile and only for explicitly configured keys.

### Storage and Transaction

V1-V8 remain immutable. V9 adds identities, nullable legacy Ticket requester / Message author IDs, INQUIRY/OFFICIAL_REPLY, UUID submission IDs, and correction references. New replies require an author ID and submission ID. A composite FK constrains correction references to the same Ticket; Application validates OFFICIAL_REPLY and existence. Unique `(ticket_id, author_user_id, client_request_id)` prevents duplicate submissions. Ordinary legacy messages have no request/correction ID.

Receipt validates identity before writes, then stores Ticket + original INQUIRY + ownership + Job atomically. Reply writes take the parent Ticket row lock before allocating a Message ID. All Message writes use the same parent lock, keeping per-Ticket ID pagination consistent across concurrent commits. Do not hold a Transaction over HTTP/AI calls. A reply failure never rolls back previously committed messages or AI results.

## HTTP

- GET /api/tickets/{ticketId}/messages?afterId=0&limit=50: USER owner or AGENT. HEAD has the same authorization. Default 50, maximum 100; ID ascending, limit+1 for hasMore. Return Ticket, canReply, messages, and nextAfterId/hasMore. Never expose internal author identities.
- POST /api/tickets/{ticketId}/replies: AGENT and valid CSRF. Accept only body, clientRequestId, optional correctsMessageId. Nonblank body, at most 2,000 code points after strip for measurement; store the original body. No HTML/Markdown interpretation.
- New committed reply: 201. Replay with the same Ticket, actor, submission ID, exact body and correction target: 200 with the existing result. Omitted/null correction targets are equivalent. A changed body or target under the same submission ID: 409 REPLY_REQUEST_CONFLICT.
- Unknown/unowned USER conversation: 404 without revealing existence. Anonymous API request: 401; missing CSRF POST may be rejected with 403 first. Non-AGENT reply: 403. Unconfirmed requester: 409 REQUESTER_UNCONFIRMED. Invalid inputs/targets: 400. Missing identity: 403 IDENTITY_UNLINKED. Database/Commit errors: safe 500 without SQL/body/identity disclosure.
- After authorization and structural input validation, check an existing submission before validating a new correction target, so replays and conflicts have deterministic semantics.

## UI and verification

Use textContent, bounded pages and same-origin Session/CSRF. Preserve a submission ID when the POST outcome is unknown; do not generate a fresh ID and automatically retry. Changing Ticket/account invalidates pending views and submission state. A new or edited submission gets a new ID. No bodies in localStorage or logs.

Use synthetic accounts and an isolated PostgreSQL Testcontainer for migration, identity rename/name reuse/restart, ownership, CSRF, replay/conflict, correction boundaries, concurrent inserts, rollback/commit failure, and unchanged Job/Attempt/Suggestion tests. Keep Provider/Worker off. Use Node tests and a local Browser run for the UI. No AWS changes, paid AI calls, push, or deployment are authorized by this implementation request.

Follow-up: persistent authentication/account management, customer follow-ups, deletion/retention operations, AI resummarization, production rollout. Any policy conflict or expansion requires review. Internal schema details may be adjusted within these accepted boundaries and recorded here.

## Implementation decision log, 2026-10-23

| Context | Alternatives | Decision and rationale | Affected files and review |
|---|---|---|---|
| Login names can be reused while a Session is still alive | Lookup ID by current name; replace authentication storage; bind an explicit immutable account key in the Principal | Bind the key in a fresh Principal and resolve its DB ID. Reject duplicate configured keys. Preserve Session/in-memory credential verification without transferring ownership by name | identity package, local/deployment Security configurations, identity tests. Administrative provisioning and account retirement remain explicit follow-ups |
| Correction references and replay must remain valid under concurrency | Application-only checks; new reply table; Message extension with DB constraints | V9 extends Message, adds same-Ticket composite FK and submission Unique. Service validates the target kind; short parent-row locks serialize writes before ID allocation | V9, TicketConversationService, JDBC repositories, migration/HTTP tests. No measured performance claim or new retention policy |
| Unknown POST outcome may already be committed | Automatic resend under a new ID; disable button only; preserve pending submission | Keep exact body/target/UUID for explicit confirmation, prevent edited unknown submissions, ignore stale views, and render text only | conversation UI, Node tests, local Browser experiment. Reloading the page loses in-memory submission state; this is not cross-session deduplication |
| Adding V9 changes current test-schema expectations | Edit old SQL; skip migration validation; scope the historical V8 validator and update the current runtime probe | Keep V1-V8 immutable. The V8-specific test validates its intended target; the current isolated resource probe expects nine successful migrations | AiPrivacyScanFailureMigrationIntegrationTest, SyntheticRuntimeResourceExperiment. Existing historical measurements are not rerun or relabeled |

Focused verification passed: 29 Java cases including the explicit Browser experiment, the 24-case conversation HTTP suite, identity configuration and historical V8 validation. Browser assertions used USER A, USER B and AGENT against an isolated PostgreSQL database; stored original/replies, CSRF/Session presence, owner isolation, correction and text-only rendering were checked. Provider calls were zero. Full regression results are recorded separately after their final run.
