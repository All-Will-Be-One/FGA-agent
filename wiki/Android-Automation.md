# Local Android automation fork

The native Android fork reads the game screen, selects supports and operates
the party and battle screens. Script mode runs a saved FGA command. In smart
mode, FGOAgent supplies the skill, target, NP and card decisions. Quest
strategy and simulation belong to the external host.

For the upstream release and native patch recorded in this checkout, run
`python3 tools/fga_release.py show` from the repository root. See the
[branch and upstream release guide](Fork-Upstream-Maintenance.md).

## Install and configure separately

The fork applicationId is `io.github.fate_grand_automata.automation` (`.test` for debug). It does not replace official FGA or automatically import its private preferences. Use normal FGA UI to import an exported compatible configuration, set party/support/script/card priorities, select the server and prepare accessibility/screen capture. Automation never grants system consent.

FGO-py Smart/Classic are not native FGA features. FGOAgent's separate replaceable policy module reuses the auto rules/data/readonly recognition; Classic remains unsupported. The host execution profile resolves recipient hints to explicit native target clicks; Android makes no default choice. Compilation and offline tests do not certify device consent or game acceptance; APK installation is separately authorized.

## Local high-level interface

Authority: `APPLICATION_ID.automation`. Only this app's UID and the already-authorized ADB shell UID2000 may invoke ContentProvider.call(method="rpc", arg=JSON). No LAN server, credential or raw touch RPC exists. Unknown operations and fields reject. Do not expose ADB to untrusted networks.

ProtocolVersion1 operations:

| op | fields | result |
| --- | --- | --- |
| capabilities / version | none | package/build/upstream source, process instance, preparation and feature flags |
| configs | none | saved id/name/server/script hash; unresolved server stays null |
| start | requestId,configId,limits{maxRuns,timeoutSeconds,stopOnApEmpty},decisionMode=script\|smart | original durable run identity and Starting/state |
| status | exactly one of runId/requestId | state, quiescent, counters, native outcome/reason |
| pause / resume / stop | requestId,runId | accepted intent/state; not proof of exit |
| events | runId,afterSequence | ordered events, earliest/next sequence, truncation flag |
| decision.next | runId | current stable observation/last receipt, non-consuming |
| decision.submit | runId,planId,battleId,turnId,phase,observationId,action | durable explicit-plan receipt |
| decision.status | runId,planId | retained receipt; unknown effects never replay |

A success envelope is `{ok:true,protocolVersion:1,result:{…}}`; failure is `{ok:false,protocolVersion:1,error:{code,message}}`. Requests are limited64KiB. IDs contain only ASCII letters/digits/underscore/dot/colon/hyphen,1..160 characters.

Only bounded tasks: maxRuns1..100 and timeoutSeconds10..7200. No SQ/refills/command spells/boost/APwait or auto-withdraw. The per-run DI preferences snapshot freezes critical settings even if the UI is edited. Resource/script policy mismatches reject rather than silently executing an alternative policy.

## Lifecycle guarantees and limits

Starting is journaled before side effects. The UI and API share a process-wide reservation. Duplicate requestId with identical payload returns the same run; changed payload conflicts. Pause/resume intent is idempotent and coalesced while retaining identities; stop supersedes pending pause. Coalesced controls retain their IDs without a 256/272 receipt ceiling; redundant pause/resume cannot exhaust stop availability. Task identities are never evicted: at10000 records new starts reject until an explicit future archival mechanism preserves tombstones.

Running/Paused/Stopping are nonquiescent. Control release requires actual native thread termination and accessibility gesture drain. Failed OCR/cache/optional-recording cleanup is diagnostic, not ongoing input; a destroyed-service screenshot close failure is also diagnostic after thread/gesture exit. A missing callback or unclosed continued pointer is not a safe exit; no compensating tap is invented. A late definitive callback can finish cleanup. A new explicit stop can retry known same-process cleanup, but process restart/foreign owner needs reconciliation. Stop acknowledgement is not release.

The total deadline includes queued startup and preparation and becomes a monotonic budget after receipt. Android process death preserves IDs as NeedsAttention/ProcessRestart and never resumes gameplay automatically. Journal corruption or unavailable durable storage rejects execution. Native persistence fsyncs file and parent directory before dispatch.

