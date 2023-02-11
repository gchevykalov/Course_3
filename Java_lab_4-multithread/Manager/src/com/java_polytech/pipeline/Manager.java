package com.java_polytech.pipeline;

import com.java_polytech.config_support.MySyntaxAnalyzer;
import com.java_polytech.pipeline_interfaces.*;

import javafx.util.Pair;
import java.util.ArrayList;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static com.java_polytech.pipeline_interfaces.RC.*;

// The class that performs pipeline management
public class Manager implements IConfigurable {
    boolean IsInited;                                    // Flag indicating whether the manager is initialized
    IReader Reader;                                      // A reference to an instance of a class that reads from a file
    static final int NUM_OF_READERS = 1;                 // Number of readers on the pipeline
    ArrayList<IExecutor> Executors = new ArrayList<>();  // A set of executors placed on the pipeline
    IWriter Writer;                                      // A reference to an instance of the class that writes to the file
    static final int NUM_OF_WRITERS = 1;                 // Number of writers on the pipeline
    FileInputStream FileIn;                              // Input file
    FileOutputStream FileOut;                            // Output file
    private static Logger logger;

    public static boolean handleRC(RC returnCode) {
        if (!returnCode.isSuccess()) {
            logger.severe("ERROR: " + returnCode.who.get() + ": " + returnCode.info);
            return false;
        }
        return true;
    }

    public Manager() {
        IsInited = false;
    }

    @Override
    public RC setConfig(String str) {
        // Perform a syntactic analysis of the configuration file
        MySyntaxAnalyzer config = new MySyntaxAnalyzer(RCWho.MANAGER, new ManagerGrammarMy());
        RC code = config.process(str);

        // If the syntactic analysis was successful, conduct a semantic analysis
        if (code.isSuccess()) {
            // Open files
            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.INPUT_FILE.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                // The value of the field is not set the number of times that is necessary
                if(val.getValue().size() != NUM_OF_READERS)
                    return new RC(RCWho.MANAGER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                            + ManagerGrammarMy.ConfigParams.INPUT_FILE.asString() + " must be set "
                            + NUM_OF_READERS + " time(s)");

                FileIn = new FileInputStream(val.getValue().get(NUM_OF_READERS - 1));
            } catch (FileNotFoundException e) { // Input file not found
                return RC_MANAGER_INVALID_INPUT_FILE;
            }

            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.OUTPUT_FILE.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                // The value of the field is not set the number of times that is necessary
                if(val.getValue().size() != NUM_OF_WRITERS)
                    return new RC(RCWho.MANAGER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                            + ManagerGrammarMy.ConfigParams.OUTPUT_FILE.asString() + " must be set "
                            + NUM_OF_WRITERS + " time(s)");

                FileOut = new FileOutputStream(val.getValue().get(NUM_OF_WRITERS - 1));
            } catch (FileNotFoundException e) { // Output file not found
                return RC_MANAGER_INVALID_OUTPUT_FILE;
            }

            // Get all classes
            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.READER_NAME.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                // The value of the field is not set the number of times that is necessary
                if(val.getValue().size() != NUM_OF_READERS)
                    return new RC(RCWho.MANAGER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                            + ManagerGrammarMy.ConfigParams.READER_NAME.asString() + " must be set "
                            + NUM_OF_READERS + " time(s)");

