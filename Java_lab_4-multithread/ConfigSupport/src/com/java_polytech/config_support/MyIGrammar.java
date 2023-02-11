package com.java_polytech.config_support;

// Grammar interface for use in a SyntaxAnalyzer
public interface MyIGrammar {
    // Returns a delimiter between the field name and value
    String getDelimiter();
    //Returns the comment attribute character
    String getCommentString();
    // Returns true if there is a field with the same name as 'key' in the grammar
    boolean isGrammarKey(String key);
    // Returns the number of fields in the grammar
    int numberOfFields();
}