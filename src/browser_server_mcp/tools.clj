(ns browser-server-mcp.tools
  (:require [etaoin.api :as e]
            [etaoin.keys :as k]
            [clojure.string :as str]))

(def ^:private max-text-length 51200)   ;; 50KB
(def ^:private max-html-length 204800)  ;; 200KB
(def ^:private default-timeout 30000)   ;; 30s
(def ^:private navigate-timeout 60000)  ;; 60s
(def ^:private max-elements 50)

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "\n... [truncated at " max-len " chars]")
    s))

(defn- success [text]
  {:content [{:type "text" :text (str text)}]})

(defn- error [text]
  {:content [{:type "text" :text (str text)}] :isError true})

(defn- parse-selector
  "Convert a selector string to an Etaoin query.
   XPath if starts with / or //, otherwise CSS."
  [s]
  (if (str/starts-with? s "/")
    {:xpath s}
    {:css s}))

(def ^:private element-attrs
  [:id :class :href :src :type :role :name :value
   :data-i18n :data-testid :aria-label :title :placeholder])

(defn- element->info
  "Extract ref + tag + text + attributes from an Etaoin element ref."
  [driver el]
  (let [tag (try (e/get-element-tag-el driver el) (catch Exception _ "unknown"))
        text (try (truncate (str (e/get-element-text-el driver el)) 200)
                  (catch Exception _ ""))
        attrs (reduce (fn [m attr]
                        (if-let [v (try (e/get-element-attr-el driver el attr)
                                        (catch Exception _ nil))]
                          (assoc m attr v)
                          m))
                      {} element-attrs)]
    (cond-> {:ref (str el) :tag tag :text text}
      (seq attrs) (assoc :attributes attrs))))

(defn- with-ref-or-selector
  "Dispatch to f-ref or f-sel based on which param is present in args.
   Returns error if neither or both are provided."
  [driver args f-sel f-ref]
  (let [ref (:ref args)
        selector (:selector args)]
    (cond
      (and ref selector)
      (error "Provide either 'ref' or 'selector', not both")

      ref
      (f-ref driver ref)

      selector
      (f-sel driver (parse-selector selector))

      :else
      (error "Either 'ref' or 'selector' is required"))))

(def ^:private ref-or-selector-props
  {:ref {:type "string" :description "Element ref from query/query_all"}
   :selector {:type "string" :description "CSS selector or XPath expression"}})

