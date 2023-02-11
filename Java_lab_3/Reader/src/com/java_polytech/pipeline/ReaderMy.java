package com.java_polytech.pipeline;

import com.java_polytech.config_support.MySyntaxAnalyzer;
import com.java_polytech.pipeline_interfaces.*;

import javafx.util.Pair;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;

import static com.java_polytech.pipeline_interfaces.RC.*;

// The class that performs buffered input from a file
public class ReaderMy implements IReader {
    static final int NUM_OF_VALUES = 1;      // How many times should the grammar field value be set
    boolean IsConsumerSpecified = false;     // Flag indicating whether the consumer is set
    boolean IsConfigSpecified = false;       // Flag indicating whether the configuration file is set
    boolean IsInputStreamSpecified = false;  // Flag indicating whether the input file is set

    InputStream inputStream;                 // Input stream
    private byte[] buffer;                   // Buffer for read information
    private int bufferSize = 0;              // Maximum buffer size
    private int wasReadBytes = 0;            // The actual number of bytes read

    private final TYPE[] supportedTypes = {TYPE.BYTE_ARRAY, TYPE.CHAR_ARRAY, TYPE.INT_ARRAY};

    IConsumer consumer;                      // The one to whom the buffer with the read information is passed

    class ByteArrayMediator implements IMediator {
        @Override
        public Object getData() {
            if (wasReadBytes <= 0) {
                return null;
            }

            byte[] data = new byte[wasReadBytes];
            System.arraycopy(buffer, 0, data, 0, wasReadBytes);
            return data;
        }
    }

    class CharArrayMediator implements IMediator {
        @Override
        public Object getData() {
            if (wasReadBytes <= 0) {
                return null;
            }

            byte[] data = new byte[wasReadBytes];
            System.arraycopy(buffer, 0, data, 0, wasReadBytes);
            CharBuffer chBuf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asCharBuffer();
            char[] array = new char[chBuf.remaining()];
            chBuf.get(array);
            return array;
        }
    }

    class IntArrayMediator implements IMediator {
        @Override
        public Object getData() {
            if (wasReadBytes <= 0) {
                return null;
            }

            byte[] data = new byte[wasReadBytes];
            System.arraycopy(buffer, 0, data, 0, wasReadBytes);
            IntBuffer intBuf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asIntBuffer();
            int[] array = new int[intBuf.remaining()];
            intBuf.get(array);
            return array;
        }
    }

    @Override
    public RC setConfig(String str) {
        // Perform a syntactic analysis of the configuration file
        MySyntaxAnalyzer config = new MySyntaxAnalyzer(RCWho.READER, new ReaderGrammarMy());
        RC code = config.process(str);

        // If the syntactic analysis was successful, conduct a semantic analysis
        if (code.isSuccess()) {
            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(ReaderGrammarMy.ConfigParams.BUFFER_SIZE.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                // The value of the field is not set the number of times that is necessary
                if(val.getValue().size() != NUM_OF_VALUES)
                    return new RC(RCWho.READER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field must be set "
                        + NUM_OF_VALUES + " time(s)");

                bufferSize = Integer.parseInt(val.getValue().get(NUM_OF_VALUES - 1));
                // A negative number or zero is specified as the value
                if(bufferSize <= 0)
                    return new RC(RCWho.READER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field must be positive");
                if(bufferSize % 4 != 0)
                    return new RC(RCWho.READER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field must be divisible by 4");
            }
            catch (NumberFormatException ex) { // The value is not a number
                return RC_READER_CONFIG_SEMANTIC_ERROR;
            }

            buffer = new byte[bufferSize];
            wasReadBytes = 0;

            IsConfigSpecified = true;
            return RC_SUCCESS;
        }
        return code;
    }

    @Override
    public TYPE[] getOutputTypes() {
        return supportedTypes;
    }

    @Override
    public IMediator getMediator(TYPE Type) {
        if (Type.equals(TYPE.BYTE_ARRAY))
            return new ByteArrayMediator();
        else if (Type.equals(TYPE.CHAR_ARRAY))
            return new CharArrayMediator();
        else if (Type.equals(TYPE.INT_ARRAY))
            return new IntArrayMediator();
        else
            return null;
    }

    @Override
    public RC setInputStream(InputStream input) {
        inputStream = input;
        IsInputStreamSpecified = true;
        return RC_SUCCESS;
    }

    @Override
    public RC setConsumer(IConsumer iConsumer) {
        consumer = iConsumer;
        RC rc = iConsumer.setProvider(this);
        if (!rc.isSuccess())
            return rc;
        IsConsumerSpecified = true;
        return RC_SUCCESS;
    }

    @Override
    public RC run() {
        if (!IsConfigSpecified)
            return new RC(RCWho.READER, RCType.CODE_CUSTOM_ERROR, "Config not specified");

        if (!IsConsumerSpecified)
            return new RC(RCWho.READER, RCType.CODE_CUSTOM_ERROR, "Consumer not specified");

        if (!IsInputStreamSpecified)
            return new RC(RCWho.READER, RCType.CODE_CUSTOM_ERROR, "Input stream not specified");

        // Read the first portion of information
        try {
            wasReadBytes = inputStream.read(buffer, 0, bufferSize);
        } catch (IOException e) {
            return RC_READER_FAILED_TO_READ;
        }

        while (wasReadBytes > 0) {
            RC consumerRC;
            // Transfer a portion for processing
            consumerRC = consumer.consume();
            if (!consumerRC.isSuccess()) { // In case of an error send a completion signal
                wasReadBytes = 0;
                consumer.consume();
                return consumerRC;
            }

            // Read the next portion of information
            try {
                wasReadBytes = inputStream.read(buffer, 0, bufferSize);
            } catch (IOException e) {
                return RC_READER_FAILED_TO_READ;
            }
        }

        // Send a completion signal
        RC consumerRC;
        wasReadBytes = 0;
        consumerRC = consumer.consume();
        if (!consumerRC.isSuccess())
            return consumerRC;

        return RC_SUCCESS;
    }
}