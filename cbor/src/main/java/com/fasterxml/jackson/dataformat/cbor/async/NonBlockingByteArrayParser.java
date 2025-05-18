package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.dataformat.cbor.CBORConstants;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;

public class NonBlockingByteArrayParser extends NonBlockingParserBase implements ByteArrayFeeder {
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public NonBlockingByteArrayParser(IOContext ctxt, int parserFeatures, int smileFeatures) {
        super(ctxt, parserFeatures, smileFeatures);
    }
    /*
    /**********************************************************************
    /* AsyncInputFeeder impl
    /**********************************************************************
     */

    @Override
    public ByteArrayFeeder getNonBlockingInputFeeder() {
        return this;
    }

    @Override
    public final boolean needMoreInput() {
        return (_inputPtr >= _inputEnd) && !_endOfInput;
    }

    @Override
    public void feedInput(byte[] buf, int start, int end) throws IOException {
        // Must not have a remaining input
        if (_inputPtr < _inputEnd) {
            _reportError("Still have %d undecoded bytes, should not call 'feedInput'", _inputEnd - _inputPtr);
        }
        if (end < start) {
            _reportError("Input end (%d) may not be before start (%d)", end, start);
        }
        // and shouldn't have been marked as end-of-input
        if (_endOfInput) {
            _reportError("Already closed, can not feed more input");
        }
        // Time to update pointers first
        _currInputProcessed += _origBufferLen;
        _streamReadConstraints.validateDocumentLength(_currInputProcessed);

        // And then update buffer settings
        _inputBuffer = buf;
        _inputPtr = start;
        _inputEnd = end;
        _origBufferLen = end - start;
    }

    @Override
    public void endOfInput() {
        _endOfInput = true;
    }

    /*
    /**********************************************************************
    /* Main-level decoding
    /**********************************************************************
    */

    @Override
    public JsonToken nextToken() throws IOException {
        // First: regardless of where we really are, need at least one more byte;
        // can simplify some of the checks by short-circuiting right away
        if (_inputPtr >= _inputEnd) {
            if (_closed) {
                return null;
            }
            // note: if so, do not even bother changing state
            if (_endOfInput) { // except for this special case
                return _eofAsNextToken();
            }
            return JsonToken.NOT_AVAILABLE;
        }
        // in the middle of tokenization?
        if (_currToken == JsonToken.NOT_AVAILABLE) {
            return _finishToken();
        }

        _clearRetainedNumData();
        _clearRetainedTagsData();
        int ch = _inputBuffer[_inputPtr++] & 0xFF;

        switch (_majorState) {
            case MAJOR_ROOT:
                return _startValue(ch);
        }

        throw new IllegalStateException("Illegal state when trying to complete token: majorState=" + _majorState);
    }

    /**
     * Method called when a major type has been detected, but not all
     * the contents have been decoded due to incomplete input available.
     */
    protected final JsonToken _finishToken() throws IOException {

        switch (_minorState) {
            case MINOR_VALUE_INT:
                return _finishNumber();
            case MINOR_VALUE_TAG:
                return _finishTag();
        }
        throw new IllegalStateException("Illegal state when trying to complete token: majorState=" + _majorState);
    }

    /*
    /**********************************************************************
    /* Second-level decoding
    /**********************************************************************
    */

    /**
     * Helper method called to detect a type of value token (at any level) and possibly
     * decode it if contained in the input buffer.
     */
    private JsonToken _startValue(int ch) throws IOException {
        int type = ch >> 5;
        int lowBits = ch & 0x1F;

        switch (type) {
            case CBORConstants.MAJOR_TYPE_INT_POS:
                _typeByte = CBORConstants.MAJOR_TYPE_INT_POS;
                return _startNumber(lowBits);
            case CBORConstants.MAJOR_TYPE_INT_NEG:
                _typeByte = CBORConstants.MAJOR_TYPE_INT_NEG;
                return _startNumber(lowBits);
            case CBORConstants.MAJOR_TYPE_BYTES:
                return _startNumber(lowBits);
            case CBORConstants.MAJOR_TYPE_TAG: // TODO:: ensure no memory issues
                return _startTag(lowBits);

        }
        // If we get this far, type byte is corrupt
        _reportError("Invalid type marker byte 0x%02x for expected value token", ch & 0xFF);
        return null;
    }

    /*
    /**********************************************************************
    /* Internal methods: second-level parsing: numbers, integral
    /**********************************************************************
    */

    private JsonToken _startNumber(int lowBits) throws IOException {
        _pendingBytesLen = _decodeNeededBytes(lowBits);
        _setNumTypesValid();

        // common case first: have all we need
        if (lowBits <= 23) {
            if (_typeByte == CBORConstants.MAJOR_TYPE_INT_NEG) {
                _numberInt = -1 - lowBits;
            } else {
                _numberInt = lowBits;
            }
            _numTypesValid = NR_INT;
            return _valueComplete(JsonToken.VALUE_NUMBER_INT);
        }

        return _finishNumber();
    }

