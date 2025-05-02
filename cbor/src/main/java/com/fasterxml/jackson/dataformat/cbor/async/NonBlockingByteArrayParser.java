package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.async.NonBlockingInputFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.io.IOContext;

import java.io.IOException;

public class NonBlockingByteArrayParser extends NonBlockingParserBase implements ByteArrayFeeder {

    /*
    /**********************************************************************
    /* Input source config
    /**********************************************************************
     */

    /**
     * This buffer is actually provided via {@link NonBlockingInputFeeder}
     */
    protected byte[] _inputBuffer = NO_BYTES;

    /**
     * In addition to current buffer pointer, and end pointer,
     * we will also need to know number of bytes originally
     * contained. This is needed to correctly update location
     * information when the block has been completed.
     */
    protected int _origBufferLen;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public NonBlockingByteArrayParser(IOContext ioContext) {
        super(ioContext);
    }

    /*
    /**********************************************************************
    /* AsyncInputFeeder impl
    /**********************************************************************
     */

    @Override
    public ObjectCodec getCodec() {
        return null;
    }

    @Override
    public void setCodec(ObjectCodec objectCodec) {

    }

    @Override
    public ByteArrayFeeder getNonBlockingInputFeeder() {
        return this;
    }

    @Override
    public Version version() {
        return null;
    }

    @Override
    public final boolean needMoreInput() {
        return (_inputPtr >= _inputEnd) && !_endOfInput;
    }

    @Override
    public void feedInput(byte[] buf, int start, int end) throws IOException {
        // Must not have remaining input
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

        // Updating pointers
        _currInputProcessed += _origBufferLen;
        _streamReadConstraints.validateDocumentLength(_currInputProcessed);

        // Updating buffer settings
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

        if (!_streamReadContext.expectMoreValues() && _pendingBytesLength == 0) {
            _streamReadContext = _streamReadContext.getParent();
            return _updateToken(JsonToken.END_ARRAY);
        }

        // TODO:: check testSharedNames in smile.

        // in the middle of tokenization, finish whatever was started
        if (_currToken == JsonToken.NOT_AVAILABLE) {
            return _finishToken();
        }

        // First: regardless of where we really are, need at least one more byte;
        // can simplify some of the checks by short-circuiting right away
        if (_inputPtr >= _inputEnd) {
            return _eofAsNextToken();
        }


        // TODO:: FAWZI NEEDED TO CLEAR ALL STORED THINGS.
        // No: fresh new token; may or may not have existing one
        _numTypesValid = NR_UNKNOWN;

        // fawzi: to convert byte to unsigned int
        int ch = _inputBuffer[_inputPtr++] & 0xFF;

        // fawzi
        // finally, shift all bits to right
        // so we have only the three most significant bits (b7,b6,b5)
        // This operation is commonly used when you need to extract specific bits from a byte,
        int type = (ch >> 5);
        // fawzi
        //  Hexadecimal: 0x1F
        //   Decimal:     31
        //   Binary:      00011111
        /**
         *  ch:          [b7 b6 b5 b4 b3 b2 b1 b0]
         *    0x1F:        [0  0  0  1  1  1  1  1]
         *                 ------------------------
         *    Result:      [0  0  0  b4 b3 b2 b1 b0]
         */
        int lowBits = ch & 0x1F;

        // TODO:: here we start to check types and act accordingly.

        switch (type) {
            case 0: // positive int
                return decodeUnsignedInteger(lowBits);
            case 1: // negative int
                return decodeNegativeInteger(lowBits);
            case 4: // start array
                return _startArrayElement(lowBits);
            case 5:
                return _startObjectElement(lowBits);
        }
        return _updateToken(JsonToken.NOT_AVAILABLE);
    }


    /**
     * Method called when decoding of a token has been started, but not yet completed due
     * to missing input; method is to continue decoding due to at least one more byte
     * being made available to decode.
     *
     * @return Token decoded, if complete; {@link JsonToken#NOT_AVAILABLE} if not
     * @throws IOException (generally {@link JsonParseException}) for decoding problems
     */
    protected final JsonToken _finishToken() throws IOException {
        switch (_minorState) {
            case MINOR_PENDING_ARRAY_LENGTH:
                _finishMajorTypeLength();
                // we're done with pending bytes length. we know the value now.
                if (_pendingBytesLength == 0) {
                    createChildArrayContext(_pending32);
                    return _updateToken(JsonToken.START_ARRAY);
                }

            case MINOR_PENDING_BYTES:
                return _completePendingBytes();
            case MINOR_PENDING_BYTES_UNSIGNED:
                return _completePendingUnsigned();
            case MINOR_PENDING_BYTES_NEGATIVE:
                return _completePendingUnsigned();

        }
        return JsonToken.NOT_AVAILABLE;
    }


