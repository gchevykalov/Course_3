package com.java_polytech.pipeline_interfaces;

public interface IConsumer {
    long END_OF_FILE_PACKET_NUMBER = -1;

    RC setProvider(IProvider provider);

    RC consume(long packet_number);
}

