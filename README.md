# browser-server-mcp

Standalone browser MCP server for Claude Code. Provides 22 browser automation tools via Chrome/ChromeDriver.

## Requirements

- [Babashka](https://babashka.org/) (bb)
- [bbin](https://github.com/babashka/bbin)
- ChromeDriver on PATH

## Install

```bash
bbin install io.github.skallet/browser-server-mcp
```

## Usage

```bash
browser-server-mcp start                  # headless, port 7117
browser-server-mcp start --headed         # visible browser
browser-server-mcp start --port 8080      # custom port
browser-server-mcp stop                   # stop running instance
```

After `start`, launch Claude Code in the same directory — it reads `.mcp.json` automatically.

## Tools (22)

**Navigation:** navigate, back, get_url, page_text, page_html
**Discovery:** query, query_all
**Inspection:** get_text, get_attribute, get_html, is_visible
**Interaction:** click, type_text, clear, hover
**Keyboard/Alerts:** press_key, dismiss_alert
**Waiting/Scrolling:** wait, scroll
**Capture/Viewport:** screenshot, resize
**Escape hatch:** execute_js

## Development

```bash
# Run unit tests (no browser needed)
bb -m browser-server-mcp.server-test
bb -m browser-server-mcp.mcp-test

# Run integration tests (needs chromedriver)
bb -m browser-server-mcp.tools-test
```
