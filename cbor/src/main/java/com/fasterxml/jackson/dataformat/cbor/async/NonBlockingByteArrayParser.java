package com.fasterxml.jackson.dataformat.cbor.async;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.async.NonBlockingInputFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.util.TextBuffer;
import com.fasterxml.jackson.dataformat.cbor.CBORConstants;

import java.io.IOException;

public class NonBlockingByteArrayParser extends NonBlockingParserBase implements ByteArrayFeeder {

    /**
     * Buffer that contains contents of String values, including
     * field names if necessary (name split across boundary,
     * contains escape sequence, or access needed to char array)
     */
    protected final TextBuffer _textBuffer;
    private final static int[] UTF8_UNIT_CODES = CBORConstants.sUtf8UnitLengths;


    protected int _pendingFieldNameBytesLength = 0;


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
        _textBuffer = ioContext.constructReadConstrainedTextBuffer();
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

        if (_streamReadContext.inObject()) {
            if (_currToken != JsonToken.FIELD_NAME && _majorState != MAJOR_FIELD_ELEMENT) {
                if (!_streamReadContext.expectMoreValues()) {
                    _streamReadContext = _streamReadContext.getParent();
                    return _updateToken(JsonToken.END_OBJECT);
                }
                return _updateToken(_decodePropertyName());
            }
        } else {
            // array
            if (!_streamReadContext.expectMoreValues() && _pendingBytesLength == 0) {
                _streamReadContext = _streamReadContext.getParent();
                return _updateToken(JsonToken.END_ARRAY);
            }
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
            case MINOR_PENDING_BYTES:
                return _completePendingBytes();
            case MINOR_PENDING_BYTES_UNSIGNED:
                return _completePendingUnsigned();
            case MINOR_PENDING_BYTES_NEGATIVE:
                return _completePendingUnsigned();
            case MINOR_FIELD_NAME_PENDING:
                boolean completed = _finishPropertyName(_inputCopyLen);
                if (completed) {
                    _majorState = MAJOR_OBJECT_ELEMENT;
                    return _updateToken(JsonToken.FIELD_NAME);
                }

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
        _minorState = MINOR_PENDING_BYTES;
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
        // and complete a major type length. but for example for major field element we're not sure if we have remaining bytes for the actual name.
        if (_majorState == MAJOR_ARRAY_ELEMENT) {
            createChildArrayContext(_pending32);
            return _updateToken(JsonToken.START_ARRAY);
        } else if (_majorState == MAJOR_OBJECT_ELEMENT) {
            createChildObjectContext(_pending32);
            return _updateToken(JsonToken.START_OBJECT);
        } else if (_majorState == MAJOR_FIELD_ELEMENT) {
            if (_pending32 > _inputEnd - _inputPtr) {
                _minorState = MINOR_FIELD_NAME_PENDING;
                _pendingFieldNameBytesLength = _pending32;
                return JsonToken.NOT_AVAILABLE;
            } else {
                _streamReadContext.setCurrentName(_decodeContiguousName(_pending32, _inputBuffer, _inputPtr));
                return _updateToken(JsonToken.FIELD_NAME);
            }

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


    protected final JsonToken _decodePropertyName() throws IOException
    {
        _majorState = MAJOR_FIELD_ELEMENT;
        int ch = _inputBuffer[_inputPtr++] & 0xFF;
        int type = (ch >> 5);
        int lowBits = ch & 0x1F;

        String name = null;
        // name consists of less than 23 byte.
        if (lowBits <= 23) {
            if (lowBits > (_inputEnd - _inputPtr)) {
                _pendingFieldNameBytesLength = lowBits;
               _finishPropertyName(0);
                // because we already increased the index by one when we did the check of expect more values
                // and while the field name isn't finished in this token, we need to decrease the index again as this's not counted as field.
                _streamReadContext.decreaseIndex(1);
                return JsonToken.NOT_AVAILABLE;
            }
                // we have all the bytes.
                name = _decodeContiguousName(lowBits, _inputBuffer, _inputPtr);
        } else {
            // because we already increased the index by one when we did the check of expect more values
            // and while the field name isn't finished in this token, we need to decrease the index again as this's not counted as field.
            _streamReadContext.decreaseIndex(1);

            // we need to read more bytes to know the length of the field name.
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
                return JsonToken.NOT_AVAILABLE;
            }

            if (lowBits > (_inputEnd - _inputPtr)) {
                _finishPropertyName(0);
                return JsonToken.NOT_AVAILABLE;
            }

            // we have all the bytes needed to read the length, so we can decode it.
            int value = 0;
            while (_inputPtr < _inputEnd && _pendingBytesLength != 0) {
                value = (value << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                _pendingBytesLength--;
            }

            // now we know the length of the field name, let's assume we have enough bytes to read it.
            name = _decodeContiguousName(value, _inputBuffer, _inputPtr);
        }
        _streamReadContext.setCurrentName(name);
        return JsonToken.FIELD_NAME;
    }

    private final String _decodeContiguousName(final int len,  byte[] inBuf, int _inputPtr) throws IOException
    {
        // note: caller ensures we have enough bytes available
        int outPtr = 0;
        char[] outBuf = _textBuffer.emptyAndGetCurrentSegment();
        if (outBuf.length < len) { // one minor complication
            outBuf = _textBuffer.expandCurrentSegment(len);
        }
        int inPtr = _inputPtr;
        final int[] codes = UTF8_UNIT_CODES;

        // First a tight loop for ASCII
        final int end = inPtr + len;
        while (true) {
            int i = inBuf[inPtr] & 0xFF;
            int code = codes[i];
            if (code != 0) {
                break;
            }
            outBuf[outPtr++] = (char) i;
            if (++inPtr == end) {
                return _textBuffer.setCurrentAndReturn(outPtr);
            }
        }

        // But in case there's multi-byte char, use a full loop
        while (inPtr < end) {
            int i = inBuf[inPtr++] & 0xFF;
            int code = codes[i];
            if (code != 0) {
                // 05-Jul-2021, tatu: As per [dataformats-binary#289] need to
                //     be careful wrt end-of-buffer truncated codepoints
                if ((inPtr + code) > end) {
                    final int firstCharOffset = len - (end - inPtr) - 1;
                    _reportTruncatedUTF8InName(len, firstCharOffset, i, code);
                }

                switch (code) {
                    case 1:
                    {
                        final int c2 = inBuf[inPtr++];
                        if ((c2 & 0xC0) != 0x080) {
                            _reportInvalidOther(c2 & 0xFF, inPtr);
                        }
                        i = ((i & 0x1F) << 6) | (c2 & 0x3F);
                    }
                    break;
                    case 2:
                    {
                        final int c2 = inBuf[inPtr++];
                        if ((c2 & 0xC0) != 0x080) {
                            _reportInvalidOther(c2 & 0xFF, inPtr);
                        }
                        final int c3 = inBuf[inPtr++];
                        if ((c3 & 0xC0) != 0x080) {
                            _reportInvalidOther(c3 & 0xFF, inPtr);
                        }
                        i = ((i & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
                    }
                    break;
                    case 3:
                        // 30-Jan-2021, tatu: TODO - validate surrogate case too?
                        i = ((i & 0x07) << 18)
                                | ((inBuf[inPtr++] & 0x3F) << 12)
                                | ((inBuf[inPtr++] & 0x3F) << 6)
                                | (inBuf[inPtr++] & 0x3F);
                        // note: this is the codepoint value; need to split, too
                        i -= 0x10000;
                        outBuf[outPtr++] = (char) (0xD800 | (i >> 10));
                        i = 0xDC00 | (i & 0x3FF);
                        break;
                    default: // invalid
                        throw _constructReadException("Invalid UTF-8 byte 0x%s in Object property name",
                                Integer.toHexString(i));
                }
            }
            outBuf[outPtr++] = (char) i;
        }
        return _textBuffer.setCurrentAndReturn(outPtr);
    }

    // @since 2.18.1
    private String _reportTruncatedUTF8InString(int strLenBytes, int truncatedCharOffset,
                                                int firstUTFByteValue, int bytesExpected)
            throws IOException
    {
        throw _constructError(String.format(
                "Truncated UTF-8 character in Unicode String value (%d bytes): "
                        +"byte 0x%02X at offset #%d indicated %d more bytes needed",
                strLenBytes, firstUTFByteValue, truncatedCharOffset, bytesExpected));
    }

    // @since 2.13
    private String _reportTruncatedUTF8InName(int strLenBytes, int truncatedCharOffset,
                                              int firstUTFByteValue, int bytesExpected)
            throws IOException
    {
        throw _constructReadException(String.format(
                "Truncated UTF-8 character in Map key (%d bytes): "
                        +"byte 0x%02X at offset #%d indicated %d more bytes needed",
                strLenBytes, firstUTFByteValue, truncatedCharOffset, bytesExpected));
    }



    protected void _reportInvalidOther(int mask) throws JsonParseException {
        _reportError("Invalid UTF-8 middle byte 0x"+Integer.toHexString(mask));
    }

    protected void _reportInvalidOther(int mask, int ptr) throws JsonParseException {
        _inputPtr = ptr;
        _reportInvalidOther(mask);
    }


    protected boolean _finishPropertyName(int readBytes) throws IOException {
        byte[] srcBuffer = _inputBuffer;
        byte[] copyBuffer = _inputCopy;
        int srcPtr = _inputPtr;

        while (srcPtr < _inputEnd) {
          copyBuffer[readBytes++] = srcBuffer[srcPtr++];
        }

        if (readBytes == _pendingFieldNameBytesLength) {
            _streamReadContext.setCurrentName(_decodeContiguousName(readBytes, copyBuffer, 0));
            _inputPtr = _inputPtr + readBytes;
            return true;
        }

        if (srcPtr == _inputEnd) {
            _inputPtr = srcPtr;
            _inputCopyLen = readBytes;
            _minorState = MINOR_FIELD_NAME_PENDING;
        }
        return false;
    }


}
