package dev.toonformat.jtoon.decoder;

import dev.toonformat.jtoon.util.StringEscaper;

import java.util.*;
import java.util.regex.Matcher;

import static dev.toonformat.jtoon.util.Headers.*;

public class ArrayDecoder {

    private ArrayDecoder() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Parses array from header string and following lines.
     * Detects array type (tabular, list, or primitive) and routes accordingly.
     */
    public static List<Object> parseArray(String header, int depth, DecodeContext context) {
        String arrayDelimiter = extractDelimiterFromHeader(header, context);

        return parseArrayWithDelimiter(header, depth, arrayDelimiter, context);
    }

    /**
     * Extracts delimiter from array header.
     * Returns tab, pipe, or comma (default) based on header pattern.
     */
    public static String extractDelimiterFromHeader(String header, DecodeContext context) {
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
     * Parses array from header string and following lines with a specific
     * delimiter.
     * Detects array type (tabular, list, or primitive) and routes accordingly.
     */
    public static List<Object> parseArrayWithDelimiter(String header, int depth, String arrayDelimiter, DecodeContext context) {
        Matcher tabularMatcher = TABULAR_HEADER_PATTERN.matcher(header);
        Matcher arrayMatcher = ARRAY_HEADER_PATTERN.matcher(header);

        if (tabularMatcher.find()) {
            return TabularArrayDecoder.parseTabularArray(header, depth, arrayDelimiter, context);
        }

        if (arrayMatcher.find()) {
            int headerEndIdx = arrayMatcher.end();
            String afterHeader = header.substring(headerEndIdx).trim();

            if (afterHeader.startsWith(":")) {
                String inlineContent = afterHeader.substring(1).trim();

                if (!inlineContent.isEmpty()) {
                    List<Object> result = parseArrayValues(inlineContent, arrayDelimiter);
                    validateArrayLength(header, result.size());
                    context.currentLine++;
                    return result;
                }
            }

            context.currentLine++;
            if (context.currentLine < context.lines.length) {
                String nextLine = context.lines[context.currentLine];
                int nextDepth = getDepth(nextLine, context);
                String nextContent = nextLine.substring(nextDepth * context.options.indent());

                if (nextDepth <= depth) {
                    // The next line is not a child of this array,
                    // the array is empty
                    List<Object> empty = new ArrayList<>();
                    validateArrayLength(header, 0);
                    return empty;
                }

                if (nextContent.startsWith("- ")) {
                    context.currentLine--;
                    return parseListArray(depth, header, context);
                } else {
                    context.currentLine++;
                    List<Object> result = parseArrayValues(nextContent, arrayDelimiter);
                    validateArrayLength(header, result.size());
                    return result;
                }
            }
            List<Object> empty = new ArrayList<>();
            validateArrayLength(header, 0);
            return empty;
        }

        if (context.options.strict()) {
            throw new IllegalArgumentException("Invalid array header: " + header);
        }
        return Collections.emptyList();
    }

    /**
     * Parses list array format where items are prefixed with "- ".
     * Example: items[2]:\n - item1\n - item2
     */
    protected static List<Object> parseListArray(int depth, String header, DecodeContext context) {
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
                int lineDepth = getDepth(line, context);
                if (shouldTerminateListArray(lineDepth, depth, line, context)) {
                    shouldContinue = false;
                } else {
                    ListItemDecoder.processListArrayItem(line, lineDepth, depth, result, context);
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
    private static int findNextNonBlankLine(int startIndex, DecodeContext context) {
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
    private static boolean handleBlankLineInListArray(int depth, DecodeContext context) {
        int nextNonBlankLine = findNextNonBlankLine(context.currentLine + 1, context);

        if (nextNonBlankLine >= context.lines.length) {
            return true; // End of file - terminate array
        }

        int nextDepth = getDepth(context.lines[nextNonBlankLine], context);
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
    private static boolean shouldTerminateListArray(int lineDepth, int depth, String line, DecodeContext context) {
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
     * Calculates indentation depth (nesting level) of a line.
     * Counts leading spaces in multiples of the configured indent size.
     * In strict mode, validates indentation (no tabs, proper multiples).
     */
    public static int getDepth(String line, DecodeContext context) {
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
            validateIndentation(line, context);
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
    private static void validateIndentation(String line, DecodeContext context) {
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

    /**
     * Parses array values from a delimiter-separated string.
     */
    protected static List<Object> parseArrayValues(String values, String arrayDelimiter) {
        List<Object> result = new ArrayList<>();
        List<String> rawValues = parseDelimitedValues(values, arrayDelimiter);
        for (String value : rawValues) {
            result.add(PrimitiveDecoder.parse(value));
        }
        return result;
    }

    /**
     * Checks if a line is blank (empty or only whitespace).
     */
    protected static boolean isBlankLine(String line) {
        return line.trim().isEmpty();
    }

    /**
     * Splits a string by delimiter, respecting quoted sections.
     * Whitespace around delimiters is tolerated and trimmed.
     */
    protected static List<String> parseDelimitedValues(String input, String arrayDelimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        char delimChar = arrayDelimiter.charAt(0);

        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                i++;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
                i++;
            } else if (c == '"') {
                current.append(c);
                inQuotes = !inQuotes;
                i++;
            } else if (c == delimChar && !inQuotes) {
                // Found delimiter - add current value (trimmed) and reset
                String value = current.toString().trim();
                result.add(value);
                current = new StringBuilder();
                // Skip whitespace after delimiter
                do {
                    i++;
                } while (i < input.length() && Character.isWhitespace(input.charAt(i)));
            } else {
                current.append(c);
                i++;
            }
        }

        // Add final value
        if (!current.isEmpty() || input.endsWith(arrayDelimiter)) {
            result.add(current.toString().trim());
        }

        return result;
    }

    /**
     * Validates array length if declared in header.
     */
    protected static void validateArrayLength(String header, int actualLength) {
        Integer declaredLength = extractLengthFromHeader(header);
        if (declaredLength != null && declaredLength != actualLength) {
            throw new IllegalArgumentException(
                String.format("Array length mismatch: declared %d, found %d", declaredLength, actualLength));
        }
    }

    /**
     * Extracts declared length from array header.
     * Returns the number specified in [n] or null if not found.
     */
    private static Integer extractLengthFromHeader(String header) {
        Matcher matcher = ARRAY_HEADER_PATTERN.matcher(header);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
