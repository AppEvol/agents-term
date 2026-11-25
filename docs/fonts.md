# Bundled fonts

The fonts in `app/src/main/res/font/` are licensed under the SIL Open Font License 1.1. Each file is
a subset with the Latin characters the app uses.

| File | Font | Source |
| --- | --- | --- |
| `plus_jakarta_sans.ttf`, `plus_jakarta_sans_semibold.ttf` | Plus Jakarta Sans Regular, SemiBold | https://github.com/tokotype/PlusJakartaSans |
| `space_grotesk.ttf`, `space_grotesk_semibold.ttf` | Space Grotesk Regular, SemiBold (the `wght=600` instance of the variable font) | https://github.com/floriankarsten/space-grotesk |
| `jetbrains_mono.ttf`, `jetbrains_mono_bold.ttf` | JetBrains Mono Regular, Bold | https://github.com/JetBrains/JetBrainsMono |

The heavier files were added for Markdown, which needs a real bold. They are subset to the same
characters as the regular files with `pyftsubset` (fontTools), so a bold word never falls back to a
different font for a character the regular file has.
