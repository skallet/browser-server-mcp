# Captcha Solving via 2captcha.com — Design Spec

## Goal

Add a single `solve_captcha` MCP tool that auto-detects captcha type on the current page, submits it to 2captcha.com for solving, and injects the solution back into the page.

## Architecture

New namespace `browser_server_mcp.captcha` handles all captcha logic: detection, 2captcha API communication, and solution injection. The existing `tools.clj` gets one new tool entry (`solve_captcha`) that delegates to this namespace.

Uses `babashka.http-client` (built into bb) for HTTP calls to 2captcha API.

## Supported Captcha Types

- **reCAPTCHA v2** — detected via `___grecaptcha_cfg` JS object or `iframe[src*="recaptcha"]`
- **hCaptcha** — detected via `iframe[src*="hcaptcha"]` or `.h-captcha[data-sitekey]`
- **Image captcha** — user provides selector, element is screenshotted and base64-encoded

## Tool Schema

```json
{
  "name": "solve_captcha",
  "description": "Solve a captcha on the current page using 2captcha.com service. Auto-detects reCAPTCHA v2 and hCaptcha. For image captchas, provide selector pointing to the captcha image.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "api_key": { "type": "string", "description": "2captcha.com API key" },
      "type": { "type": "string", "description": "Captcha type: recaptcha_v2, hcaptcha, or image. Auto-detected if omitted." },
      "selector": { "type": "string", "description": "CSS/XPath for image captcha element (required when type=image)" }
    },
    "required": ["api_key"]
  }
}
```

## Flow

1. **Detect**: Run JS to find captcha type + sitekey on page
2. **Submit**: POST to `https://2captcha.com/in.php` with type-specific params
3. **Poll**: GET `https://2captcha.com/res.php` every 5s (max 120s)
4. **Inject**: Set response textarea + call JS callback

## Detection JavaScript

- reCAPTCHA v2: search `___grecaptcha_cfg.clients` for sitekey, fall back to `iframe[src*="recaptcha"]` data-sitekey extraction
- hCaptcha: query `.h-captcha[data-sitekey]` or `iframe[src*="hcaptcha"]`

## Injection JavaScript

- reCAPTCHA v2: set `textarea[name="g-recaptcha-response"]` value + call callback from `___grecaptcha_cfg.clients`
- hCaptcha: set `textarea[name="h-captcha-response"]` value + call `data-callback` function
- Image: fill the user-specified input with text solution (separate `result_selector` param or return text)

## Error Cases

- No captcha detected → error message
- 2captcha API error (bad key, insufficient balance) → propagate error
- Polling timeout (120s) → timeout error
- Solution injection failure → error with details
