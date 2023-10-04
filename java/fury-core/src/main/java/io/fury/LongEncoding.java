/*
 * Copyright 2023 The Fury Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury;

/**
 * Encoding option for long.
 *
 * @author chaokunyang
 */
public enum LongEncoding {
  /**
   * Write long as little endian 8bytes, no compression.
   */
  LE_RAW_BYTES,
  /**
   * Fury SLI(Small long as int) Encoding:
   * <li>If long is in [0xc0000000, 0x3fffffff], encode as 4 bytes int: `| little-endian: ((int) value) << 1 |`</li>
   * <li>Otherwise write as 9 bytes: `| 0b1 | little-endian 8bytes long |`</li>.
   * <p>Faster than {@link #PVL}, but compression is not good as {@link #PVL} such as for ints in short range.
   */
  SLI,
  /**
   * Fury Progressive Variable-length Long Encoding:
   * <li>positive long format: first bit in every byte indicate whether has next byte,
   * then next byte should be read util first bit is unset.</li>
   * <li>Negative number will be converted to positive number by ` (v << 1) ^ (v >> 63)` to reduce cost
   * of small negative numbers.
   * </li>
   */
  PVL,
}
