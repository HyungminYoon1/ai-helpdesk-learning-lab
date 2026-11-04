# Explicit customer-message confirmation

The V12 backend implements the agreed current-assignee confirmation contract. The 2026-11-04 staff queue projection and explicit-selection UI are locally verified with focused backend tests, JavaScript and an actual Browser/PostgreSQL experiment. The existing customer composer and original AI processing are unchanged.

## What a checkpoint means

An audit row's `id` identifies that stored confirmation. `through_message_id` identifies the last customer Message explicitly included in it. For the same Ticket and assignment, records `(id=11, through=80)` and `(id=12, through=71)` are both retained; effective progress is 80. Neither last audit ID nor recorded_at defines progress. There is no expiry/TTL or deletion of lower confirmations.

Progress is scoped to the current assignment_version. After A/version 1 confirms through 80 and hands off to B/version 2, version 1's record remains history and version 2 starts with NULL progress. B's own explicit action is required. A returning in version 3 also starts a distinct scope. This records an explicit declaration, not proof of what a person actually read or understood.

## API and authorization

- `GET /api/tickets/{ticketId}/customer-message-confirmations/current` returns assignmentVersion, effectiveThroughMessageId, latestCustomerMessageId, needsConfirmation and canConfirm. HEAD has the same access rules. Neither writes confirmation records.
- `POST /api/tickets/{ticketId}/customer-message-confirmations` accepts exactly clientRequestId, expectedAssignmentVersion and throughMessageId. Require positive signed Long values/canonical UUID. Bound the actual JSON stream to 32 KiB before parsing, reject duplicate/trailing/extra fields and coercible wrong types. Oversize returns 413.
- Both routes require AGENT Authentication with linked durable identity and active membership in the Ticket's service. USER cannot read this staff-only state. CSRF remains enabled for POST.
- New confirmations require the current assignee and matching assignment version. SUPERVISOR must explicitly transfer to self first. A same-Ticket INQUIRY or CUSTOMER_FOLLOWUP is a valid target; staff replies, other Tickets and missing Messages return 400 INVALID_CONFIRMATION_TARGET. Do not replace an old explicit target with the newest Message.
- Before submission lookup, recheck current service access. An exact stored Ticket/actor/UUID/version/target replay returns 200 with no new row even after handoff; changed version or target returns 409 CONFIRMATION_REQUEST_CONFLICT. Revoked membership blocks replay as well. New records return 201 only after the transactional service proxy commits.
- The response separates the historical confirmation `{id,assignmentVersion,throughMessageId,recordedAt}` from currentConfirmation. Replaying an old confirmation never establishes progress for a new assignee.
- Confirmation UUID uniqueness belongs to this command's table. Message submission UUIDs still share their existing official-reply/follow-up namespace; there is no new global UUID namespace across unrelated commands.

NULL progress means no explicit confirmation in this assignment. If no customer Messages exist, latest is NULL, needsConfirmation and canConfirm are false. Otherwise needsConfirmation compares the latest customer ID with effective progress. canConfirm means the active caller is the current assignee and a customer target exists; it does not mean new work necessarily exists. Customers, ownership, Ticket status and AI state are independent.

## Staff queue projection (2026-11-04)

The existing assignment-queue response retains id, title, status, createdAt, assignmentVersion and its UNASSIGNED/MINE, ID-ascending pagination. Each entry adds latestCustomerMessageId, latestCustomerMessageAt, effectiveThroughMessageId, needsConfirmation and canConfirm. It includes RESOLVED tickets. latestCustomerMessageAt belongs to the highest-ID INQUIRY/CUSTOMER_FOLLOWUP, not MAX(created_at) or a later official reply. The existing createdAt field still uses MIN(message.created_at); it is neither a Commit timestamp nor a pagination cursor. A legacy empty conversation has NULL customer ID/time/progress and false needsConfirmation/canConfirm. Unassigned entries cannot be confirmed.

JDBC selects a bounded Ticket page and projects its current-assignment MAX and latest customer row in one statement snapshot. It makes no per-entry application queries and adds no schema migration or speculative index. Existing service/assignee/ID, Ticket/Message and assignment/progress indexes remain. Application code derives the booleans; the Controller performs response mapping only. The queue GET/HEAD and refresh write no business rows and trigger no AI call. This is a refreshable snapshot, not live worker state or a durable dispatch ledger.

Conversation capabilities add canViewConfirmations using the existing active staff membership check. Customer ownership alone, including a revoked staff member viewing their own ticket as a customer, returns false. The flag helps the UI avoid a staff-only request for customer views; it is not an authorization token. The separate confirmation endpoint still enforces Session role and DB membership. Its five-field current-state response is unchanged. Server and strict conversation decoder must be released together.

