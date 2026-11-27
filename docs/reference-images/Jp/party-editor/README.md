# JP party editor calibration
Calibrated on the 20:9 Japanese roster page with full-screen landscape capture. The
small controller templates were cut at 720p from the formation/list states seen
during a rebuild on a physical device. They are visual UI landmarks, not servant
or equipment templates. The editor generates the latter at run time from a
read-only reference preset; no user's roster is bundled in the APK.

The formation-only RPC is separate from a battle or quest. From the JP Party
Formation screen on Party XV, with capture/accessibility prepared and no running
script, call `party.rebuild` with `requestId` and
`action: {"referencePreset":4}`. Poll `party.status` with the same ID.
Only XV is writable. Keep the game foreground until the terminal result.
Do not retry an interrupted edit against an unrecognized screen: return to the
normal Party XV formation page and inspect its state first. The setup copy, if
used as an acceptance fixture, is not part of this RPC. This route does not
change the Master's Mystic Code or choose a borrowed support servant.

Craft Essence matching uses the exact displayed name and limit break, not level
or HP/ATK. Search ordinary copies first when the reference is ordinary. If one
cannot be found, a positively identified max limit broken copy is permitted.
An ordinary copy never substitutes for a required max limit broken copy.
After reopening XV, read each equipped Craft Essence's details again.

- `ce-scroll-thumb.png`: centered interior of the ordinary Craft Essence
  inventory's scroll thumb at 720p. Tested on device frames with the thumb at
  top and mid-list (.99+), and on a short Event Bonus filter list without a
  displayed thumb (.44). This guards against beginning a level-sorted search
  below the highest-level CEs when the filter changes.
- `ce-inline-max-limit.png`: ordinary CE details' inline JP limit-break marker
  (not the separate event-effect style). Crop contains only the bracketed UI text.

The native battle party gate also uses the same JP active-dot crop on the
quest confirmation screen. On the device's actual XV quest confirmation
frame, the existing EN template scored .85 on XV and up to .79 on inactive
positions, making the unique match sensitive to the user's global threshold.
The JP template scored .96 at XV, under .46 on inactive dots, with a dedicated
.85 threshold. The gate still fails closed on a missing/ambiguous marker.
