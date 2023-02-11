package com.java_polytech.pipeline;

import com.java_polytech.config_support.MyIGrammar;

// The class implementing a grammar interface for an executor
public class ExecutorGrammarMy implements MyIGrammar {
    // The enum containing the executor's grammar fields
    public enum ConfigParams {
        OUT_BUFFER_SIZE("OUT_BUFFER_SIZE"),
        MODE("MODE");

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
    public boolean isGrammarKey(String str) {
        for (ConfigParams fieldName : ConfigParams.values())
            if (str.equals(fieldName.asString()))
                return true;
        return false;
    }

    @Override
    public int numberOfFields() {
        return ConfigParams.values().length;
    }
}