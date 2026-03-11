(ns browser-server-mcp.server-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [browser-server-mcp.server :as server]))

(deftest test-parse-args-start
  (testing "start with defaults"
    (let [opts (server/parse-args ["start"])]
      (is (= :start (:command opts)))
      (is (= true (:headless opts)))
      (is (= 7117 (:port opts)))))

  (testing "start --headed"
    (let [opts (server/parse-args ["start" "--headed"])]
      (is (= :start (:command opts)))
      (is (= false (:headless opts)))))

  (testing "start --port 8080"
    (let [opts (server/parse-args ["start" "--port" "8080"])]
      (is (= :start (:command opts)))
      (is (= 8080 (:port opts)))))

  (testing "start --headed --port 9090"
    (let [opts (server/parse-args ["start" "--headed" "--port" "9090"])]
      (is (= :start (:command opts)))
      (is (= false (:headless opts)))
      (is (= 9090 (:port opts))))))

(deftest test-parse-args-stop
  (testing "stop command"
    (let [opts (server/parse-args ["stop"])]
      (is (= :stop (:command opts))))))

(deftest test-parse-args-help
  (testing "--help flag"
    (let [opts (server/parse-args ["--help"])]
      (is (= :help (:command opts))))))

(deftest test-parse-args-no-args
  (testing "no arguments defaults to help"
    (let [opts (server/parse-args [])]
      (is (= :help (:command opts))))))

(deftest test-mcp-json-content
  (testing "generates correct .mcp.json content"
    (let [content (server/mcp-json-content 7117)]
      (is (= {"mcpServers" {"browser" {"type" "http"
                                        "url" "http://127.0.0.1:7117/mcp"}}}
             content)))
    (let [content (server/mcp-json-content 8080)]
      (is (= "http://127.0.0.1:8080/mcp"
             (get-in content ["mcpServers" "browser" "url"]))))))

(defn run [& _args]
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