Native `ExitState.timesRan` is the completed-run count. LimitRuns satisfies a finite count request; APRanOut satisfies an explicit AP request. An AP request reaching its finite run ceiling without APRanOut is not success. WithdrawDisabled means detected defeat, not completed retreat. Other errors/timeouts do not invent defeat or victory. Native log/notify events are diagnostic observations.

Dynamic mode uses external-plan5 with exactly two complete phase requests per turn: skills{actions,delayMillis} then cards{target?,order}. No standalone skill/finish/target or Observe request starts another observation cycle. It exports one app-private PNG at each phase boundary, without a before-Attack parent. Skills execute sequentially through original Caster and cancellable configured waits (default1500ms after each, including last). Original clickAttack supplies its first parsed hand; the adapter converts it without parsing again.

Cards target is a direct click on the existing enemy header plus the original ordinary wait. It omits Caster.selectEnemyTarget's separate info-window-dismiss input because that input overlaps a card on this page; no Back/Attack re-entry or policy re-planning occurs. Explicit order goes to original Caster; original AutoBattle owns animation/next turn/results. Strategy remains entirely external. Completed is only call return, gameEffect unverified.

No effect/scene/known-owner/hand equality proof or observation TTL gates native delivery. Base caller permission, message parameters, single execution ownership, durable IDs and cancellation/resource scope remain. Historical response metadata is readable; new v5 submissions are phase batches. Frame tokens invalidate on release/restart or bounded eviction. Conditional target-dialog detection remains TODO. The external policy supplies its full finite ranking of3..8 distinct card/NP slots; native delivers each candidate once, without choosing substitutes or proving accepted card count.

## Build and update

Use JDK 21 and the repository's Gradle wrapper. Run the protocol tests with
`./gradlew -p automation test`, the native script tests with `./gradlew :scripts:test`,
and the Android unit tests with `./gradlew :app:testCiUnitTest`. The debug build
task is `./gradlew :app:assembleDebug`.

For upstream merges, review the small Battle route and app runner/DI/platform seams; keep new protocol/adapters separate. Compare confirmation/target/NP warning and cleanup contracts, rerun JVM/native/policy replay tests, APK compile and the same acceptance matrix. Do not copy or rewrite native execution to resolve a merge conflict. No publication/production switch is included. Preserve the separate application/configuration and known baseline for rollback.

Configuration discovery reports current metadata, not an execution attestation. v1 start selects configId and limits; it does not carry an expected server/script revision. Do not edit config/server between discovery and start. The actual dispatch configuration is validated/frozen, while atomic revision binding remains outside this candidate.


## Explicit platform lifecycle candidate

The existing UID-protected provider now offers platform.status/prepare/close, platform.shizuku.request, explicit platform.accessibility.enable/disable and platform.capture.permission with a fixed mode. Host ADB invokes the ordinary MainActivity and the same capture launcher; no new exported sensitive Activity or URL scheme is added. Shizuku13.1.5 forwards fixed Android settings/appops Binder commands for this app’s accessibility entry and own-package PROJECT_MEDIA only. It preserves other accessibility services, never watches/re-enables user-disabled access, and does not change battle modules, screenshot providers or native strategy. No custom Shizuku UserService is started: that bootstrap failed inside HyperOS LoadedApk before entering the app’s permission code.

New permission: moe.shizuku.manager.permission.API_V23, with normal explicit Shizuku client consent. This is shell-level access, broader than a technically scoped accessibility permission. The binder-delivery provider is exported but protected by INTERACT_ACROSS_USERS_FULL; that permission is not requested by this app. No root/Sui initialization or pairing is used. PROJECT_MEDIA allow can persist after Shizuku exits: any authorized trial must save and restore the original app/user mode. Installation and actual privilege use require separate disclosure/approval; JVM tests are not device validation.

Stop the owned task and await quiescent before closing platform services. platform.close cancels pending preparation and ignores a late capture result; a later explicit prepare can use the normal system flow again. Never routinely force-stop FGA, which can remove Android16 accessibility authorization.

## Availability remediation (2026-10-23)

Smart DI never reads unused legacy AutoSkill/Spam/card-priority getters. Only enabled limits and selected refill fields are validated; the automation snapshot still forbids all resource use. No-boost maps to upstream Enabled.Skip: startQuest calls its Skip coordinate unconditionally after the existing wait, including on pages without a boost chooser. This is inherited behavior, not conditional target/page detection or proof of quest progress.