    /*
    /**********************************************************************
    /* Second-level decoding, root level
    /**********************************************************************
     */

    private final JsonToken _startValue(int ch) throws IOException {
        return _updateToken(JsonToken.NOT_AVAILABLE);
    }

    private JsonToken _startArrayElement(int lowBits) throws IOException {
        _majorState = MAJOR_ARRAY_ELEMENT;

        int len = _decodeMajorTypeLength(lowBits);
        if (len == -2) {
            _minorState = MINOR_PENDING_BYTES;
            return _updateToken(JsonToken.NOT_AVAILABLE);
        }
        createChildArrayContext(len);
        return _updateToken(JsonToken.START_ARRAY);
    }

    private JsonToken _startObjectElement(int lowBits) throws IOException {
        _majorState = MAJOR_OBJECT_ELEMENT;

        int len = _decodeMajorTypeLength(lowBits);
        if (len == -2) {
            _minorState = MINOR_PENDING_BYTES;
            return _updateToken(JsonToken.NOT_AVAILABLE);
        }
        createChildObjectContext(len);
        return _updateToken(JsonToken.START_OBJECT);
    }

    private JsonToken decodeUnsignedInteger(int lowBits) throws JsonParseException, StreamConstraintsException {
        _majorState = MAJOR_UNSIGNED_INT_ELEMENT;
        _decodeUnsignedIntegerValue(lowBits);
        if (_numberInt == -2) {
            _minorState = MINOR_PENDING_BYTES_UNSIGNED;
            return _updateToken(JsonToken.NOT_AVAILABLE);
        }
        return _updateToken(JsonToken.VALUE_NUMBER_INT);
    }

    private JsonToken decodeNegativeInteger(int lowBits) throws JsonParseException, StreamConstraintsException {
        _majorState = MAJOR_NEGATIVE_INT_ELEMENT;
        _decodeNegativeIntegerValue(lowBits);
        if (_numberInt == -2) {
            _minorState = MINOR_PENDING_BYTES_NEGATIVE;
            return _updateToken(JsonToken.NOT_AVAILABLE);
        }
        return _updateToken(JsonToken.VALUE_NUMBER_INT);
    }



    private int _decodeMajorTypeLength(int lowBits) throws JsonParseException {
        if (lowBits == 31) {
            return -1; // indefinite
        }
        if (lowBits <= 23) {
            // fawzi
            return lowBits; // length is lowBits (y3ny lw 22 yb2a lengh is 22 w based b2a 3la el type lw 2 mathln, yb2a 22 byte, lw type object yb2a 22 item fel object
        }
        // fawzi
        // 0 -> 1 byte
        // 1 -> 2 bytes
        // 2 -> 4 bytes
        // 3 -> 8 bytes
        int lengthInidcator = lowBits - 24;
        if (lengthInidcator > 3) {
            throw _constructError(String.format(
                    "Invalid 5-bit length indicator for `JsonToken.%s`: 0x%02X; only 0x00-0x17, 0x1F allowed",
                    currentToken(), lowBits));
        }

        _pendingBytesLength = (int) Math.pow(2, lengthInidcator);

        // we don't have all the bytes needed to read the length, but let's try to read existing bytes, if any'
        int neededInputEnd = _inputPtr + _pendingBytesLength;
        if (neededInputEnd > _inputEnd) {
            _finishMajorTypeLength();
            return -2;
        }

        // we have all the bytes needed to read the length, so we can decode it.
        int value = 0;
        while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
               value = (value << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
               _pendingBytesLength--;
       }

        return value;
    }

    private void _finishMajorTypeLength() {
        while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
            _pending32 = (_pending32 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            _pendingBytesLength--;
        }
        _minorState = MINOR_PENDING_ARRAY_LENGTH;
    }

    private void _finishUnsignedInteger() {
        if (_numTypesValid == NR_INT) {
            while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
                _pending32 = (_pending32 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                _pendingBytesLength--;
            }
        } else if (_numTypesValid == NR_LONG) {
            while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
                _pending64 = (_pending64 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                _pendingBytesLength--;
            }
        }

    }

