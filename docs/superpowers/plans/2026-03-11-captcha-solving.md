# Captcha Solving Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `solve_captcha` MCP tool that detects, solves via 2captcha.com, and injects captcha solutions.

**Architecture:** New `browser_server_mcp.captcha` namespace for 2captcha API + detection + injection logic. Single new tool registered in `tools.clj`. Uses `babashka.http-client` for HTTP.

**Tech Stack:** Babashka, etaoin, babashka.http-client, 2captcha.com REST API

---

## Chunk 1: Core captcha namespace with 2captcha API

### Task 1: Create captcha namespace with 2captcha submit + poll

**Files:**
- Create: `src/browser_server_mcp/captcha.clj`
- Create: `test/browser_server_mcp/captcha_test.clj`

- [ ] **Step 1: Write failing test for `submit-captcha`**

Create `test/browser_server_mcp/captcha_test.clj`:

```clojure
(ns browser-server-mcp.captcha-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [browser-server-mcp.captcha :as captcha]))

(deftest test-build-submit-params
  (testing "builds recaptcha_v2 params"
    (let [params (captcha/build-submit-params
                   {:type :recaptcha_v2
                    :api-key "test-key"
                    :sitekey "site123"
                    :page-url "https://example.com"})]
      (is (= "test-key" (get-in params [:query-params :key])))
      (is (= "userrecaptcha" (get-in params [:query-params :method])))
      (is (= "site123" (get-in params [:query-params :googlekey])))
      (is (= "https://example.com" (get-in params [:query-params :pageurl])))))

  (testing "builds hcaptcha params"
    (let [params (captcha/build-submit-params
                   {:type :hcaptcha
                    :api-key "test-key"
                    :sitekey "site456"
                    :page-url "https://example.com"
                    :user-agent "Mozilla/5.0"})]
      (is (= "hcaptcha" (get-in params [:form-params :method])))
      (is (= "site456" (get-in params [:form-params :sitekey])))
      (is (= "Mozilla/5.0" (get-in params [:form-params :userAgent])))))

  (testing "builds base64 image params"
    (let [params (captcha/build-submit-params
                   {:type :image
                    :api-key "test-key"
                    :image-base64 "iVBOR..."})]
      (is (= "base64" (get-in params [:form-params :method])))
      (is (= "iVBOR..." (get-in params [:form-params :body]))))))

(deftest test-parse-submit-response
  (testing "parses OK response"
    (is (= "12345" (captcha/parse-submit-response "OK|12345"))))

  (testing "returns nil for error"
    (is (nil? (captcha/parse-submit-response "ERROR_WRONG_USER_KEY"))))

  (testing "returns nil for empty"
    (is (nil? (captcha/parse-submit-response "")))))

(deftest test-parse-result-response
  (testing "parses ready response"
    (is (= "solution-token" (captcha/parse-result-response "OK|solution-token"))))

  (testing "returns :not-ready for pending"
    (is (= :not-ready (captcha/parse-result-response "CAPCHA_NOT_READY"))))

  (testing "returns nil for error"
    (is (nil? (captcha/parse-result-response "ERROR_CAPTCHA_UNSOLVABLE")))))

(defn -main [& _args]
  (let [{:keys [fail error]} (run-tests 'browser-server-mcp.captcha-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.captcha-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Write minimal implementation**

Create `src/browser_server_mcp/captcha.clj`:

```clojure
(ns browser-server-mcp.captcha
  (:require [babashka.http-client :as http]
            [clojure.string :as str]))

(def ^:private submit-url "https://2captcha.com/in.php")
(def ^:private result-url "https://2captcha.com/res.php")
(def ^:private poll-interval-ms 5000)
(def ^:private max-poll-ms 120000)

(defmulti build-submit-params :type)

