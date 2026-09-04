# Public screenshot guide

This folder holds the public README gallery. The checked-in SVG files are clearly labeled placeholders, not mockups of the application.

## Final filenames and order

Replace the placeholders with real images in this order:

| Order | Filename | Show | README caption |
| --- | --- | --- | --- |
| 1 | `overlay.png` | Orbit invoked over a recognizable, non-sensitive app through the assistant/Side-button flow | Orbit over any app |
| 2 | `chat.png` | A complete conversation with a polished rich response and composer | Full conversations |
| 3 | `deck.png` | A deliberate mix of Standard and Wide Deck tiles | Your command center |
| 4 | `documents.png` | A PDF page plus the Ask Orbit entry point or attached page card | Work with documents |
| 5 | `theme-studio.png` | The Theme Studio preview and controls; publish only when the feature reaches the intended channel | Make Orbit yours |
| 6, optional | `routines.png` | A readable Routine with a few representative steps or a trigger | Reusable automation |

The public README currently displays five `*-placeholder.svg` files. When final PNGs arrive, add them with the names above and change only the five corresponding `src` values in the README. The table and captions can remain unchanged. If Theme Studio is not Stable at launch, keep its Beta label or replace that slot with `routines.png`.

## Capture specifications

- Preferred orientation: portrait.
- Preferred aspect ratio: approximately **1:2** (modern phone screen).
- Recommended size: **1080 × 2160 px** or a nearby native device resolution; keep every primary image at one consistent size.
- Format: optimized PNG. Aim for clear text without committing unnecessarily large files.
- Capture the real application. Do not composite features into a screen that never existed.
- Keep system bars when they help establish genuine Android integration; otherwise crop only empty outer device chrome. Do not crop away UI that changes the meaning of the interaction.
- Use one clean Orbit theme and consistent font, accent, display scale, and navigation style across the set. A restrained dark or AMOLED presentation fits the repository treatment.
- Keep content short enough to read at README width. Prefer one obvious capability per image.

## Privacy checklist

Before committing each screenshot:

- use demo conversations and neutral documents;
- remove real names, email addresses, phone numbers, avatars, account IDs, tokens, calendars, locations, and filenames;
- clear or hide personal notifications and status-bar indicators;
- check the source app visible behind the overlay for private tabs, messages, or account details;
- check image metadata and remove location/device metadata where practical;
- open the final committed file at full resolution and inspect every corner.

## Gallery consistency

Use the same capture device or viewport where possible. Keep the subject at a similar scale and avoid mixing framed device mockups with raw screenshots. The first three images—overlay, chat, and Deck—should remain the strongest product story if the gallery is reduced for launch.
