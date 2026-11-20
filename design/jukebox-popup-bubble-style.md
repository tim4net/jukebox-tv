# Jukebox TV — Pop-Up Video bubble style (job 3 of 5)

Mockup: `jukebox-popup-bubble-1920x1080.png` — 80s stage scene with three
trivia bubbles overlaid, in the same retro-MTV neon family as the attract
screen, countdown, icon and banner.

## Bubble rules

- Rounded speech bubble, solid bright fill, bold dark-navy text, small tail
  pointer toward the thing it comments on.
- One fact per bubble, max ~8 words. Never more than 3 bubbles on screen
  at once; stagger them in over 1–2 s, hold ~6 s, fade out.
- Bubble text is always trivia about the song / artist / video — never
  UI chrome, never the room code.

## Palette

| Use | Hex |
| --- | --- |
| Background navy | `#0A0E2E` |
| Hot pink (bubble 1, neon) | `#FF2E88` |
| Cyan (bubble 2, neon) | `#00E5FF` |
| Bubble yellow (bubble 3) | `#FFD319` |
| Purple (neon accents) | `#B537F2` |
| Bubble text / dark navy | `#0A0E2E` |
| White | `#FFFFFF` |

## Fonts (Google Fonts)

- Bubble headers / "DID YOU KNOW?" — **Bungee** (retro signage feel,
  all caps).
- Bubble body text — **Poppins SemiBold**. If Bungee is unavailable,
  fall back to **Monoton** for headers, Poppins stays for body.

## Notes for Del

- The mockup's trivia lines are placeholders ("shot in just one day",
  "guitar solo was improvised") — real facts come from the track data.
- Bubble positions in the mockup (top-left, mid-left, bottom-left) are
  illustrative; in the app, place bubbles clear of the singer / focal
  point and of the QR + room-code panel.
