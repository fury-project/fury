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

import { fromString } from "../platformBuffer";
import { BinaryReader } from "../reader";

export enum Encoding {
  UTF_8, // Using UTF-8 as the fallback
  LOWER_SPECIAL,
  LOWER_UPPER_DIGIT_SPECIAL,
  FIRST_TO_LOWER_SPECIAL,
  ALL_TO_LOWER_SPECIAL,
}
export class MetaString {
  /** Defines the types of supported encodings for MetaStrings. */

  private string: string;
  private encoding: Encoding;
  private specialChar1: string;
  private specialChar2: string;
  private bytes: Uint8Array;
  private stripLastChar: boolean;

  /**
   * Constructs a MetaString with the specified encoding and data.
   *
   * @param encoding The type of encoding used for the string data.
   * @param bytes The encoded string data as a byte array.
   */
  public constructor(
    string: string, encoding: Encoding, specialChar1: string, specialChar2: string, bytes: Uint8Array) {
    this.string = string;
    this.encoding = encoding;
    this.specialChar1 = specialChar1;
    this.specialChar2 = specialChar2;
    this.bytes = bytes;
    if (encoding != Encoding.UTF_8) {
      this.stripLastChar = (bytes[0] & 0x80) != 0;
    } else {
      this.stripLastChar = false;
    }
  }

  public getString() {
    return this.string;
  }

  public getEncoding() {
    return this.encoding;
  }

  public getSpecialChar1() {
    return this.specialChar1;
  }

  public getSpecialChar2() {
    return this.specialChar2;
  }

  public getBytes() {
    return this.bytes;
  }

  public isStripLastChar() {
    return this.stripLastChar;
  }
}

/** Decodes MetaString objects back into their original plain text form. */
export class MetaStringDecoder {
  private specialChar1: string;
  private specialChar2: string;

  public constructor(specialChar1: string, specialChar2: string) {
    this.specialChar1 = specialChar1;
    this.specialChar2 = specialChar2;
  }

  public decode(reader: BinaryReader, len: number, encoding: Encoding): string {
    if (!len) {
      return "";
    }
    switch (encoding) {
      case Encoding.LOWER_SPECIAL:
        return this.decodeLowerSpecial(reader.bufferRef(len));
      case Encoding.LOWER_UPPER_DIGIT_SPECIAL:
        return this.decodeLowerUpperDigitSpecial(reader.bufferRef(len));
      case Encoding.FIRST_TO_LOWER_SPECIAL:
        return this.decodeRepFirstLowerSpecial(reader.bufferRef(len));
      case Encoding.ALL_TO_LOWER_SPECIAL:
        return this.decodeRepAllToLowerSpecial(reader.bufferRef(len));
      case Encoding.UTF_8:
        return reader.stringUtf8(len);
      default:
        throw new Error("Unexpected encoding flag: " + encoding);
    }
  }

  /** Decoding method for {@link Encoding#LOWER_SPECIAL}. */
  private decodeLowerSpecial(data: Uint8Array) {
    const decoded = [];
    const totalBits = data.length * 8; // Total number of bits in the data
    const stripLastChar = (data[0] & 0x80) != 0; // Check the first bit of the first byte
    const bitMask = 0b11111; // 5 bits for the mask
    let bitIndex = 1; // Start from the second bit
    while (bitIndex + 5 <= totalBits && !(stripLastChar && (bitIndex + 2 * 5 > totalBits))) {
      const byteIndex = Math.floor(bitIndex / 8);
      const intraByteIndex = bitIndex % 8;
      // Extract the 5-bit character value across byte boundaries if needed
      let charValue;
      if (intraByteIndex > 3) {
        charValue
            = ((data[byteIndex] & 0xFF) << 8)
            | (byteIndex + 1 < data.length ? (data[byteIndex + 1] & 0xFF) : 0);
        charValue = ((charValue >> (11 - intraByteIndex)) & bitMask);
      } else {
        charValue = data[byteIndex] >> (3 - intraByteIndex) & bitMask;
      }
      bitIndex += 5;
      decoded.push(this.decodeLowerSpecialChar(charValue));
    }
    return decoded.join("");
  }

