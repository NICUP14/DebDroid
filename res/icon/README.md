# DebDroid icon

Isometric container cube with a shell prompt on its face.

## Colors
| Role | Hex |
|---|---|
| Top face | `#E0103A` |
| Right face | `#A80D2E` |
| Left face | `#7A0A22` |
| Prompt | `#0B0B0D` |
| Icon background | `#0F0F12` |

## Android
Copy everything in `android/res/` into `app/src/main/res/` (merging folders), then in `AndroidManifest.xml`:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    ... >
```

- Android 8+ uses the adaptive icon (`mipmap-anydpi-v26`): foreground cube on the background color, mark kept inside the 66 dp safe zone.
- Android 13+ themed icons use `ic_launcher_monochrome`.
- Older devices use the `ic_launcher.png` / `ic_launcher_round.png` bitmaps.

## Play Store
`playstore/ic_launcher-playstore.png`: 512×512, opaque, square (Play applies its own mask).

## Web
`favicon.ico` (16/32/48), `favicon.svg`, `apple-touch-icon.png` (180), `icon-192.png`, `icon-512.png`, and `icon-maskable-512.png` for PWA manifests.

## Sources
`svg/` holds the editable vector sources: the bare mark, the full icon, and the adaptive foreground and monochrome layers.