## Staff confirmation UI

The queue renders the latest customer activity and current-assignment progress as text, then links to the same-origin conversation by a validated Ticket ID. The conversation's staff panel requests current progress only when canViewConfirmations is true. It offers loaded INQUIRY/CUSTOMER_FOLLOWUP IDs with an initially empty selection; replies and unrequested latest IDs are never selected automatically.

Selecting a target captures both throughMessageId and expectedAssignmentVersion. Refresh, a new customer Message or A-B-A reassignment does not silently update that version. A new explicit selection is needed to change the intended scope. The server still rechecks the current assignee/version and same-Ticket target at write time.

The client generates a UUID only when it is ready to POST with CSRF. Any unconfirmed POST preserves the exact UUID/version/target for explicit replay; a changed target is blocked until that submission is reconciled or the page is left. It never submits automatically. A matching 200/201 response confirms that historical submission; its current-state snapshot is not used to overwrite a newer read. The UI shows the historical result separately and GETs current state again. If this GET fails, the saved submission is cleared and only the read is retried.

For a POST response with both HTTP 409 and code ASSIGNMENT_VERSION_CONFLICT, the confirmation endpoint has already checked current service access and looked up this actor's submission before rejecting the old version, without inserting a new record. The UI keeps the old selection but disables resubmission and offers an explicit reselection button. Only that user action clears the rejected pending command and target, then GETs current state again. A fresh read must permit confirmation and the user must select a loaded customer Message before a new UUID/version can be submitted. Ordinary refresh alone does not replace the old selection, and reset never POSTs automatically.

Other 409 codes (including CONFIRMATION_REQUEST_CONFLICT), missing/malformed error JSON, connection failures and 4xx/5xx with an unexpected status/code pairing cannot enable this action. They retain the original pending command. The UI trusts only the recognized status/code combination from this endpoint, never a suggested replacement version or raw error text. This refines reconciliation of a known version rejection; it is not a general rule that any HTTP 409 proves an earlier command was never stored.

Ticket generation/read sequence guards reject late GET/JSON/POST responses. pagehide clears the panel, in-memory selection and pending submission; no persistent recovery or local storage is introduced. Customer views never render the staff panel. Neither confirmation nor refresh changes Ticket state or creates messages/AI work. These controls record an explicit declaration, not proof that a human read or understood the text.

## Storage and Commit

V1-V11 remain immutable. V12 adds ticket_customer_message_confirmations, with required actor/UUID/positive version/target/time, Unique(ticket_id,actor_user_id,client_request_id), a same-Ticket assignment-event FK and a same-Ticket Message FK. The identity FK preserves the actual actor. Customer Message kind and authorization are application rules, not proven by FKs alone. Existing data is not backfilled or changed.

New writes lock Ticket FOR UPDATE, then the caller's service membership FOR SHARE, preserving existing lock order and two-second lock timeout. Handoff and customer Message insertion use the same parent Ticket lock. Membership revocation takes the membership lock and does not acquire Ticket afterwards. No external call, AI Job, Attempt, reservation, automatic Ticket state change or message mutation occurs in the transaction.

The current projection reads assignment, assignee, latest customer ID and assignment-scoped MAX in one SQL statement snapshot. It does not combine an earlier cached assignment with later progress. GET's short transaction keeps the membership shared lock for authorization; it is not marked database read-only because PostgreSQL row-locking statements are used. Business data stays unchanged.

Failed INSERT/confirmed Commit rollback removes only that transaction's new rows. Unknown Commit/HTTP acknowledgement still requires the original UUID/version/target for explicit reconciliation. ON CONFLICT guards the unique key but does not replace parent locking or current authorization. A response describes the transaction's observation, not all later commits.

## Decisions and review notes

