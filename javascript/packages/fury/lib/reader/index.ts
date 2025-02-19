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

import { LATIN1, UTF16, UTF8 } from "../type";
import { isNodeEnv } from "../util";
import { PlatformBuffer, alloc, fromUint8Array } from "../platformBuffer";
import { readLatin1String } from "./string";

export class BinaryReader {
  private sliceStringEnable;
  private cursor = 0;
  private dataView!: DataView;
  private platformBuffer!: PlatformBuffer;
  private bigString = "";
  private byteLength = 0;

  constructor(config: {
    useSliceString?: boolean;
  }) {
    this.sliceStringEnable = isNodeEnv && config.useSliceString;
  }

  reset(ab: Uint8Array) {
    this.platformBuffer = fromUint8Array(ab);
    this.byteLength = this.platformBuffer.byteLength;
    this.dataView = new DataView(this.platformBuffer.buffer, this.platformBuffer.byteOffset, this.byteLength);
    if (this.sliceStringEnable) {
      this.bigString = this.platformBuffer.toString("latin1", 0, this.byteLength);
    }
    this.cursor = 0;
  }

  uint8() {
    return this.dataView.getUint8(this.cursor++);
  }

  int8() {
    return this.dataView.getInt8(this.cursor++);
  }

  uint16() {
    const result = this.dataView.getUint16(this.cursor, true);
    this.cursor += 2;
    return result;
  }

  int16() {
    const result = this.dataView.getInt16(this.cursor, true);
    this.cursor += 2;
    return result;
  }

  skip(len: number) {
    this.cursor += len;
  }

  int32() {
    const result = this.dataView.getInt32(this.cursor, true);
    this.cursor += 4;
    return result;
  }

  uint32() {
    const result = this.dataView.getUint32(this.cursor, true);
    this.cursor += 4;
    return result;
  }

  int64() {
    const result = this.dataView.getBigInt64(this.cursor, true);
    this.cursor += 8;
    return result;
  }

  uint64() {
    const result = this.dataView.getBigUint64(this.cursor, true);
    this.cursor += 8;
    return result;
  }

  sliInt64() {
    const i = this.dataView.getUint32(this.cursor, true);
    if ((i & 0b1) != 0b1) {
      this.cursor += 4;
      return BigInt(i >> 1);
    }
    this.cursor += 1;
    return this.varInt64();
  }

  float32() {
    const result = this.dataView.getFloat32(this.cursor, true);
    this.cursor += 4;
    return result;
  }

  float64() {
    const result = this.dataView.getFloat64(this.cursor, true);
    this.cursor += 8;
    return result;
  }

  stringUtf8At(start: number, len: number) {
    return this.platformBuffer.toString("utf8", start, start + len);
  }

