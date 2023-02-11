package com.java_polytech.pipeline;

import com.java_polytech.config_support.MyIGrammar;

// The class implementing a grammar interface for a writer
public class WriterGrammarMy implements MyIGrammar {
    // The enum containing the writer's grammar fields
    public enum ConfigParams {
        BUFFER_SIZE("BUFFER_SIZE");

        private final String parameter;

        ConfigParams(String param) {
            parameter = param;
        }

        public String asString() {
            return parameter;
        }
    }

    static private final String DELIMITER = "\\s*=\\s*"; // Delimiter between the field name and value

    @Override
    public String getDelimiter() {
        return DELIMITER;
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