  private decodeLowerUpperDigitSpecial(data: Uint8Array) {
    const decoded = [];
    let bitIndex = 1;
    const stripLastChar = (data[0] & 0x80) != 0; // Check the first bit of the first byte
    const bitMask = 0b111111; // 6 bits for mask
    const numBits = data.length * 8;
    while (bitIndex + 6 <= numBits && !(stripLastChar && (bitIndex + 2 * 6 > numBits))) {
      const byteIndex = Math.floor(bitIndex / 8);
      const intraByteIndex = bitIndex % 8;

      // Extract the 6-bit character value across byte boundaries if needed
      let charValue;
      if (intraByteIndex > 2) {
        charValue
            = ((data[byteIndex] & 0xFF) << 8)
            | (byteIndex + 1 < data.length ? (data[byteIndex + 1] & 0xFF) : 0);
        charValue = (((charValue >> (10 - intraByteIndex)) & bitMask));
      } else {
        charValue = data[byteIndex] >> (2 - intraByteIndex) & bitMask;
      }
      bitIndex += 6;
      decoded.push(this.decodeLowerUpperDigitSpecialChar(charValue));
    }
    return decoded.join("");
  }

  /** Decoding special char for LOWER_SPECIAL based on encoding mapping. */
  private decodeLowerSpecialChar(charValue: number) {
    if (charValue >= 0 && charValue <= 25) {
      return String.fromCharCode("a".charCodeAt(0) + charValue);
    } else if (charValue === 26) {
      return ".";
    } else if (charValue === 27) {
      return "_";
    } else if (charValue === 28) {
      return "$";
    } else if (charValue === 29) {
      return "|";
    } else {
      throw new Error("Invalid character value for LOWER_SPECIAL: " + charValue);
    }
  }

  /** Decoding special char for LOWER_UPPER_DIGIT_SPECIAL based on encoding mapping. */
  private decodeLowerUpperDigitSpecialChar(charValue: number) {
    if (charValue >= 0 && charValue <= 25) {
      return String.fromCharCode("a".charCodeAt(0) + charValue);
    } else if (charValue >= 26 && charValue <= 51) {
      return String.fromCharCode("A".charCodeAt(0) + (charValue - 26));
    } else if (charValue >= 52 && charValue <= 61) {
      return String.fromCharCode("0".charCodeAt(0) + (charValue - 52));
    } else if (charValue === 62) {
      return this.specialChar1;
    } else if (charValue === 63) {
      return this.specialChar2;
    } else {
      throw new Error(
        "Invalid character value for LOWER_UPPER_DIGIT_SPECIAL: " + charValue);
    }
  }

  capitalize(str: string) {
    if (typeof str !== "string" || str.length === 0) {
      return str; // 如果不是字符串或为空，直接返回原值
    }
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  private decodeRepFirstLowerSpecial(data: Uint8Array) {
    const str = this.decodeLowerSpecial(data);
    return this.capitalize(str);
  }

  private decodeRepAllToLowerSpecial(data: Uint8Array) {
    const str = this.decodeLowerSpecial(data);
    const builder = [];
    const chars = [...str];
    for (let i = 0; i < chars.length; i++) {
      if (chars[i] === "|") {
        const c = chars[++i];
        builder.push(c.toUpperCase());
      } else {
        builder.push(chars[i]);
      }
    }
    return builder.toString();
  }
}

class StringStatistics {
  public constructor(
    public digitCount: number,
    public upperCount: number,
    public canLowerSpecialEncoded: boolean,
    public canLowerUpperDigitSpecialEncoded: boolean
  ) {

  }
}

/** Encodes plain text strings into MetaString objects with specified encoding mechanisms. */
export class MetaStringEncoder {
  private specialChar1: string;
  private specialChar2: string;

  /**
   * Creates a MetaStringEncoder with specified special characters used for encoding.
   *
   * @param specialChar1 The first special character used in custom encoding.
   * @param specialChar2 The second special character used in custom encoding.
   */
  public constructor(specialChar1: string, specialChar2: string) {
    this.specialChar1 = specialChar1;
    this.specialChar2 = specialChar2;
  }

  /**
   * Encodes the input string to MetaString using adaptive encoding, which intelligently chooses the
   * best encoding based on the string's content.
   *
   * @param input The string to encode.
   * @return A MetaString object representing the encoded string.
   */
  public encode(input: string): MetaString {
    return this.encodeByEncodings(input, [Encoding.ALL_TO_LOWER_SPECIAL, Encoding.FIRST_TO_LOWER_SPECIAL, Encoding.LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL, Encoding.UTF_8]);
  }

  public isLatin1(str: string) {
    return fromString(str).byteLength === str.length;
  }

  public encodeByEncodings(input: string, encodings: Encoding[]) {
    if (!input) {
      return new MetaString(input, Encoding.UTF_8, this.specialChar1, this.specialChar2, new Uint8Array());
    }
    if (!this.isLatin1(input)) {
      return new MetaString(
        input,
        Encoding.UTF_8,
        this.specialChar1,
        this.specialChar2,
        new TextEncoder().encode(input));
    }
    const encoding = this.computeEncodingByEncodings(input, encodings);
    return this.encodeByEncoding(input, encoding);
  }

