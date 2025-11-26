package dev.toonformat.jtoon.decoder;

import dev.toonformat.jtoon.PathExpansion;
import dev.toonformat.jtoon.util.StringEscaper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static dev.toonformat.jtoon.util.Headers.KEYED_ARRAY_PATTERN;

public class ListItemDecoder {

    /**
     * Processes a single list array item if it matches the expected depth.
     */
    public static void processListArrayItem(String line, int lineDepth, int depth, List<Object> result, DecodeContext context) {
        if (lineDepth == depth + 1) {
            String content = line.substring((depth + 1) * context.options.indent());

            if (content.startsWith("-")) {
                result.add(parseListItem(content, depth, context));
            } else {
                context.currentLine++;
            }
        } else {
            context.currentLine++;
        }
    }

    /**
     * Parses a single list item starting with "- ".
     * Item can be a scalar value or an object with nested fields.
     */
    public static Object parseListItem(String content, int depth, DecodeContext context) {
        // Handle empty item: just "-" or "- "
        String itemContent;
        if (content.length() > 2) {
            itemContent = content.substring(2).trim();
        } else {
            itemContent = "";
        }

        // Handle empty item: just "-"
        if (itemContent.isEmpty()) {
            context.currentLine++;
            return new LinkedHashMap<>();
        }

        // Check for standalone array (e.g., "[2]: 1,2")
        if (itemContent.startsWith("[")) {
            // For nested arrays in list items, default to comma delimiter if not specified
            String nestedArrayDelimiter = ArrayDecoder.extractDelimiterFromHeader(itemContent, context);
            // parseArrayWithDelimiter handles currentLine increment internally
            // For inline arrays, it increments. For multi-line arrays, parseListArray
            // handles it.
            // We need to increment here only if it was an inline array that we just parsed
            // Actually, parseArrayWithDelimiter always handles currentLine, so we don't
            // need to increment
            return ArrayDecoder.parseArrayWithDelimiter(itemContent, depth + 1, nestedArrayDelimiter, context);
        }

        // Check for keyed array pattern (e.g., "tags[3]: a,b,c" or "data[2]{id}: ...")
        Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(itemContent);
        if (keyedArray.matches()) {
            String originalKey = keyedArray.group(1).trim();
            String key = StringEscaper.unescape(originalKey);
            String arrayHeader = itemContent.substring(keyedArray.group(1).length());

            // For nested arrays in list items, default to comma delimiter if not specified
            String nestedArrayDelimiter = ArrayDecoder.extractDelimiterFromHeader(arrayHeader, context);
            List<Object> arrayValue = ArrayDecoder.parseArrayWithDelimiter(arrayHeader, depth + 2, nestedArrayDelimiter, context);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put(key, arrayValue);

            // parseArrayWithDelimiter manages currentLine correctly:
            // - For inline arrays, it increments currentLine
            // - For multi-line arrays (list/tabular), the array parsers leave currentLine
            // at the line after the array
            // So we don't need to increment here. Just parse additional fields.
            parseListItemFields(item, depth, context);

            return item;
        }

        int colonIdx = TabularArrayDecoder.findUnquotedColon(itemContent);

        // Simple scalar: - value
        if (colonIdx <= 0) {
            context.currentLine++;
            return PrimitiveDecoder.parse(itemContent);
        }

        // Object item: - key: value
        String key = StringEscaper.unescape(itemContent.substring(0, colonIdx).trim());
        String value = itemContent.substring(colonIdx + 1).trim();

        context.currentLine++;

        Map<String, Object> item = new LinkedHashMap<>();
        Object parsedValue;
        // If no next line exists, handle simple case
        if (context.currentLine >= context.lines.length) {
            parsedValue = value.trim().isEmpty() ? new LinkedHashMap<>() : PrimitiveDecoder.parse(value);
        } else {
            // List item is at depth + 1, so pass depth + 1 to parseObjectItemValue
            parsedValue = parseObjectItemValue(value, depth + 1, context);
        }
        item.put(key, parsedValue);
        parseListItemFields(item, depth, context);

        return item;
    }

