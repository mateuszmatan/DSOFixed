package com.bbh.scm

import com.cloudbees.groovy.cps.NonCPS

class BitbucketRepository implements Serializable {

    @NonCPS
    private static String cloudRe() {
        String re = /^https?:\/\/(?:[^@\/]+@)?(?:api\.)?bitbucket\.org\/(?:2\.0\/repositories\/)?([^\/]+)\/([^\/]+?)(?:\.git)?(?:\/.*)?$/
        return re
    }

    @NonCPS
    private static String restRe() {
        String re = /^(https?:\/\/.+?)\/rest\/api\/(?:1\.0|latest)\/(projects|users)\/([^\/]+)\/repos\/([^\/]+).*$/
        return re
    }

    @NonCPS
    private static String browseRe() {
        String re = /^(https?:\/\/.+?)\/(projects|users)\/([^\/]+)\/repos\/([^\/?#]+).*$/
        return re
    }

    @NonCPS
    private static String cloneRe() {
        String re = /^(https?:\/\/)(?:[^@\/]+@)?(.+?)\/scm\/([^\/]+)\/([^\/]+?)(?:\.git)?$/
        return re
    }

    @NonCPS
    static Map parse(Map scmCfg) {
        String url = ((scmCfg?.url ?: '') as String).trim().replaceAll('/+$', '')
        String type = ((scmCfg?.type ?: (url.toLowerCase().contains('bitbucket.org') ? 'cloud' : 'server')) as String).toLowerCase()
        return type == 'cloud' ? parseCloud(url, scmCfg) : parseServer(url, scmCfg)
    }

    @NonCPS
    private static Map parseCloud(String url, Map scmCfg) {
        List groups = firstMatch(url, cloudRe())
        String workspace = (scmCfg?.workspace ?: (groups ? groups[1] : null)) as String
        String slug      = (scmCfg?.repoSlug ?: (groups ? groups[2] : null)) as String
        if (!workspace || !slug) {
            return [error: "Cannot determine the Bitbucket Cloud repository from scm.bitbucket.url='${url}'. Expected https://bitbucket.org/<workspace>/<repository>".toString()]
        }
        return [
                type     : 'cloud',
                workspace: workspace,
                repoSlug : slug,
                apiBase  : ((scmCfg?.apiUrl ?: 'https://api.bitbucket.org/2.0') as String).replaceAll('/+$', ''),
                webUrl   : "https://bitbucket.org/${workspace}/${slug}".toString(),
                cloneUrl : (scmCfg?.cloneUrl ?: "https://bitbucket.org/${workspace}/${slug}.git").toString()
        ]
    }

    @NonCPS
    private static Map parseServer(String url, Map scmCfg) {
        String base = null
        String project = null
        String slug = null

        List rest = firstMatch(url, restRe())
        List browse = firstMatch(url, browseRe())
        List clone = firstMatch(url, cloneRe())
        if (rest) {
            base = rest[1]; project = projectKey(rest[2] as String, rest[3] as String); slug = rest[4]
        } else if (browse) {
            base = browse[1]; project = projectKey(browse[2] as String, browse[3] as String); slug = browse[4]
        } else if (clone) {
            base = (clone[1] as String) + (clone[2] as String)
            project = (clone[3] as String).startsWith('~') ? clone[3] as String : (clone[3] as String).toUpperCase()
            slug = clone[4]
        }

        base    = ((scmCfg?.apiUrl ?: base) as String)?.replaceAll('/+$', '')
        project = (scmCfg?.projectKey ?: project) as String
        slug    = (scmCfg?.repoSlug ?: slug) as String
        if (!base || !project || !slug) {
            return [error: "Cannot determine the Bitbucket repository from scm.bitbucket.url='${url}'. Expected https://<host>/projects/<KEY>/repos/<slug> or set projectKey and repoSlug".toString()]
        }

        String webPath = project.startsWith('~') ? "users/${project.substring(1)}" : "projects/${project}"
        return [
                type      : 'server',
                baseUrl   : base,
                projectKey: project,
                repoSlug  : slug,
                apiBase   : "${base}/rest/api/1.0/projects/${project}/repos/${slug}".toString(),
                webUrl    : "${base}/${webPath}/repos/${slug}".toString(),
                cloneUrl  : (scmCfg?.cloneUrl ?: "${base}/scm/${project.toLowerCase()}/${slug}.git").toString()
        ]
    }

    @NonCPS
    private static List firstMatch(String text, String regex) {
        def matcher = (text ?: '') =~ regex
        return matcher ? (matcher[0] as List) : null
    }

    @NonCPS
    private static String projectKey(String kind, String key) {
        return kind == 'users' ? "~${key}".toString() : key
    }
}