  /**
   * Encodes the input string to MetaString using specified encoding.
   *
   * @param input The string to encode.
   * @param encoding The encoding to use.
   * @return A MetaString object representing the encoded string.
   */
  public encodeByEncoding(input: string, encoding: Encoding) {
    if (encoding != Encoding.UTF_8 && !this.isLatin1(input)) {
      throw new Error("Non-ASCII characters in meta string are not allowed");
    }
    if (!input) {
      return new MetaString(input, Encoding.UTF_8, this.specialChar1, this.specialChar2, new Uint8Array());
    }
    let bytes: Uint8Array;
    switch (encoding) {
      case Encoding.LOWER_SPECIAL:
        bytes = this.encodeLowerSpecial(input);
        return new MetaString(input, encoding, this.specialChar1, this.specialChar2, bytes);
      case Encoding.LOWER_UPPER_DIGIT_SPECIAL:
        bytes = this.encodeLowerUpperDigitSpecial(input);
        return new MetaString(input, encoding, this.specialChar1, this.specialChar2, bytes);
      case Encoding.FIRST_TO_LOWER_SPECIAL:
        bytes = this.encodeFirstToLowerSpecial([...input]);
        return new MetaString(input, encoding, this.specialChar1, this.specialChar2, bytes);
      case Encoding.ALL_TO_LOWER_SPECIAL:
      {
        const chars = [...input];
        const upperCount = this.countUppers(chars);
        bytes = this.encodeAllToLowerSpecial(chars, upperCount);
        return new MetaString(input, encoding, this.specialChar1, this.specialChar2, bytes);
      }
      default:
        bytes = new TextEncoder().encode(input);
        return new MetaString(input, Encoding.UTF_8, this.specialChar1, this.specialChar2, bytes);
    }
  }

  public computeEncoding(input: string) {
    return this.computeEncodingByEncodings(input, [Encoding.ALL_TO_LOWER_SPECIAL, Encoding.FIRST_TO_LOWER_SPECIAL, Encoding.LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL, Encoding.UTF_8]);
  }

  public computeEncodingByEncodings(input: string, encodings: Encoding[]) {
    const encodingSet = new Set(encodings);
    if (!input) {
      if (encodingSet.has(Encoding.LOWER_SPECIAL)) {
        return Encoding.LOWER_SPECIAL;
      }
    }
    const chars = [...input];
    const statistics = this.computeStatistics(chars);
    if (statistics.canLowerSpecialEncoded) {
      if (encodingSet.has(Encoding.LOWER_SPECIAL)) {
        return Encoding.LOWER_SPECIAL;
      }
    }
    if (statistics.canLowerUpperDigitSpecialEncoded) {
      if (statistics.digitCount != 0) {
        if (encodingSet.has(Encoding.LOWER_UPPER_DIGIT_SPECIAL)) {
          return Encoding.LOWER_UPPER_DIGIT_SPECIAL;
        }
      }
      const upperCount = statistics.upperCount;
      if (upperCount === 1 && this.isUpperCase(chars[0])) {
        if (encodingSet.has(Encoding.FIRST_TO_LOWER_SPECIAL)) {
          return Encoding.FIRST_TO_LOWER_SPECIAL;
        }
      }
      if ((chars.length + upperCount) * 5 < (chars.length * 6)) {
        if (encodingSet.has(Encoding.ALL_TO_LOWER_SPECIAL)) {
          return Encoding.ALL_TO_LOWER_SPECIAL;
        }
      }
      if (encodingSet.has(Encoding.LOWER_UPPER_DIGIT_SPECIAL)) {
        return Encoding.LOWER_UPPER_DIGIT_SPECIAL;
      }
    }
    return Encoding.UTF_8;
  }

  isUpperCase(str: string) {
    // 检查字符串是否为空
    if (typeof str !== "string" || str.length === 0) {
      return false; // 如果不是字符串或为空，返回 false
    }

    // 使用正则表达式检查是否所有字母字符都是大写的
    return /^[^a-z]*$/.test(str);
  }

  isDigit(str: string) {
    // 检查字符串是否为空
    if (typeof str !== "string" || str.length === 0) {
      return false; // 如果不是字符串或为空，返回 false
    }

    // 使用正则表达式检查是否所有字母字符都是大写的
    return /^[^0-9]*$/.test(str);
  }