| Context/options | Decision and rationale | Affected files/follow-up |
|---|---|---|
| Mutable checkpoint alone, checkpoint plus audit, or audit plus MAX | Append-only audit records with assignment-scoped MAX; retain requests without regressing progress | V12, domain/repository/service. Retention/deletion remains a separate policy |
| Last stored row/time or last confirmed customer ID | MAX(through_message_id), filtered by Ticket and current assignment | JDBC projection/tests. Lower requests are valid history; no invented expiration |
| Cached assignment then separate progress read or one projection | One statement snapshot; membership check stays in the transaction | Repository/service. A forced handoff between authorization and projection checks consistency |
| Reuse conversation submission namespace or separate command | Separate confirmation Unique as specified in the design; compare version/target on replay | Request/service/controller tests. Never reinterpret old confirmations as current-assignee actions |
| One progress index or indexes matching FK lookup paths | Add assignment-progress, message-reference and actor-reference indexes, plus Unique/PK | V12. This is structural support, not measured latency/scale evidence |
| Per-ticket follow-up queries versus a bounded snapshot projection | Extend the existing queue SQL with latest customer row and assignment-scoped MAX; keep ID pagination and all three ticket states | Support repository/service/controller. No new migration, N+1 application requests or measured speed claim |
| UI guesses staff rights from ownership/reply rights versus an explicit read capability | Add canViewConfirmations from the existing service membership result; keep sensitive progress in the protected endpoint | Conversation service/DTO/decoder. Non-assignees can read; revoked owners remain customers |
| Auto-select latest/version or preserve a deliberate selection | Capture the target and assignment together; preserve the exact pending request across refresh and handoff | Confirmation client/panel and Node tests. Unknown POST replay does not become a new confirmation |
| Apply POST's historical/current response or refresh authoritative current state | Display the saved event separately, then GET current state; a failed GET never repeats the saved POST | Confirmation client/panel. Late responses cannot replace the selected Ticket's newer state |
| Require full-page re-entry, silently update versions, discard all errors or provide typed conflict recovery | Only 409/ASSIGNMENT_VERSION_CONFLICT enables explicit reset, authorized GET and a fresh selection/new UUID. Keep ambiguous outcomes intact | Confirmation client/panel/HTML and tests. This endpoint's lookup-before-version-check order is the basis; no schema/server policy or audit deletion changes |

## Verification

Focused local verification passed 42 MockMvc/PostgreSQL cases and one V11-to-V12 migration case. They cover authority, all important replay scopes, MAX versus audit ID, no-expiry preservation, A-B-A, new customer activity, actual lock waits in both orders for handoff/revocation/customer insertion, single-statement current projection, bounded input and safe lock timeout. The tests also compare existing nonempty inquiry/reply/assignment/AI/Attempt/Suggestion/category/confirmation data after rollback. A deferred FK trigger forces failure at actual Commit so the HTTP path cannot return 201 prematurely.

The migration test compares old V11 rows and verifies required fields, shared-Ticket references and the new submission key. The current runtime probe now expects V12; no historical resource measurement was rerun or relabeled. Provider/Worker are disabled and Provider mock interactions are zero.

