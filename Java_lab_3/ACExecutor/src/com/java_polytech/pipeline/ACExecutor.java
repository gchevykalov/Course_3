package com.java_polytech.pipeline;

import com.java_polytech.config_support.MySyntaxAnalyzer;
import com.java_polytech.pipeline_interfaces.*;

import javafx.util.Pair;
import java.util.ArrayList;
import java.util.Locale;

import static com.java_polytech.pipeline_interfaces.RC.*;

// The class that performs arithmetic encoding/decoding
public class ACExecutor implements IExecutor {
    // The enum containing the executor's mode
    enum Mode {
        ENCODE("ENCODE"),
        DECODE("DECODE"),
        INCORRECT("INCORRECT");

        private final String mode;

        Mode(String param){
            mode = param;
        }

        public String asString(){
            return mode;
        }

        static Mode ToEnum(String str) {
            if (str.equalsIgnoreCase(ENCODE.asString()))
                return ENCODE;
            else if (str.equalsIgnoreCase(DECODE.asString()))
                return DECODE;
            else
                return INCORRECT;
        }
    }

    boolean IsConsumerSpecified = false;  // Flag indicating whether the consumer is set
    boolean IsConfigSpecified = false;    // Flag indicating whether the configuration file is set
    boolean IsTypeSpecified = false;      // Flag indicating whether the TYPE is set
    static final int NUM_OF_VALUES = 1;   // How many times should the grammar field value be set

    private final TYPE[] supportedTypes = {TYPE.BYTE_ARRAY};
    private TYPE currentType;

    IProvider Prev;
    IConsumer Next;                       // The one to whom the buffer with the processed information is passed
    IMediator Mediator;

    private Mode mode;                    // Operating mode
    byte[] outBuffer;                     // Buffer for accumulating data before passing on
    int outBufferIndex = 0;               // Current buffer position
    int outBufferSize = 0;                // Maximum buffer size

    class ByteArrayMediator implements IMediator {
        @Override
        public Object getData() {
            if (outBufferIndex <= 0) {
                return null;
            }

            byte[] data = new byte[outBufferIndex];
            System.arraycopy(outBuffer, 0, data, 0, outBufferIndex);
            return data;
        }
    }

    // The class that reads data from the received buffer in the desired format
    private static class ReaderFromBuffer {
        static private final int TO_NEXT_BYTE = 0;   // Indicates that it needs to move to the next byte
        static private final int IT_IS_NEW_BYTE = 8; // A sign that it has just moved to a new byte
        static public final int EOF = -1;            // Indicates the end of the buffer
        static private final int MASK = 1;           // Mask for getting the bit
        private int bitsToGo;                        // The number of bits before switching to a new byte
        private int garbageBits;                     // Number of dummy bits
        private final byte[] buf;                    // Received buffer
        private int index;                           // Current buffer position

        ReaderFromBuffer(byte[] bytes){
            buf = bytes;
            bitsToGo = IT_IS_NEW_BYTE;
        }

        public byte readByte() {
            if(index >= buf.length)
                return EOF;
            return buf[index++];
        }

        public int readBit() {
            if (buf == null) { // In case of end of file
                garbageBits++;
                if (garbageBits > ACExecutor.VALUE_BITS_NUM) // If there are too many dummy bits it is an error
                    return garbageBits;
                else // Otherwise, return a dummy bit
                    return 1;
            }
            if(bitsToGo == TO_NEXT_BYTE) { // Move to the next byte
                index++;
                bitsToGo = IT_IS_NEW_BYTE;
            }
            if(index == buf.length)
                return EOF;
            else
                return (buf[index]>>(IT_IS_NEW_BYTE - bitsToGo--)) & MASK;
        }

        public boolean isEnded() {
            if(buf == null)
                return true;
            return index >= buf.length;
        }
    }

    // The class that writes data to the buffer in the desired format
    private class WriterToBuffer {
        static private final int TO_NEXT_BYTE = 0;    // Indicates that it needs to move to the next byte
        static private final int IT_IS_NEW_BYTE = 8;  // A sign that it has just moved to a new byte
        static private final int MASK = 0x80;         // Mask for putting the bit
        static private final int SHIFT = 1;           // Offset by this number of bits when writing a bit
        private int currentByte;                      // Current byte to processing
        private int bitsToGo;                         // The number of bits before switching to a new byte

        WriterToBuffer(){
            bitsToGo = IT_IS_NEW_BYTE;
        }

        public RC writeByte(byte b) {
            outBuffer[outBufferIndex] = b;
            outBufferIndex++;
            if (outBufferIndex == outBufferSize) { // // When it has accumulated the necessary amount, it passes on the accumulated in the buffer
                RC consumerRC = Next.consume();
                outBufferIndex = 0;
                return consumerRC;
            }
            return RC_SUCCESS;
        }

