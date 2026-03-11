# browser-server-mcp

Standalone browser MCP server for Claude Code. Provides 23 browser automation tools via Chrome/ChromeDriver.

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

## Tools (23)

**Navigation:** navigate, back, get_url, page_text, page_html
**Discovery:** query, query_all
**Inspection:** get_text, get_attribute, get_html, is_visible
**Interaction:** click, type_text, clear, hover
**Keyboard/Alerts:** press_key, dismiss_alert
**Waiting/Scrolling:** wait, scroll
**Capture/Viewport:** screenshot, resize
**Escape hatch:** execute_js
**Captcha:** solve_captcha

### solve_captcha

Auto-detects and solves captchas on the current page via [2captcha.com](https://www.2captcha.com/), then injects the solution into the page. Supports reCAPTCHA v2, hCaptcha, and image captcha.

Requires a 2captcha.com account and API key.

| Parameter | Required | Description |
|-----------|----------|-------------|
| `api_key` | Yes | 2captcha.com API key |
| `type` | No | `"recaptcha_v2"`, `"hcaptcha"`, or `"image"` (auto-detected if omitted) |
| `selector` | No | CSS/XPath selector for the captcha image element (required when `type="image"`) |

## Development

```bash
# Run unit tests (no browser needed)
bb -m browser-server-mcp.server-test
bb -m browser-server-mcp.mcp-test

# Run integration tests (needs chromedriver)
bb -m browser-server-mcp.tools-test
```
