# 0025. Visual identity: navy and gold

- **Status:** Accepted
- **Date:** 2026-11-10
- **Recorded:** after implementation. The restyle was built on 2026-11-09 and 2026-11-10 and this ADR was written
  when it was finished. Under [0001](0001-record-architecture-decisions.md) it should have come first; it records
  the decision as built, including the two defects the change produced.
- **Deciders:** project owner (at the client's request)

The same decision, with the same number, exists in Assignment 1
(`assignment 1/docs/adr/0025-visual-identity-navy-and-gold.md`). The four design files below are byte-identical in
both projects.

## Context

The v2.1 interface was correct but plain: one `ThemeData`, flat white cards and the default sans-serif font.
Every screen worked, but little on it said "Stark Group". The client asked for a richer, "luxurious" look. Three
things were not allowed to suffer:
- **Readability.** Body text must stay at or above 4.5:1 contrast. Status badges must keep their words
  (guideline 14.2: "Green + text", "Amber + text", "Red or grey + text").
- **Responsive behaviour.** The app must still work from 360 px wide to desktop (guideline section 6, Usability),
  with the existing breakpoints: cards below 600 px, a compact table from 600 to 1024 px, a full table above
  (guideline 14.2).
- **Behaviour and tests.** This is a visual change only. Routes, providers, repositories, models, validators,
  strings and test keys stay as they are, and all 315 existing frontend tests must still pass unchanged.

Other forces:
- Guideline 14.2: "one central ThemeData — no hard-coded colours or font sizes in widgets".
- Guideline 16.3: `dart format` and `flutter analyze` with zero issues, and no commented-out code.
- Flutter Web builds with the **CanvasKit** renderer. CanvasKit draws text with the fonts the app ships or
  downloads; it does not use the fonts installed on the viewer's computer. A serif that is "usually installed"
  cannot be relied on.
- The frontend has no image assets, and the guideline asks for exact pinned dependencies (section 8). A new
  package needs a reason.

## Decision

**One palette file is the only source of colour. A bundled serif (Playfair Display) is used for headings and
figures. `AppDecorations` and `brand.dart` hold the shared decoration and widgets. Gold is an accent and is never
used for text on a light surface.**

The design language lives in four files (`FE/` = `stark-vms-frontend/lib/`):

| File | What it holds |
|---|---|
| `FE/core/theme/app_palette.dart` | Every colour value: `navy`, `navyLight`, `navyDeep`, `gold`, `goldSoft`, `goldTint`, `goldInk`, `ink`, `inkMuted`, `background` (warm cream), `surface`, `surfaceMuted`, `outline`, `outlineSoft` and the status hues. |
| `FE/core/theme/app_decorations.dart` | The shared visual vocabulary: `headerGradient`, `pageGradient`, `cardShadow`, `raisedShadow`, `card()`, `accentCard()`, `headerBanner()`, `iconPlate()`, `goldRule()`, `radius`. |
| `FE/core/theme/app_theme.dart` | The one `ThemeData`: Playfair Display on the display and headline sizes, and the button, input, dialog, tab, table and navigation-rail styles. |
| `FE/shared/widgets/brand.dart` | The shared widgets: `PageHeader` (navy gradient banner, serif title, gold rule, optional icon and actions, `dense` flag for phones), `SectionHeading`, `StatPlate`, `IconPlate`, `Ornament`, `AccentEdgeCard`, `InitialsAvatar`, `DialogTitle`. |

**Colour.**
- No `Color(0x…)` or `Colors.x` literal outside `FE/core/theme/`. Widgets read colour through
  `Theme.of(context).colorScheme`, `StatusColors.of(context)` or `AppDecorations`.
- The status hues (green, amber, red, grey) still reach widgets only through `StatusColors`
  ([0011](0011-time-and-expiry-with-injected-clock.md) decides the expiry states; this ADR only decides their
  colours).

**Typography.**
- Playfair Display (Medium 500, SemiBold 600, Bold 700) is in `stark-vms-frontend/assets/fonts/` with its licence,
  `OFL.txt` (SIL Open Font License 1.1). It is declared in `pubspec.yaml` under `flutter: fonts:`.
- It is used for **headings and figures only**: the display and headline sizes, page and section titles, and the
  numbers on the dashboard plates. Body text, labels, table cells and form fields keep the default sans-serif,
  which reads better at small sizes.

**Decoration.**
- Pages open with a `PageHeader`: a navy gradient banner with a serif title and a short gold rule.
- The page background is a warm cream (`background`), and cards are white with a soft shadow (`cardShadow`).
- A card that carries a tone (a status, a figure) gets a coloured strip down its leading edge (`accentCard`,
  `AccentEdgeCard`).
- Leading icons sit on round tinted plates (`IconPlate`). Small `CustomPainter` ornaments (`Ornament`, the arcs on
  the login page and the banner) add texture.
- No images, no SVG and no network requests are involved.

**Accessibility rule.**
- Gold (`#C8A449`) measures about **2.4:1** on white, which is too low for text.
- Gold is therefore used only for rules, borders, card edges, selected indicators, and glyphs or text that sit
  **on a navy fill** (the top bar, the page banner, the account initials).
- Gold-toned text on a light surface uses `goldInk` (`#7A5E12`), which passes 4.5:1 on `surface` and on
  `background`. Body text uses `ink` and `inkMuted` (5.6:1 on `surface`).
- Status is never signalled by colour alone. Every badge keeps its words ("Active", "Expires today",
  "Disabled"), and a status-tinted card edge or icon plate always sits next to a badge or label that names the
  state.
- Focus and hover overlays stay on, and inputs keep a 2 px navy focus border.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| A Material "seed colour" scheme only (`ColorScheme.fromSeed(seedColor: navy)`) | The cheapest option, and close to the v2.1 look, which the client found plain. A seed generates tonal colours, but not a gold accent, a serif or banners. It also gives no control over which tones meet 4.5:1 for text. |
| Fetch the font at runtime with the `google_fonts` package | A new dependency, and a network fetch on first paint. Headings render in a fallback font and then jump when the font arrives. It fails offline, on a blocked network and in CI. Bundling the font costs about 370 KB once and always works. |
| Bundle images or SVG clip art (textures, crests, illustrations) | Needs `flutter_svg` or raster assets: a new dependency plus page weight on every first load. Gradients, Material icons on tinted plates and a few small `CustomPainter`s do the same job in code, scale to any width and follow the palette. |
| Restyle each screen locally, with no shared widgets | Faster for the first screen, but the screens would drift apart. Two copies of the dashboard plate and three dialog title styles had already appeared during the work before `StatPlate` and `DialogTitle` were shared. |
| Gold text for headings and figures on white | It looks the most "luxurious", but at 2.4:1 it fails WCAG AA for any text size. Gold stays on rules and edges; the serif and the navy carry the headings. |

## Consequences

**Positive**
- One place to change a colour, a radius or a shadow. Guideline 14.2's "one central ThemeData — no hard-coded
  colours" is now checked by a simple search: no colour literal exists outside `FE/core/theme/`.
- Every screen opens the same way (banner, section headings, plates), so v2.0 and v2.1 screens look like one
  product.
- The look is the same on every computer and works offline, because nothing is fetched at runtime.
- No new package. All 315 existing tests passed without change; 3 tests were added for the login demo hint
  (`test/unit/app_config_test.dart`), so the suite now has 318.

**Negative / accepted trade-offs**
- **About 370 KB of font** (three weights of about 123 KB each) is added to the first load of the web app.
- **One more layer every screen depends on.** A change to `brand.dart` or `AppDecorations` touches every screen,
  so a visual regression is wide. The responsive widget tests at 360, 800 and 1400 px are the safety net. There
  are no golden tests ([0020](0020-test-strategy-and-ci.md)), so a colour or spacing change is not caught by a
  test; it needs a look in the browser.
- Two defects came from this change. Both were found and fixed before release:
  - **`accentCard` had to become a widget.** The first version was a `BoxDecoration` with a rounded radius and a
    border whose left side was a different colour from the others. Flutter cannot paint that: a `BoxDecoration`
    cannot hold a border radius together with a border whose sides differ in colour, and it asserts on every
    paint. `AppDecorations.accentCard({child, accent})` is now a widget: a rounded, clipped card with a
    `ColoredBox` strip positioned along its leading edge.
  - **`PageHeader` has a `dense` flag.** A 92 px page banner above a lazy `ListView` on a 360 px phone left the
    users list too short to build a single row. `PageHeader(dense: true)` trims the padding, the icon plate and
    the title one step. Every list and form screen passes `dense: isCompact`, and the driver detail banner, built
    by hand, uses the same trim on phones.
- The serif's figures are old-style (their heights vary). That suits the dashboard plates; tables keep the
  sans-serif, so their columns of numbers still line up.
- The driver and vehicle detail banners are built by hand from `AppDecorations.headerBanner` instead of
  `PageHeader`, because they carry a status badge under the title and keys that tests read
  (`driverDetail.name`, `vehicleDetail.number`).

**Follow-ups**
- Retake the screenshots in `stark-vms-frontend/docs/screenshots/` with the E2E journey after any later visual
  change. The current set was taken on 2026-11-10, while the restyle was being finished.
- Optionally add a small set of golden tests for `PageHeader`, `StatPlate` and the badges, pinned to the bundled
  font, if visual regressions become a problem.
- Keep the four design files byte-identical with Assignment 1 when either side changes.

## Related

- Guideline: sections 6 (Usability: responsive from 360 px, "follows the Stark Group design guideline"), 8 (pinned
  versions; no new package), 14.2 (one central ThemeData, no hard-coded colours or font sizes, badge = colour +
  text, breakpoints), 16.3 (format, analyze, no commented-out code), 17 (every decision recorded; 17.2 review
  checklist).
- ADRs: [0001](0001-record-architecture-decisions.md) (this record, written late),
  [0004](0004-frontend-state-management-with-riverpod.md) (unchanged: the restyle touches no provider),
  [0011](0011-time-and-expiry-with-injected-clock.md) (the expiry states whose colours `StatusColors` paints),
  [0015](0015-non-functional-requirements-strategy.md) (Usability), [0016](0016-deployment-render-and-netlify.md)
  (the same build and deploy; the font ships inside `build/web`), [0019](0019-dashboard-as-a-read-model.md) (the
  dashboard plates show its figures), [0020](0020-test-strategy-and-ci.md) (responsive widget tests, no golden
  tests).
- Files: `FE/core/theme/app_palette.dart`, `FE/core/theme/app_decorations.dart`, `FE/core/theme/app_theme.dart`,
  `FE/core/theme/status_colors.dart`, `FE/shared/widgets/brand.dart`, `FE/shared/widgets/data_table_frame.dart`,
  `stark-vms-frontend/assets/fonts/` (`PlayfairDisplay-Medium.ttf`, `-SemiBold.ttf`, `-Bold.ttf`, `OFL.txt`),
  `stark-vms-frontend/pubspec.yaml` (`fonts:`).

Path prefixes: `FE/` = `stark-vms-frontend/lib/`.
