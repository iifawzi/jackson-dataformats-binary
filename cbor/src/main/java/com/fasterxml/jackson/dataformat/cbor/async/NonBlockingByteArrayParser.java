package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.dataformat.cbor.CBORConstants;

import java.io.IOException;
import java.io.OutputStream;

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
            case MINOR_VALUE_UNSIGNED_INT:
                return _finishUnsignedNumber();
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
                return _startUnsignedNumber(lowBits);
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

    private JsonToken _startUnsignedNumber(int lowBits) throws IOException {
        _pendingBytesLen = _decodeNeededBytes(lowBits);
        _setNumTypesValid();

        // common case first: have all we need
        if (lowBits <= 23) {
            _numberInt = lowBits;
            _numTypesValid = NR_INT;
            return _valueComplete(JsonToken.VALUE_NUMBER_INT);
        }

        return _finishUnsignedNumber();
    }

    private JsonToken _finishUnsignedNumber() throws IOException {
        while (_inputPtr < _inputEnd && _pendingBytesLen-- > 0) {
            if (_numTypesValid == NR_LONG) {
                _pending64 = (_pending64 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            } else {
                _pending32 = (_pending32 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            }
        }

        if (_pendingBytesLen != 0) {
            _minorState = MINOR_VALUE_UNSIGNED_INT;
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
                _numberBigInt = _bigPositive(_pending64);
                _numTypesValid = NR_BIGINT;
                return;
            }
            _numberLong = _pending64;
        } else {
            if (_pending32 < 0) {
                _numberLong = _pending32 & 0xFFFFFFFFL;
                _numTypesValid = NR_LONG;
                return;
            }
            _numberInt = _pending32;
        }
    }

    private void _clearRetainedNumData() {
        _numberInt = 0;
        _numberLong = 0;
        _numberBigInt = null;
        _pending64 = 0;
        _pending32 = 0;
        _numTypesValid = NR_UNKNOWN;
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
