#!/usr/bin/env bb

;; Resolve project root relative to this script
(def project-dir
  (-> (System/getProperty "babashka.file")
      (java.io.File.)
      (.getCanonicalFile)
      (.getParentFile)   ; bin/
      (.getParentFile)   ; project root
      (.getPath)))

;; Load deps from project bb.edn and add src to classpath
(let [bb-edn (read-string (slurp (str project-dir "/bb.edn")))]
  (babashka.deps/add-deps bb-edn))
(babashka.classpath/add-classpath (str project-dir "/src"))

(require '[browser-server-mcp.server :as server])

(apply server/-main *command-line-args*)
