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

import { Config, HalfMaxInt32, HalfMinInt32, LATIN1, UTF8 } from "../type";
import { PlatformBuffer, alloc, strByteLength } from "../platformBuffer";
import { OwnershipError } from "../error";
import { toFloat16 } from "./number";

declare global {
  // eslint-disable-next-line
  var isOneByteString: ((input: string) => boolean) | undefined;
}

const MAX_POOL_SIZE = 1024 * 1024 * 3; // 3MB

export class BinaryWriter {
  private cursor = 0;
  private byteLength = 0;
  private platformBuffer!: PlatformBuffer;
  private dataView!: DataView;
  private reserved = 0;
  private locked = false;
  private config: Config;

  constructor(config: Config) {
    this.initPoll();
    this.config = config;
  }

  private initPoll() {
    this.byteLength = 1024 * 100;
    this.platformBuffer = alloc(this.byteLength);
    this.dataView = new DataView(this.platformBuffer.buffer, this.platformBuffer.byteOffset);
  }

  reserve(len: number) {
    this.reserved += len;
    if (this.byteLength - this.cursor <= this.reserved) {
      const newAb = alloc(this.byteLength * 2 + len);
      this.platformBuffer.copy(newAb, 0);
      this.platformBuffer = newAb;
      this.byteLength = this.platformBuffer.byteLength;
      this.dataView = new DataView(this.platformBuffer.buffer, this.platformBuffer.byteOffset);
    }
  }

  reset() {
    if (this.locked) {
      throw new OwnershipError("Ownership of writer was held by dumpAndOwn, but not released");
    }
    this.cursor = 0;
    this.reserved = 0;
  }

  uint8(v: number) {
    this.dataView.setUint8(this.cursor, v);
    this.cursor++;
  }

  int8(v: number) {
    this.dataView.setInt8(this.cursor, v);
    this.cursor++;
  }

  int24(v: number) {
    this.dataView.setUint32(this.cursor, v, true);
    this.cursor += 3;
  }

  uint16(v: number) {
    this.dataView.setUint16(this.cursor, v, true);
    this.cursor += 2;
  }

  int16(v: number) {
    this.dataView.setInt16(this.cursor, v, true);
    this.cursor += 2;
  }

  skip(len: number) {
    this.cursor += len;
  }

  int32(v: number) {
    this.dataView.setInt32(this.cursor, v, true);
    this.cursor += 4;
  }

  uint32(v: number) {
    this.dataView.setUint32(this.cursor, v, true);
    this.cursor += 4;
  }

  int64(v: bigint) {
    this.dataView.setBigInt64(this.cursor, v, true);
    this.cursor += 8;
  }

  sliInt64(v: bigint | number) {
    if (v <= HalfMaxInt32 && v >= HalfMinInt32) {
      // write:
      // 00xxx -> 0xxx
      // 11xxx -> 1xxx
      // read:
      // 0xxx -> 00xxx
      // 1xxx -> 11xxx
      this.dataView.setUint32(this.cursor, Number(v) << 1, true);
      this.cursor += 4;
    } else {
      const BIG_LONG_FLAG = 0b1; // bit 0 set, means big long.
      this.dataView.setUint8(this.cursor, BIG_LONG_FLAG);
      this.cursor += 1;
      this.varInt64(BigInt(v));
    }
  }

  float32(v: number) {
    this.dataView.setFloat32(this.cursor, v, true);
    this.cursor += 4;
  }

  float64(v: number) {
    this.dataView.setFloat64(this.cursor, v, true);
    this.cursor += 8;
  }

  buffer(v: Uint8Array) {
    this.reserve(v.byteLength);
    this.platformBuffer.set(v, this.cursor);
    this.cursor += v.byteLength;
  }

  uint64(v: bigint) {
    this.dataView.setBigUint64(this.cursor, v, true);
    this.cursor += 8;
  }

  bufferWithoutMemCheck(bf: PlatformBuffer, byteLen: number) {
    bf.copy(this.platformBuffer, this.cursor);
    this.cursor += byteLen;
  }

  fastWriteStringUtf8(string: string, buffer: Uint8Array, offset: number) {
    let c1: number;
    let c2: number;
    for (let i = 0; i < string.length; ++i) {
      c1 = string.charCodeAt(i);
      if (c1 < 128) {
        buffer[offset++] = c1;
      } else if (c1 < 2048) {
        const u1 = (c1 >> 6) | 192;
        const u2 = (c1 & 63) | 128;
        this.dataView.setUint16(offset, (u1 << 8) | u2);
        offset += 2;
      } else if (
        (c1 & 0xfc00) === 0xd800
        && ((c2 = string.charCodeAt(i + 1)) & 0xfc00) === 0xdc00
      ) {
        c1 = 0x10000 + ((c1 & 0x03ff) << 10) + (c2 & 0x03ff);
        ++i;
        const u1 = (c1 >> 18) | 240;
        const u2 = ((c1 >> 12) & 63) | 128;
        const u3 = ((c1 >> 6) & 63) | 128;
        const u4 = (c1 & 63) | 128;
        this.dataView.setUint32(offset, (u1 << 24) | (u2 << 16) | (u3 << 8) | u4);
        offset += 4;
      } else {
        const u1 = (c1 >> 12) | 224;
        const u2 = ((c1 >> 6) & 63) | 128;
        this.dataView.setUint16(offset, (u1 << 8) | u2);
        offset += 2;
        buffer[offset++] = (c1 & 63) | 128;
      }
    }
  }

