package com.bbh.scm

import com.cloudbees.groovy.cps.NonCPS

import java.util.regex.Matcher
import java.util.regex.Pattern

class BitbucketRepository implements Serializable {

    private static final Pattern CLOUD  = Pattern.compile('^https?://(?:[^@/]+@)?(?:api\\.)?bitbucket\\.org/(?:2\\.0/repositories/)?([^/]+)/([^/]+?)(?:\\.git)?(?:/.*)?$')
    private static final Pattern REST   = Pattern.compile('^(https?://.+?)/rest/api/(?:1\\.0|latest)/(projects|users)/([^/]+)/repos/([^/]+).*$')
    private static final Pattern BROWSE = Pattern.compile('^(https?://.+?)/(projects|users)/([^/]+)/repos/([^/?#]+).*$')
    private static final Pattern CLONE  = Pattern.compile('^(https?://)(?:[^@/]+@)?(.+?)/scm/([^/]+)/([^/]+?)(?:\\.git)?$')

    @NonCPS
    static Map parse(Map scmCfg) {
        String url  = ((scmCfg?.url ?: '') as String).trim().replaceAll('/+$', '')
        String type = ((scmCfg?.type ?: (url.toLowerCase().contains('bitbucket.org') ? 'cloud' : 'server')) as String).toLowerCase()
        return type == 'cloud' ? parseCloud(url, scmCfg) : parseServer(url, scmCfg)
    }

    @NonCPS
    private static Map parseCloud(String url, Map scmCfg) {
        Matcher m = CLOUD.matcher(url)
        boolean matched = m.matches()
        String workspace = (scmCfg?.workspace ?: (matched ? m.group(1) : null)) as String
        String slug      = (scmCfg?.repoSlug  ?: (matched ? m.group(2) : null)) as String
        if (!workspace || !slug) {
            throw new IllegalArgumentException("Cannot determine the Bitbucket Cloud repository from scm.bitbucket.url='${url}'. Expected https://bitbucket.org/<workspace>/<repository>")
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

        Matcher rest = REST.matcher(url)
        Matcher browse = BROWSE.matcher(url)
        Matcher clone = CLONE.matcher(url)
        if (rest.matches()) {
            base = rest.group(1); project = projectKey(rest.group(2), rest.group(3)); slug = rest.group(4)
        } else if (browse.matches()) {
            base = browse.group(1); project = projectKey(browse.group(2), browse.group(3)); slug = browse.group(4)
        } else if (clone.matches()) {
            base = clone.group(1) + clone.group(2)
            project = clone.group(3).startsWith('~') ? clone.group(3) : clone.group(3).toUpperCase()
            slug = clone.group(4)
        }
        base    = ((scmCfg?.apiUrl ?: base) as String)?.replaceAll('/+$', '')
        project = (scmCfg?.projectKey ?: project) as String
        slug    = (scmCfg?.repoSlug ?: slug) as String
        if (!base || !project || !slug) {
            throw new IllegalArgumentException("Cannot determine the Bitbucket repository from scm.bitbucket.url='${url}'. Expected https://<host>/projects/<KEY>/repos/<slug> or set projectKey/repoSlug")
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
    private static String projectKey(String kind, String key) {
        return kind == 'users' ? "~${key}".toString() : key
    }
}
