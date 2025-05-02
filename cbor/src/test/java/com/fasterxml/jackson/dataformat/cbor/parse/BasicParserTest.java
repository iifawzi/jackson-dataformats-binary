package com.fasterxml.jackson.dataformat.cbor.parse;

import java.io.*;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.async.NonBlockingByteArrayParser;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.dataformat.cbor.*;
import com.fasterxml.jackson.dataformat.cbor.testutil.ThrottledInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for simple value types.
 */
public class BasicParserTest extends CBORTestBase
{
    /**
     * Test for verifying handling of 'true', 'false' and 'null' literals
     */
    @Test
    public void testSimpleLiterals() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = cborGenerator(out);
        gen.writeBoolean(true);
        assertEquals("/", gen.getOutputContext().toString());
        gen.close();
        JsonParser p = cborParser(out);
        assertEquals(JsonToken.VALUE_TRUE, p.nextToken());
        assertNull(p.nextToken());
        p.close();

        out = new ByteArrayOutputStream();
        gen = cborGenerator(out);
        gen.writeBoolean(false);
        gen.close();
        p = cborParser(out);
        assertEquals(JsonToken.VALUE_FALSE, p.nextToken());
        assertEquals("/", p.getParsingContext().toString());

        assertNull(p.nextToken());
        p.close();

