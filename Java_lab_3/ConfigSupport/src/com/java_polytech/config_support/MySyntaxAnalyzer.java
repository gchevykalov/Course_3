package com.java_polytech.config_support;

import com.java_polytech.pipeline_interfaces.RC;

import javafx.util.Pair;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Scanner;
import java.util.ArrayList;

// The class that parses the configuration file of a class that implements the IGrammar interface
public class MySyntaxAnalyzer {
    RC.RCWho who;                                              // Grammar owner
    MyIGrammar curGrammar;                                       // The grammar on the basis of which the syntactic analysis takes place
    private HashMap<String, ArrayList<String>> LOADED_PARAMS;  // Container with parsing results

    public MySyntaxAnalyzer(RC.RCWho owner, MyIGrammar grammar) {
        who = owner;
        curGrammar = grammar;
    }

    public RC process(String filename) {
        HashMap<String, ArrayList<String>> hashMap = new HashMap<>();

        // Load configuration file
        File file = new File(filename);
        Scanner scanner;
        try {
            scanner = new Scanner(file);
        }
        catch (FileNotFoundException ex) {
            return new RC(who, RC.RCType.CODE_CONFIG_FILE_ERROR, "Config not found");
        }

        // Read file
        String line;
        while (scanner.hasNext()) {
            line = scanner.nextLine();

            // Skip empty lines
            if(line.equals(""))
                continue;
            //Skip comments
            if(line.matches("\\s*#.*"))
                continue;

            // Highlight the field name and its value using a delimiter
            String[] field = line.split(curGrammar.getDelimiter());
            if (field.length != 2)
                return new RC(who, RC.RCType.CODE_CONFIG_GRAMMAR_ERROR, "Config grammar error");

            String key = field[0].trim();
            // Check whether the field is a grammar element
            if (curGrammar.isGrammarKey(key)) {
                // Add a value to a set of existing ones
                ArrayList<String> curArray = hashMap.get(key);
                if(curArray == null) {
                    curArray = new ArrayList<>();
                }
                curArray.add(field[1].trim());
                hashMap.put(key, curArray);
            }
            else
                return new RC(who, RC.RCType.CODE_CONFIG_GRAMMAR_ERROR, "In config file, unknown field " + field[0]);
        }

        // Check if all the necessary elements are filled in
        if (hashMap.size() != curGrammar.numberOfFields())
            return new RC(who, RC.RCType.CODE_CONFIG_GRAMMAR_ERROR, "In config file, not enough fields");

        LOADED_PARAMS = hashMap;
        scanner.close();
        return RC.RC_SUCCESS;
    }

    public Pair<RC, ArrayList<String>> GetFieldValues(String field) {
        if (curGrammar.isGrammarKey(field))
            return new Pair<>(RC.RC_SUCCESS, LOADED_PARAMS.get(field));
        else
            return new Pair<>(new RC(who, RC.RCType.CODE_CUSTOM_ERROR, "Unknown field asked from grammar"),
                    LOADED_PARAMS.get(field));
    }
}