  stringOfVarUInt32Fast(v: string) {
    const isLatin1 = globalThis.isOneByteString!(v);
    const len = isLatin1 ? v.length : strByteLength(v);
    this.dataView.setUint8(this.cursor++, isLatin1 ? LATIN1 : UTF8);
    this.varUInt32(len);
    this.reserve(len);
    if (isLatin1) {
      this.platformBuffer.write(v, this.cursor, "latin1");
    } else {
      if (len < 40) {
        this.fastWriteStringUtf8(v, this.platformBuffer, this.cursor);
      } else {
        this.platformBuffer.write(v, this.cursor, "utf8");
      }
    }
    this.cursor += len;
  }

  stringOfVarUInt32Slow(v: string) {
    const len = strByteLength(v);
    const isLatin1 = len === v.length;
    this.dataView.setUint8(this.cursor++, isLatin1 ? LATIN1 : UTF8);
    this.varUInt32(len);
    this.reserve(len);
    if (isLatin1) {
      if (len < 40) {
        for (let index = 0; index < v.length; index++) {
          this.platformBuffer[this.cursor + index] = v.charCodeAt(index);
        }
      } else {
        this.platformBuffer.write(v, this.cursor, "latin1");
      }
    } else {
      if (len < 40) {
        this.fastWriteStringUtf8(v, this.platformBuffer, this.cursor);
      } else {
        this.platformBuffer.write(v, this.cursor, "utf8");
      }
    }
    this.cursor += len;
  }

  varInt32(v: number) {
    return this.varUInt32((v << 1) ^ (v >> 31));
  }

  varUInt32(value: number) {
    value = (value >>> 0) & 0xFFFFFFFF; // keep only the lower 32 bits

    if (value >> 7 == 0) {
      this.platformBuffer[this.cursor++] = value;
      return;
    }
    const rawCursor = this.cursor;
    let u32 = 0;
    if (value >> 14 == 0) {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7) << 16);
      this.cursor += 2;
    } else if (value >> 21 == 0) {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7 & 0x7f | 0x80) << 16) | ((value >> 14) << 8);
      this.cursor += 3;
    } else if (value >> 28 == 0) {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7 & 0x7f | 0x80) << 16) | ((value >> 14 & 0x7f | 0x80) << 8) | (value >> 21);
      this.cursor += 4;
    } else {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7 & 0x7f | 0x80) << 16) | ((value >> 14 & 0x7f | 0x80) << 8) | (value >> 21 & 0x7f | 0x80);
      this.platformBuffer[rawCursor + 4] = value >> 28;
      this.cursor += 5;
    }
    this.dataView.setUint32(rawCursor, u32);
  }

  varInt64(v: bigint) {
    if (typeof v !== "bigint") {
      v = BigInt(v);
    }
    return this.varUInt64((v << 1n) ^ (v >> 63n));
  }

  varUInt64(val: bigint | number) {
    if (typeof val !== "bigint") {
      val = BigInt(val);
    }
    val = val & 0xFFFFFFFFFFFFFFFFn; // keep only the lower 64 bits

    while (val > 127) {
      this.platformBuffer[this.cursor++] = Number(val & 127n | 128n);
      val >>= 7n;
    }
    this.platformBuffer[this.cursor++] = Number(val);
    return;
  }

  tryFreePool() {
    if (this.byteLength > MAX_POOL_SIZE) {
      this.initPoll();
    }
  }

  dump() {
    const result = alloc(this.cursor);
    this.platformBuffer.copy(result, 0, 0, this.cursor);
    this.tryFreePool();
    return result;
  }

  dumpAndOwn() {
    this.locked = true;
    return {
      get: () => {
        return this.platformBuffer.subarray(0, this.cursor);
      },
      dispose: () => {
        this.locked = false;
      },
    };
  }

  float16(value: number) {
    this.uint16(toFloat16(value));
  }

  getCursor() {
    return this.cursor;
  }

  setUint32Position(offset: number, v: number) {
    this.dataView.setUint32(offset, v, true);
  }

  setUint8Position(offset: number, v: number) {
    this.dataView.setUint8(offset, v);
  }

  setUint16Position(offset: number, v: number) {
    this.dataView.setUint16(offset, v, true);
  }

  getByteLen() {
    return this.byteLength;
  }

  getReserved() {
    return this.reserved;
  }

  stringOfVarUInt32(v: string) {
    return globalThis.isOneByteString
      ? this.stringOfVarUInt32Fast(v)
      : this.stringOfVarUInt32Slow(v);
  }
}
