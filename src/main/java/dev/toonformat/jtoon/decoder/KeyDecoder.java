package dev.toonformat.jtoon.decoder;

import dev.toonformat.jtoon.PathExpansion;
import dev.toonformat.jtoon.util.StringEscaper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static dev.toonformat.jtoon.util.Headers.KEYED_ARRAY_PATTERN;

public class KeyDecoder {

    private KeyDecoder() { throw new UnsupportedOperationException("Utility class cannot be instantiated"); }

    /**
     * Processes a keyed array line (e.g., "key[3]: value").
     */
    protected static void processKeyedArrayLine(Map<String, Object> result, String content, Matcher keyedArray,
                                       int parentDepth, DecodeContext context) {
        String originalKey = keyedArray.group(1).trim();
        String key = StringEscaper.unescape(originalKey);
        String arrayHeader = content.substring(keyedArray.group(1).length());
        List<Object> arrayValue = ArrayDecoder.parseArray(arrayHeader, parentDepth + 1, context);

        // Handle path expansion for array keys
        if (shouldExpandKey(originalKey, context)) {
            expandPathIntoMap(result, key, arrayValue, context);
        } else {
            // Check for conflicts with existing expanded paths
            DecodeHelper.checkPathExpansionConflict(result, key, arrayValue, context);
            result.put(key, arrayValue);
        }
    }

    /**
     * Processes a key-value line (e.g., "key: value").
     */
    protected static void processKeyValueLine(Map<String, Object> result, String content, int depth, DecodeContext context) {
        int colonIdx = DecodeHelper.findUnquotedColon(content);

        if (colonIdx > 0) {
            String key = content.substring(0, colonIdx).trim();
            String value = content.substring(colonIdx + 1).trim();
            parseKeyValuePairIntoMap(result, key, value, depth, context);
        } else {
            // No colon found in key-value context - this is an error
            if (context.options.strict()) {
                throw new IllegalArgumentException(
                    "Missing colon in key-value context at line " + (context.currentLine + 1));
            }
            context.currentLine++;
        }
    }

