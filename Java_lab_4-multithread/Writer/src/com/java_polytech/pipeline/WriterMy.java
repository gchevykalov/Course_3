package com.java_polytech.pipeline;

import com.java_polytech.config_support.MySyntaxAnalyzer;
import com.java_polytech.pipeline_interfaces.*;

import javafx.util.Pair;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;

import static com.java_polytech.pipeline_interfaces.RC.*;

// The class that performs buffered output to a file
public class WriterMy implements IWriter {
    static final int NUM_OF_VALUES = 1;        // How many times should the grammar field value be set
    boolean IsConfigSpecified  = false;        // Flag indicating whether the configuration file is set
    boolean IsOutputStreamSpecified  = false;  // Flag indicating whether the output file is set
    boolean IsTypeSpecified  = false;          // Flag indicating whether the TYPE is set

    private final TYPE[] supportedTypes = {TYPE.BYTE_ARRAY, TYPE.INT_ARRAY, TYPE.CHAR_ARRAY};
    private TYPE currentType;

    IProvider Prev;
    IMediator Mediator;

    private OutputStream outputStream;         // Output stream
    private byte[] buffer;                     // Buffer for accumulating data before output
    private int index = 0;                     // Current buffer position

    RC CurrentState = RC_SUCCESS;
    private final ArrayDeque<Long> availablePackets = new ArrayDeque<>();

    @Override
    public RC getCurrentState() {
        return CurrentState;
    }

    @Override
    public RC setConfig(String str) {
        // Perform a syntactic analysis of the configuration file
        MySyntaxAnalyzer config = new MySyntaxAnalyzer(RCWho.WRITER, new WriterGrammarMy());
        RC code = config.process(str);
        int bufferSize;

        // If the syntactic analysis was successful, conduct a semantic analysis
        if (code.isSuccess()) {
            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(WriterGrammarMy.ConfigParams.BUFFER_SIZE.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                // The value of the field is not set the number of times that is necessary
                if(val.getValue().size() != NUM_OF_VALUES)
                    return new RC(RCWho.WRITER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field must be set "
                            + NUM_OF_VALUES + " time(s)");

                bufferSize = Integer.parseInt(val.getValue().get(NUM_OF_VALUES - 1));
                // A negative number or zero is specified as the value
                if(bufferSize <= 0)
                    return new RC(RCWho.WRITER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field must be positive");
                if(bufferSize % 4 != 0)
                    return new RC(RCWho.WRITER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field must be divisible by 4");
            }
            catch (NumberFormatException ex) { // The value is not a number
                return RC_WRITER_CONFIG_SEMANTIC_ERROR;
            }

            buffer = new byte[bufferSize];
            index = 0;

            IsConfigSpecified  = true;
            return RC_SUCCESS;
        }
        return code;
    }

    @Override
    public RC setProvider(IProvider provider) {
        Prev = provider;
        TYPE[] providedTypes = provider.getOutputTypes();
        for (int generalType = 0; generalType < supportedTypes.length && !IsTypeSpecified; generalType++)
            for (TYPE providedType : providedTypes)
                if (providedType.equals(supportedTypes[generalType])) {
                    currentType = supportedTypes[generalType];
                    IsTypeSpecified = true;
                    break;
                }

        if (!IsTypeSpecified) {
            return RC_WRITER_TYPES_INTERSECTION_EMPTY_ERROR;
        }

        Mediator = Prev.getMediator(currentType);
        return RC_SUCCESS;
    }

    @Override
    public RC setOutputStream(OutputStream output) {
        outputStream = output;
        IsOutputStreamSpecified  = true;
        return RC_SUCCESS;
    }

    @Override
    public RC consume(long packet_number) {
        if (!IsConfigSpecified )
            return new RC(RCWho.WRITER, RCType.CODE_CUSTOM_ERROR, "Config not specified");

        if (!IsOutputStreamSpecified )
            return new RC(RCWho.WRITER, RCType.CODE_CUSTOM_ERROR, "Output stream not specified");

        availablePackets.addLast(packet_number);
        return CurrentState;
    }

    @Override
    public void run() {
        while (availablePackets.isEmpty()) {
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
            }
        }

        long current_packet = availablePackets.removeFirst();
        while (current_packet != IConsumer.END_OF_FILE_PACKET_NUMBER && CurrentState.isSuccess()) {
            byte[] bytes = null;

            if (currentType == TYPE.BYTE_ARRAY)
                bytes = (byte[]) Mediator.getData(current_packet);
            else if (currentType == TYPE.CHAR_ARRAY) {
                char[] chars = (char[]) Mediator.getData(current_packet);
                ByteBuffer byteBuffer = ByteBuffer.allocate(chars.length * 2);
                CharBuffer charBuffer = byteBuffer.asCharBuffer();
                charBuffer.put(chars);
                bytes = byteBuffer.array();
            } else if (currentType == TYPE.INT_ARRAY) {
                int[] ints = (int[]) Mediator.getData(current_packet);
                ByteBuffer byteBuffer = ByteBuffer.allocate(ints.length * 4);
                IntBuffer intBuffer = byteBuffer.asIntBuffer();
                intBuffer.put(ints);
                bytes = byteBuffer.array();
            }

            if (bytes == null && index != 0) {
                try {
                    outputStream.write(buffer, 0, index);
                } catch (IOException e) {
                    CurrentState = RC_WRITER_FAILED_TO_WRITE;
                    return;
                }
                CurrentState = RC_SUCCESS;
                return;
            } else if (bytes == null) {
                CurrentState = RC_SUCCESS;
                return;
            }
            for (byte aByte : bytes) {
                if (index == buffer.length) {
                    try {
                        outputStream.write(buffer, 0, index);
                    } catch (IOException e) {
                        CurrentState = RC_WRITER_FAILED_TO_WRITE;
                        return;
                    }
                    index = 0;
                }

                buffer[index] = aByte;
                index++;

            }
            while (availablePackets.isEmpty()) {
                try {
                    Thread.sleep(100);
                }
                catch (InterruptedException e) {
                }
            }
            current_packet = availablePackets.removeFirst();
        }
        if (index != 0 && CurrentState.isSuccess()) {
            try {
                outputStream.write(buffer, 0, index);
            } catch (IOException e) {
                CurrentState = RC_WRITER_FAILED_TO_WRITE;
            }
        }
    }
}