  stringUtf8(len: number) {
    const result = this.platformBuffer.toString("utf8", this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  stringUtf16LE(len: number) {
    const result = this.platformBuffer.toString("utf16le", this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  stringOfVarUInt32() {
    switch (this.uint8()) {
      case LATIN1:
        return this.stringLatin1(this.varUInt32());
      case UTF8:
        return this.stringUtf8(this.varUInt32());
      case UTF16:
        return this.stringUtf16LE(this.varUInt32());
      default:
        break;
    }
  }

  stringLatin1(len: number) {
    if (this.sliceStringEnable) {
      return this.stringLatin1Fast(len);
    }
    return this.stringLatin1Slow(len);
  }

  private stringLatin1Fast(len: number) {
    const result = this.bigString.substring(this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  private stringLatin1Slow(len: number) {
    const rawCursor = this.cursor;
    this.cursor += len;
    return readLatin1String(this.platformBuffer, len, rawCursor);
  }

  buffer(len: number) {
    const result = alloc(len);
    this.platformBuffer.copy(result, 0, this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  bufferRef(len: number) {
    const result = this.platformBuffer.subarray(this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  varUInt32() {
    // Reduce memory reads as much as possible. Reading a uint32 at once is far faster than reading four uint8s separately.
    if (this.byteLength - this.cursor >= 5) {
      const fourByteValue = this.dataView.getUint32(this.cursor++, true);
      // | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits |
      let result = fourByteValue & 0x7f;
      if ((fourByteValue & 0x80) != 0) {
        this.cursor++;
        // 0x3f80: 0b1111111 << 7
        result |= (fourByteValue >>> 1) & 0x3f80;
        if ((fourByteValue & 0x8000) != 0) {
          this.cursor++;
          // 0x1fc000: 0b1111111 << 14
          result |= (fourByteValue >>> 2) & 0x1fc000;
          if ((fourByteValue & 0x800000) != 0) {
            this.cursor++;
            // 0xfe00000: 0b1111111 << 21
            result |= (fourByteValue >>> 3) & 0xfe00000;
            if ((fourByteValue & 0x80000000) != 0) {
              result |= (this.uint8()) << 28;
            }
          }
        }
      }
      return result;
    }
    let byte = this.uint8();
    let result = byte & 0x7f;
    if ((byte & 0x80) != 0) {
      byte = this.uint8();
      result |= (byte & 0x7f) << 7;
      if ((byte & 0x80) != 0) {
        byte = this.uint8();
        result |= (byte & 0x7f) << 14;
        if ((byte & 0x80) != 0) {
          byte = this.uint8();
          result |= (byte & 0x7f) << 21;
          if ((byte & 0x80) != 0) {
            byte = this.uint8();
            result |= (byte) << 28;
          }
        }
      }
    }
    return result;
  }

  varInt32() {
    const v = this.varUInt32();
    return (v >> 1) ^ -(v & 1); // zigZag decode
  }

  bigUInt8() {
    return BigInt(this.uint8() >>> 0);
  }

  varUInt64() {
    // Creating BigInts is too performance-intensive; we'll use uint32 instead.
    if (this.byteLength - this.cursor < 8) {
      let byte = this.bigUInt8();
      let result = byte & 0x7fn;
      if ((byte & 0x80n) != 0n) {
        byte = this.bigUInt8();
        result |= (byte & 0x7fn) << 7n;
        if ((byte & 0x80n) != 0n) {
          byte = this.bigUInt8();
          result |= (byte & 0x7fn) << 14n;
          if ((byte & 0x80n) != 0n) {
            byte = this.bigUInt8();
            result |= (byte & 0x7fn) << 21n;
            if ((byte & 0x80n) != 0n) {
              byte = this.bigUInt8();
              result |= (byte & 0x7fn) << 28n;
              if ((byte & 0x80n) != 0n) {
                byte = this.bigUInt8();
                result |= (byte & 0x7fn) << 35n;
                if ((byte & 0x80n) != 0n) {
                  byte = this.bigUInt8();
                  result |= (byte & 0x7fn) << 42n;
                  if ((byte & 0x80n) != 0n) {
                    byte = this.bigUInt8();
                    result |= (byte & 0x7fn) << 49n;
                    if ((byte & 0x80n) != 0n) {
                      byte = this.bigUInt8();
                      result |= (byte) << 56n;
                    }
                  }
                }
              }
            }
          }
        }
      }
      return result;
    }
    const l32 = this.dataView.getUint32(this.cursor++, true);
    let byte = l32 & 0xff;
    let rl28 = byte & 0x7f;
    let rh28 = 0;
    if ((byte & 0x80) != 0) {
      byte = l32 & 0xff00 >> 8;
      this.cursor++;
      rl28 |= (byte & 0x7f) << 7;
      if ((byte & 0x80) != 0) {
        byte = l32 & 0xff0000 >> 16;
        this.cursor++;
        rl28 |= (byte & 0x7f) << 14;
        if ((byte & 0x80) != 0) {
          byte = l32 & 0xff000000 >> 24;
          this.cursor++;
          rl28 |= (byte & 0x7f) << 21;
          if ((byte & 0x80) != 0) {
            const h32 = this.dataView.getUint32(this.cursor++, true);
            byte = h32 & 0xff;
            rh28 |= (byte & 0x7f);
            if ((byte & 0x80) != 0) {
              byte = h32 & 0xff00 >> 8;
              this.cursor++;
              rh28 |= (byte & 0x7f) << 7;
              if ((byte & 0x80) != 0) {
                byte = h32 & 0xff0000 >> 16;
                this.cursor++;
                rh28 |= (byte & 0x7f) << 14;
                if ((byte & 0x80) != 0) {
                  byte = h32 & 0xff000000 >> 24;
                  this.cursor++;
                  rh28 |= (byte & 0x7f) << 21;
                  if ((byte & 0x80) != 0) {
                    return (BigInt(this.uint8()) << 56n) | BigInt(rh28) << 28n | BigInt(rl28);
                  }
                }
              }
            }
          }
        }
      }
    }

    return BigInt(rh28) << 28n | BigInt(rl28);
  }

  varInt64() {
    const v = this.varUInt64();
    return (v >> 1n) ^ -(v & 1n); // zigZag decode
  }

  float16() {
    const asUint16 = this.uint16();
    const sign = asUint16 >> 15;
    const exponent = (asUint16 >> 10) & 0x1F;
    const mantissa = asUint16 & 0x3FF;

    // IEEE 754-2008
    if (exponent === 0) {
      if (mantissa === 0) {
        // +-0
        return sign === 0 ? 0 : -0;
      } else {
        // Denormalized number
        return (sign === 0 ? 1 : -1) * mantissa * 2 ** (1 - 15 - 10);
      }
    } else if (exponent === 31) {
      if (mantissa === 0) {
        // Infinity
        return sign === 0 ? Infinity : -Infinity;
      } else {
        // NaN
        return NaN;
      }
    } else {
      // Normalized number
      return (sign === 0 ? 1 : -1) * (1 + mantissa * 2 ** -10) * 2 ** (exponent - 15);
    }
  }

  getCursor() {
    return this.cursor;
  }

  setCursor(v: number) {
    this.cursor = v;
  }
}
