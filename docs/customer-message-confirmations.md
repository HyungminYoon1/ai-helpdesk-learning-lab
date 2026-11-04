# Explicit customer-message confirmation

The V12 backend implements the agreed current-assignee confirmation contract. The 2026-11-04 staff queue projection is locally verified; the confirmation button and Browser flow are the next verification slice. The existing customer composer and original AI processing are unchanged.

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

The existing assignment-queue response retains id, title, status, createdAt, assignmentVersion and its UNASSIGNED/MINE, ID-ascending pagination. Each entry adds latestCustomerMessageId, latestCustomerMessageAt, effectiveThroughMessageId, needsConfirmation and canConfirm. It includes RESOLVED tickets. latestCustomerMessageAt belongs to the highest-ID INQUIRY/CUSTOMER_FOLLOWUP, not MAX(created_at) or a later official reply. The existing createdAt field keeps its earlier receipt-time behavior. A legacy empty conversation has NULL customer ID/time/progress and false needsConfirmation/canConfirm. Unassigned entries cannot be confirmed.

JDBC selects a bounded Ticket page and projects its current-assignment MAX and latest customer row in one statement snapshot. It makes no per-entry application queries and adds no schema migration or speculative index. Existing service/assignee/ID, Ticket/Message and assignment/progress indexes remain. Application code derives the booleans; the Controller performs response mapping only. The queue GET/HEAD and refresh write no business rows and trigger no AI call. This is a refreshable snapshot, not live worker state or a durable dispatch ledger.

Conversation capabilities add canViewConfirmations using the existing active staff membership check. Customer ownership alone, including a revoked staff member viewing their own ticket as a customer, returns false. The flag helps the UI avoid a staff-only request for customer views; it is not an authorization token. The separate confirmation endpoint still enforces Session role and DB membership. Its five-field current-state response is unchanged. Server and strict conversation decoder must be released together.

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

## Verification

Focused local verification passed 42 MockMvc/PostgreSQL cases and one V11-to-V12 migration case. They cover authority, all important replay scopes, MAX versus audit ID, no-expiry preservation, A-B-A, new customer activity, actual lock waits in both orders for handoff/revocation/customer insertion, single-statement current projection, bounded input and safe lock timeout. The tests also compare existing nonempty inquiry/reply/assignment/AI/Attempt/Suggestion/category/confirmation data after rollback. A deferred FK trigger forces failure at actual Commit so the HTTP path cannot return 201 prematurely.

The migration test compares old V11 rows and verifies required fields, shared-Ticket references and the new submission key. The current runtime probe now expects V12; no historical resource measurement was rerun or relabeled. Provider/Worker are disabled and Provider mock interactions are zero.

Final local `mvnw -B -ntp verify` passed 1,165 Surefire tests and 2 packaged Failsafe tests, zero failures/errors/skips, BUILD SUCCESS. The 43 focused cases overlap the full count. The Surefire fork-JVM exit-timeout warning occurred again as in the preceding list/composer run; test-process shutdown remains unresolved. This paragraph records local verification before repository publication; it includes no new confirmation UI/Browser test, JavaScript change/run, remote CI/Push, Image, Cloud or real Provider call. Subsequent Push/CI evidence is tracked separately in the [WIL publication record](https://github.com/HyungminYoon1/jungle-sw-ai-lab-advanced-wil/blob/main/week12/lab-reports/2026-11-03-documentation-publication.md).

The subsequent queue slice passed eight new PostgreSQL/MockMvc cases plus 75 existing assignment/conversation/follow-up cases (83 total). They check read-only snapshots, role/revocation, customer/staff capability separation, RESOLVED inclusion, ID pages, latest customer ID versus timestamps/staff replies, lower late confirmations and A-B-A scope. This focused run is not a new full regression, Browser, remote CI or deployment result.
