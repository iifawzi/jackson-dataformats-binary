package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.dataformat.cbor.CBORParserBase;
import com.fasterxml.jackson.dataformat.cbor.CBORReadContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

public abstract class NonBlockingParserBase extends CBORParserBase {

    /*
    /**********************************************************************
    /* Major state constants
    /**********************************************************************
     */

    /**
     * State right after parser has been constructed, before seeing the first byte.
     */
    protected final static int MAJOR_ROOT = 0;

    protected final static int MAJOR_UNSIGNED_INT_ELEMENT = 1;
    protected final static int MAJOR_NEGATIVE_INT_ELEMENT = 2;
    protected final static int MAJOR_ARRAY_ELEMENT = 5;
    protected final static int MAJOR_OBJECT_ELEMENT = 6;
    protected final static int MAJOR_FIELD_ELEMENT = 7;

    /**
     * State after non-blocking input source has indicated that no more input
     * is forthcoming AND we have exhausted all the input
     */
    protected final static int MAJOR_CLOSED = 10;

    // // // "Sub-states"
    protected final static int MINOR_PENDING_BYTES = 1;
    protected final static int MINOR_PENDING_BYTES_UNSIGNED = 3;
    protected final static int MINOR_PENDING_BYTES_NEGATIVE = 4;
    protected final static int MINOR_FIELD_NAME_PENDING = 5;

    /*
    /**********************************************************************
    /* Other buffering
    /**********************************************************************
     */

    /**
     * Temporary buffer for holding content if input not contiguous (but can
     * fit in buffer)
     */
    protected byte[] _inputCopy;

    /**
     * Number of bytes buffered in <code>_inputCopy</code>
     */
    protected int _inputCopyLen;


    /**
     * Temporary storage for 32-bit values (int, float), as well as length markers
     * for length-prefixed values.
     */
    protected int _pending32;

    protected long _pending64;


    /**
     * fawzi
     * Bytes that are pending to be read to know the length/value of current major type.
     */
    protected int _pendingBytesLength = 0;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public NonBlockingParserBase(IOContext ioContext) {
        super(ioContext);
        _majorState = MAJOR_ROOT;
        _inputCopy = ioContext.allocReadIOBuffer(500);
    }


    /*
    /**********************************************************************
    /* Internal methods, state changes
    /**********************************************************************
     */

    /**
     * Helper method called at point when all input has been exhausted and
     * input feeder has indicated no more input will be forthcoming.
     */
    protected final JsonToken _eofAsNextToken() throws IOException {
        _majorState = MAJOR_CLOSED;
        if (!_streamReadContext.inRoot()) {
            _handleEOF();
        }
        close();
        return _updateTokenToNull();
    }

    @Override
    public JsonToken nextToken() throws IOException {
        return null;
    }

    @Deprecated // since 2.17
    @Override
    public String getCurrentName() throws IOException { return currentName(); }

    @Override // since 2.17
    public String currentName() throws IOException
    {
        if (_currToken == JsonToken.START_OBJECT) {
            CBORReadContext parent = _streamReadContext.getParent();
            return parent.getCurrentName();
        }
        return _streamReadContext.getCurrentName();
    }

    @Override
    public ObjectCodec getCodec() {
        return null;
    }

    @Override
    public void setCodec(ObjectCodec objectCodec) {

    }

    @Override
    public Version version() {
        return null;
    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public JsonStreamContext getParsingContext() {
        return null;
    }

    @Override
    public JsonLocation getCurrentLocation() {
        return null;
    }

    @Override
    public JsonLocation getTokenLocation() {
        return null;
    }

    @Override
    public void overrideCurrentName(String s) {

    }

    @Override
    public String getText() throws IOException {
        return _currToken.asString();
    }

    @Override
    public char[] getTextCharacters() throws IOException {
        return new char[0];
    }

    @Override
    public boolean hasTextCharacters() {
        return false;
    }

    @Override
    public Number getNumberValue() throws IOException {
        return null;
    }

    @Override
    public NumberType getNumberType() throws IOException {
        return null;
    }

    @Override
    public int getIntValue() throws IOException {
        if (_majorState == MAJOR_NEGATIVE_INT_ELEMENT) {
            return -_numberInt - 1;
        }
       return _numberInt;
    }

    @Override
    public long getLongValue() throws IOException {
        if (_majorState == MAJOR_NEGATIVE_INT_ELEMENT) {
            return -_numberLong - 1;
        }
        return _numberLong;
    }

    @Override
    public BigInteger getBigIntegerValue() throws IOException {
        if (_majorState == MAJOR_NEGATIVE_INT_ELEMENT) {
            return _numberBigInt.negate().subtract(BigInteger.ONE);
        }
        return _numberBigInt;
    }

    @Override
    public float getFloatValue() throws IOException {
        return 0;
    }

    @Override
    public double getDoubleValue() throws IOException {
        return 0;
    }

    @Override
    public BigDecimal getDecimalValue() throws IOException {
        return null;
    }

    @Override
    public int getTextLength() throws IOException {
        return 0;
    }

    @Override
    public int getTextOffset() throws IOException {
        return 0;
    }

    @Override
    public byte[] getBinaryValue(Base64Variant base64Variant) throws IOException {
        return new byte[0];
    }

       /*
    /**********************************************************************
    /* Handling of nested scope, state
    /**********************************************************************
     */

}
