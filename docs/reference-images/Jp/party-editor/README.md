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
