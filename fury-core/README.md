# fury-core Module

This module provides core functionalities for the FURY project.

## Zstd Compression Integration

This module now includes support for compressing and decompressing type metadata using the Zstd compression library.

**Features:**

- **Improved Compression:** Zstd generally offers better compression ratios than the previous Deflater-based implementation.
- **ZstdMetaCompressor:** A new `ZstdMetaCompressor` class has been implemented to handle Zstd compression and decompression.
- **Unit Tests:** Unit tests have been added to verify the functionality and correctness of the `ZstdMetaCompressor`.

**Usage:**

- To use the `ZstdMetaCompressor`, inject it into your service classes:

   ```java
   @Autowired
   private MetaCompressor metaCompressor; 

   public void processMetadata(byte[] metadata) {
       byte[] compressedMetadata = metaCompressor.compress(metadata);
       // ... use compressedMetadata ...
   }   