package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.dataformat.cbor.CBORParserBase;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;

public abstract class NonBlockingParserBase extends CBORParserBase {

    /*
    /**********************************************************************
    /* Major state constants
    /**********************************************************************
     */

    /**
     * State right after a parser has been constructed or a root value has been
     * finished, but the next token has not yet been recognized.
     */
    protected final static int MAJOR_ROOT = 0;

    /**
     * State after a non-blocking input source has indicated that no more input
     * is forthcoming AND we have exhausted all the input
     */
    protected final static int MAJOR_CLOSED = 5;

    // // // "Sub-states"
    protected final static int MINOR_VALUE_INT = 1;
    protected final static int MINOR_VALUE_TAG = 2;

    /*
    /**********************************************************************
    /* Input source config
    /**********************************************************************
     */

    /**
     * In addition to the current buffer pointer and end pointer,
     * we will also need to know the number of bytes originally
     * contained. This is needed to correctly update location
     * information when the token has been completed.
     */
    protected int _origBufferLen;

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
     * Number of bytes needed to finish decoding a major type
     */
    protected int _pendingBytesLen;

    /**
     * Temporary storage for 32-bit values (int, float), as well as length markers
     * for length-prefixed values.
     */
    protected int _pending32;

    /**
     * Temporary storage for 64-bit values (long, double), secondary storage
     * for some other things (scale of BigDecimal values)
     */
    protected long _pending64;


    /*
    /**********************************************************************
    /* Additional parsing state
    /**********************************************************************
     */

    /**
     * Current main decoding state
     */
    protected int _majorState;

    /**
     * Addition indicator within a state; contextually relevant for just that state
     */
    protected int _minorState;

    /**
     * Value of {@link #_majorState} after completing a scalar value
     */
    protected int _majorStateAfterValue;

    /**
     * Flag sent when calling the application indicates that there will
     * be no more input to parse.
     */
    protected boolean _endOfInput = false;

    /**
     * Flag Indicating which majorType we're processing
     */
    protected int _typeByte;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
    */

    protected NonBlockingParserBase(IOContext ctxt, int parserFeatures, int cborFeatures) {
        super(ctxt, parserFeatures, cborFeatures);
        // We don't need a lot; for most things maximum known a-priori length below 70 bytes
        _inputCopy = ctxt.allocReadIOBuffer(500);
        _updateTokenToNull();
        _majorState = MAJOR_ROOT;
        _majorStateAfterValue = MAJOR_ROOT;
    }

    @Override
    public ObjectCodec getCodec() {
        return null;
    }

    @Override
    public void setCodec(ObjectCodec c) {
        throw new UnsupportedOperationException("Can not use ObjectMapper with non-blocking parser");
    }

    @Override
    public boolean canParseAsync() {
        return true;
    }

    /*
    /**********************************************************
    /* Abstract methods from JsonParser
    /**********************************************************
     */

    @Override
    public abstract int releaseBuffered(OutputStream out) throws IOException;

    @Override
    protected void _closeInput() {
        // nothing to do here
    }

    /*
    /**********************************************************************
    /* Internal methods, state changes
    /**********************************************************************
     */

    /**
     * Helper method called at the point when all inputs have been exhausted, and
     * the input feeder has indicated no more input will be forthcoming.
     */
    protected JsonToken _eofAsNextToken() throws IOException {
        // NOTE: here we can and should close input, release buffers, since
        // this is "hard" EOF, not a boundary imposed by header token.
        _tagValues.clear();
        close();

        // 30-Jan-2021, tatu: But also MUST verify that end-of-content is actually
        //   allowed (see [dataformats-binary#240] for example)
        _handleEOF();
        return _updateTokenToNull();
    }

    @Override
    protected void _handleEOF() throws JsonParseException {
        if (_streamReadContext.inRoot()) {
            return;
        }
        // Ok; end-marker or fixed-length Array/Object?
        final JsonLocation loc = _streamReadContext.startLocation(_ioContext.contentReference());
        final String startLocDesc = (loc == null) ? "[N/A]" : loc.sourceDescription();
        if (_streamReadContext.hasExpectedLength()) { // specific length
            final int expMore = _streamReadContext.getRemainingExpectedLength();
            if (_streamReadContext.inArray()) {
                _reportInvalidEOF(String.format(
                                " in Array value: expected %d more elements (start token at %s)",
                                expMore, startLocDesc),
                        null);
            } else {
                _reportInvalidEOF(String.format(
                                " in Object value: expected %d more properties (start token at %s)",
                                expMore, startLocDesc),
                        null);
            }
        } else {
            if (_streamReadContext.inArray()) {
                _reportInvalidEOF(String.format(
                                " in Array value: expected an element or close marker (0xFF) (start token at %s)",
                                startLocDesc),
                        null);
            } else {
                _reportInvalidEOF(String.format(
                                " in Object value: expected a property or close marker (0xFF) (start token at %s)",
                                startLocDesc),
                        null);
            }
        }
    }

    protected final JsonToken _valueComplete(JsonToken t) throws IOException
    {
        _majorState = _majorStateAfterValue;
        return _updateToken(t);
    }

    @Override
    public String getCurrentName() throws IOException {
        return "";
    }


    @Override
    public Version version() {
        return null;
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
        return "";
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
    public long getLongValue() throws IOException {
        return 0;
    }

    @Override
    public BigInteger getBigIntegerValue() throws IOException {
        return null;
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
}
