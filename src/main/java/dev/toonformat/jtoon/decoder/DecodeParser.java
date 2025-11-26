package dev.toonformat.jtoon.decoder;

import dev.toonformat.jtoon.DecodeOptions;
import dev.toonformat.jtoon.PathExpansion;
import dev.toonformat.jtoon.util.StringEscaper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.toonformat.jtoon.util.Headers.ARRAY_HEADER_PATTERN;
import static dev.toonformat.jtoon.util.Headers.KEYED_ARRAY_PATTERN;

/**
 * Inner parser class managing line-by-line parsing state.
 * Maintains currentLine index and uses recursive descent for nested structures.
 */
public class DecodeParser {

  //  private final String[] lines;
  //  private final DecodeOptions options;
  //  private final String delimiter;
  //  private int currentLine = 0;
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
            int depth = getDepth(line);

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
            int colonIdx = findUnquotedColon(content);
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
        while (lineIndex < context.lines.length && isBlankLine(context.lines[lineIndex])) {
            lineIndex++;
        }
        if (lineIndex < context.lines.length) {
            int nextDepth = getDepth(context.lines[lineIndex]);
            if (nextDepth == 0) {
                throw new IllegalArgumentException(
                    "Multiple primitives at root depth in strict mode at line " + (lineIndex + 1));
            }
        }
    }

    /**
     * Extracts delimiter from array header.
     * Returns tab, pipe, or comma (default) based on header pattern.
     */
    private String extractDelimiterFromHeader(String header) {
        Matcher matcher = ARRAY_HEADER_PATTERN.matcher(header);
        if (matcher.find()) {
            String delimChar = matcher.group(3);
            if (delimChar != null) {
                if ("\t".equals(delimChar)) {
                    return "\t";
                } else if ("|".equals(delimChar)) {
                    return "|";
                }
            }
        }
        // Default to comma
        return context.delimiter;
    }

    /**
     * Checks if a line is blank (empty or only whitespace).
     */
    private boolean isBlankLine(String line) {
        return line.trim().isEmpty();
    }

    /**
     * Parses list array format where items are prefixed with "- ".
     * Example: items[2]:\n - item1\n - item2
     */
    protected List<Object> parseListArray(int depth, String header, DecodeContext context) {
        List<Object> result = new ArrayList<>();
        context.currentLine++;

        boolean shouldContinue = true;
        while (shouldContinue && context.currentLine < context.lines.length) {
            String line = context.lines[context.currentLine];

            if (isBlankLine(line)) {
                if (handleBlankLineInListArray(depth, context)) {
                    shouldContinue = false;
                }
            } else {
                int lineDepth = getDepth(line);
                if (shouldTerminateListArray(lineDepth, depth, line)) {
                    shouldContinue = false;
                } else {
                    processListArrayItem(line, lineDepth, depth, result);
                }
            }
        }

        if (header != null) {
            ArrayDecoder.validateArrayLength(header, result.size());
        }
        return result;
    }

    /**
     * Finds the next non-blank line starting from the given index.
     */
    private int findNextNonBlankLine(int startIndex, DecodeContext context) {
        int index = startIndex;
        while (index < context.lines.length && isBlankLine(context.lines[index])) {
            index++;
        }
        return index;
    }

    /**
     * Handles blank line processing in list array.
     * Returns true if array should terminate, false if line should be skipped.
     */
    private boolean handleBlankLineInListArray(int depth, DecodeContext context) {
        int nextNonBlankLine = findNextNonBlankLine(context.currentLine + 1, context);

        if (nextNonBlankLine >= context.lines.length) {
            return true; // End of file - terminate array
        }

        int nextDepth = getDepth(context.lines[nextNonBlankLine]);
        if (nextDepth <= depth) {
            return true; // Blank line is outside array - terminate
        }

        // Blank line is inside array
        if (context.options.strict()) {
            throw new IllegalArgumentException("Blank line inside list array at line " + (context.currentLine + 1));
        }
        // In non-strict mode, skip blank lines
        context.currentLine++;
        return false;
    }

    /**
     * Determines if list array parsing should terminate based online depth.
     * Returns true if array should terminate, false otherwise.
     */
    private boolean shouldTerminateListArray(int lineDepth, int depth, String line) {
        if (lineDepth < depth + 1) {
            return true; // Line depth is less than expected - terminate
        }
        // Also terminate if line is at expected depth but doesn't start with "-"
        if (lineDepth == depth + 1) {
            String content = line.substring((depth + 1) * context.options.indent());
            return !content.startsWith("-"); // Not an array item - terminate
        }
        return false;
    }

    /**
     * Processes a single list array item if it matches the expected depth.
     */
    private void processListArrayItem(String line, int lineDepth, int depth, List<Object> result) {
        if (lineDepth == depth + 1) {
            String content = line.substring((depth + 1) * context.options.indent());

            if (content.startsWith("-")) {
                result.add(ListItemDecoder.parseListItem(content, depth, context));
            } else {
                context.currentLine++;
            }
        } else {
            context.currentLine++;
        }
    }

    /**
     * Parses additional key-value pairs at root level.
     */
    private void parseRootObjectFields(Map<String, Object> obj, int depth) {
        while (context.currentLine < context.lines.length) {
            String line = context.lines[context.currentLine];
            int lineDepth = getDepth(line);

            if (lineDepth != depth) {
                return;
            }

            // Skip blank lines
            if (isBlankLine(line)) {
                context.currentLine++;
                continue;
            }

            String content = line.substring(depth * context.options.indent());

            Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(content);
            if (keyedArray.matches()) {
                processRootKeyedArrayLine(obj, content, keyedArray, depth);
            } else {
                int colonIdx = findUnquotedColon(content);
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
            if (isBlankLine(line)) {
                context.currentLine++;
                continue;
            }

            int depth = getDepth(line);

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
        if (isBlankLine(line)) {
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
        int colonIdx = findUnquotedColon(content);

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

        checkFinalValueConflict(finalSegment, existing, value);

        // LWW: last write wins (always overwrite in non-strict, or if types match in
        // strict)
        current.put(finalSegment, value);
    }

    private void checkFinalValueConflict(String finalSegment, Object existing, Object value) {
        if (existing != null && context.options.strict()) {
            // Check for conflicts in strict mode
            if (existing instanceof Map && !(value instanceof Map)) {
                throw new IllegalArgumentException(
                    String.format("Path expansion conflict: %s is object, cannot set to %s",
                        finalSegment, value.getClass().getSimpleName()));
            }
            if (existing instanceof List && !(value instanceof List)) {
                throw new IllegalArgumentException(
                    String.format("Path expansion conflict: %s is array, cannot set to %s",
                        finalSegment, value.getClass().getSimpleName()));
            }
        }
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
            int nextDepth = getDepth(context.lines[context.currentLine + 1]);
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
        checkFinalValueConflict(key, existing, value);
    }

    /**
     * Finds the index of the first unquoted colon in a line.
     * Critical for handling quoted keys like "order:id": value.
     */
    private int findUnquotedColon(String content) {
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ':' && !inQuotes) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Calculates indentation depth (nesting level) of a line.
     * Counts leading spaces in multiples of the configured indent size.
     * In strict mode, validates indentation (no tabs, proper multiples).
     */
    private int getDepth(String line) {
        // Blank lines (including lines with only spaces) have depth 0
        if (isBlankLine(line)) {
            return 0;
        }

        // Validate indentation (including tabs) in strict mode
        // Check for tabs first before any other processing
        if (context.options.strict() && !line.isEmpty() && line.charAt(0) == '\t') {
            throw new IllegalArgumentException(
                String.format("Tab character used in indentation at line %d", context.currentLine + 1));
        }

        if (context.options.strict()) {
            validateIndentation(line);
        }

        int depth;
        int indentSize = context.options.indent();
        int leadingSpaces = 0;

        // Count leading spaces
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') {
                leadingSpaces++;
            } else {
                break;
            }
        }

        // Calculate depth based on indent size
        depth = leadingSpaces / indentSize;

        // In strict mode, check if it's an exact multiple
        if (context.options.strict() && leadingSpaces > 0
            && leadingSpaces % indentSize != 0) {
            throw new IllegalArgumentException(
                String.format("Non-multiple indentation: %d spaces with indent=%d at line %d",
                    leadingSpaces, indentSize, context.currentLine + 1));
        }

        return depth;
    }

    /**
     * Validates indentation in strict mode.
     * Checks for tabs, mixed tabs/spaces, and non-multiple indentation.
     */
    private void validateIndentation(String line) {
        if (line.trim().isEmpty()) {
            // Blank lines are allowed (handled separately)
            return;
        }

        int indentSize = context.options.indent();
        int leadingSpaces = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                throw new IllegalArgumentException(
                    String.format("Tab character used in indentation at line %d", context.currentLine + 1));
            } else if (c == ' ') {
                leadingSpaces++;
            } else {
                // Reached non-whitespace
                break;
            }
        }

        // Check for non-multiple indentation (only if there's actual content)
        if (leadingSpaces > 0 && leadingSpaces % indentSize != 0) {
            throw new IllegalArgumentException(
                String.format("Non-multiple indentation: %d spaces with indent=%d at line %d",
                    leadingSpaces, indentSize, context.currentLine + 1));
        }
    }
}