        out = new ByteArrayOutputStream();
        gen = cborGenerator(out);
        gen.writeNull();
        gen.close();
        p = cborParser(out);
        assertEquals(JsonToken.VALUE_NULL, p.nextToken());
        assertNull(p.nextToken());
        p.close();
    }

    @Test
    public void testMediumText() throws Exception
    {
        _testMedium(1100);
        _testMedium(1300);
        _testMedium(1900);
        _testMedium(2300);
        _testMedium(3900);
    }

    @Test
    public void testMediumText2() throws Exception
    {
        for (int prefix : Arrays.asList(197, 198, 199, 200, 201, 497, 499, 500, 501)) {
            _testMedium(prefix, 1300);
            _testMedium(prefix, 1900);
            _testMedium(prefix, 2300);
            _testMedium(prefix, 3900);
        }
    }

    private void _testMedium(int len) throws Exception {
        _testMedium(0, len);
    }

    private void _testMedium(int asciiPrefixLen, int len) throws Exception
    {
        // First, use size that should fit in output buffer, but
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator gen = cborGenerator(out);
        final String MEDIUM = asciiPrefixLen == 0 ?
                generateUnicodeString(len) : generateUnicodeStringWithAsciiPrefix(asciiPrefixLen, len);
        gen.writeString(MEDIUM);
        gen.close();

        final byte[] b = out.toByteArray();

        // verify that it is indeed non-chunked still...
        assertEquals((byte) (CBORConstants.PREFIX_TYPE_TEXT + 25), b[0]);

        JsonParser p = cborParser(b);
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertEquals(MEDIUM, p.getText());
        assertNull(p.nextToken());
        p.close();
    }

    @Test
    public void testCurrentLocationByteOffset() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator gen = cborGenerator(out);
        gen.writeString("1234567890");
        gen.writeString("1234567890");
        gen.close();

        final byte[] b = out.toByteArray();

        JsonParser p = cborParser(b);

        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertEquals(1, p.currentLocation().getByteOffset());
        p.getText(); // fully read token.
        assertEquals(11, p.currentLocation().getByteOffset());

        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertEquals(12, p.currentLocation().getByteOffset());
        p.getText();
        assertEquals(22, p.currentLocation().getByteOffset());

        assertNull(p.nextToken());
        assertEquals(22, p.currentLocation().getByteOffset());

        p.close();
        assertEquals(22, p.currentLocation().getByteOffset());
    }

    @Test
    public void testLongNonChunkedText() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        final String LONG = generateUnicodeString(37000);
        final byte[] LONG_B = LONG.getBytes("UTF-8");
        final int BYTE_LEN = LONG_B.length;
        out.write(CBORConstants.BYTE_ARRAY_INDEFINITE);
        out.write((byte) (CBORConstants.PREFIX_TYPE_TEXT + 25));
        out.write((byte) (BYTE_LEN >> 8));
        out.write((byte) BYTE_LEN);
        out.write(LONG.getBytes("UTF-8"));
        out.write(CBORConstants.BYTE_BREAK);

        final byte[] b = out.toByteArray();
        assertEquals(BYTE_LEN + 5, b.length);

        // Important! Need to construct a stream, to force boundary conditions
        JsonParser p = cborParser(new ByteArrayInputStream(b));
        assertToken(JsonToken.START_ARRAY, p.nextToken());
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        String actual = p.getText();

        final int end = Math.min(LONG.length(), actual.length());
        for (int i = 0; i < end; ++i) {
            if (LONG.charAt(i) != actual.charAt(i)) {
                fail("Character #"+i+" (of "+end+") differs; expected 0x"+Integer.toHexString(LONG.charAt(i))
                        +" found 0x"+Integer.toHexString(actual.charAt(i)));
            }
        }

        assertEquals(LONG.length(), actual.length());

        assertEquals(LONG, p.getText());
        assertToken(JsonToken.END_ARRAY, p.nextToken());
        assertNull(p.nextToken());
        p.close();
    }

    @Test
    public void testLongChunkedText() throws Exception
    {
        // First, try with ASCII content
        StringBuilder sb = new StringBuilder(21000);
        for (int i = 0; i < 21000; ++i) {
            sb.append('Z');
        }
        _testLongChunkedText(sb.toString());
        // Second, with actual variable byte-length Unicode
        _testLongChunkedText(generateUnicodeString(21000));
    }

    @Test
    public void testLongChunkedText2() throws Exception
    {
        // The text buffer starting size is 200 bytes, let's cycle around that
        // amount to verify the tight ascii loop.
        for (int prefix = 194; prefix < 202; ++prefix) {
            _testLongChunkedText(generateUnicodeStringWithAsciiPrefix(prefix, 21000));
        }
    }

    @SuppressWarnings("resource")
    public void _testLongChunkedText(String input) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator gen = cborGenerator(out);
        gen.writeString(input);
        gen.close();

        final int textByteCount = input.getBytes("UTF-8").length;
        final byte[] b = out.toByteArray();
        assertEquals((byte) (CBORConstants.PREFIX_TYPE_TEXT + 0x1F), b[0]);
        assertEquals(CBORConstants.BYTE_BREAK, b[b.length-1]);

        // First, verify validity by scanning
        int i = 1;
        int total = 0;

        for (int end = b.length-1; i < end; ) {
            int type = b[i++] & 0xFF;
            int len = type - CBORConstants.PREFIX_TYPE_TEXT;

            if (len < 24) { // tiny, fine
                ;
            } else if (len == 24) { // 1-byte
                len = (b[i++] & 0xFF);
            } else if (len == 25) { // 2-byte
                len = ((b[i++] & 0xFF) << 8) + (b[i++] & 0xFF);
            }
            i += len;
            total += len;
        }
        assertEquals(b.length-1, i);
        assertEquals(textByteCount, total);

        JsonParser p;

        // then skipping
        p = cborParser(new ByteArrayInputStream(b));
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertNull(p.nextToken());
        p.close();

        // and then with actual full parsing/access
        p = cborParser(new ThrottledInputStream(new ByteArrayInputStream(b), 3));
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        String actual = p.getText();
        assertNull(p.nextToken());
        assertEquals(input.length(), actual.length());
        if (!input.equals(actual)) {
            _debugDiff(input, actual);
        }
        assertEquals(input, actual);
        p.close();

        // one more thing: with 2.8 we have new `getText()` variant
        p = cborParser(new ByteArrayInputStream(b));
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        StringWriter w = new StringWriter();
        int len = p.getText(w);
        actual = w.toString();
        assertEquals(len, actual.length());
        assertEquals(input.length(), actual.length());
        if (!input.equals(actual)) {
            _debugDiff(input, actual);
        }
        p.close();
    }

    private void _debugDiff(String expected, String actual)
    {
        int i = 0;
        while (i < expected.length() && expected.charAt(i) == actual.charAt(i)) { ++i; }
        fail("Strings differ at #"+i+" (length "+expected.length()+"); expected 0x"
                +Integer.toHexString(expected.charAt(i))+", got 0x"
                +Integer.toHexString(actual.charAt(i)));
    }

    @Test
    public void testStringField() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartObject();
        generator.writeStringField("a", "b");
        generator.writeEndObject();
        generator.close();

        CBORParser parser = cborParser(out.toByteArray());
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());
        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("a", parser.currentName());
        assertEquals("a", parser.getText());
        assertEquals("a", parser.getValueAsString());
        assertEquals("a", parser.getValueAsString("x"));
        assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
        assertEquals("a", parser.currentName());
        assertEquals("b", parser.getText());
        assertEquals("b", parser.getValueAsString());
        assertEquals("b", parser.getValueAsString("x"));
        assertEquals(1, parser.getTextLength());
        assertEquals(JsonToken.END_OBJECT, parser.nextToken());

        // For fun, release
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        assertEquals(0, parser.releaseBuffered(extra));

        parser.close();
    }

    @Test
    public void testNestedObject() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartObject();
        generator.writeFieldName("ob");
        generator.writeStartObject();
        generator.writeNumberField("num", 3);
        generator.writeEndObject();
        generator.writeFieldName("arr");
        generator.writeStartArray();
        generator.writeEndArray();
        generator.writeEndObject();
        generator.close();

        CBORParser parser = cborParser(out.toByteArray());
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());

        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("ob", parser.currentName());
        assertEquals("ob", parser.getText());
        assertEquals("ob", parser.getValueAsString());
        assertEquals("ob", parser.getValueAsString("x"));
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());
        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("num", parser.currentName());
        assertEquals("num", parser.getText());
        assertEquals("num", parser.getValueAsString());
        assertEquals("num", parser.getValueAsString("y"));
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(JsonToken.END_OBJECT, parser.nextToken());

        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("arr", parser.currentName());
        assertEquals("arr", parser.getText());
        assertEquals("arr", parser.getValueAsString());
        assertEquals("arr", parser.getValueAsString("z"));
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        assertEquals(JsonToken.END_OBJECT, parser.nextToken());
        parser.close();
    }

    @Test
    public void testBufferRelease() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartObject();
        generator.writeStringField("a", "1");
        generator.writeEndObject();
        generator.flush();
        // add stuff that is NOT part of the Object
        out.write(new byte[] { 1, 2, 3 });
        generator.close();

        CBORParser parser = cborParser(out.toByteArray());
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());
        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
        assertEquals(JsonToken.END_OBJECT, parser.nextToken());

        // Fine; but now should be able to retrieve 3 bytes that are (likely)
        // to have been  buffered

        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        assertEquals(3, parser.releaseBuffered(extra));
        byte[] extraBytes = extra.toByteArray();
        assertEquals((byte) 1, extraBytes[0]);
        assertEquals((byte) 2, extraBytes[1]);
        assertEquals((byte) 3, extraBytes[2]);

        parser.close();
    }

    // partially fed
    @Test
    public void testStartArrayFedInPortions() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartArray(null, 100000);
        for (int i = 0; i < 100000; ++i) {
        generator.writeString("a");
        }
        generator.close();
        byte[] data = out.toByteArray();

        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 3);
        JsonToken n = parser.nextToken();
        assertEquals(JsonToken.NOT_AVAILABLE, n);

        parser.feedInput(data, 3, 4);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());

        parser.feedInput(data, 4, 5);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // fed one by one
    @Test
    public void testArrayStartOneByOne() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartArray(null, 100000);
        for (int i = 0; i < 100000; ++i) {
            generator.writeString("a");
        }
        generator.close();
        byte[] data = out.toByteArray();

        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.NOT_AVAILABLE,  parser.nextToken());

        parser.feedInput(data, 1, 2);
        assertEquals(JsonToken.NOT_AVAILABLE,  parser.nextToken());

        parser.feedInput(data, 2, 3);
        assertEquals(JsonToken.NOT_AVAILABLE,  parser.nextToken());
        parser.feedInput(data, 3, 4);
        assertEquals(JsonToken.NOT_AVAILABLE,  parser.nextToken());
        parser.feedInput(data, 4, 5);
        assertEquals(JsonToken.START_ARRAY,  parser.nextToken());
    }

    @Test
    public void testStartArrayElement() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartArray(null, 1);
        generator.writeNumber(5000000000000L);
        generator.close();
        byte[] data = out.toByteArray();

        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1000);
        JsonToken n3 = parser.nextToken();
        assertEquals(JsonToken.START_ARRAY, n3);
        assertEquals("[", parser.getText());
    }

    @Test
    public void testNumberFedAtOnce() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 500
        byte[] data = new byte[] {
                (byte) 0x19,
                (byte) 0x01,
                (byte) 0xf4,

        };
        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1000);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(500, parser.getIntValue());
    }

    @Test
    public void testNumberFedOneByOne() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 500
        byte[] data = new byte[] {
                (byte) 0x19,
                (byte) 0x01,
                (byte) 0xf4,

        };
        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 1, 2);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 2, 3);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(500, parser.getIntValue());
    }

    // 18446744073709551615
    @Test
    public void testBigIntegerPositiveNumberFedAtOnce() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = new byte[] {
                (byte) 0x1B,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,

        };
        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1000);
        // 64 bit but it doesn't fit in long in java, so we need to use bigInteger.
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(new BigInteger("18446744073709551615"), parser.getBigIntegerValue());

    }

    // 18446744073709551615
    @Test
    public void testBigIntegerPositiveNumberFedOneByOne() throws IOException {
        // 64 bit but it doesn't fit in long in java, so we need to use bigInteger.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = new byte[] {
                (byte) 0x1B,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,

        };
        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 1, 2);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 2, 3);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 3, 4);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 4, 5);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 5, 6);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 6, 7);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 7, 8);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 8, 9);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(new BigInteger("18446744073709551615"), parser.getBigIntegerValue());
    }


    // -5
    @Test
    public void testNegativeIntFedAtOnce() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = new byte[] {
                (byte) 0x24,

        };
        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1000);

        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(-5, parser.getIntValue());
    }

    // -18446744073709551615
    // 3B FF FF FF FF FF FF FF FE
    @Test
    public void testNegativeBigIntFedAtOnce() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = new byte[] {
                (byte) 0x3B,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFE,
        };
        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1000);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(new BigInteger("-18446744073709551615"), parser.getBigIntegerValue());
        parser.close();
    }


    // -18446744073709551615
    // 3B FF FF FF FF FF FF FF FE
    @Test
    public void testNegativeIntFedOneByOne() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = new byte[] {
                (byte) 0x3B,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFE,
        };
        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 1, 2);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 2, 3);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 3, 4);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 4, 5);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 5, 6);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 6, 7);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 7, 8);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        parser.feedInput(data, 8, 9);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(new BigInteger("-18446744073709551615"), parser.getBigIntegerValue());
        parser.close();
    }

    // Tests for small positive integers (0-23)
    @Test
    public void testSmallPositiveIntegerFedAtOnce() throws IOException {
        // 23 (0x17 in CBOR)
        byte[] data = new byte[] { (byte) 0x17 };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(23, parser.getIntValue());
    }

    // Test for positive integer that fits in one byte (24-255)
    @Test
    public void testOneBytePositiveIntegerFedAtOnce() throws IOException {
        // 255 (0x18 0xFF in CBOR)
        byte[] data = new byte[] { (byte) 0x18, (byte) 0xFF };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 2);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(255, parser.getIntValue());
    }

    // Test for positive integer that fits in two bytes (256-65535)
    @Test
    public void testTwoBytePositiveIntegerFedAtOnce() throws IOException {
        // 65535 (0x19 0xFF 0xFF in CBOR)
        byte[] data = new byte[] { (byte) 0x19, (byte) 0xFF, (byte) 0xFF };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 3);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(65535, parser.getIntValue());
    }

    // Test for positive integer that fits in four bytes
    @Test
    public void testFourBytePositiveIntegerFedAtOnce() throws IOException {
        // 1000000 (0x1A 000F 4240 in CBOR)
        byte[] data = new byte[] {
                (byte) 0x1A,
                (byte) 0x00,
                (byte) 0x0F,
                (byte) 0x42,
                (byte) 0x40
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 5);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1000000, parser.getIntValue());
    }

    // Test for small negative integers (-1 to -24)
    @Test
    public void testSmallNegativeIntegerFedAtOnce() throws IOException {
        // -16 (0x2F in CBOR)
        byte[] data = new byte[] { (byte) 0x2F };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(-16, parser.getIntValue());
    }

    // Test for one byte negative integer
    @Test
    public void testOneByteNegativeIntegerFedAtOnce() throws IOException {
        // -100 (0x38 0x63 in CBOR)
        byte[] data = new byte[] { (byte) 0x38, (byte) 0x63 };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 2);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(-100, parser.getIntValue());
    }

    // Test for two byte negative integer fed one byte at a time
    @Test
    public void testTwoByteNegativeIntegerFedOneByOne() throws IOException {
        // -1000 (0x39 03E7 in CBOR)
        byte[] data = new byte[] { (byte) 0x39, (byte) 0x03, (byte) 0xE7 };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();

        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());

        parser.feedInput(data, 1, 2);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());

        parser.feedInput(data, 2, 3);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(-1000, parser.getIntValue());
    }

    // Test for empty array
    @Test
    public void testEmptyArrayFedAtOnce() throws IOException {
        // Empty array (0x80 in CBOR)
        byte[] data = new byte[] { (byte) 0x80 };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // Test for array with small size (< 24 elements)
    @Test
    public void testSmallArrayFedAtOnce() throws IOException {
        // Array of size 3 (0x83 in CBOR)
        byte[] data = new byte[] { (byte) 0x83 };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // Test for array with two byte length fed one byte at a time
    @Test
    public void testTwoByteArrayLengthFedOneByOne() throws IOException {
        // Array of size 1000 (0x99 03E8 in CBOR)
        byte[] data = new byte[] { (byte) 0x99, (byte) 0x03, (byte) 0xE8 };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();

        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());

        parser.feedInput(data, 1, 2);
        assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());

        parser.feedInput(data, 2, 3);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // Test for maximum small positive integer (23)
    @Test
    public void testMaxSmallPositiveInteger() throws IOException {
        byte[] data = new byte[] { (byte) 0x17 };  // 23 in CBOR
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(23, parser.getIntValue());
    }

    // Test for minimum value requiring one byte (24)
    @Test
    public void testMinOneBytePositiveInteger() throws IOException {
        byte[] data = new byte[] { (byte) 0x18, (byte) 0x18 };  // 24 in CBOR
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 2);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(24, parser.getIntValue());
    }

    // Test for four byte positive integer edge case
    @Test
    public void testFourBytePositiveIntegerMaxValue() throws IOException {
        // 2147483647 (Integer.MAX_VALUE)
        byte[] data = new byte[] {
                (byte) 0x1A,
                (byte) 0x7F,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 5);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(Integer.MAX_VALUE, parser.getIntValue());
    }

    // Test for eight byte positive integer edge case
    @Test
    public void testEightBytePositiveIntegerMaxLong() throws IOException {
        // 9223372036854775807L (Long.MAX_VALUE)
        byte[] data = new byte[] {
                (byte) 0x1B,
                (byte) 0x7F,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 9);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(Long.MAX_VALUE, parser.getLongValue());
    }

    // Test for minimum small negative integer (-1)
    @Test
    public void testMinSmallNegativeInteger() throws IOException {
        byte[] data = new byte[] { (byte) 0x20 };  // -1 in CBOR
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(-1, parser.getIntValue());
    }

    // Test for maximum small negative integer (-24)
    @Test
    public void testMaxSmallNegativeInteger() throws IOException {
        byte[] data = new byte[] { (byte) 0x37 };  // -24 in CBOR
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(-24, parser.getIntValue());
    }

    // Test for four byte negative integer edge case
    @Test
    public void testFourByteNegativeIntegerMinValue() throws IOException {
        // -2147483648 (Integer.MIN_VALUE)
        byte[] data = new byte[] {
                (byte) 0x3A,
                (byte) 0x7F,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 5);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(Integer.MIN_VALUE, parser.getIntValue());
    }

    // Test for eight byte negative integer edge case fed one by one
    @Test
    public void testEightByteNegativeIntegerMinLongOneByOne() throws IOException {
        // -9223372036854775808L (Long.MIN_VALUE)
        byte[] data = new byte[] {
                (byte) 0x3B,
                (byte) 0x7F,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();

        for (int i = 0; i < 8; i++) {
            parser.feedInput(data, i, i + 1);
            assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        }

        parser.feedInput(data, 8, 9);
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(Long.MIN_VALUE, parser.getLongValue());
    }

    // Test for array with maximum small size (23 elements)
    @Test
    public void testMaxSmallArraySize() throws IOException {
        byte[] data = new byte[] { (byte) 0x97 };  // Array of size 23
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // Test for array with minimum one byte size (24 elements)
    @Test
    public void testMinOneByteArraySize() throws IOException {
        byte[] data = new byte[] { (byte) 0x98, (byte) 0x18 };  // Array of size 24
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 2);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // Test for array with four byte length
    @Test
    public void testFourByteArrayLength() throws IOException {
        // Array of size 1000000
        byte[] data = new byte[] {
                (byte) 0x9A,
                (byte) 0x00,
                (byte) 0x0F,
                (byte) 0x42,
                (byte) 0x40
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 5);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // Test for two byte array length with maximum value
    @Test
    public void testTwoByteArrayMaxLength() throws IOException {
        // Array of size 65535
        byte[] data = new byte[] {
                (byte) 0x99,
                (byte) 0xFF,
                (byte) 0xFF
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 3);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }

    // Test for array with large size fed one byte at a time
    @Test
    public void testLargeArraySizeOneByOne() throws IOException {
        // Array of size 100000
        byte[] data = new byte[] {
                (byte) 0x9A,
                (byte) 0x00,
                (byte) 0x01,
                (byte) 0x86,
                (byte) 0xA0
        };
        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();

        for (int i = 0; i < 4; i++) {
            parser.feedInput(data, i, i + 1);
            assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
        }

        parser.feedInput(data, 4, 5);
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
    }


    // Test sequence of consecutive small positive integers
    @Test
    public void testSequentialSmallPositiveIntegers() throws IOException {
        // Test integers 0 through 10
        for (int i = 0; i <= 10; i++) {
            byte[] data = new byte[] { (byte)(i) };
            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            parser.feedInput(data, 0, 1);
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(i, parser.getIntValue());
            parser.close();
        }
    }

    // Test sequence of consecutive small negative integers
    @Test
    public void testSequentialSmallNegativeIntegers() throws IOException {
        // Test integers -1 through -10
        for (int i = 1; i <= 10; i++) {
            byte[] data = new byte[] { (byte)(0x20 + i - 1) };
            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            parser.feedInput(data, 0, 1);
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(-i, parser.getIntValue());
            parser.close();
        }
    }

    // Test powers of 2 for positive integers
    @Test
    public void testPositivePowersOfTwo() throws IOException {
        // Test 2^0 through 2^16
        for (int i = 0; i <= 16; i++) {
            int value = 1 << i;
            byte[] data;
            if (value <= 23) {
                data = new byte[] { (byte)value };
            } else if (value <= 255) {
                data = new byte[] { 0x18, (byte)value };
            } else if (value <= 65535) {
                data = new byte[] {
                        0x19,
                        (byte)(value >> 8),
                        (byte)value
                };
            } else {
                data = new byte[] {
                        0x1A,
                        (byte)(value >> 24),
                        (byte)(value >> 16),
                        (byte)(value >> 8),
                        (byte)value
                };
            }

            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            parser.feedInput(data, 0, data.length);
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(value, parser.getIntValue());
            parser.close();
        }
    }

    // Test powers of 2 for negative integers
    @Test
    public void testNegativePowersOfTwo() throws IOException {
        // Test -2^0 through -2^16
        for (int i = 0; i <= 16; i++) {
            int value = -(1 << i);
            byte[] data;
            if (Math.abs(value) <= 24) {
                data = new byte[] { (byte)(0x20 - value - 1) };
            } else if (Math.abs(value) <= 255) {
                data = new byte[] { 0x38, (byte)(Math.abs(value) - 1) };
            } else if (Math.abs(value) <= 65535) {
                int absValue = Math.abs(value) - 1;
                data = new byte[] {
                        0x39,
                        (byte)(absValue >> 8),
                        (byte)absValue
                };
            } else {
                int absValue = Math.abs(value) - 1;
                data = new byte[] {
                        0x3A,
                        (byte)(absValue >> 24),
                        (byte)(absValue >> 16),
                        (byte)(absValue >> 8),
                        (byte)absValue
                };
            }

            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            parser.feedInput(data, 0, data.length);
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(value, parser.getIntValue());
            parser.close();
        }
    }

    // Test array sizes in powers of 2
    @Test
    public void testArraySizesPowersOfTwo() throws IOException {
        // Test arrays of size 2^0 through 2^8
        for (int i = 0; i <= 8; i++) {
            int size = 1 << i;
            byte[] data;
            if (size <= 23) {
                data = new byte[] { (byte)(0x80 + size) };
            } else if (size <= 255) {
                data = new byte[] { (byte)0x98, (byte)size };
            } else {
                data = new byte[] {
                        (byte)0x99,
                        (byte)(size >> 8),
                        (byte)size
                };
            }

            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            parser.feedInput(data, 0, data.length);
            assertEquals(JsonToken.START_ARRAY, parser.nextToken());
            parser.close();
        }
    }

    // Test boundary values for different integer sizes
    @Test
    public void testIntegerBoundaries() throws IOException {
        // Test values near size boundaries
        int[] testValues = {
                23, 24, // Small integer boundary
                255, 256, // One-byte boundary
                65535, 65536, // Two-byte boundary
                16777215, 16777216 // Three-byte boundary
        };

        for (int value : testValues) {
            byte[] data;
            if (value <= 23) {
                data = new byte[] { (byte)value };
            } else if (value <= 255) {
                data = new byte[] { 0x18, (byte)value };
            } else if (value <= 65535) {
                data = new byte[] {
                        0x19,
                        (byte)(value >> 8),
                        (byte)value
                };
            } else {
                data = new byte[] {
                        0x1A,
                        (byte)(value >> 24),
                        (byte)(value >> 16),
                        (byte)(value >> 8),
                        (byte)value
                };
            }

            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            parser.feedInput(data, 0, data.length);
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(value, parser.getIntValue());
            parser.close();
        }
    }

    // Test incremental feeding of different sized integers
    @Test
    public void testIncrementalFeeding() throws IOException {
        // Test different sized integers with incremental feeding
        int[] testValues = {500, 50000, 5000000};

        for (int value : testValues) {
            byte[] data;
            if (value <= 255) {
                data = new byte[] { 0x18, (byte)value };
            } else if (value <= 65535) {
                data = new byte[] {
                        0x19,
                        (byte)(value >> 8),
                        (byte)value
                };
            } else {
                data = new byte[] {
                        0x1A,
                        (byte)(value >> 24),
                        (byte)(value >> 16),
                        (byte)(value >> 8),
                        (byte)value
                };
            }

            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            for (int i = 0; i < data.length - 1; i++) {
                parser.feedInput(data, i, i + 1);
                assertEquals(JsonToken.NOT_AVAILABLE, parser.nextToken());
            }
            parser.feedInput(data, data.length - 1, data.length);
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(value, parser.getIntValue());
            parser.close();
        }
    }

    // Test array size boundaries
    @Test
    public void testArraySizeBoundaries() throws IOException {
        // Test array sizes near boundaries
        int[] testSizes = {
                23, 24, // Small array boundary
                255, 256, // One-byte boundary
                65535, 65536 // Two-byte boundary
        };

        for (int size : testSizes) {
            byte[] data;
            if (size <= 23) {
                data = new byte[] { (byte)(0x80 + size) };
            } else if (size <= 255) {
                data = new byte[] { (byte)0x98, (byte)size };
            } else if (size <= 65535) {
                data = new byte[] {
                        (byte)0x99,
                        (byte)(size >> 8),
                        (byte)size
                };
            } else {
                data = new byte[] {
                        (byte)0x9A,
                        (byte)(size >> 24),
                        (byte)(size >> 16),
                        (byte)(size >> 8),
                        (byte)size
                };
            }

            NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
            parser.feedInput(data, 0, data.length);
            assertEquals(JsonToken.START_ARRAY, parser.nextToken());
            parser.close();
        }
    }

    @Test
    public void testArrayWithNumbers() throws IOException {
        // Array containing nested arrays with numbers
        // Structure: [2, [1, 2], [3], [4, 5]]
        byte[] data = new byte[] {
                (byte) 0x84,  // Array of 4 elements
                (byte) 0x02,  // Number 2
                (byte) 0x82, (byte) 0x01, (byte) 0x02,  // Array [1, 2]
                (byte) 0x81, (byte) 0x03,  // Array [3]
                (byte) 0x82, (byte) 0x04, (byte) 0x05   // Array [4, 5]
        };

        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, data.length);

        // Start of outer array
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());

        // First element: 2
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(2, parser.getIntValue());

        // Second element: array [1, 2]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1, parser.getIntValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(2, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Third element: array [3]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(3, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Fourth element: array [4, 5]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(4, parser.getIntValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(5, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // End of outer array
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // No more tokens
        assertEquals(null, parser.nextToken());

        parser.close();
    }

    @Test
    public void testNestedArraysWithSingleNumbers() throws IOException {
        // [[1], [2], [3], [4], [5]]
        byte[] data = new byte[] {
                (byte) 0x85,  // Array of 5 elements
                (byte) 0x81, (byte) 0x01,  // [1]
                (byte) 0x81, (byte) 0x02,  // [2]
                (byte) 0x81, (byte) 0x03,  // [3]
                (byte) 0x81, (byte) 0x04,  // [4]
                (byte) 0x81, (byte) 0x05   // [5]
        };

        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, data.length);

        assertEquals(JsonToken.START_ARRAY, parser.nextToken());

        for (int i = 1; i <= 5; i++) {
            assertEquals(JsonToken.START_ARRAY, parser.nextToken());
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(i, parser.getIntValue());
            assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        }

        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(null, parser.nextToken());
        parser.close();
    }

    @Test
    public void testDeepNestedArrays() throws IOException {
        // [1, [2, [3, [4, [5]]]]]
        byte[] data = new byte[] {
                (byte) 0x82,  // Array of 2 elements
                (byte) 0x01,  // 1
                (byte) 0x82,  // Array of 2
                (byte) 0x02,  // 2
                (byte) 0x82,  // Array of 2
                (byte) 0x03,  // 3
                (byte) 0x82,  // Array of 2
                (byte) 0x04,  // 4
                (byte) 0x81,  // Array of 1
                (byte) 0x05   // 5
        };

        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, data.length);

        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1, parser.getIntValue());

        // Start diving into nested arrays
        for (int i = 2; i <= 4; i++) {
            assertEquals(JsonToken.START_ARRAY, parser.nextToken());
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(i, parser.getIntValue());
        }

        // Deepest level
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(5, parser.getIntValue());

        // Close all arrays
        for (int i = 0; i < 5; i++) {
            assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        }

        assertEquals(null, parser.nextToken());
        parser.close();
    }

    @Test
    public void testArrayWithMultipleNestedLevels() throws IOException {
        // [1, [2, 3], [4, [5, 6]], [7, [8, [9, 10]]]]
        byte[] data = new byte[] {
                (byte) 0x84,  // Array of 4 elements
                (byte) 0x01,  // 1
                (byte) 0x82, (byte) 0x02, (byte) 0x03,  // [2, 3]
                (byte) 0x82, (byte) 0x04, (byte) 0x82, (byte) 0x05, (byte) 0x06,  // [4, [5, 6]]
                (byte) 0x82, (byte) 0x07, (byte) 0x82, (byte) 0x08, (byte) 0x82, (byte) 0x09, (byte) 0x0A  // [7, [8, [9, 10]]]
        };

        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, data.length);

        assertEquals(JsonToken.START_ARRAY, parser.nextToken());

        // First element
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1, parser.getIntValue());

        // Second element [2, 3]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(2, parser.getIntValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(3, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Third element [4, [5, 6]]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(4, parser.getIntValue());
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(5, parser.getIntValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(6, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Fourth element [7, [8, [9, 10]]]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(7, parser.getIntValue());
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(8, parser.getIntValue());
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(9, parser.getIntValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(10, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(null, parser.nextToken());
        parser.close();
    }

    @Test
    public void testArrayWithEmptyArrays() throws IOException {
        // [[], [1], [], [1, []], [[], []], [1, [], 2]]
        byte[] data = new byte[] {
                (byte) 0x86,  // Array of 6 elements
                (byte) 0x80,  // []
                (byte) 0x81, (byte) 0x01,  // [1]
                (byte) 0x80,  // []
                (byte) 0x82, (byte) 0x01, (byte) 0x80,  // [1, []]
                (byte) 0x82, (byte) 0x80, (byte) 0x80,  // [[], []]
                (byte) 0x83, (byte) 0x01, (byte) 0x80, (byte) 0x02  // [1, [], 2]
        };

        NonBlockingByteArrayParser parser = new CBORFactory().createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, data.length);

        assertEquals(JsonToken.START_ARRAY, parser.nextToken());

        // First element: []
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Second element: [1]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Third element: []
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Fourth element: [1, []]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1, parser.getIntValue());
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Fifth element: [[], []]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        // Sixth element: [1, [], 2]
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1, parser.getIntValue());
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(2, parser.getIntValue());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        assertEquals(null, parser.nextToken());
        parser.close();
    }

    @Test
    public void testStartObjectToken() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartObject(null, 1);
        generator.writeStringField("id", UUID.randomUUID().toString());
        generator.close();
        byte[] data = out.toByteArray();

        NonBlockingByteArrayParser parser = new CBORFactory()
                .createNonBlockingByteArrayParser();
        parser.feedInput(data, 0, 1);
        assertEquals(JsonToken.START_OBJECT,  parser.nextToken());
        assertEquals("{", parser.getText());
    }
}