    private JsonToken _completePendingBytes() throws IOException {
        // shift current left, and add new byte.
        // minus 1 from input because we moved forward already, we want to add the current token.

        // TODO:: FAWZI HANDLE ALL INPUT WHEN FED AT ONCE, CHECK SMILE AS WELL

        // Not all input available, copy one byte at a time
        while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
            _pending32 = (_pending32 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            _pendingBytesLength--;
            _streamReadContext.decreaseIndex(1);
        }


        if (_pendingBytesLength > 0) {
            return _updateToken(JsonToken.NOT_AVAILABLE);
        }

        // all remaining bits are available, so we can decode the length/value
        // and complete a major type
        if (_majorState == MAJOR_ARRAY_ELEMENT) {
            createChildArrayContext(_pending32);
            return _updateToken(JsonToken.START_ARRAY);
        } else if (_majorState == MAJOR_OBJECT_ELEMENT) {
            createChildObjectContext(_pending32);
            return _updateToken(JsonToken.START_OBJECT);
        }
        return _updateToken(JsonToken.NOT_AVAILABLE);
    }

    private JsonToken _completePendingUnsigned() throws IOException {
        while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
            if (_numTypesValid == NR_INT) {
                _pending32 = (_pending32 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            } else if (_numTypesValid == NR_LONG) {
                _pending64 = (_pending64 << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
            }

            _pendingBytesLength--;
            _streamReadContext.decreaseIndex(1);
        }

        if (_pendingBytesLength > 0) {
            return _updateToken(JsonToken.NOT_AVAILABLE);
        }

        if (_numTypesValid == NR_INT) {
            if (_pending32 < 0) {
                _numberLong = _pending32 & 0xFFFFFFFFL;
                _numTypesValid = NR_LONG;
            } else {
                _numberInt = _pending32;
            }
        } else {
            if (_pending64 < 0L) {
            _numberBigInt = _bigPositive(_pending64);
            _numTypesValid = NR_BIGINT;
            } else {
                _numberLong = _pending64;
            }
        }

         return _updateToken(JsonToken.VALUE_NUMBER_INT);
    }


    private void _decodeNegativeIntegerValue(int lowBits) throws JsonParseException {
        if (lowBits <= 23) {
            _numberInt = lowBits;
            return;
        }
        int missingBytes = lowBits - 24;
        if (missingBytes > 3) {
            throw _constructError(String.format(
                    "Invalid 5-bit length indicator for `JsonToken.%s`: 0x%02X; only 0x00-0x17, 0x1F allowed",
                    currentToken(), lowBits));
        }
        _pendingBytesLength = (int) Math.pow(2, missingBytes);

        if (_pendingBytesLength <= 4) {
            _numTypesValid = NR_INT;
        } else {
            _numTypesValid = NR_LONG;
        }

        int neededInputEnd = _inputPtr + _pendingBytesLength;
        if (neededInputEnd > _inputEnd) {
            _finishUnsignedInteger();
            _numberInt = -2;
            return;
        }

        if (_numTypesValid == NR_INT) {
            int value = 0;
            while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
                value = (value << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                _pendingBytesLength--;
            }

            if (value < 0) {
                _numberLong = value & 0xFFFFFFFFL;
                _numTypesValid = NR_LONG;
            }
            _numberInt = value;
        } else if (_numTypesValid == NR_LONG) {
            long value = 0L;
            while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
                value = (value << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                _pendingBytesLength--;
            }
            _numberLong = value;

            if (value < 0L) {
                _numberBigInt = _bigPositive(value);
                _numTypesValid = NR_BIGINT;
            }
        }
    }

    private void _decodeUnsignedIntegerValue(int lowBits) throws JsonParseException {
        if (lowBits <= 23) {
            _numberInt = lowBits;
            return;
        }
        int missingBytes = lowBits - 24;
        if (missingBytes > 3) {
            throw _constructError(String.format(
                    "Invalid 5-bit length indicator for `JsonToken.%s`: 0x%02X; only 0x00-0x17, 0x1F allowed",
                    currentToken(), lowBits));
        }
        _pendingBytesLength = (int) Math.pow(2, missingBytes);

        if (_pendingBytesLength <= 4) {
            _numTypesValid = NR_INT;
        } else {
            _numTypesValid = NR_LONG;
        }

        int neededInputEnd = _inputPtr + _pendingBytesLength;
        if (neededInputEnd > _inputEnd) {
            _finishUnsignedInteger();
            _numberInt = -2;
            return;
        }

        if (_numTypesValid == NR_INT) {
            int value = 0;
            while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
                value = (value << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                _pendingBytesLength--;
            }

            if (value < 0) {
                _numberLong = value & 0xFFFFFFFFL;
                _numTypesValid = NR_LONG;
            }
            _numberInt = value;
        } else if (_numTypesValid == NR_LONG) {
            long value = 0L;
            while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
                value = (value << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                _pendingBytesLength--;
            }
            _numberLong = value;

            if (value < 0L) {
                _numberBigInt = _bigPositive(value);
                _numTypesValid = NR_BIGINT;
            }
        }
    }

}
