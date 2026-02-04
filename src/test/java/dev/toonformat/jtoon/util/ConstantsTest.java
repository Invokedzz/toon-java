package dev.toonformat.jtoon.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static dev.toonformat.jtoon.util.Constants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Constants.
 */
@Tag("unit")
public class ConstantsTest {

    @Test
    @DisplayName("throws unsupported Operation Exception for calling the constructor")
    void throwsOnConstructor() throws NoSuchMethodException {
        // Given
        final Constructor<Constants> constructor = Constants.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        // When
        final InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, constructor::newInstance);

        // Then
        final Throwable cause = thrown.getCause();
        assertInstanceOf(UnsupportedOperationException.class, cause);
        assertEquals("Utility class cannot be instantiated", cause.getMessage());
    }

    @Test
    void expectsListItemMarker() {
        assertEquals("-", LIST_ITEM_MARKER);
    }

    @Test
    void expectsListItemPrefix() {
        assertEquals("- ", LIST_ITEM_PREFIX);
    }

    @Test
    void expectsComma() {
        assertEquals(",", COMMA);
    }

    @Test
    void expectsColon() {
        assertEquals(":", COLON);
    }

    @Test
    void expectsSpace() {
        assertEquals(" ", SPACE);
    }

    @Test
    void expectsDot() {
        assertEquals(".", DOT);
    }

    @Test
    void expectsHashtag() {
        assertEquals("#", HASHTAG);
    }

    @Test
    void expectsOpenBracket() {
        assertEquals("[", OPEN_BRACKET);
    }

    @Test
    void expectsCloseBracket() {
        assertEquals("]", CLOSE_BRACKET);
    }

    @Test
    void expectsOpenBrace() {
        assertEquals("{", OPEN_BRACE);
    }

    @Test
    void expectsCloseBrace() {
        assertEquals("}", CLOSE_BRACE);
    }

    @Test
    void expectsNullLiteral() {
        assertEquals("null", NULL_LITERAL);
    }

    @Test
    void expectsTrueLiteral() {
        assertEquals("true", TRUE_LITERAL);
    }

    @Test
    void expectsFalseLiteral() {
        assertEquals("false", FALSE_LITERAL);
    }

    @Test
    void expectsBackslash() {
        assertEquals('\\', BACKSLASH);
    }

    @Test
    void expectsDoubleQuote() {
        assertEquals('"', DOUBLE_QUOTE);
    }
}
