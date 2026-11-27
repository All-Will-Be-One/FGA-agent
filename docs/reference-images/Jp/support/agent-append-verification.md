# Agent Append Skill verification (JP)

The two color-label reference templates come from [FGA(mod), MIT, revision
fcdbd378](https://github.com/puruuun/FGA-Modified/tree/fcdbd37843aa9ed1032e5aafb1813efe7a0d0476).
Existing FGA `skill_ten.png` is unchanged. The capture test uses the dedicated JP
device at native 2712 × 1220, normalized to 720-pixel height using
`INTER_AREA`, as in FGA. Raw screenshots contain friend identities and are not
part of the repository. Regular/Append display used the same stationary list.

At horizontal and vertical fractional shifts of 0, 0.25, 0.5, 0.75 and 0.9
comparison pixels, expected color label matches were 0.894–0.990, other label
matches were 0.742–0.855 (gate 0.88). The two verified Lv.10 digit regions
scored 0.746–0.895, while the corresponding locked row scored 0.235–0.455
(gate 0.68). Template threshold evidence is for these frames, not every
skin/resolution. Re-identify the candidate after changing views; a clipped
row or uncertain display must never be selected due to this check.
