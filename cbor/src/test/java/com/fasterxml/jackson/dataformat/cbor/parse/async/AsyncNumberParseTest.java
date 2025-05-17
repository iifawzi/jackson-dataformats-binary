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
        _testInt(f, 0);
        _testInt(f, 23);
        // single byte
        _testInt(f, 255);
        _testInt(f, 23);
        // 2 bytes
        _testInt(f, 256);
        _testInt(f, 65535);
        // 3 bytes
        _testInt(f, 65536);
        _testInt(f, 16777215);
        // 4 bytes
        _testInt(f, 16777216);
        _testInt(f, 2147483647);
    }

    @Test
    public void testLongValues() throws Exception {
        CBORFactory f = cborFactory();

        // 4 bytes
        _testLong(f, 2147483648L);
        _testLong(f, 4294967295L);

        // 5 bytes
        _testLong(f, 4294967296L);
        _testLong(f, 34359738367L);

        // 6 bytes
        _testLong(f, 34359738368L);
        _testLong(f, 281474976710655L);

        // 7 bytes
        _testLong(f, 281474976710656L);
        _testLong(f, 72057594037927935L);

        // 8 bytes
        _testLong(f, 72057594037927936L);
        _testLong(f, 9223372036854775807L);
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