    /**
     * Checks if a key should be expanded (is a valid identifier segment).
     * Keys with dots that are valid identifiers can be expanded.
     * Quoted keys are never expanded.
     */
    protected static boolean shouldExpandKey(String key, DecodeContext context) {
        if (context.options.expandPaths() != PathExpansion.SAFE) {
            return false;
        }
        // Quoted keys should not be expanded
        if (key.trim().startsWith("\"") && key.trim().endsWith("\"")) {
            return false;
        }
        // Check if key contains dots and is a valid identifier pattern
        if (!key.contains(".")) {
            return false;
        }
        // Valid identifier: starts with letter or underscore, followed by letters,
        // digits, underscores
        // Each segment must match this pattern
        String[] segments = key.split("\\.");
        for (String segment : segments) {
            if (!segment.matches("^[a-zA-Z_]\\w*$")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Expands a dotted key into nested object structure.
     */
    protected static void expandPathIntoMap(Map<String, Object> map, String dottedKey, Object value, DecodeContext context) {
        String[] segments = dottedKey.split("\\.");
        Map<String, Object> current = map;

        // Navigate/create nested structure
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            Object existing = current.get(segment);

            if (existing == null) {
                // Create new nested object
                Map<String, Object> nested = new LinkedHashMap<>();
                current.put(segment, nested);
                current = nested;
            } else if (existing instanceof Map) {
                // Use existing nested object
                @SuppressWarnings("unchecked")
                Map<String, Object> existingMap = (Map<String, Object>) existing;
                current = existingMap;
            } else {
                // Conflict: existing is not a Map
                if (context.options.strict()) {
                    throw new IllegalArgumentException(
                        String.format("Path expansion conflict: %s is %s, cannot expand to object",
                            segment, existing.getClass().getSimpleName()));
                }
                // LWW: overwrite with new nested object
                Map<String, Object> nested = new LinkedHashMap<>();
                current.put(segment, nested);
                current = nested;
            }
        }

        // Set final value
        String finalSegment = segments[segments.length - 1];
        Object existing = current.get(finalSegment);

        DecodeHelper.checkFinalValueConflict(finalSegment, existing, value, context);

        // LWW: last write wins (always overwrite in non-strict, or if types match in
        // strict)
        current.put(finalSegment, value);
    }

    /**
     * Parses a key-value string into an Object, handling nested objects, empty
     * values, and primitives.
     *
     * @param value the value string to parse
     * @param depth the depth at which the key-value pair is located
     * @return the parsed value (Map, List, or primitive)
     */
    private static Object parseKeyValue(String value, int depth, DecodeContext context) {
        // Check if next line is nested (deeper indentation)
        if (context.currentLine + 1 < context.lines.length) {
            int nextDepth = DecodeHelper.getDepth(context.lines[context.currentLine + 1], context);
            if (nextDepth > depth) {
                context.currentLine++;
                // parseNestedObject manages currentLine, so we don't increment here
                return parseNestedObject(depth, context);
            } else {
                // If value is empty, create empty object; otherwise parse as primitive
                Object parsedValue;
                if (value.trim().isEmpty()) {
                    parsedValue = new LinkedHashMap<>();
                } else {
                    parsedValue = PrimitiveDecoder.parse(value);
                }
                context.currentLine++;
                return parsedValue;
            }
        } else {
            // If value is empty, create empty object; otherwise parse as primitive
            Object parsedValue;
            if (value.trim().isEmpty()) {
                parsedValue = new LinkedHashMap<>();
            } else {
                parsedValue = PrimitiveDecoder.parse(value);
            }
            context.currentLine++;
            return parsedValue;
        }
    }

    /**
     * Puts a key-value pair into a map, handling path expansion.
     *
     * @param map          the map to put the key-value pair into
     * @param originalKey  the original key before being unescaped (used for path
     *                     expansion check)
     * @param unescapedKey the unescaped key
     * @param value        the value to put
     */
    private static void putKeyValueIntoMap(Map<String, Object> map, String originalKey, String unescapedKey,
                                    Object value, DecodeContext context) {
        // Handle path expansion
        if (shouldExpandKey(originalKey, context)) {
            expandPathIntoMap(map, unescapedKey, value, context);
        } else {
            DecodeHelper.checkPathExpansionConflict(map, unescapedKey, value, context);
            map.put(unescapedKey, value);
        }
    }

    /**
     * Parses a key-value pair and adds it to an existing map.
     */
    protected static void parseKeyValuePairIntoMap(Map<String, Object> map, String key, String value,
                                                 int depth, DecodeContext context) {
        String unescapedKey = StringEscaper.unescape(key);

        Object parsedValue = parseKeyValue(value, depth, context);
        putKeyValueIntoMap(map, key, unescapedKey, parsedValue, context);
    }

    /**
     * Parses nested object starting at currentLine.
     */
    protected static Map<String, Object> parseNestedObject(int parentDepth, DecodeContext context) {
        Map<String, Object> result = new LinkedHashMap<>();

        while (context.currentLine < context.lines.length) {
            String line = context.lines[context.currentLine];

            // Skip blank lines
            if (DecodeHelper.isBlankLine(line)) {
                context.currentLine++;
                continue;
            }

            int depth = DecodeHelper.getDepth(line, context);

            if (depth <= parentDepth) {
                return result;
            }

            if (depth == parentDepth + 1) {
                processDirectChildLine(result, line, parentDepth, depth, context);
            } else {
                context.currentLine++;
            }
        }

        return result;
    }

    /**
     * Processes a line at depth == parentDepth + 1 (direct child).
     * Returns true if the line was processed, false if it was a blank line that was
     * skipped.
     */
    private static void processDirectChildLine(Map<String, Object> result, String line, int parentDepth, int depth, DecodeContext context) {
        // Skip blank lines
        if (DecodeHelper.isBlankLine(line)) {
            context.currentLine++;
            return;
        }

        String content = line.substring((parentDepth + 1) * context.options.indent());
        Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(content);

        if (keyedArray.matches()) {
            KeyDecoder.processKeyedArrayLine(result, content, keyedArray, parentDepth, context);
        } else {
            KeyDecoder.processKeyValueLine(result, content, depth, context);
        }
    }

}
