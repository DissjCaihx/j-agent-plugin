package com.jagent.api.version;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version range helper shared by the runtime and framework adapter plugins. */
public final class VersionRange {
    private static final Pattern VERSION = Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)(?:[-+](.*))?$");
    private VersionRange() { }

    public static boolean isValid(String expression) {
        try { validate(expression); return true; }
        catch (IllegalArgumentException error) { return false; }
    }

    public static void validate(String expression) {
        if (expression == null || expression.trim().isEmpty()) throw new IllegalArgumentException("Version range is empty");
        String range = expression.trim();
        if ("*".equals(range) || range.matches("[0-9]+(?:\\.[0-9]+)*(?:\\.[xX]|\\.\\*)")) return;
        if ((range.startsWith("[") || range.startsWith("(")) && (range.endsWith("]") || range.endsWith(")"))) {
            String inner = range.substring(1, range.length() - 1);
            int comma = inner.indexOf(',');
            if (comma < 0) {
                if (range.charAt(0) != '[' || range.charAt(range.length() - 1) != ']') throw new IllegalArgumentException("A single version must use [version]");
                parse(inner.trim());
                return;
            }
            if (inner.indexOf(',', comma + 1) >= 0) throw new IllegalArgumentException("Version range must have one interval");
            String lower = inner.substring(0, comma).trim(), upper = inner.substring(comma + 1).trim();
            if (!lower.isEmpty()) parse(lower);
            else if (range.charAt(0) != '(') throw new IllegalArgumentException("Unbounded lower range must be exclusive");
            if (!upper.isEmpty()) parse(upper);
            else if (range.charAt(range.length() - 1) != ')') throw new IllegalArgumentException("Unbounded upper range must be exclusive");
            return;
        }
        for (String constraint : range.split("\\s+")) {
            if (constraint.contains(",")) {
                for (String part : constraint.split(",")) parseComparator(part.trim());
            } else parseComparator(constraint);
        }
    }

    public static boolean includes(String version, String expression) {
        validate(expression);
        if (version == null || version.trim().isEmpty()) return false;
        String range = expression.trim();
        if ("*".equals(range)) return true;
        if (range.endsWith(".x") || range.endsWith(".X") || range.endsWith(".*")) return version.startsWith(range.substring(0, range.length() - 1));
        if ((range.startsWith("[") || range.startsWith("(")) && (range.endsWith("]") || range.endsWith(")"))) {
            String inner = range.substring(1, range.length() - 1);
            int comma = inner.indexOf(',');
            if (comma < 0) return compare(version, inner.trim()) == 0;
            String lower = inner.substring(0, comma).trim(), upper = inner.substring(comma + 1).trim();
            int low = lower.isEmpty() ? 1 : compare(version, lower);
            int high = upper.isEmpty() ? -1 : compare(version, upper);
            return (lower.isEmpty() || (range.charAt(0) == '[' ? low >= 0 : low > 0))
                    && (upper.isEmpty() || (range.charAt(range.length() - 1) == ']' ? high <= 0 : high < 0));
        }
        for (String constraint : range.split("\\s+")) {
            if (constraint.contains(",")) {
                for (String part : constraint.split(",")) if (!compareConstraint(version, part.trim())) return false;
            } else if (!compareConstraint(version, constraint)) return false;
        }
        return true;
    }

    private static void parseComparator(String value) {
        String version = value.replaceFirst("^(>=|<=|>|<|=)", "");
        if (version.isEmpty()) throw new IllegalArgumentException("Invalid version constraint: " + value);
        parse(version);
    }

    private static boolean compareConstraint(String current, String constraint) {
        String operator = "=";
        String expected = constraint;
        for (String candidate : new String[]{">=", "<=", ">", "<", "="}) {
            if (constraint.startsWith(candidate)) { operator = candidate; expected = constraint.substring(candidate.length()); break; }
        }
        int result = compare(current, expected);
        if (">=".equals(operator)) return result >= 0;
        if ("<=".equals(operator)) return result <= 0;
        if (">".equals(operator)) return result > 0;
        if ("<".equals(operator)) return result < 0;
        return result == 0;
    }

    private static int compare(String left, String right) {
        Version a = parse(left), b = parse(right);
        int count = Math.max(a.parts.length, b.parts.length);
        for (int i = 0; i < count; i++) {
            long x = i < a.parts.length ? a.parts[i] : 0, y = i < b.parts.length ? b.parts[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        if (a.qualifier.isEmpty()) return b.qualifier.isEmpty() ? 0 : 1;
        if (b.qualifier.isEmpty()) return -1;
        return a.qualifier.compareToIgnoreCase(b.qualifier);
    }

    private static Version parse(String value) {
        Matcher matcher = VERSION.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid version: " + value);
        String[] pieces = matcher.group(1).split("\\.");
        long[] parts = new long[pieces.length];
        try { for (int i = 0; i < pieces.length; i++) parts[i] = Long.parseLong(pieces[i]); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid version: " + value, error); }
        return new Version(parts, matcher.group(2) == null ? "" : matcher.group(2));
    }

    private static final class Version {
        final long[] parts; final String qualifier;
        Version(long[] parts, String qualifier) { this.parts = parts; this.qualifier = qualifier; }
    }
}
