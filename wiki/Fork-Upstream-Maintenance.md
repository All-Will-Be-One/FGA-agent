# Native automation fork maintenance

| Repository and branch | Role |
| --- | --- |
| `All-Will-Be-One/FGA-agent` · `fgoagent/main` | Integration branch for the native Android fork. Use its remote tip as the maintenance baseline. |
| `All-Will-Be-One/FGA-agent` · `feature/agent-reserved-party` | Feature branch for the reserved-party work. A push here updates this branch only. |
| `All-Will-Be-One/FGA-agent` · `master` | Inherited GitHub default branch. Its CI contains the Play Store release job. |
| `All-Will-Be-One/FGA-agent` · `desktop` | Separate desktop development line. |
| `Fate-Grand-Automata/FGA` · `master` | Official upstream. The fork integrates published stable release tags from this line. |

From a checkout whose `origin` is the native fork, inspect GitHub's default branch
and the live tips even if the clone fetches only one branch:

```bash
git ls-remote --symref origin HEAD refs/heads/fgoagent/main refs/heads/feature/agent-reserved-party refs/heads/master
```

Merge the resolved release tag's fixed commit, rather than unreleased branch
changes. The sole daily monitor is configured separately; this repository adds
no cron job, webhook, or scheduled workflow.

## Version record

`fga-version.properties` records the upstream Release ID, tag, full commit SHA,
native patch and Android versionCode. Gradle reads this record for every variant;
the inherited `FGA_VERSION_NAME`/`FGA_VERSION_CODE` CI environment variables no
longer override the native fork's identity.

The visible name combines the upstream version with four lowercase hexadecimal
patch digits (for example, `3081-0x0002`). Read the current value with
`python3 tools/fga_release.py show`.
The upstream version and native patch are independent: an upstream-only upgrade
preserves the native patch. Increment the patch only for our own changes,
including the `0x0009` to `0x000a` transition. A retry changes neither counter.
Git release tags may use the prefix `v`, for example `v3081-0x0001`.

The v3070 integration initially reset the existing patch by mistake. Its corrected
record restores `0x0001` from the prior native baseline; this correction does not
increment it again to `0x0002`.

Android `versionCode = upstream_number * 65536 + patch` increases across both
patches and upstream upgrades. The counter never wraps; out-of-range releases
fail before changing the record. Check previous APK manifests before first use
of this scheme to ensure no downgrade. APK installation is a separate action.

Native RPC upstream provenance comes from the same record. Candidate provenance
is the full local commit SHA regardless of annotated tags, retaining the `-dirty`
suffix for uncommitted builds; deployment builds must be clean.

```bash
python3 tools/fga_release.py show
python3 tools/fga_release.py bump-patch
python3 -m unittest discover -s tools/tests -v
```

Do not bump for checks, builds or upstream-only upgrades. Bump once when preparing
a release with our own changes. The properties file is the shared version source;
commit it with the release's source changes.

## Local 0x0001 cancellation candidate

The 3068-0x0001 candidate increments Android versionCode to 201064449. It is a local candidate, not published, installed or live-accepted. The external-plan Receiver classifies only `ExternalPlanNotPermitted` during its wait/execute boundary as ordinary ScriptAbort when the same still-owned Smart task has already accepted stop, remains nonquiescent and does not require reconciliation. The original RPC permission guard is unchanged; foreign ownership, unknown control state, persistence failures and unrelated exceptions are not relabelled as cancellation. This adds no action, polling loop or retry.

Deterministic fake-controller tests cover the interval before the runner's cancellation flag is delivered, including inner publish/executing permission checks. Factory guard/open startup windows outside the Receiver are not included. A clean candidate commit and inspected APK version/source/package/signature remain separate from any future installation, host deployment or game acceptance.

## Check and integrate

`check` uses the existing authenticated `gh` CLI for read-only official Release
and tag resolution. It prints JSON and does not merge, push, or schedule work.
Store that JSON outside the source tree to freeze the identity for the attempt:

```bash
python3 tools/fga_release.py check > /tmp/fga-upstream-release.json
```

Use an independent clean maintenance checkout, preserve rollback commits and
dirty execution work, fetch the exact tag and sufficient common history, and
merge it onto a branch based on current `origin/fgoagent/main`. Keep native
mechanics upstream-compatible and review the Battle route, runner, DI, platform
and gesture seams. Never modify the host's pinned submodule in place.

After merging the fixed release commit, update the version and candidate state:

```bash
python3 tools/fga_release.py prepare --release-json /tmp/fga-upstream-release.json
```

The tool verifies that the release commit is in the current history. Repeating
`prepare` for the same identity preserves both its patch and recorded progress.
Commit the candidate, then run:

```bash
./gradlew -p automation test
./gradlew :scripts:test
./gradlew :app:testCiUnitTest :app:assembleDebug
```

Also run the host's offline protocol, policy/replay, cancellation/reconnect and
saved-frame checks against the candidate. Keep fake tests, compilation, saved
frames and any separately authorized live acceptance distinct. Inspect the
actual APK version, source revision, package, signature and CRC.

Record an unsuccessful attempt explicitly; it remains eligible for retry:

```bash
python3 tools/fga_release.py record --release-json /tmp/fga-upstream-release.json --status failed --note 'Describe the failed check'
```

After tests, build and review pass on the exact clean commit, record `validated`.
This is local readiness and is reported as `awaiting_sync`, not synchronization.
After the authorized push to `fgoagent/main`, record `synced`; that operation
checks that the remote branch equals the exact candidate commit. Update the host
gitlink separately after the native commit is available remotely, following the
host release/deployment procedure. Avoid the inherited `master` Play workflow.

The per-checkout journal is `.git/fga-upstream-state.json` (resolved correctly for
worktrees). Entries use Release ID + tag + SHA, with explicit candidate, failed,
validated and synced states. Validation and synchronization apply only to their
recorded clean source commit. Failed checks never mark a release synchronized.
This journal contains no credentials and is not shipped in source or APKs.

Upstream v3068 automatically extracts bundled support images after a version
change when the app's main Activity next starts. Existing files with bundled
names are rewritten by upstream. A data-preserving package update does not launch
that Activity, but users who replaced default support images should retain a
backup before their next ordinary app launch. This fork retains that upstream
behavior; no battle mechanics or support-image policy is added by this merge.
