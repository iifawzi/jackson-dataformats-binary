package com.fasterxml.jackson.dataformat.cbor.parse.async;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.async.AsyncReaderWrapper;
import com.fasterxml.jackson.dataformat.cbor.async.AsyncTestBase;
import org.junit.jupiter.api.Test;


import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncNumberParseTest extends AsyncTestBase {

    @Test
    public void testIntValues() throws Exception {
        CBORFactory f = cborFactory();
        _testInt(f, 13);
        _testInt(f, -19);
        // two bytes
        _testInt(f, 255);
        _testInt(f, -127);
        // three
        _testInt(f, 256);
        _testInt(f, 0xFFFF);
        _testInt(f, -300);
        _testInt(f, -0xFFFF);
        // and all 4 bytes
        _testInt(f, 0x7FFFFFFF);
        _testInt(f, -0x7FFF0002);
        _testInt(f, 0x70000000 << 1);
    }

    @Test
    public void testLongValues() throws Exception {
        CBORFactory f = cborFactory();

        _testLong(f, 1L + Integer.MAX_VALUE);
        _testLong(f, Long.MIN_VALUE);
        _testLong(f, Long.MAX_VALUE);
        _testLong(f, -1L + Integer.MIN_VALUE);
    }


    public void _testInt(CBORFactory f, int value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = cborGenerator(f, out);
        gen.writeNumber(value);
        gen.close();
        byte[] input = out.toByteArray();

        _verifyInt(f, input, 0, 900, value);
        _verifyInt(f, input, 0, 3, value);
        _verifyInt(f, input, 0, 1, value);
    }

    private void _verifyInt(CBORFactory f, byte[] data, int offset, int readSize, int value) throws IOException {
        AsyncReaderWrapper r = asyncForBytes(f, readSize, data, offset);
        assertNull(r.currentToken());

        assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
        assertEquals(value, r.getNumberValue());
        assertEquals(JsonParser.NumberType.INT, r.getNumberType());
        assertNull(r.nextToken());
        assertTrue(r.isClosed());
    }

    public void _testLong(CBORFactory f, long value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = cborGenerator(f, out);
        gen.writeNumber(value);
        gen.close();
        byte[] input = out.toByteArray();

        _verifyLong(f, input, 0, 900, value);
        _verifyLong(f, input, 0, 3, value);
        _verifyLong(f, input, 0, 1, value);
    }

    private void _verifyLong(CBORFactory f, byte[] data, int offset, int readSize, long value) throws IOException {
        AsyncReaderWrapper r = asyncForBytes(f, readSize, data, offset);
        assertNull(r.currentToken());

        assertToken(JsonToken.VALUE_NUMBER_INT, r.nextToken());
        assertEquals(value, r.getNumberValue());
        assertEquals(JsonParser.NumberType.LONG, r.getNumberType());
        assertNull(r.nextToken());
        assertTrue(r.isClosed());
    }

}
