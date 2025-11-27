package dev.toonformat.jtoon.decoder;

import dev.toonformat.jtoon.DecodeOptions;
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
     * Parses a key-value pair at root level, creating a new Map.
     */
    private Object parseKeyValuePair(String key, String value, int depth, boolean parseRootFields) {
        Map<String, Object> obj = new LinkedHashMap<>();
        KeyDecoder.parseKeyValuePairIntoMap(obj, key, value, depth, context);

        if (parseRootFields) {
            parseRootObjectFields(obj, depth);
        }
        return obj;
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
        if (KeyDecoder.shouldExpandKey(originalKey, context)) {
            KeyDecoder.expandPathIntoMap(obj, key, arrayValue, context);
        } else {
            // Check for conflicts with existing expanded paths
            DecodeHelper.checkPathExpansionConflict(obj, key, arrayValue, context);
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

                    KeyDecoder.parseKeyValuePairIntoMap(obj, key, value, depth, context);
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
        if (KeyDecoder.shouldExpandKey(originalKey, context)) {
            KeyDecoder.expandPathIntoMap(obj, key, arrayValue, context);
        } else {
            // Check for conflicts with existing expanded paths
            DecodeHelper.checkPathExpansionConflict(obj, key, arrayValue, context);
            obj.put(key, arrayValue);
        }
    }
}
