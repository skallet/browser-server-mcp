(ns browser-server-mcp.mcp-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [browser-server-mcp.mcp :as mcp]))

(deftest test-parse-jsonrpc
  (testing "parses valid JSON-RPC request"
    (let [req (mcp/parse-jsonrpc
               "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")]
      (is (= "2.0" (:jsonrpc req)))
      (is (= 1 (:id req)))
      (is (= "initialize" (:method req)))
      (is (= {} (:params req)))))

  (testing "parses notification (no id)"
    (let [req (mcp/parse-jsonrpc
               "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")]
      (is (= "notifications/initialized" (:method req)))
      (is (nil? (:id req)))))

  (testing "returns error for invalid JSON"
    (let [result (mcp/parse-jsonrpc "not json")]
      (is (:error result)))))

(deftest test-jsonrpc-response
  (testing "formats success response"
    (let [resp (mcp/jsonrpc-response 1 {:name "browser"})]
      (is (= "2.0" (:jsonrpc resp)))
      (is (= 1 (:id resp)))
      (is (= {:name "browser"} (:result resp)))))

  (testing "formats error response"
    (let [resp (mcp/jsonrpc-error 1 -32601 "Method not found")]
      (is (= "2.0" (:jsonrpc resp)))
      (is (= 1 (:id resp)))
      (is (= -32601 (get-in resp [:error :code]))))))

(deftest test-handle-initialize
  (testing "returns server info and capabilities"
    (let [result (mcp/handle-method
                  "initialize"
                  {:protocolVersion "2025-03-26"}
                  nil)]
      (is (= "2025-03-26" (:protocolVersion result)))
      (is (= "browser-server-mcp" (get-in result [:serverInfo :name])))
      (is (get-in result [:capabilities :tools])))))

(deftest test-handle-tools-list
  (testing "returns tool schemas"
    (let [result (mcp/handle-method "tools/list" {} nil)
          tools (:tools result)
          tool-names (set (map :name tools))]
      (is (contains? tool-names "navigate"))
      (is (contains? tool-names "screenshot"))
      (is (contains? tool-names "click"))
      (is (contains? tool-names "page_text"))
      (is (= 22 (count tools))))))

(deftest test-handle-unknown-method
  (testing "returns error for unknown method"
    (let [result (mcp/handle-method "unknown/method" {} nil)]
      (is (:error result))
      (is (= -32601 (get-in result [:error :code]))))))

(deftest test-handle-http-request
  (testing "POST with initialize returns JSON response"
    (let [body (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize"
                                      :params {:protocolVersion "2025-03-26"}})
          response (mcp/handle-http-post body nil (atom nil))]
      (is (= 200 (:status response)))
      (is (str/includes? (get-in response [:headers "Content-Type"]) "application/json"))
      (let [parsed (json/parse-string (:body response) true)]
        (is (= 1 (:id parsed)))
        (is (= "2025-03-26" (get-in parsed [:result :protocolVersion]))))))

  (testing "POST with notification returns 202"
    (let [body (json/generate-string {:jsonrpc "2.0" :method "notifications/initialized"})
          response (mcp/handle-http-post body nil (atom nil))]
      (is (= 202 (:status response)))))

  (testing "POST with session id tracking"
    (let [body (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize"
                                      :params {:protocolVersion "2025-03-26"}})
          response (mcp/handle-http-post body nil (atom nil))
          session-id (get-in response [:headers "Mcp-Session-Id"])]
      (is (some? session-id)))))

(defn -main [& _args]
  (let [{:keys [fail error]} (run-tests 'browser-server-mcp.mcp-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
