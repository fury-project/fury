package com.example.fury.service;

import com.example.fury.core.compression.MetaCompressor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MyService {

    @Autowired
    private MetaCompressor metaCompressor;

    public void processMetadata(byte[] metadata) {
        byte[] compressedMetadata = metaCompressor.compress(metadata);
        // ... use compressedMetadata ...
    }
}