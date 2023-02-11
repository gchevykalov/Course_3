package com.java_polytech.pipeline;

import com.java_polytech.config_support.MySyntaxAnalyzer;
import com.java_polytech.pipeline_interfaces.*;

import javafx.util.Pair;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.ArrayList;

import static com.java_polytech.pipeline_interfaces.RC.*;

// The class that performs buffered input from a file
public class ReaderMy implements IReader {
    static final int NUM_OF_VALUES = 1;      // How many times should the grammar field value be set
    boolean IsConsumerSpecified = false;     // Flag indicating whether the consumer is set
    boolean IsConfigSpecified = false;       // Flag indicating whether the configuration file is set
    boolean IsInputStreamSpecified = false;  // Flag indicating whether the input file is set

    InputStream inputStream;                 // Input stream

    int bufferSize = 0;
    int wasReadBytes = 0;
    byte[] currentBuffer;
    long current_packet_number = 0;
    private final HashMap<Long, byte[]> availableBuffers = new HashMap<>();
    private static final int MAX_NUM = 5;

    private final TYPE[] supportedTypes = { TYPE.BYTE_ARRAY };

    RC CurrentState = RC_SUCCESS;

    @Override
    public RC getCurrentState() {
        return CurrentState;
    }

    IConsumer consumer;                      // The one to whom the buffer with the read information is passed

    class ByteArrayMediator implements IMediator {
        @Override
        public Object getData(long packet_number) {
            if (packet_number == IConsumer.END_OF_FILE_PACKET_NUMBER) {
                return null;
            }
            if (!availableBuffers.containsKey(packet_number) &&
                    current_packet_number == IConsumer.END_OF_FILE_PACKET_NUMBER) {
                CurrentState = new RC(RCWho.READER, RCType.CODE_CUSTOM_ERROR, "Invalid index asked");
                return null;
            }
            while (!availableBuffers.containsKey(packet_number) &&
                    current_packet_number != IConsumer.END_OF_FILE_PACKET_NUMBER) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {}
            }
            if (availableBuffers.containsKey(packet_number)) {
                byte[] buf = availableBuffers.get(packet_number);
                if (buf.length == 0) {
                    CurrentState = new RC(RCWho.READER, RCType.CODE_CUSTOM_ERROR, "Something wrong with file reading");
                    return null;
                }

                byte[] data = new byte[buf.length];
                System.arraycopy(buf, 0, data, 0, buf.length);
                availableBuffers.remove(packet_number);
                return data;
            }
            CurrentState = new RC(RCWho.READER, RCType.CODE_CUSTOM_ERROR, "Something goes wrong in Mediator");
            return null;
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

            currentBuffer = new byte[bufferSize];
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
    public void run() {
        if (!IsConfigSpecified) {
            CurrentState = new RC(RC.RCWho.READER, RC.RCType.CODE_CUSTOM_ERROR, "Config not specified");
            consumer.consume(IConsumer.END_OF_FILE_PACKET_NUMBER);
            return;
        }

        if (!IsConsumerSpecified) {
            CurrentState = new RC(RC.RCWho.READER, RC.RCType.CODE_CUSTOM_ERROR, "Consumer not specified");
            consumer.consume(IConsumer.END_OF_FILE_PACKET_NUMBER);
            return;
        }
        if (!IsInputStreamSpecified) {
            CurrentState = new RC(RC.RCWho.READER, RC.RCType.CODE_CUSTOM_ERROR, "Input stream not specified");
            consumer.consume(IConsumer.END_OF_FILE_PACKET_NUMBER);
            return;
        }

        try {
            wasReadBytes = inputStream.read(currentBuffer, 0, bufferSize);
        } catch (IOException e) {
            CurrentState = RC_READER_FAILED_TO_READ;
            consumer.consume(IConsumer.END_OF_FILE_PACKET_NUMBER);
            return;
        }

        while (wasReadBytes > 0 && CurrentState.isSuccess()) {
            while(availableBuffers.size() >= MAX_NUM)
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {}

            RC tmp_rc;
            byte[] tmp = new byte[wasReadBytes];
            System.arraycopy(currentBuffer, 0, tmp, 0, wasReadBytes);
            availableBuffers.put(current_packet_number, tmp);
            tmp_rc = consumer.consume(current_packet_number);
            if(current_packet_number == 0x8fffffff)
                current_packet_number = -1;
            current_packet_number++;
            if (!tmp_rc.isSuccess()) {
                consumer.consume(IConsumer.END_OF_FILE_PACKET_NUMBER);
                CurrentState = tmp_rc;
                return;
            }

            try {
                wasReadBytes = inputStream.read(currentBuffer, 0, bufferSize);
            } catch (IOException e) {
                consumer.consume(IConsumer.END_OF_FILE_PACKET_NUMBER);
                CurrentState = RC_READER_FAILED_TO_READ;
                return;
            }
        }

        RC consumerRC;
        wasReadBytes = 0;
        consumerRC = consumer.consume(IConsumer.END_OF_FILE_PACKET_NUMBER);
        if (!consumerRC.isSuccess())
            CurrentState = consumerRC;
    }
}