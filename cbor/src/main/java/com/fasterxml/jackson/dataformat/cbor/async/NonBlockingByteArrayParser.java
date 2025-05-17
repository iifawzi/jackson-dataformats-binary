package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.io.IOContext;

import java.io.IOException;
import java.io.OutputStream;

public class NonBlockingByteArrayParser extends NonBlockingParserBase implements ByteArrayFeeder {
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public NonBlockingByteArrayParser(IOContext ctxt, int parserFeatures, int smileFeatures)
    {
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
        return (_inputPtr >=_inputEnd) && !_endOfInput;
    }

    @Override
    public void feedInput(byte[] buf, int start, int end) throws IOException
    {
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
    public JsonToken nextToken() throws IOException
    {
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

        throw new IllegalStateException("Illegal state when trying to complete token: majorState="+_majorState);
    }

    /**
     * Method called when a major type has been detected, but not all
     * the contents have been decoded due to incomplete input available.
     */
    protected final JsonToken _finishToken() throws IOException
    {
        throw new IllegalStateException("Illegal state when trying to complete token: majorState="+_majorState);
    }

    @Override
    public int releaseBuffered(OutputStream out) throws IOException
    {
        int count = _inputEnd - _inputPtr;
        if (count < 1) {
            return 0;
        }
        int origPtr = _inputPtr;
        out.write(_inputBuffer, origPtr, count);
        return count;
    }
}
