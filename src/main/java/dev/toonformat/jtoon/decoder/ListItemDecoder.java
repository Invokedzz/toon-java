package dev.toonformat.jtoon.decoder;

import dev.toonformat.jtoon.util.StringEscaper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static dev.toonformat.jtoon.util.Headers.KEYED_ARRAY_PATTERN;

public class ListItemDecoder {

    private ListItemDecoder() { throw new UnsupportedOperationException("Utility class cannot be instantiated"); }

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

        int colonIdx = DecodeHelper.findUnquotedColon(itemContent);

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
            return KeyDecoder.parseNestedObject(depth + 1, context);
        }

        // Handle empty value without nested content or non-empty value
        return isEmpty ? new LinkedHashMap<>() : PrimitiveDecoder.parse(value);
    }

    /**
     * Parses additional fields for a list item object.
     */
    private static void parseListItemFields(Map<String, Object> item, int depth, DecodeContext context) {
        while (context.currentLine < context.lines.length) {
            String line = context.lines[context.currentLine];
            int lineDepth = DecodeHelper.getDepth(line, context);

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
        int colonIdx = DecodeHelper.findUnquotedColon(fieldContent);
        if (colonIdx <= 0) {
            return false;
        }

        String fieldKey = StringEscaper.unescape(fieldContent.substring(0, colonIdx).trim());
        String fieldValue = fieldContent.substring(colonIdx + 1).trim();

        Object parsedValue = parseFieldValue(fieldValue, depth + 2, context);

        // Handle path expansion
        if (KeyDecoder.shouldExpandKey(fieldKey, context)) {
            KeyDecoder.expandPathIntoMap(item, fieldKey, parsedValue, context);
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
            int nextDepth = DecodeHelper.getDepth(context.lines[context.currentLine + 1], context);
            if (nextDepth > fieldDepth) {
                context.currentLine++;
                // parseNestedObject manages currentLine, so we don't increment here
                return KeyDecoder.parseNestedObject(fieldDepth, context);
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
        if (KeyDecoder.shouldExpandKey(originalKey, context)) {
            KeyDecoder.expandPathIntoMap(item, key, arrayValue, context);
        } else {
            item.put(key, arrayValue);
        }

        // parseArrayWithDelimiter manages currentLine correctly
        return true;
    }

    /**
     * Finds the depth of the next non-blank line, skipping blank lines.
     *
     * @return the depth of the next non-blank line, or null if none exists
     */
    private static Integer findNextNonBlankLineDepth(DecodeContext context) {
        int nextLineIdx = context.currentLine;
        while (nextLineIdx < context.lines.length && DecodeHelper.isBlankLine(context.lines[nextLineIdx])) {
            nextLineIdx++;
        }

        if (nextLineIdx >= context.lines.length) {
            return null;
        }

        return DecodeHelper.getDepth(context.lines[nextLineIdx], context);
    }

}
