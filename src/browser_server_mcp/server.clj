(ns browser-server-mcp.server
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def project-dir (System/getProperty "user.dir"))
(def port-file (str project-dir "/.browser-port"))
(def pid-file (str project-dir "/.browser-pid"))
(def mcp-json-file (str project-dir "/.mcp.json"))

(defn mcp-json-content
  "Generate the .mcp.json content map for a given port."
  [port]
  {"mcpServers" {"browser" {"type" "http"
                             "url" (str "http://127.0.0.1:" port "/mcp")}}})

(defn parse-args
  "Parse CLI arguments. Returns map with :command and options."
  [args]
  (if (empty? args)
    {:command :help}
    (let [cmd (first args)]
      (case cmd
        "start" (loop [remaining (rest args)
                       opts {:command :start :headless true :port 7117}]
                  (if (empty? remaining)
                    opts
                    (case (first remaining)
                      "--headed" (recur (rest remaining) (assoc opts :headless false))
                      "--port"   (let [val (second remaining)]
                                   (if (or (nil? val) (str/starts-with? val "--"))
                                     (do (println "Error: --port requires a numeric argument")
                                         (System/exit 1))
                                     (let [parsed (try (Integer/parseInt val)
                                                       (catch Exception _
                                                         (println (str "Error: invalid port number: " val))
                                                         (System/exit 1)))]
                                       (recur (drop 2 remaining) (assoc opts :port parsed)))))
                      "--captcha-api-key"
                      (let [val (second remaining)]
                        (if (or (nil? val) (str/starts-with? val "--"))
                          (do (println "Error: --captcha-api-key requires an argument")
                              (System/exit 1))
                          (recur (drop 2 remaining) (assoc opts :captcha-api-key val))))
                      (do (println (str "Unknown option: " (first remaining)))
                          (System/exit 1)))))
        "stop"   {:command :stop}
        "--help" {:command :help}
        "-h"     {:command :help}
        (do (println (str "Unknown command: " cmd))
            (println "Usage: browser-server-mcp <start|stop> [options]")
            (System/exit 1))))))

(defn stop-server! []
  (if (fs/exists? pid-file)
    (let [pid-str (str/trim (slurp pid-file))]
      (println (str "Stopping browser server (PID " pid-str ")..."))
      (let [pid-long (try (Long/parseLong pid-str)
                          (catch Exception _
                            (println (str "Error: malformed PID file: " pid-str))
                            nil))]
        (when pid-long
          (try
            (if-let [ph (-> (java.lang.ProcessHandle/of pid-long)
                            (.orElse nil))]
              (do
                (.destroy ph)
                (Thread/sleep 5000)
                (when (.isAlive ph)
                  (println "Force killing...")
                  (.destroyForcibly ph)))
              (println "Process not found (already stopped?)"))
            (catch Exception e
              (println (str "Process already stopped: " (.getMessage e)))))))
      (fs/delete-if-exists port-file)
      (fs/delete-if-exists pid-file)
      (fs/delete-if-exists mcp-json-file)
      (println "Stopped."))
    (println "No browser server running (no .browser-pid file).")))

(defn write-discovery-files! [port]
  (spit port-file (str port))
  (spit pid-file (str (.pid (java.lang.ProcessHandle/current))))
  (spit mcp-json-file (json/generate-string (mcp-json-content port) {:pretty true})))

(defn cleanup-discovery-files! []
  (fs/delete-if-exists port-file)
  (fs/delete-if-exists pid-file)
  (fs/delete-if-exists mcp-json-file))

(defn print-help []
  (println "Usage: browser-server-mcp <command> [options]")
  (println)
  (println "Commands:")
  (println "  start [options]   Start browser MCP server")
  (println "  stop              Stop running instance")
  (println)
  (println "Options:")
  (println "  --headed                Show browser window (default: headless)")
  (println "  --port PORT             Server port (default: 7117)")
  (println "  --captcha-api-key KEY   2captcha.com API key for solve_captcha tool")
  (println "                          (or set CAPTCHA_API_KEY env var)")
  (println "  --help, -h              Show this help"))

(defn start-server! [{:keys [headless port captcha-api-key]}]
  (require '[etaoin.api :as e]
           '[browser-server-mcp.mcp :as mcp])

  ;; Check for chromedriver
  (when-not (fs/which "chromedriver")
    (println "Error: chromedriver not found on PATH.")
    (println "Install: brew install chromedriver (macOS), or ensure chromedriver is on PATH")
    (System/exit 1))

  (let [chrome-fn (resolve 'etaoin.api/chrome)
        chrome-opts (cond-> {:args ["--no-sandbox"]}
                      headless (assoc :headless true)
                      (System/getenv "CHROME_BINARY") (assoc :path-browser (System/getenv "CHROME_BINARY")))
        driver (chrome-fn chrome-opts)
        resolved-captcha-key (or captcha-api-key (System/getenv "CAPTCHA_API_KEY"))
        server-opts (cond-> {:port port :host "127.0.0.1"}
                      resolved-captcha-key (assoc :captcha-api-key resolved-captcha-key))]
    (when resolved-captcha-key
      (println "Captcha solving enabled (2captcha.com)"))
    (try
      (let [server-info ((resolve 'browser-server-mcp.mcp/start-server!) driver server-opts)]

        ;; Write discovery files
        (write-discovery-files! port)

        ;; Register shutdown hook
        (.addShutdownHook (Runtime/getRuntime)
          (Thread. (fn []
                     (println "\nShutting down browser server...")
                     (try ((resolve 'etaoin.api/quit) driver) (catch Exception _))
                     (try ((resolve 'browser-server-mcp.mcp/stop-server!) server-info) (catch Exception _))
                     (cleanup-discovery-files!)
                     (println "Done."))))

        (println (str "Browser server started on port " port
                      (when headless " (headless)")))
        (println (str "PID: " (.pid (java.lang.ProcessHandle/current))))
        (println "Press Ctrl+C to stop.")

        ;; Block forever
        @(promise))
      (catch Exception e
        (println (str "Error starting server: " (.getMessage e)))
        (try ((resolve 'etaoin.api/quit) driver) (catch Exception _))
        (cleanup-discovery-files!)
        (System/exit 1)))))

(defn -main [& args]
  (let [opts (parse-args args)]
    (case (:command opts)
      :start (start-server! opts)
      :stop  (stop-server!)
      :help  (print-help))))
