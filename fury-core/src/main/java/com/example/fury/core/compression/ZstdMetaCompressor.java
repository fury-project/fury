package com.example.fury.core.compression;

import com.github.luben.zstd.Zstd;

public class ZstdMetaCompressor implements MetaCompressor {

    @Override
    public byte[] compress(byte[] metadata) {
        try {
            return Zstd.compress(metadata);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress metadata: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] decompress(byte[] compressedMetadata) {
        try {
            return Zstd.decompress(compressedMetadata);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress metadata: " + e.getMessage(), e);
        }
    }
}