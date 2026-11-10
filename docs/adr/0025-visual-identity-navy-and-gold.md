# 0025. Visual identity: navy and gold

- **Status:** Accepted
- **Date:** 2026-11-10
- **Recorded:** after implementation. The restyle was built on 2026-11-09 and 2026-11-10, first in Assignment 2 and
  then ported here; this ADR was written when it was finished. Under [0001](0001-record-architecture-decisions.md)
  it should have come first; it records the decision as built, including the two defects the change produced.
- **Deciders:** project owner (at the client's request)

The same decision, with the same number, exists in Assignment 2
(`assignment 2/docs/adr/0025-visual-identity-navy-and-gold.md`). The four design files below are byte-identical in
both projects. This copy covers the v1.1 screens: login, dashboard, vehicle list, vehicle form and vehicle detail.

## Context

The v1.1 interface was correct but plain: one `ThemeData`, flat white cards and the default sans-serif font.
Every screen worked, but little on it said "Stark Group". The client asked for a richer, "luxurious" look, the same
as Assignment 2's. Three things were not allowed to suffer:
- **Readability.** Body text must stay at or above 4.5:1 contrast. Status badges must keep their words
  (guideline 14.2: "Green + text", "Amber + text", "Red or grey + text").
- **Responsive behaviour.** The app must still work from 360 px wide to desktop (guideline section 6, Usability),
  with the existing breakpoints: cards below 600 px, a compact table from 600 to 1024 px, a full table above
  (guideline 14.2).
- **Behaviour and tests.** This is a visual change only. Routes, providers, repositories, models, validators,
  strings and test keys stay as they are, and all 106 frontend tests must still pass unchanged.

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

The design language lives in four files (`FE/` = `stark-vms-frontend/lib/`), copied byte for byte from
Assignment 2:

| File | What it holds |
|---|---|
| `FE/core/theme/app_palette.dart` | Every colour value: `navy`, `navyLight`, `navyDeep`, `gold`, `goldSoft`, `goldTint`, `goldInk`, `ink`, `inkMuted`, `background` (warm cream), `surface`, `surfaceMuted`, `outline`, `outlineSoft` and the status hues. |
| `FE/core/theme/app_decorations.dart` | The shared visual vocabulary: `headerGradient`, `pageGradient`, `cardShadow`, `raisedShadow`, `card()`, `accentCard()`, `headerBanner()`, `iconPlate()`, `goldRule()`, `radius`. |
| `FE/core/theme/app_theme.dart` | The one `ThemeData`: Playfair Display on the display and headline sizes, and the button, input, dialog, tab, table and navigation-rail styles. |
| `FE/shared/widgets/brand.dart` | The shared widgets: `PageHeader` (navy gradient banner, serif title, gold rule, optional icon and actions, `dense` flag for phones), `SectionHeading`, `StatPlate`, `IconPlate`, `Ornament`, `AccentEdgeCard`. It also holds `InitialsAvatar` and `DialogTitle`, which only Assignment 2 uses; they stay so the two copies remain identical. |

**Colour.**
- No `Color(0x…)` or `Colors.x` literal outside `FE/core/theme/`. Widgets read colour through
  `Theme.of(context).colorScheme`, `StatusColors.of(context)` or `AppDecorations`.
- The vehicle status hues (green, amber, grey) still reach widgets only through `StatusColors`.

**Typography.**
- Playfair Display (Medium 500, SemiBold 600, Bold 700) is in `stark-vms-frontend/assets/fonts/` with its licence,
  `OFL.txt` (SIL Open Font License 1.1). It is declared in `pubspec.yaml` under `flutter: fonts:`.
- It is used for **headings and figures only**: the display and headline sizes, page and section titles, the
  vehicle number on the detail banner and the total on the dashboard plate. Body text, labels, table cells and form
  fields keep the default sans-serif, which reads better at small sizes.

**Decoration.**
- Pages open with a `PageHeader`: a navy gradient banner with a serif title and a short gold rule.
- The page background is a warm cream (`background`), and cards are white with a soft shadow (`cardShadow`).
- A card that carries a tone (a status, a figure) gets a coloured strip down its leading edge (`accentCard`,
  `AccentEdgeCard`); on phones each vehicle card takes its status tone.
- Leading icons sit on round tinted plates (`IconPlate`). Small `CustomPainter` ornaments (`Ornament`, the arcs on
  the login page and the banner) add texture.
- No images, no SVG and no network requests are involved.

**Accessibility rule.**
- Gold (`#C8A449`) measures about **2.4:1** on white, which is too low for text.
- Gold is therefore used only for rules, borders, card edges, selected indicators, and glyphs or text that sit
  **on a navy fill** (the top bar, the page banner, the account initials).
- Gold-toned text on a light surface uses `goldInk` (`#7A5E12`), which passes 4.5:1 on `surface` and on
  `background`. Body text uses `ink` and `inkMuted` (5.6:1 on `surface`).
- Status is never signalled by colour alone. Every badge keeps its words ("Active", "Maintenance", "Inactive"),
  and a status-tinted card edge or icon plate always sits next to the badge that names the state.
- Focus and hover overlays stay on, and inputs keep a 2 px navy focus border.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| A Material "seed colour" scheme only (`ColorScheme.fromSeed(seedColor: navy)`) | The cheapest option, and close to the v1.1 look, which the client found plain. A seed generates tonal colours, but not a gold accent, a serif or banners. It also gives no control over which tones meet 4.5:1 for text. |
| Fetch the font at runtime with the `google_fonts` package | A new dependency, and a network fetch on first paint. Headings render in a fallback font and then jump when the font arrives. It fails offline, on a blocked network and in CI. Bundling the font costs about 370 KB once and always works. |
| Bundle images or SVG clip art (textures, crests, illustrations) | Needs `flutter_svg` or raster assets: a new dependency plus page weight on every first load. Gradients, Material icons on tinted plates and a few small `CustomPainter`s do the same job in code, scale to any width and follow the palette. |
| A separate, simpler look for v1 | Two looks for one product, and two sets of theme files to maintain. Copying Assignment 2's files unchanged keeps one design language and makes a later sync a file copy. |
| Gold text for headings and figures on white | It looks the most "luxurious", but at 2.4:1 it fails WCAG AA for any text size. Gold stays on rules and edges; the serif and the navy carry the headings. |

## Consequences

**Positive**
- One place to change a colour, a radius or a shadow. Guideline 14.2's "one central ThemeData — no hard-coded
  colours" is now checked by a simple search: no colour literal exists outside `FE/core/theme/`.
- v1.1 and Assignment 2 look like one product, and the theme folders of the two projects are identical.
- The look is the same on every computer and works offline, because nothing is fetched at runtime.
- No new package, and no test changed: all 106 tests pass as before.

**Negative / accepted trade-offs**
- **About 370 KB of font** (three weights of about 123 KB each) is added to the first load of the web app.
- **One more layer every screen depends on.** A change to `brand.dart` or `AppDecorations` touches every screen,
  so a visual regression is wide. v1.1 has no golden tests and checks narrow screens only in the vehicle list test
  (cards at 400 px; compact and full tables at 800 and 1400 px), so a colour or spacing change needs a look in the
  browser.
- Two defects came from this change. Both appeared while the shared files were built in Assignment 2 and were fixed
  there before the files were copied here:
  - **`accentCard` had to become a widget.** The first version was a `BoxDecoration` with a rounded radius and a
    border whose left side was a different colour from the others. Flutter cannot paint that: a `BoxDecoration`
    cannot hold a border radius together with a border whose sides differ in colour, and it asserts on every
    paint. `AppDecorations.accentCard({child, accent})` is now a widget: a rounded, clipped card with a
    `ColoredBox` strip positioned along its leading edge.
  - **`PageHeader` has a `dense` flag.** A 92 px page banner above a lazy `ListView` on a 360 px phone left
    Assignment 2's users list too short to build a single row. `PageHeader(dense: true)` trims the padding, the icon
    plate and the title one step. Here the dashboard, the vehicle list and the vehicle form pass
    `dense: isCompact`.
- The serif's figures are old-style (their heights vary). That suits the dashboard plate; tables keep the
  sans-serif, so their columns of numbers still line up.
- The screenshots in `stark-vms-frontend/docs/screenshots/` were taken on 2026-11-08 and still show the v1.1 look
  (see Follow-ups).

**Follow-ups**
- Retake the four screenshots with the E2E journey (`flutter drive`, see the frontend README).
- Add responsive widget tests at 360, 800 and 1400 px for the dashboard, form and detail screens, as Assignment 2
  has.
- Keep the four design files byte-identical with Assignment 2 when either side changes.

## Related

- Guideline: sections 6 (Usability: responsive from 360 px, "follows the Stark Group design guideline"), 8 (pinned
  versions; no new package), 14.2 (one central ThemeData, no hard-coded colours or font sizes, badge = colour +
  text, breakpoints), 16.3 (format, analyze, no commented-out code), 17 (every decision recorded; 17.2 review
  checklist).
- ADRs: [0001](0001-record-architecture-decisions.md) (this record, written late),
  [0004](0004-frontend-state-management-with-riverpod.md) (unchanged: the restyle touches no provider),
  [0015](0015-non-functional-requirements-strategy.md) (Usability), [0016](0016-deployment-render-and-netlify.md)
  (the same build and deploy; the font ships inside `build/web`), [0020](0020-test-strategy-and-ci.md) (the widget
  tests that guard the restyle).
- Files: `FE/core/theme/app_palette.dart`, `FE/core/theme/app_decorations.dart`, `FE/core/theme/app_theme.dart`,
  `FE/core/theme/status_colors.dart`, `FE/shared/widgets/brand.dart`, `FE/shared/widgets/data_table_frame.dart`,
  `stark-vms-frontend/assets/fonts/` (`PlayfairDisplay-Medium.ttf`, `-SemiBold.ttf`, `-Bold.ttf`, `OFL.txt`),
  `stark-vms-frontend/pubspec.yaml` (`fonts:`).

Path prefixes: `FE/` = `stark-vms-frontend/lib/`.
