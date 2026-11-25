A small table is as wide as its content:

| Exit | Meaning |
| :--- | :--- |
| `0` | Success |
| `3` | App not connected |

Alignment, and cells that have to wrap:

| Check | Tool | Result | Notes |
| :--- | :---: | ---: | :--- |
| Formatting | `ktlint` | 0 | Import order and `.editorconfig` rules are part of this check |
| Code smells | `detekt` | 12 | Unused private code, **complexity** and [naming](https://detekt.dev) |
| Dead code | `vulture` | 3 | |

Too wide for any phone, so it scrolls sideways:

| Device | Width | Height | Density | Scale | Released | Chipset | Battery |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pixel 9a | 412dp | 923dp | 420dpi | 2.625 | 2025 | Tensor G4 | 5100mAh |
| Galaxy S25 | 360dp | 780dp | 480dpi | 3.0 | 2025 | Snapdragon 8 Elite | 4000mAh |
