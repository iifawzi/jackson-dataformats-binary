package com.fasterxml.jackson.dataformat.cbor;

import com.fasterxml.jackson.core.base.ParserMinimalBase;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.DupDetector;
import com.fasterxml.jackson.core.util.TextBuffer;

import java.io.IOException;

public abstract class CBORParserBase extends ParserMinimalBase {

    /*
    /**********************************************************
    /* Abstract methods for subclasses to provide
    /**********************************************************
     */

    protected abstract void _closeInput() throws IOException;

    /*
    /**********************************************************************
    /* Config
    /**********************************************************************
     */

    protected int _formatFeatures;

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
     * Flag that indicates whether a parser is closed or not. Gets
     * set when parser is either closed by explicit call
     * ({@link #close}) or when end-of-input is reached.
     */
    protected boolean _closed;

    /*
    /**********************************************************************
    /* Decoded values, text, binary
    /**********************************************************************
     */

    /**
     * Buffer that contains contents of String values, including
     * field names if necessary (name split across boundary,
     * contains an escape sequence, or access needed to char an array)
     */
    protected final TextBuffer _textBuffer;

    protected CBORParser.TagList _tagValues = new CBORParser.TagList();

    /**
     * Temporary buffer that is needed if field name is accessed
     * using {@link #getTextCharacters} method (instead of String
     * returning alternatives)
     */
    protected char[] _nameCopyBuffer;

    /**
     * Flag set to indicate whether the field name is available
     * from the name copy buffer or not (in addition to its String
     * representation being available via read context)
     */
    protected boolean _nameCopied;

    /**
     * We will hold on to decoded binary data, for duration of
     * current event, so that multiple calls to
     * {@link #getBinaryValue} will not need to decode data more
     * than once.
     */
    protected byte[] _binaryValue;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    protected CBORParserBase(IOContext ctxt, int parserFeatures, int formatFeatures)
    {
        super(parserFeatures, ctxt.streamReadConstraints());
        _formatFeatures = formatFeatures;
        _ioContext = ctxt;
        DupDetector dups = Feature.STRICT_DUPLICATE_DETECTION.enabledIn(parserFeatures)
                ? DupDetector.rootDetector(this) : null;
        _streamReadContext = CBORReadContext.createRootContext(dups);
        _textBuffer = ctxt.constructReadConstrainedTextBuffer();
    }
}
