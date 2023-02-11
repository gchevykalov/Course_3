package com.java_polytech.pipeline;

import com.java_polytech.config_support.MyIGrammar;

// The class implementing a grammar interface for a manager
public class ManagerGrammarMy implements MyIGrammar {
    // The enum containing the manager's grammar fields
    public enum ConfigParams {
        INPUT_FILE("INPUT_FILE"),
        OUTPUT_FILE("OUTPUT_FILE"),
        READER_NAME("READER_NAME"),
        WRITER_NAME("WRITER_NAME"),
        EXECUTOR_NAME("EXECUTOR_NAME"),
        READER_CONFIG("READER_CONFIG"),
        WRITER_CONFIG("WRITER_CONFIG"),
        EXECUTOR_CONFIG("EXECUTOR_CONFIG");

        private final String parameter;

        ConfigParams(String param) {
            parameter = param;
        }

        public String asString() {
            return parameter;
        }
    }

    static private final String DELIMITER = "\\s*=\\s*"; // Delimiter between the field name and value
    final static private String COMMENT_STRING = "#";

    @Override
    public String getDelimiter() {
        return DELIMITER;
    }

    @Override
    public String getCommentString() {
        return COMMENT_STRING;
    }

    @Override
    public boolean isGrammarKey(String s) {
        for (ConfigParams prefix : ConfigParams.values())
            if (s.equals(prefix.asString()))
                return true;
        return false;
    }

    @Override
    public int numberOfFields() {
        return ConfigParams.values().length;
    }
}
