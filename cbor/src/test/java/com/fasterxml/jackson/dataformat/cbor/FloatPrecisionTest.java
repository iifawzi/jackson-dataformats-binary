package com.fasterxml.jackson.dataformat.cbor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

// for [jackson-core#730]
public class FloatPrecisionTest extends CBORTestBase
{
    // for [jackson-core#730]
    @Test
    public void testFloatRoundtrips() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator gen = cborGenerator(out);
        gen.writeStartObject();
        gen.writeStringField("key", "value");
        gen.writeEndObject();
        gen.close();
        byte[] expected = out.toByteArray();

        CBORParser parser = cborParser(expected);

        parser.nextToken();
        parser.nextToken();
        JsonToken a = parser.nextToken();
        parser.getText();
    }
}
