package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORTestBase;

import java.io.IOException;

public abstract class AsyncTestBase extends CBORTestBase {
    protected AsyncReaderWrapper asyncForBytes(CBORFactory f, int bytesPerRead, byte[] bytes, int padding) throws IOException {
        return new AsyncReaderWrapperForByteArray(f.createNonBlockingByteArrayParser(), bytesPerRead, bytes, padding);
    }

    protected final JsonToken verifyStart(AsyncReaderWrapper reader) throws Exception {
        assertToken(JsonToken.NOT_AVAILABLE, reader.currentToken());
        return reader.nextToken();
    }
}