        public RC writeBit(int bit) {
            currentByte >>= SHIFT;

            if(bit > 0)
                currentByte |= MASK;

            bitsToGo--;

            if(bitsToGo == TO_NEXT_BYTE){
                bitsToGo = IT_IS_NEW_BYTE;
                return writeByte((byte)currentByte);
            }
            return RC_SUCCESS;
        }

        private RC outBufferClose() {
            if(bitsToGo != IT_IS_NEW_BYTE){
                RC tmp = writeByte((byte)(currentByte>>bitsToGo));
                if(!tmp.isSuccess())
                    return tmp;
            }
            if(outBufferIndex > 0) {
                RC consumerRC = Next.consume();
                if (!consumerRC.isSuccess())
                    return consumerRC;
            }

            // Send a completion signal
            outBufferIndex = 0;
            RC consumerRC = Next.consume();
            if (!consumerRC.isSuccess())
                return consumerRC;

            return RC_SUCCESS;
        }
    }

    private WriterToBuffer writer;         // A reference to an instance of the class that writes to the buffer

    private int bitsToFollow = 0;                              // Number of additional bits
    static private final int TABLE_SIZE = 258;                 // Size of auxiliary buffers
    static private final int EOF_SYMBOL = 256;                 // Stop symbol (Indicates the end of the encoded sequence)
    private final long[] table = new long[TABLE_SIZE];         // Buffer indicating the number of each character
    private final long[] cumulative = new long[TABLE_SIZE];    // Buffer indicating the accumulated number of characters
    private final int[] byteToIndex = new int[TABLE_SIZE - 2]; // Buffer for fast byte-to-index conversion
    private final int[] indexToByte = new int[TABLE_SIZE];     // Buffer for fast index-to-byte conversion
    static private final int BYTE_SHIFT = 8;                   // Shift by one byte in bits
    static private final int LONG_SIZE = 8;                    // Number of bytes in long
    static private final long MAX_VALUE = 0x0000000000ffffffL; // The maximum value of the parameter during processing
    static private final long FIRST_QTR = MAX_VALUE / 4 + 1;   // A milestone for scaling
    static private final long HALF = 2 * FIRST_QTR;            // A milestone for scaling
    static private final long THIRD_QTR = 3 * FIRST_QTR;       // A milestone for scaling
    static private final int VALUE_BITS_NUM = (LONG_SIZE - 5) * BYTE_SHIFT; // The number of bits in the maximum value
    static private final int SUCCESS = 0;                      // Success code
    private long low = 0;                     // Lower border
    private long high = MAX_VALUE;            // Upper bound
    private long currentValue = 0;            // The current value of the parameter
    private int valueBitsFilled = 0;          // Number of filled parameter bits
    private boolean decodingComplete = false; // Indication of decoding completion
    private boolean correctValue = true;      // Indication of the relevance of the parameter

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
            return RC_EXECUTOR_TYPES_INTERSECTION_EMPTY_ERROR;
        }

        Mediator = Prev.getMediator(currentType);
        return RC_SUCCESS;
    }

    @Override
    public RC setConsumer(IConsumer iConsumer) {
        Next = iConsumer;
        IsConsumerSpecified  = true;
        RC rc = iConsumer.setProvider(this);
        if (!rc.isSuccess())
            return rc;
        return RC_SUCCESS;
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
    public RC setConfig(String str) {
        // Perform a syntactic analysis of the configuration file
        MySyntaxAnalyzer config = new MySyntaxAnalyzer(RCWho.EXECUTOR, new ExecutorGrammarMy());
        RC code = config.process(str);

        // If the syntactic analysis was successful, conduct a semantic analysis
        if (code.isSuccess()) {
            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(ExecutorGrammarMy.ConfigParams.OUT_BUFFER_SIZE.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                // The value of the field is not set the number of times that is necessary
                if(val.getValue().size() != NUM_OF_VALUES)
                    return new RC(RCWho.EXECUTOR, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                            + ExecutorGrammarMy.ConfigParams.OUT_BUFFER_SIZE + " must be set "
                            + NUM_OF_VALUES + " time(s)");

                outBufferSize = Integer.parseInt(val.getValue().get(NUM_OF_VALUES - 1));
                // A negative number or zero is specified as the value
                if(outBufferSize <= 0)
                    return new RC(RCWho.EXECUTOR, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                            + ExecutorGrammarMy.ConfigParams.OUT_BUFFER_SIZE + " must be positive");
            } catch (NumberFormatException ex) { // The value is not a number
                return RC_EXECUTOR_CONFIG_SEMANTIC_ERROR;
            }

            outBuffer = new byte[outBufferSize];
            outBufferIndex = 0;

            Pair<RC, ArrayList<String>> val = config.GetFieldValues(ExecutorGrammarMy.ConfigParams.MODE.asString());

            if (!val.getKey().isSuccess())
                return val.getKey();

            // The value of the field is not set the number of times that is necessary
            if(val.getValue().size() != NUM_OF_VALUES)
                return new RC(RCWho.EXECUTOR, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                        + ExecutorGrammarMy.ConfigParams.MODE + " must be set "
                        + NUM_OF_VALUES + " time(s)");

            mode = Mode.ToEnum(val.getValue().get(NUM_OF_VALUES - 1).toUpperCase(Locale.ROOT));

            if (mode == Mode.INCORRECT) // Incorrect operation mode
                return RC_EXECUTOR_CONFIG_SEMANTIC_ERROR;

            writer = new WriterToBuffer();
            IsConfigSpecified = true;
            startModel();
            return RC_SUCCESS;
        }
        return code;
    }

    @Override
    public RC consume() {
        if (!IsConfigSpecified)
            return new RC(RCWho.EXECUTOR, RCType.CODE_CUSTOM_ERROR, "Config not specified");

        if (!IsConsumerSpecified)
            return new RC(RCWho.EXECUTOR, RCType.CODE_CUSTOM_ERROR, "Consumer not specified");

        byte[] bytes = (byte[])Mediator.getData();
        if (mode == Mode.ENCODE)
            return Encode(bytes);
        else
            return Decode(bytes);
    }

    private void startModel() {
        // Fill buffers for fast byte-to-index and index-to-byte conversion
        for (int counter = 0; counter < TABLE_SIZE - 2; counter++) {
            byteToIndex[counter] = counter + 1;
            indexToByte[counter + 1] = counter;
        }
        // Fill buffer indicating the number of each character and buffer indicating the accumulated number of characters with default values
        for (int counter = 0; counter < TABLE_SIZE; counter++) {
            table[counter] = 1;
            cumulative[counter] = TABLE_SIZE - 1 - counter;
        }
        table[0] = 0;
    }

    private void updateModel(int index) {
        int i;
        if (cumulative[0] == MAX_VALUE) { // Scale values
            int cumul = 0;
            for (i = TABLE_SIZE - 1; i >= 0; i--) {
                table[i] = (table[i] + 1) / 2;
                cumulative[i] = cumul;
                cumul += table[i];
            }
        }
        i = index;
        while (table[i] == table[i - 1]) { // It is looking for a place for the specified symbol so that it is found correctly during decoding
            i--;
        }
        if (i < index) { // Make an exchange
            int b_i, b_index;
            b_i = indexToByte[i];
            b_index = indexToByte[index];
            indexToByte[i] = b_index;
            indexToByte[index] = b_i;
            byteToIndex[b_i] = index;
            byteToIndex[b_index] = i;
        }
        table[i] += 1; // Perform an increment
        while (i > 0) {
            i -= 1;
            cumulative[i] += 1;
        }
    }

    private RC bitPlusFollow(int bit) {
        RC tmp = writer.writeBit(bit); // Write the specified bit to the buffer
        if(!tmp.isSuccess())
            return tmp;

        while (bitsToFollow > 0) { // Process additional bits
            tmp = writer.writeBit(1 - bit);
            if(!tmp.isSuccess())
                return tmp;
            bitsToFollow--;
        }
        return RC_SUCCESS;
    }

    private RC encodeSymbol(int symbol) {
        long range = high - low + 1;   // Size of the current code region
        high = low + (range * cumulative[symbol - 1]) / cumulative[0] - 1;
        low = low + (range * cumulative[symbol]) / cumulative[0];

        RC tmp;
        for (;;) {
            if (high < HALF) {
                tmp = bitPlusFollow(0);    // Output 0 if in low half
                if(!tmp.isSuccess())
                    return tmp;
            }
            else if (low >= HALF) {
                tmp = bitPlusFollow(1);    // Output 1 if in high half
                if(!tmp.isSuccess())
                    return tmp;
                low -= HALF;
                high -= HALF;          // Subtract offset to top
            }
            else if (low >= FIRST_QTR && high < THIRD_QTR) { // Output an opposite bit later if in middle half
                bitsToFollow += 1;
                low -= FIRST_QTR;      // Subtract offset to middle
                high -= FIRST_QTR;
            }
            else break;                // Otherwise exit loop
            low = 2 * low;
            high = 2 * high + 1;       // Scale up code range
        }
        return RC_SUCCESS;
    }

    private RC Encode(byte[] bytes) {
        if (bytes == null) { // Finish coding
            encodeSymbol(EOF_SYMBOL);

            bitsToFollow++;
            if (low < FIRST_QTR)
                bitPlusFollow(0);
            else
                bitPlusFollow(1);

            return writer.outBufferClose();
        }

        int index;
        ReaderFromBuffer reader = new ReaderFromBuffer(bytes);
        while (!reader.isEnded()) { // Execute for all buffer bytes
            byte data = reader.readByte();
            index = byteToIndex[Byte.toUnsignedInt(data)];
            RC tmp = encodeSymbol(index);
            if(!tmp.isSuccess())
                return tmp;
            updateModel(index);
        }

        return RC_SUCCESS;
    }

    private int makeActual(ReaderFromBuffer reader){
        if(!correctValue){ // Restore the value in such a way as to simulate a return to the place where the loop was interrupted
            int bit;
            bit = reader.readBit();
            if (bit == ReaderFromBuffer.EOF) {
                return ReaderFromBuffer.EOF;
            }
            if(bit > 1)
                return -bit;

            currentValue = 2 * currentValue + bit;
        }
        for (;;) {
            if (high < HALF) {
                // Nothing
            }
            else if (low >= HALF) {    // Expand high half
                currentValue -= HALF;
                low -= HALF;
                high -= HALF;          // Subtract offset to top
            }
            else if (low >= FIRST_QTR && high < THIRD_QTR) { // Expand middle half
                currentValue -= FIRST_QTR;
                low -= FIRST_QTR;      // Subtract offset to middle
                high -= FIRST_QTR;
            }
            else {
                correctValue = true;
                break;                // Otherwise exit loop
            }
            low = 2 * low;
            high = 2 * high + 1;       // Scale up code range

            int bit;
            bit = reader.readBit();
            if (bit == ReaderFromBuffer.EOF) { // Interrupting the loop because the buffer has run out
                correctValue = false;
                return ReaderFromBuffer.EOF;
            }
            if(bit > 1) // Too many dummy bits
                return -bit;

            currentValue = 2 * currentValue + bit;
        }
        return SUCCESS;
    }

    private Pair<Integer, Integer> decodeSymbol(ReaderFromBuffer reader) {
        if(!correctValue) { // Restoring the actual value of the parameter
            int tmp = makeActual(reader);
            if(tmp != SUCCESS)
                return new Pair<>(tmp, 0);
        }

        long range = high - low + 1;   // Size of the current code region
        int symbol;
        long cumul = ((currentValue - low + 1) * cumulative[0] - 1) / range;

        for (symbol = 1; cumulative[symbol] > cumul; ++symbol); // Find symbol
        high = low + (range * cumulative[symbol - 1]) / cumulative[0] - 1;
        low = low + (range * cumulative[symbol]) / cumulative[0];

        int tmp = makeActual(reader); // Restoring the actual value of the parameter
        if(tmp != SUCCESS)
            return new Pair<>(tmp, symbol);

        return new Pair<>(SUCCESS, symbol);
    }

    private RC Decode(byte[] bytes){
        if (bytes != null && decodingComplete)
            return new RC(RCWho.EXECUTOR, RCType.CODE_CUSTOM_ERROR, "Incorrect input file");
        if(bytes == null && decodingComplete)
                return RC_SUCCESS;

        ReaderFromBuffer reader = new ReaderFromBuffer(bytes);
        if(valueBitsFilled != VALUE_BITS_NUM) { // Perform the initial filling of the parameter
            for (int counter = valueBitsFilled; counter < VALUE_BITS_NUM; ++counter) {
                int bit = reader.readBit();
                if (bit == ReaderFromBuffer.EOF)
                    return RC_SUCCESS;
                if (bit > 1)
                    return new RC(RCWho.EXECUTOR, RCType.CODE_CUSTOM_ERROR, "Incorrect input file");

                currentValue = 2 * currentValue + bit;
                ++valueBitsFilled;
            }
        }

        while (true) {
            Pair<Integer, Integer> p = decodeSymbol(reader);
            int symbol = p.getValue();
            if(p.getKey() != ReaderFromBuffer.EOF && p.getKey() < 0)
                return new RC(RCWho.EXECUTOR, RCType.CODE_CUSTOM_ERROR, "Incorrect input file");
            if (symbol == EOF_SYMBOL && reader.isEnded()) { // Executes until it encounters a stop symbol
                decodingComplete = true;
                return writer.outBufferClose();
            }

            RC tmp = writer.writeByte((byte)indexToByte[symbol]);
            if(!tmp.isSuccess())
                return tmp;
            updateModel(symbol);

            if(p.getKey() == ReaderFromBuffer.EOF)
                break;
        }
        return RC_SUCCESS;
    }
}
