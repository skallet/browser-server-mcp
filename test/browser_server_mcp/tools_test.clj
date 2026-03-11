(ns browser-server-mcp.tools-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [etaoin.api :as e]
            [clojure.string :as str]
            [browser-server-mcp.tools :as tools]))

(deftest test-tool-schemas
  (testing "returns 22 tool schemas without captcha key"
    (let [schemas (tools/tool-schemas {})]
      (is (= 22 (count schemas)))
      (is (not (some #(= "solve_captcha" (:name %)) schemas)))))

  (testing "returns 23 tool schemas with captcha key"
    (let [schemas (tools/tool-schemas {:captcha-api-key "test-key"})]
      (is (= 23 (count schemas)))
      (is (some #(= "solve_captcha" (:name %)) schemas))))

  (testing "all schemas have required fields"
    (doseq [schema (tools/tool-schemas {:captcha-api-key "test-key"})]
      (is (string? (:name schema)) (str "missing name in " schema))
      (is (string? (:description schema)) (str "missing description in " (:name schema)))
      (is (map? (:inputSchema schema)) (str "missing inputSchema in " (:name schema))))))

;; Start a real browser for integration tests
(def driver (e/chrome {:headless true}))

(deftest test-navigate
  (testing "navigates to URL and returns url + title"
    (let [result (tools/call-tool driver "navigate" {:url "https://example.com"})
          text (get-in result [:content 0 :text])
          info (read-string text)]
      (is (not (:isError result)))
      (is (str/includes? (:url info) "example.com"))
      (is (str/includes? (:title info) "Example Domain")))))

(deftest test-page-text
  (testing "returns visible text after navigation"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "page_text" {})]
      (is (not (:isError result)))
      (let [text (get-in result [:content 0 :text])]
        (is (str/includes? text "Example Domain"))))))

(deftest test-page-html
  (testing "returns HTML source after navigation"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "page_html" {})]
      (is (not (:isError result)))
      (let [text (get-in result [:content 0 :text])]
        (is (str/includes? text "<html"))
        (is (str/includes? text "Example Domain"))))))

(deftest test-wait
  (testing "wait visible for existing element"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "wait" {:selector "h1" :condition "visible"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "Condition met"))))

  (testing "wait visible is default condition"
    (let [result (tools/call-tool driver "wait" {:selector "h1"})]
      (is (not (:isError result)))))

  (testing "wait times out for non-existent element"
    (let [result (tools/call-tool driver "wait"
                   {:selector "#nonexistent-wait-test" :timeout_ms 1000})]
      (is (:isError result))))

  (testing "wait seconds (sleep)"
    (let [start (System/currentTimeMillis)
          result (tools/call-tool driver "wait" {:seconds 0.5})
          elapsed (- (System/currentTimeMillis) start)]
      (is (not (:isError result)))
      (is (>= elapsed 400)))))

(deftest test-scroll
  (testing "scroll to element by selector"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "scroll" {:selector "a"})]
      (is (not (:isError result)))))

  (testing "scroll by offset"
    (let [result (tools/call-tool driver "scroll" {:x 0 :y 100})]
      (is (not (:isError result)))))

  (testing "scroll to bottom"
    (let [result (tools/call-tool driver "scroll" {:position "bottom"})]
      (is (not (:isError result)))))

  (testing "scroll to top"
    (let [result (tools/call-tool driver "scroll" {:position "top"})]
      (is (not (:isError result))))))

(deftest test-screenshot
  (testing "saves full page screenshot"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [path "/tmp/jean-test-screenshot.png"
          result (tools/call-tool driver "screenshot" {:path path})]
      (is (not (:isError result)))
      (is (.exists (java.io.File. path)))
      (.delete (java.io.File. path))))

  (testing "saves element screenshot when selector provided"
    (let [path "/tmp/jean-test-element-screenshot.png"
          result (tools/call-tool driver "screenshot" {:path path :selector "h1"})]
      (is (not (:isError result)))
      (is (.exists (java.io.File. path)))
      (let [size (.length (java.io.File. path))]
        (is (pos? size)))
      (.delete (java.io.File. path)))))

(deftest test-resize
  (testing "resizes browser window"
    (let [result (tools/call-tool driver "resize" {:width 375 :height 812})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "375")))))

(deftest test-execute-js
  (testing "executes JavaScript and returns result"
    (let [result (tools/call-tool driver "execute_js" {:script "return document.title"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "Example Domain")))))

(deftest test-back
  (testing "navigates back and returns url + title"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (tools/call-tool driver "navigate" {:url "https://www.iana.org/domains/reserved"})
    (let [result (tools/call-tool driver "back" {})
          text (get-in result [:content 0 :text])
          info (read-string text)]
      (is (not (:isError result)))
      (is (str/includes? (:url info) "example.com")))))

(deftest test-get-url
  (testing "returns current url and title"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "get_url" {})
          text (get-in result [:content 0 :text])
          info (read-string text)]
      (is (not (:isError result)))
      (is (str/includes? (:url info) "example.com"))
      (is (str/includes? (:title info) "Example Domain")))))