(defn tool-schemas []
  [{:name "navigate"
    :description "Navigate to a URL"
    :inputSchema {:type "object"
                  :properties {:url {:type "string" :description "URL to navigate to"}}
                  :required ["url"]}}
   {:name "back"
    :description "Navigate back in browser history"
    :inputSchema {:type "object" :properties {}}}
   {:name "get_url"
    :description "Get the current URL and page title"
    :inputSchema {:type "object" :properties {}}}
   {:name "page_text"
    :description "Extract all visible text content from the current page (truncated to 50KB)"
    :inputSchema {:type "object" :properties {}}}
   {:name "page_html"
    :description "Get full HTML source of the current page (truncated to 200KB)"
    :inputSchema {:type "object" :properties {}}}
   {:name "query"
    :description "Find the first element matching a selector. Returns {ref, tag, text, attributes}. The ref can be passed to other tools."
    :inputSchema {:type "object"
                  :properties {:selector {:type "string" :description "CSS selector or XPath expression"}}
                  :required ["selector"]}}
   {:name "query_all"
    :description "Find all elements matching a selector (max 50). Returns [{ref, tag, text, attributes}, ...]. Refs can be passed to other tools."
    :inputSchema {:type "object"
                  :properties {:selector {:type "string" :description "CSS selector or XPath expression"}}
                  :required ["selector"]}}
   {:name "get_text"
    :description "Get an element's text content"
    :inputSchema {:type "object"
                  :properties ref-or-selector-props}}
   {:name "get_attribute"
    :description "Get an element's attribute value"
    :inputSchema {:type "object"
                  :properties (assoc ref-or-selector-props
                                     :name {:type "string" :description "Attribute name"})
                  :required ["name"]}}
   {:name "get_html"
    :description "Get an element's inner HTML"
    :inputSchema {:type "object"
                  :properties ref-or-selector-props}}
   {:name "is_visible"
    :description "Check whether an element is visible (displayed and non-zero size)"
    :inputSchema {:type "object"
                  :properties ref-or-selector-props}}
   {:name "click"
    :description "Click an element"
    :inputSchema {:type "object"
                  :properties ref-or-selector-props}}
   {:name "type_text"
    :description "Type text into an input element"
    :inputSchema {:type "object"
                  :properties (assoc ref-or-selector-props
                                     :text {:type "string" :description "Text to type"}
                                     :human {:type "boolean" :description "Type with human-like delays (default false)"})
                  :required ["text"]}}
   {:name "clear"
    :description "Clear the content of an input/textarea field"
    :inputSchema {:type "object"
                  :properties ref-or-selector-props}}
   {:name "hover"
    :description "Move mouse cursor to an element. Use for dropdown menus and CSS :hover states. Prefer execute_js for synthetic events."
    :inputSchema {:type "object"
                  :properties ref-or-selector-props}}
   {:name "press_key"
    :description "Press a key or key combination. Key names: Enter, Escape, Tab, ArrowDown, ArrowUp, Backspace, Delete, Home, End, PageUp, PageDown, F1-F12, or single characters."
    :inputSchema {:type "object"
                  :properties {:key {:type "string" :description "Key name (e.g. Enter, Escape, Tab, a)"}
                               :modifiers {:type "array"
                                           :items {:type "string"}
                                           :description "Modifier keys: Ctrl, Shift, Alt, Meta"}
                               :target_ref {:type "string" :description "Element ref to send key to"}
                               :target_selector {:type "string" :description "CSS/XPath selector to send key to"}}
                  :required ["key"]}}
   {:name "dismiss_alert"
    :description "Dismiss a native JavaScript alert/confirm/prompt dialog. For DOM overlays, use click or execute_js instead."
    :inputSchema {:type "object" :properties {}}}
   {:name "wait"
    :description "Wait for an element condition or sleep. Conditions: visible (default), invisible, enabled."
    :inputSchema {:type "object"
                  :properties {:selector {:type "string" :description "CSS selector or XPath to wait for"}
                               :condition {:type "string" :description "Condition: visible (default), invisible, enabled"}
                               :timeout_ms {:type "integer" :description "Timeout in ms (default 10000)"}
                               :seconds {:type "number" :description "Simple sleep duration (use sparingly)"}}}}
   {:name "scroll"
    :description "Scroll the page. Modes: by selector (into view), by offset (x/y pixels), by position (top/bottom)."
    :inputSchema {:type "object"
                  :properties {:selector {:type "string" :description "CSS selector or XPath — scroll element into view"}
                               :x {:type "integer" :description "Horizontal scroll offset in pixels"}
                               :y {:type "integer" :description "Vertical scroll offset in pixels"}
                               :position {:type "string" :description "Scroll to: top or bottom"}}}}
   {:name "screenshot"
    :description "Save a screenshot of the current page or a specific element"
    :inputSchema {:type "object"
                  :properties {:path {:type "string" :description "File path to save screenshot"}
                               :selector {:type "string" :description "Optional CSS/XPath selector for element screenshot"}}
                  :required ["path"]}}
   {:name "resize"
    :description "Resize the browser viewport"
    :inputSchema {:type "object"
                  :properties {:width {:type "integer" :description "Window width in pixels"}
                               :height {:type "integer" :description "Window height in pixels"}}
                  :required ["width" "height"]}}
   {:name "execute_js"
    :description "Execute JavaScript in the browser. Use 'return' to get a value back. This is the escape hatch for anything not covered by dedicated tools."
    :inputSchema {:type "object"
                  :properties {:script {:type "string" :description "JavaScript code to execute"}}
                  :required ["script"]}}
   {:name "solve_captcha"
    :description "Solve a captcha on the current page using 2captcha.com. Auto-detects reCAPTCHA v2 and hCaptcha. For image captchas, provide the selector for the captcha image element. Returns the solution text for image captchas."
    :inputSchema {:type "object"
                  :properties {:api_key {:type "string" :description "2captcha.com API key"}
                               :type {:type "string" :description "Captcha type: recaptcha_v2, hcaptcha, or image. Auto-detected if omitted."}
                               :selector {:type "string" :description "CSS/XPath for image captcha element (required when type=image)"}}
                  :required ["api_key"]}}])

(defn- url+title [driver]
  (pr-str {:url (e/get-url driver) :title (e/get-title driver)}))

(defn- do-navigate [driver args]
  (e/go driver (:url args))
  (success (url+title driver)))

(defn- do-get-url [driver _args]
  (success (url+title driver)))

(defn- do-page-text [driver _args]
  (let [text (e/js-execute driver "return document.body.innerText || document.body.textContent || ''")]
    (success (truncate (str text) max-text-length))))

