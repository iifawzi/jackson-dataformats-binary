package com.fasterxml.jackson.dataformat.cbor;

import com.fasterxml.jackson.core.base.ParserMinimalBase;
import com.fasterxml.jackson.core.json.JsonReadContext;

public abstract class CBORParserBase extends ParserMinimalBase {
    /*
    /**********************************************************************
    /* Current input data
    /**********************************************************************
     */

    /**
     * Pointer to next available character in buffer
     */
    protected int _inputPtr = 0;

    /**
     * Index of character after the last available one in the buffer.
     */
    protected int _inputEnd = 0;

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
     * Alternative to {@code _tokenInputTotal} that will only contain
     * offset within input buffer, as int.
     */
    protected int _tokenOffsetForTotal;

    /**
     * Information about parser context, context in which
     * the next token is to be parsed (root, array, object).
     *<p>
     * NOTE: before 2.13 was "_parsingContext"
     */
    protected JsonReadContext _streamReadContext;

}