  private computeStatistics(chars: string[]) {
    let canLowerUpperDigitSpecialEncoded = true;
    let canLowerSpecialEncoded = true;
    let digitCount = 0;
    let upperCount = 0;
    for (const c of chars) {
      if (canLowerUpperDigitSpecialEncoded) {
        if (!((c >= "a" && c <= "z")
          || (c >= "A" && c <= "Z")
          || (c >= "0" && c <= "9")
          || (c === this.specialChar1 || c === this.specialChar2))) {
          // Character outside of LOWER_UPPER_DIGIT_SPECIAL set
          canLowerUpperDigitSpecialEncoded = false;
        }
      }
      if (canLowerSpecialEncoded) {
        if (!((c >= "a" && c <= "z") || (c === "." || c === "_" || c === "$" || c === "|"))) {
          // Character outside of LOWER_SPECIAL set
          canLowerSpecialEncoded = false;
        }
      }
      if (this.isDigit(c)) {
        digitCount++;
      }
      if (this.isUpperCase(c)) {
        upperCount++;
      }
    }
    return new StringStatistics(
      digitCount, upperCount, canLowerSpecialEncoded, canLowerUpperDigitSpecialEncoded);
  }

  private countUppers(chars: string[]) {
    let upperCount = 0;
    for (const c of chars) {
      if (this.isUpperCase(c)) {
        upperCount++;
      }
    }
    return upperCount;
  }

  public encodeLowerSpecial(input: string) {
    return this.encodeGeneric([...input], 5);
  }

  public encodeLowerUpperDigitSpecial(input: string) {
    return this.encodeGeneric([...input], 6);
  }

  public encodeFirstToLowerSpecial(chars: string[]) {
    chars[0] = chars[0].toLowerCase();
    return this.encodeGeneric(chars, 5);
  }

  public encodeAllToLowerSpecial(chars: string[], upperCount: number) {
    const newChars = new Array(chars.length + upperCount).fill(0);
    let newIdx = 0;
    for (const c of chars) {
      if (this.isUpperCase(c)) {
        newChars[newIdx++] = "|";
        newChars[newIdx++] = c.toLowerCase();
      } else {
        newChars[newIdx++] = c;
      }
    }
    return this.encodeGeneric(newChars, 5);
  }

  private encodeGeneric(chars: string[], bitsPerChar: number) {
    const totalBits = chars.length * bitsPerChar + 1;
    const byteLength = Math.floor((totalBits + 7) / 8); // Calculate number of needed bytes
    const bytes = new Uint8Array(byteLength).fill(0);
    let currentBit = 1;
    for (const c of chars) {
      const value
          = (bitsPerChar === 5) ? this.charToValueLowerSpecial(c) : this.charToValueLowerUpperDigitSpecial(c);
      // Encode the value in bitsPerChar bits
      for (let i = bitsPerChar - 1; i >= 0; i--) {
        if ((value & (1 << i)) != 0) {
          // Set the bit in the byte array
          const bytePos = Math.floor(currentBit / 8);
          const bitPos = currentBit % 8;
          bytes[bytePos] |= (1 << (7 - bitPos));
        }
        currentBit++;
      }
    }
    const stripLastChar = bytes.length * 8 >= totalBits + bitsPerChar;
    if (stripLastChar) {
      bytes[0] = (bytes[0] | 0x80);
    }
    return bytes;
  }

  private charToValueLowerSpecial(v: string) {
    const c = v.charCodeAt(0);
    if (c >= "a".charCodeAt(0) && c <= "z".charCodeAt(0)) {
      return c - "a".charCodeAt(0);
    } else if (c === ".".charCodeAt(0)) {
      return 26;
    } else if (c === "_".charCodeAt(0)) {
      return 27;
    } else if (c === "$".charCodeAt(0)) {
      return 28;
    } else if (c === "|".charCodeAt(0)) {
      return 29;
    } else {
      throw new Error("Unsupported character for LOWER_SPECIAL encoding: " + c);
    }
  }

  private charToValueLowerUpperDigitSpecial(v: string) {
    const c = v.charCodeAt(0);
    if (c >= "a".charCodeAt(0) && c <= "z".charCodeAt(0)) {
      return c - "a".charCodeAt(0);
    } else if (c >= "A".charCodeAt(0) && c <= "Z".charCodeAt(0)) {
      return 26 + (c - "A".charCodeAt(0));
    } else if (c >= "0".charCodeAt(0) && c <= "9".charCodeAt(0)) {
      return 52 + (c - "0".charCodeAt(0));
    } else if (c === this.specialChar1.charCodeAt(0)) {
      return 62;
    } else if (c === this.specialChar2.charCodeAt(0)) {
      return 63;
    } else {
      throw new Error(
        "Unsupported character for LOWER_UPPER_DIGIT_SPECIAL encoding: " + c);
    }
  }
}