    private JsonToken _finishNumber() throws IOException {
        while (_inputPtr < _inputEnd && _pendingBytesLen-- > 0) {
            if (_numTypesValid == NR_LONG) {
                _pending64 = (_pending64 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            } else {
                _pending32 = (_pending32 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            }
        }

        if (_pendingBytesLen != 0) {
            _minorState = MINOR_VALUE_INT;
            return _updateTokenToNA();
        }

        // no more bytes needed, the number is finished.
        _numIsFinished();
        return _valueComplete(JsonToken.VALUE_NUMBER_INT);
    }

    private void _setNumTypesValid() {
        if (_pendingBytesLen <= 4) {
            _numTypesValid = NR_INT;
            return;
        }
        _numTypesValid = NR_LONG;
    }

    private void _numIsFinished() {
        if ((_numTypesValid & NR_LONG) != 0) {
            if (_pending64 < 0L) {
                _promoteToAndSetBigInteger();
                return;
            }
            _numberLong = (_typeByte == CBORConstants.MAJOR_TYPE_INT_NEG) ? -1L - _pending64 : _pending64;
            return;
        }

        if (_pending32 < 0) {
            _promoteToAndSetLong();
            return;
        }
        _numberInt = (_typeByte == CBORConstants.MAJOR_TYPE_INT_NEG) ? -1 - _pending32 : _pending32;
    }

    private void _promoteToAndSetBigInteger() {
        _numTypesValid = NR_BIGINT;
        if (_typeByte == CBORConstants.MAJOR_TYPE_INT_NEG) {
            _numberBigInt = BigInteger.ONE.negate().subtract(_bigPositive(_pending64));
        } else {
            _numberBigInt = _bigPositive(_pending64);
        }
    }

    private void _promoteToAndSetLong() {
        _numTypesValid = NR_LONG;
        long unsignedValue = _pending32 & 0xFFFFFFFFL;
        _numberLong = (_typeByte == CBORConstants.MAJOR_TYPE_INT_NEG) ? -1L - unsignedValue : unsignedValue;
    }


    private void _clearRetainedNumData() {
        _numberInt = 0;
        _numberLong = 0;
        _numberBigInt = null;
        _pending64 = 0;
        _pending32 = 0;
        _numTypesValid = NR_UNKNOWN;
        _typeByte = -1;
    }

    /*
    /**********************************************************************
    /* Internal methods: second-level parsing: Tags
    /**********************************************************************
    */

    private JsonToken _startTag(int lowBits) throws IOException {
        if (lowBits <= 23) {
            _tagValues.add(lowBits);
            return _startValueAfterTag();
        }

        _pendingBytesLen = _decodeNeededBytes(lowBits);
        return _finishTag();
    }

    private JsonToken _startValueAfterTag() throws IOException {
        int ch = _inputBuffer[_inputPtr++] & 0xFF;
        int type = ch >> 5;

        // another tag to process
        if (type == CBORConstants.MAJOR_TYPE_TAG) {
            return _startTag(ch & 0x1F);
        }
        return _startValue(ch);
    }

    private JsonToken _finishTag() throws IOException {
        // for now we're supporting only int tag values (up to 2^31 - 1)
        while (_inputPtr < _inputEnd && _pendingBytesLen-- > 0) {
            _pending32 = (_pending32 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
        }

        if (_pendingBytesLen != 0) {
            _minorState = MINOR_VALUE_TAG;
            return _updateTokenToNA();
        }

        // no more bytes needed, tag finished.
        _tagValues.add(_pending32);
        return _startValueAfterTag();
    }

    private void _clearRetainedTagsData() {
        _tagValues.clear();
    }

    /*
    /**********************************************************************
    /* Abstract methods/overrides from JsonParser
    /**********************************************************************
    */

    @Override
    public int releaseBuffered(OutputStream out) throws IOException {
        int count = _inputEnd - _inputPtr;
        if (count < 1) {
            return 0;
        }
        int origPtr = _inputPtr;
        out.write(_inputBuffer, origPtr, count);
        return count;
    }

    /*
    /**********************************************************************
    /* Other helper methods
    /**********************************************************************
    */

    private int _decodeNeededBytes(int lowBits) throws IOException {
        if (lowBits <= 23) {
            return 0;
        }
        int diff = lowBits - 24;
        if (diff > 3) {
            throw _constructError(String.format(
                    "Invalid 5-bit length indicator for `JsonToken.%s`: 0x%02X; only 0x00-0x17, 0x1F allowed",
                    currentToken(), lowBits));
        }

        return 1 << diff;
    }
}
