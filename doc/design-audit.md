# Design Consistency Audit — openLog2
> Branch: `feat/ui_ux_review_upd_3` · Date: 2026-06-28

---

## 1. Hardcoded dp values — no dimension tokens exist

`Theme.kt` defines only colour tokens (`ThemeColors`) and font families. There are **no spacing, sizing, or shape tokens**. Every dimension is inlined directly in composables.

### 1a. Corner radii — 4 different values in use, no token

| Value | Used for | Files |
|-------|----------|-------|
| `3.dp` | Tags/pills, text input fields, small badges | `Components.kt:173,174`, `AnnotationPanel.kt:239,240`, `FilterPanel.kt:389,390,431,432,440` |
| `4.dp` | Buttons (`SmallButton`, `MedButton`), note rows, filter pills | `Components.kt:205,206,220,221`, `AnnotationPanel.kt:121,163` |
| `7.dp` | Context menu container | `App.kt:140,141` |
| `8.dp` | Dialogs, annotation block, annotation preview panel | `App.kt:243,244,269,270`, `AnnotationPanel.kt:188,189,192` |

No token for any of these — `RoundedCornerShape(3.dp)` and `RoundedCornerShape(4.dp)` are the most repeated and strongest candidates for a `Radii` object.

### 1b. Component heights — no token

| Value | Component | File:line |
|-------|-----------|-----------|
| `18.dp` | Log row min height | `LogViewer.kt:507` |
| `22.dp` | Row heightIn min | `LogViewer.kt:441` |
| `34.dp` | Column header bar | `LogViewer.kt:175` |
| `36.dp` / `46.dp` | AnnotationPanel header (without/with note) | `AnnotationPanel.kt:55` |
| `40.dp` | Annotation block header | `AnnotationPanel.kt:192` |

### 1c. Icon / touch-target sizes — no token

| Value | Used for | Files |
|-------|----------|-------|
| `14.dp` | Small close icon | `Components.kt:288` |
| `16.dp` | Checkbox-like icon | `Components.kt:279` |
| `18.dp` | Checkbox tile | `Components.kt:145` |
| `20.dp` | Tag row icon | `FilterPanel.kt:388,430,439` |
| `24.dp` | Collapse toggle button | `LogViewer.kt:595,683` |

### 1d. Log indent step — magic number repeated

`18.dp` is used as the per-level indent step in three separate expressions:

- `LogViewer.kt:492` — `((item.indent - 1).coerceAtLeast(0) * 18.dp.toPx())`
- `LogViewer.kt:496, 590` — `(11 + item.indent * 18).dp`
- `LogViewer.kt:543` — `item.indent * 18.dp.toPx()`
- `LogViewer.kt:678` — same pattern

The initial offset `11.dp` also appears here but only as a start-padding literal.

### 1e. Drag divider hit-area sizes

- `HDivider` click width: `10.dp` outer, `4.dp` visible bar — `Components.kt:74,88`
- `VDivider` click height: `10.dp` outer, `4.dp` visible bar — `Components.kt:101,115`

These are consistent with each other but unnamed.

---

## 2. `Color()` literals outside the theme system

`ThemeColors` covers UI chrome colours. `HL_COLORS` and `SEQ_COLORS` in `Theme.kt` define palette arrays. **The following `Color()` literals appear outside those files**, creating duplication and drift risk:

### 2a. Danger/error red `0xFFf85149` — 5 occurrences, no shared token

| File:line | Role |
|-----------|------|
| `Model.kt:10` | `LogLevel.E` text colour |
| `App.kt:479` | `DialogActionButton` danger accent (local `val accent`) |
| `FilterPanel.kt:286` | `exNeg` (exclude-tags negative colour) |
| `FilterPanel.kt:467` | `msgExNeg` (exclude-message negative colour) |
| `FilterPanel.kt:956,957,960` | "Clear filters" button border, background, text — inline |

