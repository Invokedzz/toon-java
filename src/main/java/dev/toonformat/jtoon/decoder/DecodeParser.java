package dev.toonformat.jtoon.decoder;

import dev.toonformat.jtoon.DecodeOptions;
import dev.toonformat.jtoon.PathExpansion;
import dev.toonformat.jtoon.util.StringEscaper;

import java.util.*;
import java.util.regex.Matcher;

import static dev.toonformat.jtoon.util.Headers.KEYED_ARRAY_PATTERN;

/**
 * Inner parser class managing line-by-line parsing state.
 * Maintains currentLine index and uses recursive descent for nested structures.
 */
public class DecodeParser {

    private final DecodeContext context = new DecodeContext();

    DecodeParser(String toon, DecodeOptions options) {
        this.context.lines = toon.split("\n", -1);
        this.context.options = options;
        this.context.delimiter = options.delimiter().getValue();
    }

        /**
         * Parses the current line at root level (depth 0).
         * Routes to appropriate handler based online content.
         */
        Object parseValue() {
            if (context.currentLine >= context.lines.length) {
                return null;
            }

            String line = context.lines[context.currentLine];
            int depth = DecodeHelper.getDepth(line, context);

            if (depth > 0) {
                return handleUnexpectedIndentation();
            }

            String content = line.substring(depth * context.options.indent());

            // Handle standalone arrays: [2]:
            if (content.startsWith("[")) {
                return ArrayDecoder.parseArray(content, depth, context);
            }

            // Handle keyed arrays: items[2]{id,name}:
            Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(content);
            if (keyedArray.matches()) {
                return parseKeyedArrayValue(keyedArray, content, depth);
            }

            // Handle key-value pairs: name: Ada
            int colonIdx = DecodeHelper.findUnquotedColon(content);
            if (colonIdx > 0) {
                String key = content.substring(0, colonIdx).trim();
                String value = content.substring(colonIdx + 1).trim();
                return parseKeyValuePair(key, value, depth, depth == 0);
            }

            // Bare scalar value
            return parseBareScalarValue(content, depth);
        }

    /**
     * Handles unexpected indentation at root level.
     */
    private Object handleUnexpectedIndentation() {
        if (context.options.strict()) {
            throw new IllegalArgumentException("Unexpected indentation at line " + context.currentLine);
        }
        return null;
    }

    /**
     * Parses a keyed array value (e.g., "items[2]{id,name}:").
     */
    private Object parseKeyedArrayValue(Matcher keyedArray, String content, int depth) {
        String originalKey = keyedArray.group(1).trim();
        String key = StringEscaper.unescape(originalKey);
        String arrayHeader = content.substring(keyedArray.group(1).length());

        var arrayValue = ArrayDecoder.parseArray(arrayHeader, depth, context);
        Map<String, Object> obj = new LinkedHashMap<>();

        // Handle path expansion for array keys
        if (shouldExpandKey(originalKey)) {
            expandPathIntoMap(obj, key, arrayValue);
        } else {
            // Check for conflicts with existing expanded paths
            checkPathExpansionConflict(obj, key, arrayValue);
            obj.put(key, arrayValue);
        }

        // Continue parsing root-level fields if at depth 0
        if (depth == 0) {
            parseRootObjectFields(obj, depth);
        }

        return obj;
    }

    /**
     * Parses a bare scalar value and validates in strict mode.
     */
    private Object parseBareScalarValue(String content, int depth) {
        Object result = PrimitiveDecoder.parse(content);
        context.currentLine++;

        // In strict mode, check if there are more primitives at root level
        if (context.options.strict() && depth == 0) {
            validateNoMultiplePrimitivesAtRoot();
        }

        return result;
    }

    /**
     * Validates that there are no multiple primitives at root level in strict mode.
     */
    private void validateNoMultiplePrimitivesAtRoot() {
        int lineIndex = context.currentLine;
        while (lineIndex < context.lines.length && DecodeHelper.isBlankLine(context.lines[lineIndex])) {
            lineIndex++;
        }
        if (lineIndex < context.lines.length) {
            int nextDepth = DecodeHelper.getDepth(context.lines[lineIndex], context);
            if (nextDepth == 0) {
                throw new IllegalArgumentException(
                    "Multiple primitives at root depth in strict mode at line " + (lineIndex + 1));
            }
        }
    }

    /**
     * Parses additional key-value pairs at root level.
     */
    private void parseRootObjectFields(Map<String, Object> obj, int depth) {
        while (context.currentLine < context.lines.length) {
            String line = context.lines[context.currentLine];
            int lineDepth = DecodeHelper.getDepth(line, context);

            if (lineDepth != depth) {
                return;
            }

            // Skip blank lines
            if (DecodeHelper.isBlankLine(line)) {
                context.currentLine++;
                continue;
            }

            String content = line.substring(depth * context.options.indent());

            Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(content);
            if (keyedArray.matches()) {
                processRootKeyedArrayLine(obj, content, keyedArray, depth);
            } else {
                int colonIdx = DecodeHelper.findUnquotedColon(content);
                if (colonIdx > 0) {
                    String key = content.substring(0, colonIdx).trim();
                    String value = content.substring(colonIdx + 1).trim();

                    parseKeyValuePairIntoMap(obj, key, value, depth);
                } else {
                    return;
                }
            }
        }
    }

