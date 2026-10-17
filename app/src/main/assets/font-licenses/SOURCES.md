# Motion editor fonts

Unmodified Google Fonts TTFs; actual family names are used in every locale.
Downloaded 2026-10-17. No Microsoft font binaries are bundled.

| Family | Pinned Google Fonts commit | Files in `ofl/<family>/` |
| --- | --- | --- |
| Arimo | `903d46673260c1f4c7f7ef67f5190fb03eab5042` | `Arimo[wght].ttf`, `Arimo-Italic[wght].ttf` |
| Tinos | `ba95515f1333efe9342c2ad988b9c2f6bef6dbad` | `Tinos-Regular.ttf`, `Tinos-Bold.ttf`, `Tinos-Italic.ttf`, `Tinos-BoldItalic.ttf` |
| Cousine | `ab75f7cf3e87c9ba8663f431f2ee4279c6406ee3` | `Cousine-Regular.ttf`, `Cousine-Bold.ttf`, `Cousine-Italic.ttf`, `Cousine-BoldItalic.ttf` |
| Carlito | `3dd78844021e948ceb633d1dcee3f7885561b5d9` | `Carlito-Regular.ttf`, `Carlito-Bold.ttf`, `Carlito-Italic.ttf`, `Carlito-BoldItalic.ttf` |
| Roboto | `1c627bfa375fc51cf86fabeca4f6e08a95f0aa5c` | `Roboto[wdth,wght].ttf`, `Roboto-Italic[wdth,wght].ttf` |
| Open Sans | `8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5` | `OpenSans[wdth,wght].ttf`, `OpenSans-Italic[wdth,wght].ttf` in `ofl/opensans/` |
| Oswald | `8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5` | `Oswald[wght].ttf` |
| Lobster | `75eafbbbc75dfdf8e584fd5076cbac5b1ec2432f` | `Lobster-Regular.ttf` |

Source URL pattern: `https://raw.githubusercontent.com/google/fonts/<commit>/ofl/<family>/<filename>`.
Resource filenames are lowercase with `motion_` prefix, hyphens replaced by
underscores and variation-axis brackets replaced by `_variable`; font data are unchanged.
XML families provide real normal, bold, italic and bold-italic faces where supplied.
Oswald has normal weights only; Lobster supplies one decorative regular face.
Android synthesizes missing requested styles for these two decorative families.

All eight families are SIL OFL 1.1. Individual copyright notices and license texts
are included alongside this file. All except Tinos OFL.txt come from the
same pinned folder. Tinos METADATA.pb at the pinned commit declares OFL and source
commit `3b4482a99b80ea5fc75f187b1be3120a3f5905b3`; its license is from
https://raw.githubusercontent.com/googlefonts/tinos/3b4482a99b80ea5fc75f187b1be3120a3f5905b3/OFL.txt.
The old Tinos description still mentions Apache (2013); it does not describe these
2026 binaries.

Android system fallback supplies glyphs absent from a family, including Chinese
and emoji. No separate emoji font is bundled; available emoji depend on Android.