`exNeg` and `msgExNeg` are both local `val`s created separately in two different block scopes of `FilterPanel.kt`. `App.kt:479` is yet another independent definition. All three are `0xFFf85149`. Only `Model.kt:10` has a semantic excuse (it's data-layer).

### 2b. Package/cyan `0xFF06b6d4` — 3 occurrences

| File:line | Role |
|-----------|------|
| `Model.kt:40` | `SequenceDef` default colour |
| `FilterPanel.kt:285` | `pkgColor` local val |
| `Theme.kt:45,53` | Member of `HL_COLORS` and `SEQ_COLORS` palettes |

`FilterPanel.kt:285` independently re-declares the same value already in `HL_COLORS[4]` / `SEQ_COLORS[5]`. `Model.kt:40` is a default that could reference `SEQ_COLORS.first()`.

### 2c. Sequence purple `0xFF8957e5` — 2 occurrences

| File:line | Role |
|-----------|------|
| `Filter.kt:112` | Fallback outer sequence colour |
| `Theme.kt:52` | `SEQ_COLORS[0]` |

`Filter.kt:112` should reference `SEQ_COLORS.firstOrNull()` rather than a raw literal.

### 2d. `LogLevel` colours in `Model.kt` (lines 6–11)

All six log-level colours are `Color()` literals in the data model. They are not theme-adaptive (same colour in light and dark themes). This is a known design tradeoff but worth flagging:

```kotlin
V → Color(0xFF6e7681)   // grey
D → Color(0xFF79c0ff)   // blue
I → Color(0xFF3fb950)   // green
W → Color(0xFFd29922)   // amber
E → Color(0xFFf85149)   // red  ← same as danger red above
A → Color(0xFFff7b72)   // red-orange
```

`LogLevel.E.color` and the danger-red token are the same hex but not linked — a change to one won't propagate to the other.

---

## 3. Inconsistent padding on similar UI elements

### 3a. Context-menu / popup list items — 5 different vertical paddings

All items are vertically stacked in the same context menu (`App.kt`), yet use different vertical padding:

| Padding | Element | File:line |
|---------|---------|-----------|
| `h=14, v=10` | Context menu action row | `App.kt:153` |
| `h=12, v=8` | Sub-section divider area | `App.kt:166` |
| `h=14, v=8` | Recent Files section header | `App.kt:341` |
| `h=14, v=7` | Individual recent-file row | `App.kt:359` |
| `h=14, v=6` | "…N more" footer row | `App.kt:381` |

The horizontal value is `12` or `14` (no clear rule). The vertical value steps 6→7→8→10 with no visible rhythm.

### 3b. FilterPanel section headers — 3 different vertical paddings

Most section-level rows in `FilterPanel.kt` use `padding(horizontal = 12.dp, vertical = 4.dp)`, but:

| Padding | File:line |
|---------|-----------|
| `h=12, v=4` | Most section headers `FilterPanel.kt:311,332,503,516,527,686,737,844` |
| `h=12, v=6` | Package section header `FilterPanel.kt:456`, save-filter footer `FilterPanel.kt:949` |
| `h=12, v=3` | Tag list rows `FilterPanel.kt:380`, highlighter rows `FilterPanel.kt:659,779,851` |
| `h=12, v=7` | Seq-add row `FilterPanel.kt:874` |

`v=4` vs `v=3` vs `v=6` vs `v=7` for structurally equivalent section rows.

### 3c. Log viewer rows — inconsistent top/bottom padding

Identical-looking row types inside `LogViewer.kt` use different top/bottom values:

| Padding | Row type | File:line |
|---------|----------|-----------|
| `top=2.dp, bottom=2.dp` | Normal log row (plain) | `LogViewer.kt:496` |
| `top=3.dp, bottom=3.dp` | SeqHeader / ManualHeader row | `LogViewer.kt:590,678` |

One dp difference creates uneven visual rhythm between collapsed and expanded groups.

### 3d. Settings-page selector buttons — 3 different paddings

The settings panel (`App.kt`) has multiple rows of option-selector chips. They should look identical but use different padding:

| Padding | Used for | File:line |
|---------|----------|-----------|
| `h=10, v=7` | Theme, font-size, font-family, tag-limit, tab-limit chips | `App.kt:1070,1084,1112,1126` |
| `h=12, v=8` | Font-family chips (different section) | `App.kt:1098` |

### 3e. AnnotationPanel block rows — header vs content mismatch

- Block title row (header): `padding(horizontal = 16.dp)` — `AnnotationPanel.kt:193`
- Block content row: `padding(horizontal = 12.dp, vertical = 4.dp)` — `AnnotationPanel.kt:162`
- Block selected/editing state: `padding(horizontal = 12.dp, vertical = 8.dp)` — `AnnotationPanel.kt:229`

The header uses `16.dp` horizontal while every other row uses `12.dp`, causing visual misalignment.

### 3f. Tab bar button — missing vertical padding

- `App.kt:1161`: `padding(horizontal = 12.dp)` — no vertical padding at all
- All other comparable tappable rows have at least `v=3.dp` or `v=4.dp`

---

## 4. Inconsistent font sizes on similar element types

### 4a. "×" close/delete/dismiss icons — 4 different sizes

| Size | File:line |
|------|-----------|
| `12.sp` | FilterPanel.kt:943 (saved-filter ×) |
| `13.sp` | FilterPanel.kt:818,866,1096 (seq/collapse/tag ×) |
| `14.sp` | App.kt:1173, Components.kt:254, FilterPanel.kt:460,671 (most ×) |
| `16.sp` | AnnotationPanel.kt:202 (preview panel ×) |

These are the same semantic element (close/remove action) with four different sizes.

### 4b. "+" / "−" add/exclude action icons

| Size | Sign | File:line |
|------|------|-----------|
| `11.sp` | `+` | `FilterPanel.kt:393,435,614` |
| `11.sp` | `+` | consistent ✓ |
| `12.sp` | `−` | `FilterPanel.kt:444,623` |

`+` and `−` buttons appear side-by-side in the same row but are 1sp apart.

### 4c. ALL_CAPS section label text — 3 different sizes

| Size | Used for | File:line |
|------|----------|-----------|
| `9.sp` | Column header labels (TIMESTAMP, PID, TID…) | `Components.kt:188–195` |
| `10.sp` | PREFIX, NEXT STEPS, COLLAPSED RANGES, DEFAULT SAVE FOLDER | `AnnotationPanel.kt:112,170`, `FilterPanel.kt:843`, `App.kt:1132` |
| `11.sp` | THEME, FONT SIZE, FONT FAMILY, MOST-USED TAGS, VISIBLE TABS | `App.kt:1062,1076,1090,1104,1118` |

Three different sp values for the same visual pattern (small uppercase label above a control).

### 4d. Collapse expand arrows (▾/▸/▼/▶) — 2 different sizes

| Size | Context | File:line |
|------|---------|-----------|
| `10.sp` | FilterPanel section toggle arrows | `FilterPanel.kt:304,650,884` and others |
| `14.sp` | Section header chevrons in `ColHeader` | `Components.kt:147` |

The `ColHeader` composable uses `14.sp` for its own ▾/▸, but every in-line toggle in `FilterPanel.kt` uses `10.sp`. Both render the same glyph.

### 4e. Empty-state / placeholder text — 2 different sizes

| Size | Text | File:line |
|------|------|-----------|
| `13.sp` | "Open a log file to begin", "No entries match…" | `LogViewer.kt:713,715` |
| `14.sp` | "No files open — click Open to add a log", "Loading file…" | `App.kt:97,110` |

These play the same "nothing to show" role but are rendered at different sizes in different panels.

### 4f. Dialog title text — consistent (no issue)

All dialog titles (`App.kt:246,272,303,419`) use `fontSize = 14.sp, fontWeight = FontWeight.SemiBold`. ✓

---

## 5. Summary — token candidates

If you were to add a `Spacing`, `Shapes`, and `Colors` token layer to `Theme.kt`, these are the highest-priority extractions:

| Token name | Value | Occurrences |
|------------|-------|-------------|
| `dangerRed` | `Color(0xFFf85149)` | 5 across 3 files |
| `pkgCyan` | `Color(0xFF06b6d4)` | 3 across 3 files |
| `cornerSmall` | `3.dp` | ~8 |
| `cornerMedium` | `4.dp` | ~6 |
| `cornerLarge` | `8.dp` | ~6 |
| `indentStep` | `18.dp` | 4 (always log-indent) |
| `panelHPad` | `12.dp` | >20 occurrences |
| `contextMenuHPad` | `14.dp` | ~6 |
| `labelSize` | `10.sp` or `11.sp` | ~15 across panels |
| `closeIconSize` | `14.sp` | ~8 |