    /**
     * Parses the value portion of an object item in a list, handling nested
     * objects,
     * empty values, and primitives.
     *
     * @param value the value string to parse
     * @param depth the depth of the list item
     * @return the parsed value (Map, List, or primitive)
     */
    private static Object parseObjectItemValue(String value, int depth, DecodeContext context) {
        boolean isEmpty = value.trim().isEmpty();

        // Find next non-blank line and its depth
        Integer nextDepth = findNextNonBlankLineDepth(context);
        if (nextDepth == null) {
            // No non-blank line found - create empty object
            return new LinkedHashMap<>();
        }

        // Handle empty value with nested content
        // The list item is at depth, and the field itself is conceptually at depth + 1
        // So nested content should be parsed with parentDepth = depth + 1
        // This allows nested fields at depth + 2 or deeper to be processed correctly
        if (isEmpty && nextDepth > depth) {
            return parseNestedObject(depth + 1, context);
        }

        // Handle empty value without nested content or non-empty value
        return isEmpty ? new LinkedHashMap<>() : PrimitiveDecoder.parse(value);
    }

    /**
     * Parses nested object starting at currentLine.
     */
    private static Map<String, Object> parseNestedObject(int parentDepth, DecodeContext context) {
        Map<String, Object> result = new LinkedHashMap<>();

        while (context.currentLine < context.lines.length) {
            String line = context.lines[context.currentLine];

            // Skip blank lines
            if (ArrayDecoder.isBlankLine(line)) {
                context.currentLine++;
                continue;
            }

            int depth = ArrayDecoder.getDepth(line, context);

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
        if (ArrayDecoder.isBlankLine(line)) {
            context.currentLine++;
            return;
        }

        String content = line.substring((parentDepth + 1) * context.options.indent());
        Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(content);

        if (keyedArray.matches()) {
            processKeyedArrayLine(result, content, keyedArray, parentDepth, context);
        } else {
            processKeyValueLine(result, content, depth, context);
        }
    }

    /**
     * Processes a keyed array line (e.g., "key[3]: value").
     */
    private static void processKeyedArrayLine(Map<String, Object> result, String content, Matcher keyedArray,
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
            checkPathExpansionConflict(result, key, arrayValue, context);
            result.put(key, arrayValue);
        }
    }

    /**
     * Checks for path expansion conflicts when setting a non-expanded key.
     * In strict mode, throws if the key conflicts with an existing expanded path.
     */
    private static void checkPathExpansionConflict(Map<String, Object> map, String key, Object value, DecodeContext context) {
        if (!context.options.strict()) {
            return;
        }

        Object existing = map.get(key);
        checkFinalValueConflict(key, existing, value, context);
    }

