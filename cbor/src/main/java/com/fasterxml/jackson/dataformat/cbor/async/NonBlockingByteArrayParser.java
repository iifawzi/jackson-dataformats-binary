package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.async.ByteArrayFeeder;

import java.io.IOException;

public class NonBlockingByteArrayParser extends NonBlockingParserBase implements ByteArrayFeeder {
    @Override
    public void feedInput(byte[] bytes, int i, int i1) throws IOException {

    }

    @Override
    public boolean needMoreInput() {
        return false;
    }

    @Override
    public void endOfInput() {

    }
}
