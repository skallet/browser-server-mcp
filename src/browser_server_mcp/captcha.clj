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

;; --- Detection & Injection JavaScript ---

(def detect-captcha-js
  "JavaScript IIFE that detects captcha type and sitekey on the current page.
   Returns JSON: {type: 'recaptcha_v2'|'hcaptcha'|null, sitekey: '...'|null}"
  (str
   "(function() {"
   "  function findSitekey(obj, depth) {"
   "    if (!obj || depth > 5) return null;"
   "    if (typeof obj === 'string' && obj.length > 10 && obj.length < 100) return obj;"
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
  (case captcha-type
    :recaptcha_v2
    (str
     "(function() {"
     "  var ta = document.querySelector('textarea[name=\"g-recaptcha-response\"]');"
     "  if (ta) { ta.value = '" solution "'; ta.style.display = 'none'; }"
     "  try {"
     "    if (typeof ___grecaptcha_cfg !== 'undefined' && ___grecaptcha_cfg.clients) {"
     "      var clients = ___grecaptcha_cfg.clients;"
     "      for (var k in clients) {"
     "        var c = clients[k];"
     "        if (c && c.aa && typeof c.aa.callback === 'function') {"
     "          c.aa.callback('" solution "'); return;"
     "        }"
     "        if (c) {"
     "          for (var j in c) {"
     "            var inner = c[j];"
     "            if (inner && typeof inner === 'object') {"
     "              for (var m in inner) {"
     "                if (inner[m] && typeof inner[m].callback === 'function') {"
     "                  inner[m].callback('" solution "'); return;"
     "                }"
     "              }"
     "            }"
     "          }"
     "        }"
     "      }"
     "    }"
     "  } catch(e) {}"
     "})()")

    :hcaptcha
    (str
     "(function() {"
     "  var hta = document.querySelector('textarea[name=\"h-captcha-response\"]');"
     "  if (hta) hta.value = '" solution "';"
     "  var gta = document.querySelector('textarea[name=\"g-recaptcha-response\"]');"
     "  if (gta) gta.value = '" solution "';"
     "  try {"
     "    var el = document.querySelector('.h-captcha[data-callback]');"
     "    if (el) {"
     "      var cbName = el.getAttribute('data-callback');"
     "      if (cbName && typeof window[cbName] === 'function') {"
     "        window[cbName]('" solution "');"
     "      }"
     "    }"
     "  } catch(e) {}"
     "})()")

    nil))
