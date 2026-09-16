package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

class RestClient implements Serializable {
    private final def script

    RestClient(def script) {
        this.script = script
    }

    def getJson(String url, Map auth, String label) {
        Map r = request('GET', url, null, auth, label)
        ensureSuccess(r, label)
        return parseJson(r.body as String)
    }

    def postJson(String url, def payload, Map auth, String label) {
        Map r = request('POST', url, toJson(payload), auth, label)
        ensureSuccess(r, label)
        return parseJson(r.body as String)
    }

    Map request(String method, String url, String jsonBody, Map auth, String label) {
        String tmpDir = script.env.WORKSPACE_TMP ?: "${script.env.WORKSPACE}@tmp"
        String bodyFile = ''
        if (jsonBody != null) {
            bodyFile = "${tmpDir}/rest-body-${System.nanoTime()}.json"
            script.writeFile(file: bodyFile, text: jsonBody, encoding: 'UTF-8')
        }
        String bodyArgs = bodyFile ? "-H 'Content-Type: application/json' --data-binary @'${BuildUtils.escapeForSingleQuotes(bodyFile)}'" : ''
        String out
        try {
            out = script.sh(label: label ?: "HTTP ${method}", returnStdout: true, script: """#!/bin/bash
set +x
set -euo pipefail
curl -sS -X '${method}' ${authArgs(auth)} -H 'Accept: application/json' ${bodyArgs} -w '\\n%{http_code}' '${BuildUtils.escapeForSingleQuotes(url)}'
""")
        } finally {
            if (bodyFile) script.sh(label: 'Remove request body', script: "rm -f '${BuildUtils.escapeForSingleQuotes(bodyFile)}'")
        }
        return splitStatus(out)
    }

    void ensureSuccess(Map response, String label) {
        int status = (response.status ?: 0) as int
        if (status < 200 || status > 299) {
            script.error("[HTTP] ${label ?: 'Request'} failed with HTTP ${status}: ${abbreviate(response.body as String, 500)}")
        }
    }

    @NonCPS
    static def parseJson(String text) {
        if (!text?.trim()) return null
        return new JsonSlurperClassic().parseText(text)
    }

    @NonCPS
    static String toJson(def payload) {
        return JsonOutput.toJson(payload)
    }

    @NonCPS
    static String urlEncode(String value) {
        return URLEncoder.encode(value ?: '', 'UTF-8').replace('+', '%20')
    }

    @NonCPS
    static String abbreviate(String s, int max) {
        if (s == null) return ''
        return s.length() > max ? s.substring(0, max) + '...' : s
    }

    @NonCPS
    private static String authArgs(Map auth) {
        if (!auth) return ''
        if (auth.type == 'bearer') return "-H \"Authorization: Bearer \$${auth.tokenVar}\""
        return "-u \"\$${auth.userVar}:\$${auth.passVar}\""
    }

    @NonCPS
    private static Map splitStatus(String out) {
        String text = out ?: ''
        while (text.endsWith('\n') || text.endsWith('\r')) text = text.substring(0, text.length() - 1)
        int idx = text.lastIndexOf('\n')
        String code = (idx >= 0 ? text.substring(idx + 1) : text).trim()
        String body = idx >= 0 ? text.substring(0, idx) : ''
        return [status: (code ==~ /\d+/) ? Integer.parseInt(code) : 0, body: body]
    }
}
