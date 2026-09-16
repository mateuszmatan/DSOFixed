package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class VersionUtils implements Serializable {

    private static final Set<String> RELEASE_QUALIFIERS = ['final', 'release', 'ga'] as Set

    @NonCPS
    static int compare(String a, String b) {
        List ta = tokenize(a)
        List tb = tokenize(b)
        int n = Math.max(ta.size(), tb.size())
        for (int i = 0; i < n; i++) {
            def x = i < ta.size() ? ta[i] : null
            def y = i < tb.size() ? tb[i] : null
            int c = compareToken(x, y)
            if (c != 0) return c
        }
        return 0
    }

    @NonCPS
    static boolean isUpgrade(String current, String target) {
        if (!current || !target) return false
        return compare(current, target) < 0
    }

    @NonCPS
    static boolean isConcreteVersion(String v) {
        return v != null && (v.trim() ==~ /^[vV]?\d[0-9A-Za-z.\-_+]*$/)
    }

    @NonCPS
    static String max(String a, String b) {
        if (!a) return b
        if (!b) return a
        return compare(a, b) >= 0 ? a : b
    }

    @NonCPS
    private static List tokenize(String v) {
        List out = []
        if (!v) return out
        String[] parts = v.trim().replaceFirst(/^[vV]/, '').split(/[.\-_+]/)
        for (String part : parts) {
            if (!part) continue
            def m = (part =~ /(\d+|[^\d]+)/)
            while (m.find()) {
                String p = m.group(1)
                if (p ==~ /\d+/) {
                    out << new BigInteger(p)
                } else if (!RELEASE_QUALIFIERS.contains(p.toLowerCase())) {
                    out << p.toLowerCase()
                }
            }
        }
        return out
    }

    @NonCPS
    private static int compareToken(def x, def y) {
        if (x == null && y == null) return 0
        if (x == null) {
            if (y instanceof BigInteger) return ((BigInteger) y).signum() == 0 ? 0 : -1
            return 1
        }
        if (y == null) return -compareToken(y, x)
        boolean xn = x instanceof BigInteger
        boolean yn = y instanceof BigInteger
        if (xn && yn) return ((BigInteger) x).compareTo((BigInteger) y)
        if (xn) return 1
        if (yn) return -1
        return ((String) x).compareTo((String) y)
    }
}
