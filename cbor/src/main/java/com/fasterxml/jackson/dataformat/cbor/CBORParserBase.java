package com.fasterxml.jackson.dataformat.cbor;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.base.ParserMinimalBase;
import com.fasterxml.jackson.core.io.ContentReference;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.JsonReadContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

public abstract class CBORParserBase extends ParserMinimalBase {

    /*
    /**********************************************************************
    /* Current input data
    /**********************************************************************
     */

    // Note: type of actual buffer depends on sub-class, can't include

    /**
     * Pointer to next available character in buffer
     */
    protected int _inputPtr = 0;

    /**
     * Index of character after last available one in the buffer.
     */
    protected int _inputEnd = 0;


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
     * Addition indicator within state; contextually relevant for just that state
     */
    protected int _minorState;


    /**
     * Flag that is sent when calling application indicates that there will
     * be no more input to parse.
     */
    protected boolean _endOfInput = false;

    /*
    /**********************************************************************
    /* Parsing state, location
    /**********************************************************************
     */

    /**
     * Number of characters/bytes that were contained in previous blocks
     * (blocks that were already processed prior to the current buffer).
     */
    protected long _currInputProcessed;

    /**
     * Information about parser context, context in which
     * the next token is to be parsed (root, array, object).
     * <p>
     * NOTE: before 2.13 was "_parsingContext"
     */
    protected CBORReadContext _streamReadContext;

    /*
    /**********************************************************************
    /* Generic I/O state
    /**********************************************************************
     */

    /**
     * I/O context for this reader. It handles buffer allocation
     * for the reader.
     */
    protected final IOContext _ioContext;


    /**
     * Flag that indicates whether parser is closed or not. Gets
     * set when parser is either closed by explicit call
     * ({@link #close}) or when end-of-input is reached.
     */
    protected boolean _closed;

       /*
    /**********************************************************
    /* Constants and fields of former 'JsonNumericParserBase'
    /**********************************************************
     */

    /**
     * Bitfield that indicates which numeric representations
     * have been calculated for the current type
     */
    protected int _numTypesValid = NR_UNKNOWN;

    // First primitives

    protected int _numberInt;
    protected long _numberLong;
    protected float _numberFloat;
    protected double _numberDouble;

    // And then object types

    protected BigInteger _numberBigInt;
    protected BigDecimal _numberBigDecimal;



    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public CBORParserBase(IOContext ioContext) {
        _ioContext = ioContext;
        _streamReadContext = CBORReadContext.createRootContext(null);
    }

    /*
    /**********************************************************
    /* Internal/package methods: other
    /**********************************************************
     */

    /**
     * Method called when an EOF is encountered between tokens.
     * If so, it may be a legitimate EOF, but only iff there
     * is no open non-root context.
     */
    @Override
    protected void _handleEOF() throws JsonParseException {
        if (!_streamReadContext.inRoot()) {
            String marker = _streamReadContext.inArray() ? "Array" : "Object";
            _reportInvalidEOF(String.format(
                            ": expected close marker for %s (start marker at %s)",
                            marker,
                            _streamReadContext.startLocation(_sourceReference())),
                    null);
        }
    }

    /**
     * Helper method used to encapsulate logic of including (or not) of
     * "source reference" when constructing {@link JsonLocation} instances.
     *
     * @since 2.13
     */
    protected ContentReference _sourceReference() {
        if (isEnabled(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)) {
            return _ioContext.contentReference();
        }
        return ContentReference.unknown();
    }

    /*
    /**********************************************************
    /* Internal methods, other
    /**********************************************************
     */

    protected void createChildArrayContext(final int len) throws IOException {
        _streamReadContext = _streamReadContext.createChildArrayContext(len);
        _streamReadConstraints.validateNestingDepth(_streamReadContext.getNestingDepth());
    }

    protected void createChildObjectContext(final int len) throws IOException {
        _streamReadContext = _streamReadContext.createChildObjectContext(len);
        _streamReadConstraints.validateNestingDepth(_streamReadContext.getNestingDepth());
    }

    private final static BigInteger BIT_63 = BigInteger.ONE.shiftLeft(63);

    protected final BigInteger _bigPositive(long l) {
        BigInteger biggie = BigInteger.valueOf((l << 1) >>> 1);
        return biggie.or(BIT_63);
    }

    protected final BigInteger _bigNegative(long l) {
        BigInteger unsignedBase = _bigPositive(l);
        return unsignedBase.negate().subtract(BigInteger.ONE);
    }

}