(defmethod build-submit-params :recaptcha_v2
  [{:keys [api-key sitekey page-url]}]
  {:query-params {:key api-key
                  :method "userrecaptcha"
                  :googlekey sitekey
                  :pageurl page-url
                  :json 1}})

(defmethod build-submit-params :hcaptcha
  [{:keys [api-key sitekey page-url user-agent]}]
  {:form-params {:key api-key
                 :method "hcaptcha"
                 :sitekey sitekey
                 :pageurl page-url
                 :userAgent (or user-agent "")}})

(defmethod build-submit-params :image
  [{:keys [api-key image-base64]}]
  {:form-params {:key api-key
                 :method "base64"
                 :body image-base64}})

(defn parse-submit-response
  "Parse 2captcha submit response. Returns task ID or nil."
  [body]
  (let [parts (str/split (str body) #"\|")]
    (when (= "OK" (first parts))
      (second parts))))

(defn parse-result-response
  "Parse 2captcha result response. Returns solution string, :not-ready, or nil (error)."
  [body]
  (let [s (str body)]
    (cond
      (= "CAPCHA_NOT_READY" s) :not-ready
      (str/starts-with? s "OK|") (subs s 3)
      :else nil)))

(defn submit-captcha!
  "Submit captcha to 2captcha. Returns task ID or nil."
  [opts]
  (let [params (build-submit-params opts)
        has-form? (some? (:form-params params))
        resp (if has-form?
               (http/post submit-url params)
               (http/get submit-url params))]
    (parse-submit-response (:body resp))))

(defn poll-result!
  "Poll 2captcha for result. Returns solution string or nil on timeout/error."
  [api-key task-id]
  (let [deadline (+ (System/currentTimeMillis) max-poll-ms)]
    (Thread/sleep poll-interval-ms) ;; initial wait
    (loop []
      (let [resp (try
                   (http/get result-url
                             {:query-params {:key api-key
                                             :action "get"
                                             :id task-id}})
                   (catch Exception _ nil))
            result (when resp (parse-result-response (:body resp)))]
        (cond
          (string? result) result
          (> (System/currentTimeMillis) deadline) nil
          (= :not-ready result)
          (do (Thread/sleep poll-interval-ms) (recur))
          :else nil)))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.captcha-test`
Expected: 3 tests, all PASS

- [ ] **Step 5: Commit**

```bash
git add src/browser_server_mcp/captcha.clj test/browser_server_mcp/captcha_test.clj
git commit -m "feat: add captcha namespace with 2captcha API submit/poll"
```

---

### Task 2: Add detection + injection JavaScript functions

**Files:**
- Modify: `src/browser_server_mcp/captcha.clj`
- Modify: `test/browser_server_mcp/captcha_test.clj`

- [ ] **Step 1: Write failing tests for detect-captcha and inject-solution**

Append to `captcha_test.clj` (these test the JS string generation, not browser execution):

```clojure
(deftest test-detect-js
  (testing "detect JS returns a string"
    (is (string? captcha/detect-captcha-js))
    (is (str/includes? captcha/detect-captcha-js "grecaptcha"))))

(deftest test-inject-recaptcha-js
  (testing "returns JS string with solution interpolated"
    (let [js (captcha/inject-solution-js :recaptcha_v2 "token123")]
      (is (string? js))
      (is (str/includes? js "token123"))
      (is (str/includes? js "g-recaptcha-response")))))

(deftest test-inject-hcaptcha-js
  (testing "returns JS string with solution interpolated"
    (let [js (captcha/inject-solution-js :hcaptcha "token456")]
      (is (string? js))
      (is (str/includes? js "token456"))
      (is (str/includes? js "h-captcha-response")))))
```

Add `(:require [clojure.string :as str])` to the test ns.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.captcha-test`
Expected: FAIL — `detect-captcha-js` not found

- [ ] **Step 3: Implement detection + injection JS**

Add to `captcha.clj`:

```clojure
(def detect-captcha-js
  "JavaScript that detects captcha type and sitekey on current page.
   Returns JSON: {type: 'recaptcha_v2'|'hcaptcha'|null, sitekey: '...'|null}"
  "
  (function() {
    // Try reCAPTCHA v2 via grecaptcha config
    try {
      if (typeof ___grecaptcha_cfg !== 'undefined' && ___grecaptcha_cfg.clients) {
        var clients = ___grecaptcha_cfg.clients;
        function findKey(obj, depth) {
          if (depth > 3) return null;
          var keys = Object.keys(obj);
          for (var i = 0; i < keys.length; i++) {
            if (keys[i] === 'sitekey') return obj.sitekey;
            if (typeof obj[keys[i]] === 'object' && obj[keys[i]] !== null) {
              var r = findKey(obj[keys[i]], depth + 1);
              if (r) return r;
            }
          }
          return null;
        }
        for (var k in clients) {
          var sk = findKey(clients[k], 0);
          if (sk) return JSON.stringify({type: 'recaptcha_v2', sitekey: sk});
        }
      }
    } catch(e) {}

    // Try reCAPTCHA v2 via iframe
    try {
      var rcFrame = document.querySelector('iframe[src*=\"recaptcha\"]');
      if (rcFrame) {
        var src = rcFrame.src;
        var match = src.match(/[?&]k=([^&]+)/);
        if (match) return JSON.stringify({type: 'recaptcha_v2', sitekey: match[1]});
      }
    } catch(e) {}

    // Try reCAPTCHA v2 via data-sitekey
    try {
      var rcDiv = document.querySelector('.g-recaptcha[data-sitekey]');
      if (rcDiv) return JSON.stringify({type: 'recaptcha_v2', sitekey: rcDiv.getAttribute('data-sitekey')});
    } catch(e) {}

    // Try hCaptcha
    try {
      var hcDiv = document.querySelector('.h-captcha[data-sitekey]');
      if (hcDiv) return JSON.stringify({type: 'hcaptcha', sitekey: hcDiv.getAttribute('data-sitekey')});
    } catch(e) {}

    try {
      var hcFrame = document.querySelector('iframe[src*=\"hcaptcha\"]');
      if (hcFrame) {
        var hsrc = hcFrame.src;
        var hmatch = hsrc.match(/sitekey=([^&]+)/);
        if (hmatch) return JSON.stringify({type: 'hcaptcha', sitekey: hmatch[1]});
      }
    } catch(e) {}

    return JSON.stringify({type: null, sitekey: null});
  })()
  ")

(defn inject-solution-js
  "Generate JavaScript to inject captcha solution into the page."
  [captcha-type solution]
  (case captcha-type
    :recaptcha_v2
    (str "
      (function() {
        var sol = " (pr-str solution) ";
        // Set textarea value
        var ta = document.querySelector('textarea[name=\"g-recaptcha-response\"]');
        if (ta) { ta.style.display = 'block'; ta.value = sol; }
        // Try to invoke callback
        try {
          if (typeof ___grecaptcha_cfg !== 'undefined' && ___grecaptcha_cfg.clients) {
            function findCB(obj, depth) {
              if (depth > 3) return null;
              var keys = Object.keys(obj);
              for (var i = 0; i < keys.length; i++) {
                if (keys[i] === 'callback') {
                  var cb = obj.callback;
                  return typeof cb === 'function' ? cb : window[cb];
                }
                if (typeof obj[keys[i]] === 'object' && obj[keys[i]] !== null) {
                  var r = findCB(obj[keys[i]], depth + 1);
                  if (r) return r;
                }
              }
              return null;
            }
            for (var k in ___grecaptcha_cfg.clients) {
              var cb = findCB(___grecaptcha_cfg.clients[k], 0);
              if (cb) { cb(sol); break; }
            }
          }
        } catch(e) {}
        return 'injected';
      })()")

    :hcaptcha
    (str "
      (function() {
        var sol = " (pr-str solution) ";
        // Set textarea values
        var tas = document.querySelectorAll('textarea[name=\"h-captcha-response\"], textarea[name=\"g-recaptcha-response\"]');
        for (var i = 0; i < tas.length; i++) { tas[i].value = sol; }
        // Try callback
        try {
          var el = document.querySelector('.h-captcha[data-callback]');
          if (el) {
            var cbName = el.getAttribute('data-callback');
            if (window[cbName]) window[cbName](sol);
          }
        } catch(e) {}
        return 'injected';
      })()")

    ;; image type doesn't need JS injection — solution is text typed into an input
    nil))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.captcha-test`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add src/browser_server_mcp/captcha.clj test/browser_server_mcp/captcha_test.clj
git commit -m "feat: add captcha detection and injection JavaScript"
```

---

## Chunk 2: Orchestration + tool integration

### Task 3: Add `solve-captcha!` orchestrator function

**Files:**
- Modify: `src/browser_server_mcp/captcha.clj`

- [ ] **Step 1: Write the `solve-captcha!` function**

Add to `captcha.clj`:

```clojure
(defn- screenshot-element-base64
  "Screenshot an element and return base64-encoded PNG string."
  [driver selector]
  (let [sel (if (str/starts-with? selector "/") {:xpath selector} {:css selector})
        tmp-file (str "/tmp/captcha-" (System/currentTimeMillis) ".png")]
    (require '[etaoin.api :as e])
    ((resolve 'etaoin.api/screenshot-element) driver sel tmp-file)
    (let [bytes (java.nio.file.Files/readAllBytes
                  (java.nio.file.Paths/get tmp-file (into-array String [])))
          b64 (.encodeToString (java.util.Base64/getEncoder) bytes)]
      (.delete (java.io.File. tmp-file))
      b64)))

(defn solve-captcha!
  "Full captcha solving flow: detect -> submit -> poll -> inject.
   Returns {:ok message} or {:error message}."
  [driver {:keys [api-key type selector]}]
  (require '[etaoin.api :as e])
  (let [js-exec (resolve 'etaoin.api/js-execute)
        page-url (str ((resolve 'etaoin.api/get-url) driver))
        user-agent (str ((resolve 'etaoin.api/get-user-agent) driver))
        ;; Detect captcha type + sitekey
        detected (when-not (= type "image")
                   (let [raw (js-exec driver (str "return " detect-captcha-js))]
                     (when (string? raw)
                       (cheshire.core/parse-string raw true))))
        captcha-type (keyword (or type (:type detected)))
        sitekey (:sitekey detected)]

    (cond
      (nil? captcha-type)
      {:error "No captcha detected on page. Specify type manually if needed."}

      (and (= :image captcha-type) (nil? selector))
      {:error "Image captcha requires 'selector' parameter pointing to the captcha image element."}

      :else
      (let [opts (cond-> {:type captcha-type
                          :api-key api-key
                          :page-url page-url
                          :user-agent user-agent}
                   sitekey (assoc :sitekey sitekey)
                   (= :image captcha-type)
                   (assoc :image-base64 (screenshot-element-base64 driver selector)))
            task-id (submit-captcha! opts)]
        (if (nil? task-id)
          {:error "Failed to submit captcha to 2captcha (check API key and balance)"}
          (if-let [solution (poll-result! api-key task-id)]
            ;; Inject solution
            (if (= :image captcha-type)
              {:ok (str "Image captcha solved: " solution)}
              (do
                (js-exec driver (inject-solution-js captcha-type solution))
                {:ok (str "Captcha solved and injected (" (name captcha-type) ")")}))
            {:error "Captcha solving timed out (120s)"}))))))
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src -e "(require '[browser-server-mcp.captcha])"`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/browser_server_mcp/captcha.clj
git commit -m "feat: add solve-captcha! orchestrator with detect/submit/poll/inject"
```

---

### Task 4: Register `solve_captcha` tool in tools.clj

**Files:**
- Modify: `src/browser_server_mcp/tools.clj`
- Modify: `test/browser_server_mcp/tools_test.clj`
- Modify: `test/browser_server_mcp/mcp_test.clj`

- [ ] **Step 1: Write failing test for new tool schema count**

In `tools_test.clj`, update the schema count test:

```clojure
;; Change from:
(is (= 22 (count schemas)))
;; To:
(is (= 23 (count schemas)))
```

In `mcp_test.clj`, update similarly:

```clojure
;; Change from:
(is (= 22 (count tools)))
;; To:
(is (= 23 (count tools)))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.tools-test`
Expected: FAIL — expected 23 but got 22

- [ ] **Step 3: Add tool schema + handler to tools.clj**

Add to the `tool-schemas` vector in `tools.clj` (after `execute_js`):

```clojure
{:name "solve_captcha"
 :description "Solve a captcha on the current page using 2captcha.com. Auto-detects reCAPTCHA v2 and hCaptcha. For image captchas, provide the selector for the captcha image element. Returns the solution text for image captchas."
 :inputSchema {:type "object"
               :properties {:api_key {:type "string" :description "2captcha.com API key"}
                            :type {:type "string" :description "Captcha type: recaptcha_v2, hcaptcha, or image. Auto-detected if omitted."}
                            :selector {:type "string" :description "CSS/XPath for image captcha element (required when type=image)"}}
               :required ["api_key"]}}
```

Add the handler function:

```clojure
(defn- do-solve-captcha [driver args]
  (require '[browser-server-mcp.captcha :as captcha])
  (let [result ((resolve 'browser-server-mcp.captcha/solve-captcha!)
                driver
                {:api-key (:api_key args)
                 :type (:type args)
                 :selector (:selector args)})]
    (if (:error result)
      (error (:error result))
      (success (:ok result)))))
```

Add to `tool-handlers` map:

```clojure
"solve_captcha" do-solve-captcha
```

Update the `call-tool` timeout to give `solve_captcha` 180s:

```clojure
;; In the timeout case expression, add:
"solve_captcha" 180000
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.tools-test`
Expected: Schema count test passes (23 tools)

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.mcp-test`
Expected: Tool list count test passes (23 tools)

- [ ] **Step 5: Commit**

```bash
git add src/browser_server_mcp/tools.clj test/browser_server_mcp/tools_test.clj test/browser_server_mcp/mcp_test.clj
git commit -m "feat: register solve_captcha MCP tool with 180s timeout"
```

---

### Task 5: Add require for cheshire in captcha namespace

**Files:**
- Modify: `src/browser_server_mcp/captcha.clj`

- [ ] **Step 1: Add cheshire to captcha.clj requires**

Update the ns form in `captcha.clj`:

```clojure
(ns browser-server-mcp.captcha
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))
```

Replace the inline `cheshire.core/parse-string` call in `solve-captcha!` with `json/parse-string`.

- [ ] **Step 2: Verify compilation**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src -e "(require '[browser-server-mcp.captcha])"`
Expected: No errors

- [ ] **Step 3: Run all tests**

Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.captcha-test`
Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.tools-test`
Run: `cd /home/skall/projects/browser-server-mcp && bb -cp src:test -m browser-server-mcp.mcp-test`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add src/browser_server_mcp/captcha.clj
git commit -m "fix: add cheshire require to captcha namespace"
```

---

### Task 6: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add solve_captcha to tool list in README**

Add entry to the tools table/list documenting:
- Tool name: `solve_captcha`
- Parameters: `api_key` (required), `type` (optional), `selector` (optional for image)
- What it does: auto-detects and solves captchas via 2captcha.com
- Supported types: reCAPTCHA v2, hCaptcha, image

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add solve_captcha tool to README"
```
