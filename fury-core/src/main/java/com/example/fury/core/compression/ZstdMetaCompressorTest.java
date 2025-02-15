package com.example.fury.core.compression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ZstdMetaCompressorTest {

    private final MetaCompressor compressor = new ZstdMetaCompressor();

    @Test
    public void testCompressDecompress() {
        byte[] originalData = "This is some sample metadata.".getBytes();
        byte[] compressedData = compressor.compress(originalData);
        byte[] decompressedData = compressor.decompress(compressedData);

        assertArrayEquals(originalData, decompressedData);
    }

    // Add more test cases for different input sizes and edge cases
}