Final local `mvnw -B -ntp verify` passed 1,165 Surefire tests and 2 packaged Failsafe tests, zero failures/errors/skips, BUILD SUCCESS. The 43 focused cases overlap the full count. The Surefire fork-JVM exit-timeout warning occurred again as in the preceding list/composer run; test-process shutdown remains unresolved. This paragraph records local verification before repository publication; it includes no new confirmation UI/Browser test, JavaScript change/run, remote CI/Push, Image, Cloud or real Provider call. Subsequent Push/CI evidence is tracked separately in the [WIL publication record](https://github.com/HyungminYoon1/jungle-sw-ai-lab-advanced-wil/blob/main/week12/lab-reports/2026-11-03-documentation-publication.md).

The subsequent queue slice passed eight new PostgreSQL/MockMvc cases plus 75 existing assignment/conversation/follow-up cases (83 total). They check read-only snapshots, role/revocation, customer/staff capability separation, RESOLVED inclusion, ID pages, latest customer ID versus timestamps/staff replies, lower late confirmations and A-B-A scope. This focused run is not a new full regression, Browser, remote CI or deployment result.

The complete local Node suite passed 366 cases and ESLint passed. These include target/version pinning, exact replay after handoff, failed post-save GET, malformed response, 4xx/5xx, duplicate clicks, selection changes and late JSON/POST, plus the customer-hidden/loaded-customer-target panel. Node transport doubles do not prove PostgreSQL Commit.

The explicit ConversationBrowserExperiment passed with isolated Session accounts, actual CSRF headers and PostgreSQL. It verified the staff queue link/activity, no automatic selection, two customer-only targets, missing CSRF 403, confirmation of an older visible target while the newer customer Message remains unconfirmed, NULL progress after handoff, then the new assignee's explicit confirmation. JDBC checked six messages, four assignment events, two confirmation rows with versions 3/4 and the intended targets/actors, OPEN Ticket, unchanged original/customer text and reply versions, one PENDING original Job, zero reservations/Attempts/Suggestions and no Provider interactions. Customer-hidden staff controls, text-only payload rendering and both confirmation screenshots were inspected. This is synthetic local UI/DB evidence, not real-account, Cloud or AI evidence.

The first Browser attempt reached the final screenshot but exceeded its six-minute child-process completion limit before the parent ran its DB assertions. It was a failed run. The same checks passed after increasing only the experiment limit to eight minutes (the suite took 386.2 seconds); this did not change application Job deadlines or weaken assertions. The final full Java regression is a separate run.

Final 2026-11-04 `mvnw -B -ntp verify` passed 1,173 Surefire cases and 2 packaged Failsafe cases with zero failures/errors/skips and BUILD SUCCESS. The 83 focused cases overlap that full count; the explicit Browser experiment is separate. Surefire again logged `[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).` once. The pre-existing process-shutdown issue remains unresolved despite the successful test/build result; it is distinct from the corrected Browser experiment deadline. Logs are ignored under target/week12-staff-full-verify.log and target/week12-staff-browser-recheck.log. This local verification does not establish new remote CI, ECR publication or deployment evidence.

## Explicit Browser response-loss experiment

`CustomerConfirmationRecoveryBrowserExperiment` is a separate, opt-in Session/CSRF/PostgreSQL experiment. It provisions one synthetic assignee and customer, an inquiry/follow-up, one assignment and the original Job in a fresh Testcontainer. The child CLI receives only the required system environment and synthetic credentials; no AI key or personal Browser session is used. Logs expose only stage/counters, not credentials, cookies or full payloads.

Using [Playwright request/response interception](https://playwright.dev/docs/network#modify-responses), the first POST is forwarded to the real Spring server with route.fetch(). Only after the upstream returns 201 does the test abort delivery to the page's fetch. The UI must retain the exact pending submission. Even a manual current-state GET showing the new checkpoint does not clear that command's unknown outcome. Reconfirming the original UUID/version/target returns the real server's 200 without another row.

For a second explicit target, the server's 201 is delivered normally, then only its follow-up current-state GET is aborted. The UI shows saved-refresh-failed, clears the saved submission and retries GET on the refresh button. It sends no additional POST. This condition is injected deliberately; it is not an observed production outage or a database Commit-acknowledgement fault.

The experiment passed with three upstream POST results 201/200/201 and two DB confirmation rows, matching targets/actors/version and two distinct submission IDs. All pre-existing Ticket/Message/assignment/Job/Attempt/Suggestion/category rows matched their complete before-snapshot. Provider interactions stayed zero. Three screenshots of unknown outcome, saved/read failure and restored current state were inspected. This test-only slice does not change application code/schema, rerun the earlier 1,173+2/366 totals or resolve the pre-existing full-suite JVM shutdown issue.

```powershell
.\mvnw.cmd -B -ntp '-Dtest=CustomerConfirmationRecoveryBrowserExperiment' test
```

The experiment is excluded by the default *Test naming convention. Artifacts and logs remain ignored under output/playwright and target/week12-confirmation-recovery-browser.log. It requires local Docker, PowerShell 7, Node/npx and Chrome, following the existing explicit CLI experiment approach.

## Retained Session, handoff and membership revocation

`CustomerConfirmationAuthorityBrowserExperiment` uses a second fresh Testcontainer and one synthetic Browser login. The first confirmation commits and its response is blocked. A then transfers the ticket to active member B through the actual assignment HTTP API, without navigating away or losing the pending confirmation. While A remains an active service member, the UI replays the original submission and receives 200. The saved result stays in assignment 1; current assignment 2 has NULL progress. A new UUID from non-assignee A receives 403 NOT_ASSIGNEE, verifying the business denial rather than a missing-CSRF 403.

After the Browser reaches a file gate, the Java fixture verifies the Testcontainer URL again and deactivates only A's membership in a short transaction with a two-second lock timeout. It signals the Browser only after that transaction commits. No production grant/revoke endpoint or application test hook is added. The same authenticated Browser Session then POSTs a captured copy of the original submission through HTTP: 404 SUPPORT_NOT_FOUND. The current-state GET is also 404. The CSRF endpoint remains 200, so loss of login is not used to explain the service-scoped denial.

The old submission copy is test-driver memory captured from the original request, not persistent product recovery. The real UI performs the handoff-era replay and the revoked current-state refresh; the separate revoked replay probes the HTTP boundary. Current state is replaced by an unavailable/permission message and confirmation controls are disabled. The historical result already received before revocation can remain visible; these checks do not claim to revoke previously delivered data or synchronize every other form without a conversation refresh.

The focused experiment passed with upstream confirmation POST results 201/200/403/404, one unchanged confirmation row for A/assignment 1, and current assignment 2 to B with no confirmation. Original Ticket title/status/requester/service, messages, prior assignment event and AI/Attempt/Suggestion/category rows were preserved. Only the intended handoff and A membership deactivation changed those respective states; other memberships were unchanged. Provider interactions stayed zero. Both handoff/current-state and revoked-read screenshots were inspected. This test-only slice does not rerun prior full Java/Node totals or prove cancellation of requests authorized before revocation commits.

```powershell
.\mvnw.cmd -B -ntp '-Dtest=CustomerConfirmationAuthorityBrowserExperiment' test
```

The explicit experiment is outside default *Test execution. Its ignored log is target/week12-confirmation-authority-browser.log. Application code/schema, production identities/permissions, external AI and cloud resources are unchanged.

## Explicit selection after a rejected assignment version

The subsequent UI slice adds the explicit reselection action described above. CustomerConfirmationReselectionBrowserExperiment first captures a visible selection in assignment 1. At a file gate, the isolated fixture calls the existing transactional application service to hand off A-to-B-to-A, committing assignment 3. It does not add a production test/admin endpoint or claim to test B's Browser login. The stale HTTP POST receives the actual server's typed 409. Refresh shows current assignment 3 while retaining the original version-1 selection and keeping submit/target disabled. Explicit reset sends GET only, leaves the target empty and submit disabled, then the user's new choice produces a different UUID/version 3 and 201.

The initial PostgreSQL check found one confirmation for the intended actor/Message in assignment 3. Original Ticket title/status/requester/service, Message/Job/Attempt/Suggestion/category/membership data and the first assignment event were preserved. The fixture intentionally added two handoff events and advanced the assignment version to 3. Three screenshots (rejected old selection, empty fresh selection, saved new-assignment confirmation) were inspected. This reset belongs only to the confirmation widget; official replies now have their separate [draft-review workflow](ticket-assignment.md#explicit-reply-draft-review-2026-11-04).

Node passed 380 tests (14 new, including typed-versus-ambiguous errors, failed authorization/read on reset, late selection reads and panel controls); ESLint passed. The new reselection experiment and existing response-loss and authority experiments passed together: three explicit Browser/PostgreSQL cases, zero failures/errors/skips and BUILD SUCCESS, with Provider interactions zero. The response-loss Browser case also verifies that its unknown outcome never exposes reselection. These runs are separate from the earlier full Java 1,173+2 count; backend code/schema is unchanged and that full run was not repeated. The pre-existing full-suite JVM shutdown issue remains open.

```powershell
.\mvnw.cmd -B -ntp '-Dtest=CustomerConfirmationReselectionBrowserExperiment,CustomerConfirmationRecoveryBrowserExperiment,CustomerConfirmationAuthorityBrowserExperiment' test
```

Ignored logs: target/week12-reselection-all-node.log, target/week12-reselection-eslint.log and target/week12-reselection-browsers.log. No remote CI, image publication or cloud execution is established by this local slice.

## Customer activity after selection, 2026-11-04

The same reselection experiment is now strengthened with a second file gate. After the Browser explicitly selects the inquiry under assignment 3, the isolated fixture commits exactly one customer follow-up through the existing application service. Only then may the Browser submit its pinned selection. This establishes a sequential Commit order, not a PostgreSQL lock-wait measurement or a customer Browser login.

The actual POST returns 201 for the original target. Its current projection and subsequent GET both report the old effective checkpoint, the new latest customer ID and needsConfirmation=true. A manual conversation refresh loads the new message into the target list but leaves selection empty and submit disabled. The experiment still has exactly two confirmation POSTs, 409/201, and one confirmation row; the new customer text is never silently covered.

JDBC compares the complete original message row and all prior Job/Attempt/Suggestion/category/membership snapshots. It separately validates the single intended CUSTOMER_FOLLOWUP's ticket/body/author and absent correction/reply-version fields. Original Ticket fields and first assignment event are preserved; two fixture handoffs still account for assignment 3. Provider interactions remain zero. The post-save/new-customer screenshots were inspected, and all four expected captures exist.

The strengthened explicit Browser/PostgreSQL case passed once with zero failures/errors/skips and BUILD SUCCESS. Its ignored log is target/week12-confirmation-late-message-browser.log. This is a stronger version of the existing case, not an extra case to add to earlier totals. The preceding Node 399/Lint and four-Browser results belong to the earlier reply-review slice; they were not rerun here. Application code/schema and the unresolved full-suite JVM shutdown issue are unchanged.

The two Commit orders and handoff/revocation lock boundaries already have separate HTTP/PostgreSQL cases in CustomerMessageConfirmationHttpIntegrationTest; queue MAX/new-activity/handoff projections have AssignmentQueueConfirmationHttpIntegrationTest. Those sources and their earlier reports were reviewed, not rerun or relabeled as fresh lock-contention evidence in this Browser-only slice.