(defn- do-page-html [driver _args]
  (let [html (e/js-execute driver "return document.documentElement.outerHTML")]
    (success (truncate (str html) max-html-length))))

(defn- do-wait [driver args]
  (if-let [seconds (:seconds args)]
    (do (Thread/sleep (long (* seconds 1000)))
        (success "Condition met"))
    (if-let [selector (:selector args)]
      (let [sel (parse-selector selector)
            condition (or (:condition args) "visible")
            timeout (or (:timeout_ms args) 10000)
            opts {:timeout (/ timeout 1000)}]
        (case condition
          "visible"   (do (e/wait-visible driver sel opts)   (success "Condition met"))
          "invisible" (do (e/wait-invisible driver sel opts) (success "Condition met"))
          "enabled"   (do (e/wait-enabled driver sel opts)   (success "Condition met"))
          (error (str "Unknown condition: " condition))))
      (error "Either 'selector' or 'seconds' is required"))))

(defn- do-scroll [driver args]
  (cond
    (:selector args)
    (let [sel (parse-selector (:selector args))]
      (e/scroll-query driver sel)
      (success "Scrolled"))

    (and (contains? args :x) (contains? args :y))
    (do (e/scroll driver (:x args) (:y args))
        (success "Scrolled"))

    (:position args)
    (case (:position args)
      "top"    (do (e/js-execute driver "window.scrollTo(0, 0)") (success "Scrolled"))
      "bottom" (do (e/js-execute driver "window.scrollTo(0, document.body.scrollHeight)") (success "Scrolled"))
      (error (str "Unknown position: " (:position args))))

    :else
    (error "Provide selector, x+y offset, or position")))

(defn- do-screenshot [driver args]
  (let [path (:path args)]
    (if-let [selector (:selector args)]
      (let [sel (parse-selector selector)]
        (e/screenshot-element driver sel path))
      (e/screenshot driver path))
    (success (str "Screenshot saved to: " path))))

(defn- do-click [driver args]
  (with-ref-or-selector driver args
    (fn [d sel] (e/click d sel) (success "Clicked element"))
    (fn [d ref] (e/click-el d ref) (success "Clicked element"))))

(defn- do-type-text [driver args]
  (let [text (:text args)
        human? (:human args)]
    (with-ref-or-selector driver args
      (fn [d sel]
        (if human?
          (e/fill-human d sel text)
          (e/fill d sel text))
        (success (str "Typed " (count text) " characters")))
      (fn [d ref]
        (if human?
          (e/fill-human-el d ref text)
          (e/fill-el d ref text))
        (success (str "Typed " (count text) " characters"))))))

(defn- do-clear [driver args]
  (with-ref-or-selector driver args
    (fn [d sel] (e/clear d sel) (success "Cleared element"))
    (fn [d ref] (e/clear-el d ref) (success "Cleared element"))))

(defn- do-hover [driver args]
  (with-ref-or-selector driver args
    (fn [d sel]
      (let [el (e/query d sel)
            input (e/make-mouse-input)
            _ (e/add-pointer-move-to-el input el)
            _ (e/perform-actions d input)]
        (success "Hovered element")))
    (fn [d ref]
      (let [input (e/make-mouse-input)
            _ (e/add-pointer-move-to-el input ref)
            _ (e/perform-actions d input)]
        (success "Hovered element")))))

(def ^:private key-map
  {"Enter"      k/enter
   "Escape"     k/escape
   "Tab"        k/tab
   "Backspace"  k/backspace
   "Delete"     k/delete
   "ArrowUp"    k/arrow-up
   "ArrowDown"  k/arrow-down
   "ArrowLeft"  k/arrow-left
   "ArrowRight" k/arrow-right
   "Home"       k/home
   "End"        k/end
   "PageUp"     k/pageup
   "PageDown"   k/pagedown
   "F1"  k/f1  "F2"  k/f2  "F3"  k/f3  "F4"  k/f4
   "F5"  k/f5  "F6"  k/f6  "F7"  k/f7  "F8"  k/f8
   "F9"  k/f9  "F10" k/f10 "F11" k/f11 "F12" k/f12})

(def ^:private modifier-map
  {"Ctrl"  k/control-left
   "Shift" k/shift-left
   "Alt"   k/alt-left
   "Meta"  k/meta-left})

