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