New start and plan identities/Executing transitions are durable before input. Ordinary events, state snapshots, confirmed completion and optional cleanup retain their true in-memory state when disk logging fails. Stop dispatch never waits for a successful diagnostic write; failure does not fabricate success. Native process instance IDs distinguish a new controller object from a new Android process. Only the one-per-process bridge, with its real runner/gate/owner absent, reconciles older process records as interrupted and closes their external session, preserving IDs/counts and Unconfirmed receipts without replay.

Known configured stops (CE, materials, first-clear, run ceiling, manual support/inventory) retain their reason and count. They are NeedsAttention when the requested target is unmet; only actual native counts or the explicit AP condition can fulfill the target. Unexpected errors remain failures. No additional screen, skill-effect, per-card acceptance or animation timer is introduced.

<a id="calculated-jp-formation-reserve-xv"></a>

## JP party formation

Battle confirmation reserves XV (15) for Agent launches in both script and
smart mode. The separate formation editor supports the JP 20:9 full-screen
layout. Open Party Formation on **XV** with capture and accessibility prepared
and the runner idle. The editor can replace the contents of XV.

| Operation | What it prepares |
| --- | --- |
| `party.rebuild` (`partyRebuildVersion=1`) | Reads `action.referencePreset` (1–14) and reproduces its owned lineup, equipment and support position on XV. The reference preset is read-only. |
| `party.apply` (`calculatedPartyVersion=1`) | Takes a JP XV formation and fixed battle script supplied by the host. Owned Servants and CEs use Atlas IDs. The editor reserves a front-row slot for the borrowed Servant and imports its portrait if missing. |

For an owned CE the editor searches **Event Bonus**, then **ALL**, for a copy
with the requested limit-break state. If neither list yields an ordinary copy,
the editor searches again and accepts a max-limit-broken (MLB) copy of the same
CE. An MLB request accepts only MLB. After selection the editor restores the
previous filter. It checks equipped CEs again when it reopens the saved XV.

The borrowed Servant is selected later on the support screen. In `party.apply`,
`support.servantId` takes the [unique Atlas servant ID](Support-Image-Maker.md#import-a-preferred-servant-by-name)
and `support.ce` must be null. Ordinary battle configs can use
[preferred CE images and the MLB option](Support-Selection.md#craft-essences).

Poll `party.status` with the same `requestId`. A completed `party.apply` reports
`quiescent: true`, the saved `configId`, `scriptSha256` and `readyToStart: false`.
Quest start is a separate operation and the Mystic Code stays as equipped.
If the job fails or its status is lost, inspect XV before retrying. An interrupted
edit may have already changed the preset.

## Strict support Append Skills (JP XV)

Use these optional fields in the `action` of a JP `party.apply` request.
Each array lists slots 1–5 in ascending order, with no duplicates or overlap.

| Field | What FGA checks on the borrowed support |
| --- | --- |
| `supportAppendUnlocked` (`supportAppendUnlockedVersion=1`) | A lit icon in each listed slot confirms it is unlocked (Lv.1 or higher). |
| `supportAppendMax` (`supportAppendMaxVersion=1`) | The Lv.10 marker must be visible in each listed slot. |

For example, `"supportAppendUnlocked": [2]` and `"supportAppendMax": [5]`
together require slot 2 unlocked and slot 5 at Lv.10. Exact or minimum levels
from 2 through 9, such as “slot 2 at least Lv.6”, cannot be expressed. The
completed receipt echoes nonempty lists. Changing the requirements changes the
config ID even if the script hash stays the same.

**Both lists empty or omitted.** FGA skips the Append view and uses the normal
selection and fallback settings. A generated config uses the requested Servant,
**Friends Only**, all three regular skills at Lv.10 and **Manual** fallback.

**Either list nonempty.** Start on the support list or let FGA open it from the
quest list. FGA uses **Preferred** and inspects only fully visible rows that
pass the ordinary filters. It checks the same candidate in Append view, returns
to normal view and matches the row again before tapping. A clipped, changed or
nonmatching row is skipped. An unrecognized display stops the run. If the refresh
budget is exhausted, FGA stops with `SupportRequirementsUnmet` without using
First or Manual fallback. Each battle needs its own verified
support, including on repeat runs. Entering an ongoing battle without that
verification also stops the run.
