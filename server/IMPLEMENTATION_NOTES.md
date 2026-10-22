# Execution ledger

Plan: ../../docs/superpowers/plans/2026-10-22-handleon-03-server.md (workspace sibling docs).

- Scope ruling: user authorizes implementation under server only, no commits, project creation,
  deployment or integration execution. Existing workspace retained; skill worktree/commit/live-test
  steps superseded.
- Preflight: Tasks 1–3 share role/claim/helper contracts; helper setup uses final RPCs. Tasks 2–5
  share summary upload timestamp and family consent; current delay and upload time must govern queue
  delivery. Task 6 uses the same car_links relationship.
- Ruling: car SELECT needed for upsert; anonymous profiles forbidden; authenticated upload timestamp
  server-owned; notification month/claim fields added. Detailed tradeoffs in README.
- Task 1: scaffolding, guarded test client and smoke test written; live execution deferred by
  instruction.
- Task 2: schema/RLS plus negative ownership tests written; SQL execution deferred.
- Task 3: RPC and expiry/reuse/role/consent boundary tests written; SQL execution deferred.
- Task 4: pure tests observed RED (10 failures); implementation GREEN in combined run.
- Task 5: pure tests observed RED (9 failures, 3 negative controls passed); implementation GREEN in
  combined run. SQL notification integration tests written, execution deferred.
- Task 6: Storage policies and ownership/size/MIME tests written; execution deferred.
- Initial verification: 22 pure tests passed, 0 failed, including actual 20 second deadline.
- Final independent review: 2 Important findings (queue starvation and service-role letter write
  after a re-pairing race); no Critical finding. Both addressed in the same fix pass. Minor notify
  test-title/coverage gap addressed with the related integration failure case.
- Final ruling: service-role letter persistence retained per user contract; transactional
  save_monthly_letter locks the car link and checks expected summary timestamp. New live RPC
  authorization/stale-version/re-pair tests written, not run per user instruction.
- Final fixed: deferred attempt scheduling prevents head-of-queue starvation. Query regression
  observed RED then GREEN; 20-tokenless-row live regression written but not run.
- Tooling ruling: installed Deno 2.7.12 rejects check --cached-only. Used DENO_NO_UPDATE_CHECK=1
  deno check --deny-import --frozen with the mandated DENO_DIR and existing dependencies instead. No
  package added.
- Final verification: requested cached-only pure test command -> 23 passed / 0 failed. Check of all
  14 TS files (including integration suites) -> exit 0 / no errors. Formatting complete. No
  commits/deployment/live test execution.
- Deferred limitations: PostgreSQL/runtime verification, external FCM race after final recheck and
  at-least-once redelivery remain documented in README; private family-link listing is outside the
  supplied API contract.