                Class<?> reader = Class.forName(val.getValue().get(NUM_OF_READERS - 1));
                if (IReader.class.isAssignableFrom(reader)) {
                    Reader = (IReader) reader.getDeclaredConstructor().newInstance();
                }
                else { // The class does not meet the necessary requirements
                    return RC_MANAGER_INVALID_READER_CLASS;
                }
            }
            catch (Exception e) { // The specified class was not found
                return RC_MANAGER_INVALID_READER_CLASS;
            }

            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.EXECUTOR_NAME.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                for(String name: val.getValue()){
                    Class<?> executor = Class.forName(name);
                    if (IExecutor.class.isAssignableFrom(executor)) {
                        Executors.add((IExecutor) executor.getDeclaredConstructor().newInstance());
                    }
                    else { // The class does not meet the necessary requirements
                        return RC_MANAGER_INVALID_EXECUTOR_CLASS;
                    }
                }
            }
            catch (Exception e) { // The specified class was not found
                return RC_MANAGER_INVALID_EXECUTOR_CLASS;
            }

            try {
                Pair<RC, ArrayList<String>> val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.WRITER_NAME.asString());

                if (!val.getKey().isSuccess())
                    return val.getKey();

                // The value of the field is not set the number of times that is necessary
                if(val.getValue().size() != NUM_OF_WRITERS)
                    return new RC(RCWho.MANAGER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                            + ManagerGrammarMy.ConfigParams.WRITER_NAME.asString() + " must be set "
                            + NUM_OF_WRITERS + " time(s)");

                Class<?> writer = Class.forName(val.getValue().get(NUM_OF_WRITERS - 1));
                if (IWriter.class.isAssignableFrom(writer)) {
                    Writer = (IWriter) writer.getDeclaredConstructor().newInstance();
                }
                else { // The class does not meet the necessary requirements
                    return RC_MANAGER_INVALID_WRITER_CLASS;
                }
            }
            catch (Exception e) { // The specified class was not found
                return RC_MANAGER_INVALID_WRITER_CLASS;
            }

            // Set all configurations
            RC tmp;

            Pair<RC, ArrayList<String>> val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.READER_CONFIG.asString());
            if (!val.getKey().isSuccess())
                return val.getKey();

            // The value of the field is not set the number of times that is necessary
            if(val.getValue().size() != NUM_OF_READERS)
                return new RC(RCWho.MANAGER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                        + ManagerGrammarMy.ConfigParams.READER_CONFIG.asString() + " must be set "
                        + NUM_OF_READERS + " time(s)");

            tmp = Reader.setConfig(val.getValue().get(NUM_OF_READERS - 1));
            if (!tmp.isSuccess())
                return tmp;

            val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.EXECUTOR_CONFIG.asString());
            if (!val.getKey().isSuccess())
                return val.getKey();

            // The value of the field is not set the number of times that is necessary
            if(val.getValue().size() != Executors.size())
                return new RC(RCWho.MANAGER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                        + ManagerGrammarMy.ConfigParams.EXECUTOR_CONFIG.asString() + " must be set "
                        + Executors.size() + " time(s)");

            int i = 0;
            for(IExecutor executor: Executors) {
                tmp = executor.setConfig(val.getValue().get(i++));
                if (!tmp.isSuccess())
                    return tmp;
            }

            val = config.GetFieldValues(ManagerGrammarMy.ConfigParams.WRITER_CONFIG.asString());
            if (!val.getKey().isSuccess())
                return val.getKey();

            // The value of the field is not set the number of times that is necessary
            if(val.getValue().size() != NUM_OF_WRITERS)
                return new RC(RCWho.MANAGER, RCType.CODE_CONFIG_SEMANTIC_ERROR, "The value of field "
                        + ManagerGrammarMy.ConfigParams.WRITER_CONFIG.asString() + " must be set "
                        + NUM_OF_WRITERS + " time(s)");

            tmp = Writer.setConfig(val.getValue().get(NUM_OF_WRITERS - 1));
            if (!tmp.isSuccess())
                return tmp;

            // Build a pipeline from the specified elements
            tmp = Reader.setInputStream(FileIn);
            if (!tmp.isSuccess())
                return tmp;

            tmp = Reader.setConsumer(Executors.get(0));
            if (!tmp.isSuccess())
                return tmp;

            for(int j = 0; j < Executors.size() - 1; ++j){
                tmp = Executors.get(j).setConsumer(Executors.get(j + 1));
                if (!tmp.isSuccess())
                    return tmp;
            }

            tmp = Executors.get(Executors.size() - 1).setConsumer(Writer);
            if (!tmp.isSuccess())
                return tmp;

            tmp = Writer.setOutputStream(FileOut);
            if (!tmp.isSuccess())
                return tmp;

            IsInited = true;
            return RC_SUCCESS;
        }
        return code;
    }

    static final String logFileName = "log.txt";

    private static Logger makeLogger() {
        Logger logger = Logger.getLogger("Logger");
        FileHandler fh;
        try {
            fh = new FileHandler(logFileName);
        } catch (IOException ex) {
            return null;
        }
        SimpleFormatter sf = new SimpleFormatter();
        fh.setFormatter(sf);
        logger.addHandler(fh);
        logger.setUseParentHandlers(false);

        return logger;
    }

    public boolean execute() {
        if (!IsInited) {
            handleRC(new RC(RCWho.MANAGER, RCType.CODE_CUSTOM_ERROR, "Class not initialized"));
            return false;
        }
        Thread ReaderThread = new Thread(Reader, "Reader");
        Thread[] ExecutorThreads = new Thread[Executors.size()];
        for (int i = 0; i < Executors.size(); i++)
            ExecutorThreads[i] = new Thread(Executors.get(i), "Executor");
        Thread WriterThread = new Thread(Writer, "Writer");

        ReaderThread.start();
        for (int i = 0; i < Executors.size(); i++)
            ExecutorThreads[i].start();
        WriterThread.start();

        try {
            ReaderThread.join();
            for (int i = 0; i < Executors.size(); i++)
                ExecutorThreads[i].join();
            WriterThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            FileIn.close();
            FileOut.close();
        } catch (IOException e) {
            handleRC(new RC(RC.RCWho.MANAGER, RC.RCType.CODE_CUSTOM_ERROR, "ERROR: Manager: cannot even close files"));
        }

        boolean isAnyError = false;
        if (!Reader.getCurrentState().isSuccess()) {
            handleRC(Reader.getCurrentState());
            isAnyError = true;
        }
        for (IExecutor executor : Executors)
            if (!executor.getCurrentState().isSuccess()) {
                handleRC(executor.getCurrentState());
                isAnyError = true;
            }
        if (!Writer.getCurrentState().isSuccess()) {
            handleRC(Writer.getCurrentState());
            isAnyError = true;
        }

        return !isAnyError;
    }

    public static void main(String[] args) {
        logger = makeLogger();

        if (args.length != 1) {
            handleRC(RC_MANAGER_INVALID_ARGUMENT);
            System.out.println("Failure! Check log.txt for more info!");
            return;
        }

        Manager manager = new Manager();
        if (handleRC(manager.setConfig(args[0]))) {
            if (manager.execute()) {
                System.out.println("Success!");
                return;
            }
        }
        System.out.println("Failure! Check log.txt for more info!");
    }
}