(defn- resolve-key
  "Resolve a key name to an Etaoin key constant, with optional modifiers."
  [key-name modifiers]
  (let [k (or (get key-map key-name) key-name)]
    (if (seq modifiers)
      (let [mod-keys (mapv #(get modifier-map % %) modifiers)]
        (apply k/chord (concat mod-keys [k])))
      k)))

(defn- do-press-key [driver args]
  (let [key-name (:key args)
        modifiers (:modifiers args)
        target-ref (:target_ref args)
        target-sel (:target_selector args)
        resolved (resolve-key key-name modifiers)]
    (cond
      target-ref      (e/fill-el driver target-ref resolved)
      target-sel      (e/fill driver (parse-selector target-sel) resolved)
      :else           (e/fill driver :active resolved))
    (success (str "Pressed " key-name))))

(defn- do-dismiss-alert [driver _args]
  (if (e/has-alert? driver)
    (do (e/dismiss-alert driver)
        (success "Alert dismissed"))
    (success "No alert present")))

(defn- do-query [driver args]
  (try
    (let [sel (parse-selector (:selector args))
          el (e/query driver sel)]
      (success (pr-str (element->info driver el))))
    (catch Exception _
      (error (str "Element not found: " (:selector args))))))

(defn- do-query-all [driver args]
  (let [sel (parse-selector (:selector args))
        elements (e/query-all driver sel)
        infos (mapv #(element->info driver %) (take max-elements elements))]
    (success (pr-str infos))))

(defn- do-get-text [driver args]
  (with-ref-or-selector driver args
    (fn [d sel] (success (str (e/get-element-text d sel))))
    (fn [d ref] (success (str (e/get-element-text-el d ref))))))

(defn- do-get-attribute [driver args]
  (let [attr-name (keyword (:name args))]
    (with-ref-or-selector driver args
      (fn [d sel] (success (pr-str (e/get-element-attr d sel attr-name))))
      (fn [d ref] (success (pr-str (e/get-element-attr-el d ref attr-name)))))))

(defn- do-get-html [driver args]
  (with-ref-or-selector driver args
    (fn [d sel] (success (truncate (str (e/get-element-inner-html d sel)) max-html-length)))
    (fn [d ref] (success (truncate (str (e/get-element-inner-html-el d ref)) max-html-length)))))

(defn- do-is-visible [driver args]
  (with-ref-or-selector driver args
    (fn [d sel] (success (str (e/visible? d sel))))
    (fn [d ref] (success (str (e/displayed-el? d ref))))))

(defn- do-resize [driver args]
  (let [w (:width args)
        h (:height args)]
    (e/set-window-size driver w h)
    (success (str "Resized to " w "x" h))))

(defn- do-execute-js [driver args]
  (let [script (:script args)
        result (e/js-execute driver script)]
    (success (pr-str result))))

(defn- do-back [driver _args]
  (e/back driver)
  (success (url+title driver)))

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

(def ^:private tool-handlers
  {"navigate"      do-navigate
   "back"          do-back
   "get_url"       do-get-url
   "page_text"     do-page-text
   "page_html"     do-page-html
   "query"         do-query
   "query_all"     do-query-all
   "get_text"      do-get-text
   "get_attribute" do-get-attribute
   "get_html"      do-get-html
   "is_visible"    do-is-visible
   "click"         do-click
   "type_text"     do-type-text
   "clear"         do-clear
   "hover"         do-hover
   "press_key"     do-press-key
   "dismiss_alert" do-dismiss-alert
   "wait"          do-wait
   "scroll"        do-scroll
   "screenshot"    do-screenshot
   "resize"        do-resize
   "execute_js"    do-execute-js
   "solve_captcha" do-solve-captcha})

(defn- with-timeout
  "Run f with a timeout in ms. Returns f's result, or an error map on timeout.
   Exceptions from f propagate to the caller. future-cancel on timeout is advisory only."
  [timeout-ms f]
  (let [fut (future (f))
        result (deref fut timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do (future-cancel fut)
          (error "Tool timed out"))
      result)))

(defn call-tool
  "Execute a browser tool by name. Returns MCP tool result map.
   Arguments may have string or keyword keys; normalized to keywords internally."
  [driver tool-name arguments]
  (let [arguments (update-keys (or arguments {}) keyword)]
  (if-let [handler (get tool-handlers tool-name)]
    (try
      (let [timeout (case tool-name
                      "navigate" navigate-timeout
                      "wait"     (+ (or (:timeout_ms arguments) 10000) 5000)
                      "solve_captcha" 180000
                      default-timeout)]
        (with-timeout timeout #(handler driver arguments)))
      (catch Exception ex
        (error (str "Tool error: " (.getMessage ex)))))
    (error (str "Unknown tool: " tool-name)))))
