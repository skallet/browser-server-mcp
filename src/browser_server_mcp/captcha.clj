(ns browser-server-mcp.captcha
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.util Base64]))

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
                  :pageurl page-url}})

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

;; --- Detection & Injection JavaScript ---

(def detect-captcha-js
  "JavaScript IIFE that detects captcha type and sitekey on the current page.
   Returns JSON: {type: 'recaptcha_v2'|'hcaptcha'|null, sitekey: '...'|null}"
  (str
   "(function() {"
   "  function findSitekey(obj, depth) {"
   "    if (!obj || depth > 5) return null;"
   "    if (typeof obj !== 'object') return null;"
   "    for (var k in obj) {"
   "      if (k === 'sitekey' || k === 'siteKey') {"
   "        if (typeof obj[k] === 'string' && obj[k].length > 0) return obj[k];"
   "      }"
   "      var r = findSitekey(obj[k], depth + 1);"
   "      if (r) return r;"
   "    }"
   "    return null;"
   "  }"
   "  try {"
   "    if (typeof ___grecaptcha_cfg !== 'undefined' && ___grecaptcha_cfg.clients) {"
   "      var sk = findSitekey(___grecaptcha_cfg.clients, 0);"
   "      if (sk) return JSON.stringify({type:'recaptcha_v2', sitekey:sk});"
   "    }"
   "  } catch(e) {}"
   "  try {"
   "    var iframe = document.querySelector('iframe[src*=\"recaptcha\"]');"
   "    if (iframe) {"
   "      var m = iframe.src.match(/[?&]k=([^&]+)/);"
   "      if (m) return JSON.stringify({type:'recaptcha_v2', sitekey:m[1]});"
   "    }"
   "  } catch(e) {}"
   "  try {"
   "    var el = document.querySelector('.g-recaptcha[data-sitekey]');"
   "    if (el) return JSON.stringify({type:'recaptcha_v2', sitekey:el.getAttribute('data-sitekey')});"
   "  } catch(e) {}"
   "  try {"
   "    var hel = document.querySelector('.h-captcha[data-sitekey]');"
   "    if (hel) return JSON.stringify({type:'hcaptcha', sitekey:hel.getAttribute('data-sitekey')});"
   "  } catch(e) {}"
   "  try {"
   "    var hiframe = document.querySelector('iframe[src*=\"hcaptcha\"]');"
   "    if (hiframe) {"
   "      var hm = hiframe.src.match(/[?&]sitekey=([^&]+)/);"
   "      if (hm) return JSON.stringify({type:'hcaptcha', sitekey:hm[1]});"
   "    }"
   "  } catch(e) {}"
   "  return JSON.stringify({type:null, sitekey:null});"
   "})()"))

(defn inject-solution-js
  "Returns JavaScript string to inject a captcha solution into the page.
   Returns nil for unsupported captcha types."
  [captcha-type solution]
  (let [safe (json/generate-string solution)]
    (case captcha-type
      :recaptcha_v2
      (str
       "(function() {"
       "  var sol = " safe ";"
       "  var ta = document.querySelector('textarea[name=\"g-recaptcha-response\"]');"
       "  if (ta) { ta.value = sol; ta.style.display = 'none'; }"
       "  try {"
       "    if (typeof ___grecaptcha_cfg !== 'undefined' && ___grecaptcha_cfg.clients) {"
       "      var clients = ___grecaptcha_cfg.clients;"
       "      function findCB(obj, depth) {"
       "        if (!obj || depth > 3) return null;"
       "        if (typeof obj !== 'object') return null;"
       "        for (var k in obj) {"
       "          if (k === 'callback' && typeof obj[k] === 'function') return obj[k];"
       "          var r = findCB(obj[k], depth + 1);"
       "          if (r) return r;"
       "        }"
       "        return null;"
       "      }"
       "      for (var k in clients) {"
       "        var cb = findCB(clients[k], 0);"
       "        if (cb) { cb(sol); return; }"
       "      }"
       "    }"
       "  } catch(e) {}"
       "})()")

      :hcaptcha
      (str
       "(function() {"
       "  var sol = " safe ";"
       "  var hta = document.querySelector('textarea[name=\"h-captcha-response\"]');"
       "  if (hta) hta.value = sol;"
       "  var gta = document.querySelector('textarea[name=\"g-recaptcha-response\"]');"
       "  if (gta) gta.value = sol;"
       "  try {"
       "    var el = document.querySelector('.h-captcha[data-callback]');"
       "    if (el) {"
       "      var cbName = el.getAttribute('data-callback');"
       "      if (cbName && typeof window[cbName] === 'function') {"
       "        window[cbName](sol);"
       "      }"
       "    }"
       "  } catch(e) {}"
       "})()")

      nil)))

;; --- Orchestrator ---

(defn- screenshot-element-base64
  "Screenshot a page element and return the base64-encoded PNG string.
   Selector starting with / is treated as xpath, otherwise css."
  [driver selector]
  (let [query (if (str/starts-with? selector "/")
                {:xpath selector}
                {:css selector})
        tmp-path (str (System/getProperty "java.io.tmpdir")
                      "/captcha-" (System/currentTimeMillis) ".png")
        screenshot-el-fn (resolve 'etaoin.api/screenshot-element)]
    (screenshot-el-fn driver query tmp-path)
    (let [file (java.io.File. tmp-path)
          bytes (java.nio.file.Files/readAllBytes (.toPath file))
          b64 (.encodeToString (Base64/getEncoder) bytes)]
      (.delete file)
      b64)))

(defn solve-captcha!
  "Orchestrate captcha solving: detect, submit to 2captcha, poll, inject.
   opts: {:api-key str, :type str (optional), :selector str (optional)}"
  [driver opts]
  (require '[etaoin.api :as e])
  (let [js-execute (resolve 'etaoin.api/js-execute)
        get-url    (resolve 'etaoin.api/get-url)
        page-url   (get-url driver)
        user-agent (js-execute driver "return navigator.userAgent")
        explicit-type (:type opts)
        detected   (when (not= explicit-type "image")
                     (let [raw (js-execute driver (str "return " detect-captcha-js))]
                       (when raw (json/parse-string raw true))))
        captcha-type (keyword (or explicit-type (:type detected)))
        sitekey    (:sitekey detected)
        selector   (:selector opts)]
    (cond
      (not captcha-type)
      {:error "No captcha type detected on page"}

      (and (= captcha-type :image) (not selector))
      {:error "Image captcha requires :selector option"}

      :else
      (let [submit-opts (cond-> {:type     captcha-type
                                 :api-key  (:api-key opts)
                                 :page-url page-url
                                 :user-agent user-agent
                                 :sitekey  sitekey}
                          (= captcha-type :image)
                          (assoc :image-base64 (screenshot-element-base64 driver selector)))
            task-id (submit-captcha! submit-opts)]
        (if-not task-id
          {:error "Failed to submit captcha to 2captcha"}
          (let [solution (poll-result! (:api-key opts) task-id)]
            (if solution
              (if (= captcha-type :image)
                {:ok (str "Image captcha solved: " solution)}
                (do
                  (js-execute driver (inject-solution-js captcha-type solution))
                  {:ok (str "Captcha solved and injected (" (name captcha-type) ")")}))
              {:error "Captcha solving timed out (120s)"})))))))