    /**
     * Processes a keyed array line in root object fields.
     */
    private void processRootKeyedArrayLine(Map<String, Object> obj, String content, Matcher keyedArray, int depth) {
        String originalKey = keyedArray.group(1).trim();
        String key = StringEscaper.unescape(originalKey);
        String arrayHeader = content.substring(keyedArray.group(1).length());

        var arrayValue = ArrayDecoder.parseArray(arrayHeader, depth, context);

        // Handle path expansion for array keys
        if (shouldExpandKey(originalKey)) {
            expandPathIntoMap(obj, key, arrayValue);
        } else {
            // Check for conflicts with existing expanded paths
            checkPathExpansionConflict(obj, key, arrayValue);
            obj.put(key, arrayValue);
        }
    }

    /**
     * Parses nested object starting at currentLine.
     */
    private Map<String, Object> parseNestedObject(int parentDepth) {
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
                processDirectChildLine(result, line, parentDepth, depth);
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
    private void processDirectChildLine(Map<String, Object> result, String line, int parentDepth, int depth) {
        // Skip blank lines
        if (DecodeHelper.isBlankLine(line)) {
            context.currentLine++;
            return;
        }

        String content = line.substring((parentDepth + 1) * context.options.indent());
        Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(content);

        if (keyedArray.matches()) {
            processKeyedArrayLine(result, content, keyedArray, parentDepth);
        } else {
            processKeyValueLine(result, content, depth);
        }
    }

    /**
     * Processes a keyed array line (e.g., "key[3]: value").
     */
    private void processKeyedArrayLine(Map<String, Object> result, String content, Matcher keyedArray,
                                       int parentDepth) {
        String originalKey = keyedArray.group(1).trim();
        String key = StringEscaper.unescape(originalKey);
        String arrayHeader = content.substring(keyedArray.group(1).length());
        List<Object> arrayValue = ArrayDecoder.parseArray(arrayHeader, parentDepth + 1, context);

        // Handle path expansion for array keys
        if (shouldExpandKey(originalKey)) {
            expandPathIntoMap(result, key, arrayValue);
        } else {
            // Check for conflicts with existing expanded paths
            checkPathExpansionConflict(result, key, arrayValue);
            result.put(key, arrayValue);
        }
    }

    /**
     * Processes a key-value line (e.g., "key: value").
     */
    private void processKeyValueLine(Map<String, Object> result, String content, int depth) {
        int colonIdx = DecodeHelper.findUnquotedColon(content);

        if (colonIdx > 0) {
            String key = content.substring(0, colonIdx).trim();
            String value = content.substring(colonIdx + 1).trim();
            parseKeyValuePairIntoMap(result, key, value, depth);
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
    private boolean shouldExpandKey(String key) {
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
    private void expandPathIntoMap(Map<String, Object> map, String dottedKey, Object value) {
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
    private Object parseKeyValue(String value, int depth) {
        // Check if next line is nested (deeper indentation)
        if (context.currentLine + 1 < context.lines.length) {
            int nextDepth = DecodeHelper.getDepth(context.lines[context.currentLine + 1], context);
            if (nextDepth > depth) {
                context.currentLine++;
                // parseNestedObject manages currentLine, so we don't increment here
                return parseNestedObject(depth);
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
    private void putKeyValueIntoMap(Map<String, Object> map, String originalKey, String unescapedKey,
                                    Object value) {
        // Handle path expansion
        if (shouldExpandKey(originalKey)) {
            expandPathIntoMap(map, unescapedKey, value);
        } else {
            checkPathExpansionConflict(map, unescapedKey, value);
            map.put(unescapedKey, value);
        }
    }

    /**
     * Parses a key-value pair at root level, creating a new Map.
     */
    private Object parseKeyValuePair(String key, String value, int depth, boolean parseRootFields) {
        Map<String, Object> obj = new LinkedHashMap<>();
        parseKeyValuePairIntoMap(obj, key, value, depth);

        if (parseRootFields) {
            parseRootObjectFields(obj, depth);
        }
        return obj;
    }

    /**
     * Parses a key-value pair and adds it to an existing map.
     */
    private void parseKeyValuePairIntoMap(Map<String, Object> map, String key, String value, int depth) {
        String unescapedKey = StringEscaper.unescape(key);

        Object parsedValue = parseKeyValue(value, depth);
        putKeyValueIntoMap(map, key, unescapedKey, parsedValue);
    }

    /**
     * Checks for path expansion conflicts when setting a non-expanded key.
     * In strict mode, throws if the key conflicts with an existing expanded path.
     */
    private void checkPathExpansionConflict(Map<String, Object> map, String key, Object value) {
        if (!context.options.strict()) {
            return;
        }

        Object existing = map.get(key);
        DecodeHelper.checkFinalValueConflict(key, existing, value, context);
    }
}
