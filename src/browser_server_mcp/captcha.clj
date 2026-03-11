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
