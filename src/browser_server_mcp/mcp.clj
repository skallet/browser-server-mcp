(ns browser-server-mcp.mcp
  (:require [cheshire.core :as json]))

(defn parse-jsonrpc
  "Parse a JSON-RPC message string into a map."
  [body-str]
  (try
    (json/parse-string body-str true)
    (catch Exception e
      {:error {:code -32700 :message (str "Parse error: " (.getMessage e))}})))

(defn jsonrpc-response
  "Build a JSON-RPC success response."
  [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn jsonrpc-error
  "Build a JSON-RPC error response."
  [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn tool-schemas
  "Return MCP tool schemas. Loaded from browser-server-mcp.tools namespace."
  []
  (require '[browser-server-mcp.tools])
  ((resolve 'browser-server-mcp.tools/tool-schemas)))

(defn handle-method
  "Dispatch a JSON-RPC method. Returns result map or error map."
  [method params driver]
  (case method
    "initialize"
    {:protocolVersion "2025-03-26"
     :serverInfo {:name "browser-server-mcp" :version "0.1.0"}
     :capabilities {:tools {:listChanged false}}}

    "notifications/initialized"
    nil

    "tools/list"
    {:tools (tool-schemas)}

    "tools/call"
    (do
      (require '[browser-server-mcp.tools])
      ((resolve 'browser-server-mcp.tools/call-tool) driver (:name params) (:arguments params)))

    {:error {:code -32601 :message (str "Method not found: " method)}}))

(defn- generate-session-id []
  (str (java.util.UUID/randomUUID)))

(defn handle-http-post
  "Handle an MCP Streamable HTTP POST request.
   Returns a Ring-style response map."
  [body-str driver session-id-atom]
  (let [msg (parse-jsonrpc body-str)]
    (if (:error msg)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string (jsonrpc-error nil -32700 "Parse error"))}

      (let [is-request? (contains? msg :id)
            method (:method msg)
            params (or (:params msg) {})
            result (handle-method method params driver)]
        (if is-request?
          (let [session-id (or @session-id-atom
                               (let [sid (generate-session-id)]
                                 (reset! session-id-atom sid)
                                 sid))
                response-body (if (:error result)
                                (jsonrpc-error (:id msg)
                                               (get-in result [:error :code])
                                               (get-in result [:error :message]))
                                (jsonrpc-response (:id msg) result))]
            {:status 200
             :headers {"Content-Type" "application/json"
                       "Mcp-Session-Id" session-id}
             :body (json/generate-string response-body)})

          {:status 202
           :headers {}
           :body ""})))))

(defn make-handler
  "Create a Ring handler for the MCP server."
  [driver session-id-atom]
  (fn [req]
    (let [method (:request-method req)
          uri (:uri req)]
      (cond
        (and (= method :post) (= uri "/mcp"))
        (let [body (slurp (:body req))]
          (handle-http-post body driver session-id-atom))

        (and (= method :get) (= uri "/mcp"))
        {:status 405
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error "GET SSE not supported, use POST"})}

        (and (= method :delete) (= uri "/mcp"))
        {:status 200
         :headers {}
         :body ""}

        (= uri "/health")
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:status "ok"})}

        :else
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error "Not found"})}))))

(defn start-server!
  "Start the MCP HTTP server. Returns the server instance."
  [driver {:keys [port host]
           :or {port 7117 host "127.0.0.1"}}]
  (require '[org.httpkit.server :as http])
  (let [session-id-atom (atom nil)
        handler (make-handler driver session-id-atom)
        server ((resolve 'org.httpkit.server/run-server) handler {:port port :ip host})]
    {:server server
     :session-id-atom session-id-atom}))

(defn stop-server! [{:keys [server]}]
  (when server
    (server)))
