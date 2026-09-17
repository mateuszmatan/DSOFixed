package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class VersionUtils implements Serializable {

    private static final List RELEASE_QUALIFIERS = ['final', 'release', 'ga']

    @NonCPS
    static int compare(String a, String b) {
        List ta = tokenize(a)
        List tb = tokenize(b)
        int size = Math.max(ta.size(), tb.size())
        for (int i = 0; i < size; i++) {
            def x = i < ta.size() ? ta[i] : null
            def y = i < tb.size() ? tb[i] : null
            int result = compareToken(x, y)
            if (result != 0) return result
        }
        return 0
    }

    @NonCPS
    static boolean isUpgrade(String current, String target) {
        if (!current || !target) return false
        return compare(current, target) < 0
    }

    @NonCPS
    static boolean isConcreteVersion(String value) {
        return value != null && (value.trim() ==~ /^[vV]?\d[0-9A-Za-z.\-_+]*$/)
    }

    @NonCPS
    static String max(String a, String b) {
        if (!a) return b
        if (!b) return a
        return compare(a, b) >= 0 ? a : b
    }

    @NonCPS
    private static List tokenize(String version) {
        List out = []
        if (!version) return out
        version.trim().replaceFirst(/^[vV]/, '').split(/[.\-_+]/).each { String part ->
            if (part) {
                def matcher = (part =~ /(\d+|[^\d]+)/)
                while (matcher.find()) {
                    String token = matcher.group(1)
                    if (token ==~ /\d+/) {
                        out << [true, token]
                    } else if (!RELEASE_QUALIFIERS.contains(token.toLowerCase())) {
                        out << [false, token.toLowerCase()]
                    }
                }
            }
        }
        return out
    }

    @NonCPS
    private static int compareToken(def x, def y) {
        if (x == null && y == null) return 0
        if (x == null) return y[0] ? (isZero(y[1] as String) ? 0 : -1) : 1
        if (y == null) return -compareToken(y, x)
        boolean xNumeric = x[0] as boolean
        boolean yNumeric = y[0] as boolean
        if (xNumeric && yNumeric) return compareNumeric(x[1] as String, y[1] as String)
        if (xNumeric) return 1
        if (yNumeric) return -1
        return (x[1] as String).compareTo(y[1] as String)
    }

    @NonCPS
    private static int compareNumeric(String a, String b) {
        String x = stripLeadingZeros(a)
        String y = stripLeadingZeros(b)
        if (x.length() != y.length()) return x.length() < y.length() ? -1 : 1
        return x.compareTo(y)
    }

    @NonCPS
    private static String stripLeadingZeros(String value) {
        String out = value.replaceFirst(/^0+/, '')
        return out ? out : '0'
    }

    @NonCPS
    private static boolean isZero(String value) {
        return value ==~ /0+/
    }
}
