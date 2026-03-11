#!/usr/bin/env bb

(require '[browser-server-mcp.server :as server])

(apply server/-main *command-line-args*)