    private static void checkFinalValueConflict(String finalSegment, Object existing, Object value, DecodeContext context) {
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
     * Processes a key-value line (e.g., "key: value").
     */
    private static void processKeyValueLine(Map<String, Object> result, String content, int depth, DecodeContext context) {
        int colonIdx = TabularArrayDecoder.findUnquotedColon(content);

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
     * Parses a key-value pair and adds it to an existing map.
     */
    private static void parseKeyValuePairIntoMap(Map<String, Object> map, String key, String value, int depth, DecodeContext context) {
        String unescapedKey = StringEscaper.unescape(key);

        Object parsedValue = parseKeyValue(value, depth, context);
        putKeyValueIntoMap(map, key, unescapedKey, parsedValue, context);
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
            checkPathExpansionConflict(map, unescapedKey, value, context);
            map.put(unescapedKey, value);
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
    private static Object parseKeyValue(String value, int depth, DecodeContext context) {
        // Check if next line is nested (deeper indentation)
        if (context.currentLine + 1 < context.lines.length) {
            int nextDepth = ArrayDecoder.getDepth(context.lines[context.currentLine + 1], context);
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
     * Parses additional fields for a list item object.
     */
    private static void parseListItemFields(Map<String, Object> item, int depth, DecodeContext context) {
        while (context.currentLine < context.lines.length) {
            String line = context.lines[context.currentLine];
            int lineDepth = ArrayDecoder.getDepth(line, context);

            if (lineDepth < depth + 2) {
                return;
            }

            if (lineDepth == depth + 2) {
                String fieldContent = line.substring((depth + 2) * context.options.indent());

                // Try to parse as keyed array first, then as key-value pair
                boolean wasParsed = parseKeyedArrayField(fieldContent, item, depth, context);
                if (!wasParsed) {
                    wasParsed = parseKeyValueField(fieldContent, item, depth, context);
                }

                // If neither pattern matched, skip this line to avoid infinite loop
                if (!wasParsed) {
                    context.currentLine++;
                }
            } else {
                // lineDepth > depth + 2, skip this line
                context.currentLine++;
            }
        }
    }

    /**
     * Parses a key-value field and adds it to the item map.
     *
     * @param fieldContent the field content to parse
     * @param item         the map to add the field to
     * @param depth        the depth of the list item
     * @return true if the field was processed as a key-value pair, false otherwise
     */
    private static boolean parseKeyValueField(String fieldContent, Map<String, Object> item, int depth, DecodeContext context) {
        int colonIdx = TabularArrayDecoder.findUnquotedColon(fieldContent);
        if (colonIdx <= 0) {
            return false;
        }

        String fieldKey = StringEscaper.unescape(fieldContent.substring(0, colonIdx).trim());
        String fieldValue = fieldContent.substring(colonIdx + 1).trim();

        Object parsedValue = parseFieldValue(fieldValue, depth + 2, context);

        // Handle path expansion
        if (shouldExpandKey(fieldKey, context)) {
            expandPathIntoMap(item, fieldKey, parsedValue, context);
        } else {
            item.put(fieldKey, parsedValue);
        }

        // parseFieldValue manages currentLine appropriately
        return true;
    }

    /**
     * Parses a field value, handling nested objects, empty values, and primitives.
     *
     * @param fieldValue the value string to parse
     * @param fieldDepth the depth at which the field is located
     * @return the parsed value (Map, List, or primitive)
     */
    private static Object parseFieldValue(String fieldValue, int fieldDepth, DecodeContext context) {
        // Check if next line is nested
        if (context.currentLine + 1 < context.lines.length) {
            int nextDepth = ArrayDecoder.getDepth(context.lines[context.currentLine + 1], context);
            if (nextDepth > fieldDepth) {
                context.currentLine++;
                // parseNestedObject manages currentLine, so we don't increment here
                return parseNestedObject(fieldDepth, context);
            } else {
                // If value is empty, create empty object; otherwise parse as primitive
                if (fieldValue.trim().isEmpty()) {
                    context.currentLine++;
                    return new LinkedHashMap<>();
                } else {
                    context.currentLine++;
                    return PrimitiveDecoder.parse(fieldValue);
                }
            }
        } else {
            // If value is empty, create empty object; otherwise parse as primitive
            if (fieldValue.trim().isEmpty()) {
                context.currentLine++;
                return new LinkedHashMap<>();
            } else {
                context.currentLine++;
                return PrimitiveDecoder.parse(fieldValue);
            }
        }
    }

    /**
     * Checks if a key should be expanded (is a valid identifier segment).
     * Keys with dots that are valid identifiers can be expanded.
     * Quoted keys are never expanded.
     */
    private static boolean shouldExpandKey(String key, DecodeContext context) {
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
     * Parses a keyed array field and adds it to the item map.
     *
     * @param fieldContent the field content to parse
     * @param item         the map to add the field to
     * @param depth        the depth of the list item
     * @return true if the field was processed as a keyed array, false otherwise
     */
    private static boolean parseKeyedArrayField(String fieldContent, Map<String, Object> item, int depth, DecodeContext context) {
        Matcher keyedArray = KEYED_ARRAY_PATTERN.matcher(fieldContent);
        if (!keyedArray.matches()) {
            return false;
        }

        String originalKey = keyedArray.group(1).trim();
        String key = StringEscaper.unescape(originalKey);
        String arrayHeader = fieldContent.substring(keyedArray.group(1).length());

        // For nested arrays in list items, default to comma delimiter if not specified
        String nestedArrayDelimiter = ArrayDecoder.extractDelimiterFromHeader(arrayHeader, context);
        var arrayValue = ArrayDecoder.parseArrayWithDelimiter(arrayHeader, depth + 2, nestedArrayDelimiter, context);

        // Handle path expansion for array keys
        if (shouldExpandKey(originalKey, context)) {
            expandPathIntoMap(item, key, arrayValue, context);
        } else {
            item.put(key, arrayValue);
        }

        // parseArrayWithDelimiter manages currentLine correctly
        return true;
    }

    /**
     * Expands a dotted key into nested object structure.
     */
    private static void expandPathIntoMap(Map<String, Object> map, String dottedKey, Object value, DecodeContext context) {
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
    }

    /**
     * Finds the depth of the next non-blank line, skipping blank lines.
     *
     * @return the depth of the next non-blank line, or null if none exists
     */
    private static Integer findNextNonBlankLineDepth(DecodeContext context) {
        int nextLineIdx = context.currentLine;
        while (nextLineIdx < context.lines.length && ArrayDecoder.isBlankLine(context.lines[nextLineIdx])) {
            nextLineIdx++;
        }

        if (nextLineIdx >= context.lines.length) {
            return null;
        }

        return ArrayDecoder.getDepth(context.lines[nextLineIdx], context);
    }

}
