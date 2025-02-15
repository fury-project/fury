/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fury.meta.zstd;

import com.github.luben.zstd.Zstd;
import org.apache.fury.meta.MetaCompressor;


/** A meta compressor based on {@link Zstd} compression algorithm. */
public class ZstdMetaCompressor implements MetaCompressor {
    @Override
    public byte[] compress(byte[] data, int offset, int size) {
        byte[] compressData = new byte[size];
        System.arraycopy(data, offset, compressData, 0, size);
        return Zstd.compress(compressData);
    }

    @Override
    public byte[] decompress(byte[] data, int offset, int size) {
        byte[] decompressData = new byte[size];
        System.arraycopy(data, offset, decompressData, 0, size);

        byte[] buffer = new byte[(int) Zstd.getFrameContentSize(decompressData)];
        Zstd.decompress(buffer, decompressData);
        return buffer;
    }

    @Override
    public int hashCode() {
        return ZstdMetaCompressor.class.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o != null && getClass() == o.getClass();
    }
}