(deftest test-query
  (testing "finds first element and returns ref + info"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "query" {:selector "a"})
          text (get-in result [:content 0 :text])
          info (read-string text)]
      (is (not (:isError result)))
      (is (string? (:ref info)))
      (is (= "a" (:tag info)))
      (is (string? (:text info)))
      (is (map? (:attributes info)))
      (is (str/includes? (get-in info [:attributes :href]) "iana.org"))))

  (testing "returns error for non-existent selector"
    (let [result (tools/call-tool driver "query" {:selector "#nonexistent-id-xyz"})]
      (is (:isError result)))))

(deftest test-query-all
  (testing "finds all matching elements with refs"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "query_all" {:selector "p, a, h1"})
          text (get-in result [:content 0 :text])
          elements (read-string text)]
      (is (not (:isError result)))
      (is (vector? elements))
      (is (pos? (count elements)))
      (is (every? #(string? (:ref %)) elements))
      (is (every? #(string? (:tag %)) elements))))

  (testing "returns empty vector when no matches"
    (let [result (tools/call-tool driver "query_all" {:selector "#nonexistent-xyz"})
          text (get-in result [:content 0 :text])
          elements (read-string text)]
      (is (not (:isError result)))
      (is (= [] elements)))))

(deftest test-get-text
  (testing "gets text by selector"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "get_text" {:selector "h1"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "Example Domain"))))

  (testing "gets text by ref"
    (let [q (tools/call-tool driver "query" {:selector "h1"})
          ref (:ref (read-string (get-in q [:content 0 :text])))
          result (tools/call-tool driver "get_text" {:ref ref})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "Example Domain")))))

(deftest test-get-attribute
  (testing "gets attribute by selector"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "get_attribute" {:selector "a" :name "href"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "iana.org"))))

  (testing "gets attribute by ref"
    (let [q (tools/call-tool driver "query" {:selector "a"})
          ref (:ref (read-string (get-in q [:content 0 :text])))
          result (tools/call-tool driver "get_attribute" {:ref ref :name "href"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "iana.org")))))

(deftest test-get-html
  (testing "gets inner HTML by selector"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "get_html" {:selector "body"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "Example Domain")))))

(deftest test-is-visible
  (testing "returns true for visible element"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "is_visible" {:selector "h1"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "true"))))

  (testing "returns true via ref"
    (let [q (tools/call-tool driver "query" {:selector "h1"})
          ref (:ref (read-string (get-in q [:content 0 :text])))
          result (tools/call-tool driver "is_visible" {:ref ref})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "true")))))

(deftest test-click-with-ref
  (testing "click works with ref"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [q (tools/call-tool driver "query" {:selector "a"})
          ref (:ref (read-string (get-in q [:content 0 :text])))
          result (tools/call-tool driver "click" {:ref ref})]
      (is (not (:isError result))))))

(deftest test-type-text-with-ref
  (testing "type_text works with selector"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (tools/call-tool driver "execute_js"
      {:script "var i = document.createElement('input'); i.id='test-input'; document.body.appendChild(i); return null;"})
    (let [result (tools/call-tool driver "type_text" {:selector "#test-input" :text "hello"})]
      (is (not (:isError result)))))

  (testing "type_text works with ref"
    (tools/call-tool driver "execute_js"
      {:script "document.getElementById('test-input').value=''; return null;"})
    (let [q (tools/call-tool driver "query" {:selector "#test-input"})
          ref (:ref (read-string (get-in q [:content 0 :text])))
          result (tools/call-tool driver "type_text" {:ref ref :text "world"})]
      (is (not (:isError result))))))

(deftest test-clear
  (testing "clears input field by selector"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (tools/call-tool driver "execute_js"
      {:script "var i = document.createElement('input'); i.id='clear-test'; i.value='prefilled'; document.body.appendChild(i); return null;"})
    (let [result (tools/call-tool driver "clear" {:selector "#clear-test"})]
      (is (not (:isError result))))))

(deftest test-hover
  (testing "hover works with selector"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "hover" {:selector "a"})]
      (is (not (:isError result))))))

(deftest test-press-key
  (testing "press Escape key"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "press_key" {:key "Escape"})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "Pressed"))))

  (testing "press Enter on focused element"
    (let [result (tools/call-tool driver "press_key" {:key "Enter"})]
      (is (not (:isError result)))))

  (testing "press Tab key"
    (let [result (tools/call-tool driver "press_key" {:key "Tab"})]
      (is (not (:isError result)))))

  (testing "press key with modifier"
    (let [result (tools/call-tool driver "press_key" {:key "a" :modifiers ["Ctrl"]})]
      (is (not (:isError result))))))

(deftest test-dismiss-alert
  (testing "reports no alert when none present"
    (tools/call-tool driver "navigate" {:url "https://example.com"})
    (let [result (tools/call-tool driver "dismiss_alert" {})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "No alert"))))

  (testing "dismisses actual alert"
    (tools/call-tool driver "execute_js" {:script "setTimeout(function(){alert('test')}, 50); return null;"})
    (Thread/sleep 200)
    (let [result (tools/call-tool driver "dismiss_alert" {})]
      (is (not (:isError result)))
      (is (str/includes? (get-in result [:content 0 :text]) "dismissed")))))

(deftest test-unknown-tool
  (testing "returns error for unknown tool"
    (let [result (tools/call-tool driver "nonexistent" {})]
      (is (:isError result)))))

(defn -main [& _args]
  (let [{:keys [fail error]} (run-tests 'browser-server-mcp.tools-test)]
    (e/quit driver)
    (System/exit (if (zero? (+ fail error)) 0 1))))
