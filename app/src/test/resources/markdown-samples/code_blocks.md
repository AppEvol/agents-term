A fenced block with a language:

```kotlin
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    MarkdownBlocks(blocks = blocks, style = rememberMarkdownStyle(), modifier = modifier)
}
```

Without a language, and with Markdown inside that must stay literal:

```
# not a heading
- not a list **not bold** `not a chip`
| not | a table |
```

Tildes work too:

~~~json
{"ready": true, "controlReady": false}
~~~

    Four spaces of indent is still a paragraph, not code.

An unclosed fence, as seen while a reply is streaming:

```python
def main():
    print("still typing
