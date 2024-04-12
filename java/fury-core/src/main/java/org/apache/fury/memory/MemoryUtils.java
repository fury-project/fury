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

package org.apache.fury.memory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.apache.fury.util.Platform;
import org.apache.fury.util.Preconditions;

/** Memory utils for fury. */
public class MemoryUtils {

  public static MemoryBuffer buffer(int size) {
    return wrap(new byte[size]);
  }

  public static MemoryBuffer buffer(long address, int size) {
    return MemoryBuffer.fromNativeAddress(address, size);
  }

  /**
   * Creates a new memory segment that targets to the given heap memory region.
   *
   * <p>This method should be used to turn short lived byte arrays into memory segments.
   *
   * @param buffer The heap memory region.
   * @return A new memory segment that targets the given heap memory region.
   */
  public static MemoryBuffer wrap(byte[] buffer, int offset, int length) {
    return MemoryBuffer.fromByteArray(buffer, offset, length);
  }

  public static MemoryBuffer wrap(byte[] buffer) {
    return MemoryBuffer.fromByteArray(buffer);
  }

  /**
   * Creates a new memory segment that represents the memory backing the given byte buffer section
   * of [buffer.position(), buffer,limit()).
   *
   * @param buffer a direct buffer or heap buffer
   */
  public static MemoryBuffer wrap(ByteBuffer buffer) {
    if (buffer.isDirect()) {
      return MemoryBuffer.fromByteBuffer(buffer);
    } else {
      int offset = buffer.arrayOffset() + buffer.position();
      return MemoryBuffer.fromByteArray(buffer.array(), offset, buffer.remaining());
    }
  }

  /** Get short in big endian order from provided buffer. */
  public static short getShortB(byte[] b, int off) {
    return (short) ((b[off + 1] & 0xFF) + (b[off] << 8));
  }

  // Lazy load offset and also follow graalvm offset auto replace pattern.
  private static class Offset {
    private static final long BAS_BUF_BUF;
    private static final long BAS_BUF_COUNT;
    private static final long BIS_BUF_BUF;
    private static final long BIS_BUF_POS;
    private static final long BIS_BUF_COUNT;

    static {
      try {
        BAS_BUF_BUF =
            Platform.objectFieldOffset(ByteArrayOutputStream.class.getDeclaredField("buf"));
        BAS_BUF_COUNT =
            Platform.objectFieldOffset(ByteArrayOutputStream.class.getDeclaredField("count"));
        BIS_BUF_BUF =
            Platform.objectFieldOffset(ByteArrayInputStream.class.getDeclaredField("buf"));
        BIS_BUF_POS =
            Platform.objectFieldOffset(ByteArrayInputStream.class.getDeclaredField("pos"));
        BIS_BUF_COUNT =
            Platform.objectFieldOffset(ByteArrayInputStream.class.getDeclaredField("count"));
      } catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Wrap a {@link ByteArrayOutputStream} into a {@link MemoryBuffer}. The writerIndex of buffer
   * will be the count of stream.
   */
  public static void wrap(ByteArrayOutputStream stream, MemoryBuffer buffer) {
    Preconditions.checkNotNull(stream);
    byte[] buf = (byte[]) Platform.getObject(stream, Offset.BAS_BUF_BUF);
    int count = Platform.getInt(stream, Offset.BAS_BUF_COUNT);
    buffer.pointTo(buf, 0, buf.length);
    buffer.writerIndex(count);
  }

  /**
   * Wrap a @link MemoryBuffer} into a {@link ByteArrayOutputStream}. The count of stream will be
   * the writerIndex of buffer.
   */
  public static void wrap(MemoryBuffer buffer, ByteArrayOutputStream stream) {
    Preconditions.checkNotNull(stream);
    byte[] bytes = buffer.getHeapMemory();
    Preconditions.checkNotNull(bytes);
    Platform.putObject(stream, Offset.BAS_BUF_BUF, bytes);
    Platform.putInt(stream, Offset.BAS_BUF_COUNT, buffer.writerIndex());
  }

  /**
   * Wrap a {@link ByteArrayInputStream} into a {@link MemoryBuffer}. The readerIndex of buffer will
   * be the pos of stream.
   */
  public static void wrap(ByteArrayInputStream stream, MemoryBuffer buffer) {
    Preconditions.checkNotNull(stream);
    byte[] buf = (byte[]) Platform.getObject(stream, Offset.BIS_BUF_BUF);
    int count = Platform.getInt(stream, Offset.BIS_BUF_COUNT);
    int pos = Platform.getInt(stream, Offset.BIS_BUF_POS);
    buffer.pointTo(buf, 0, count);
    buffer.readerIndex(pos);
  }

  public static int putVarUint36Small(byte[] arr, int index, long v) {
    if (v >>> 7 == 0) {
      arr[index] = (byte) v;
      return 1;
    }
    if (v >>> 14 == 0) {
      arr[index++] = (byte) ((v & 0x7F) | 0x80);
      arr[index] = (byte) (v >>> 7);
      return 2;
    }
    return bigWriteUint36(arr, index, v);
  }

  private static int bigWriteUint36(byte[] arr, int index, long v) {
    if (v >>> 21 == 0) {
      arr[index++] = (byte) ((v & 0x7F) | 0x80);
      arr[index++] = (byte) (v >>> 7 | 0x80);
      arr[index] = (byte) (v >>> 14);
      return 3;
    }
    if (v >>> 28 == 0) {
      arr[index++] = (byte) ((v & 0x7F) | 0x80);
      arr[index++] = (byte) (v >>> 7 | 0x80);
      arr[index++] = (byte) (v >>> 14 | 0x80);
      arr[index] = (byte) (v >>> 21);
      return 4;
    }
    arr[index++] = (byte) ((v & 0x7F) | 0x80);
    arr[index++] = (byte) (v >>> 7 | 0x80);
    arr[index++] = (byte) (v >>> 14 | 0x80);
    arr[index++] = (byte) (v >>> 21 | 0x80);
    arr[index] = (byte) (v >>> 28);
    return 5;
  }

  public static void putInt(Object o, long pos, int value) {
    if (!Platform.IS_LITTLE_ENDIAN) {
      value = Integer.reverseBytes(value);
    }
    Platform.putInt(o, pos, value);
  }

  public static int getInt(Object o, long pos) {
    int i = Platform.getInt(o, pos);
    return Platform.IS_LITTLE_ENDIAN ? i : Integer.reverseBytes(i);
  }

  public static long getLong(Object o, long pos) {
    long v = Platform.getLong(o, pos);
    return Platform.IS_LITTLE_ENDIAN ? v : Long.reverseBytes(v);
  }

  public static void putFloat(Object o, long pos, float value) {
    int v = Float.floatToRawIntBits(value);
    if (!Platform.IS_LITTLE_ENDIAN) {
      v =  Integer.reverseBytes(v);
    }
    Platform.putInt(o, pos, v);
  }

  public static void putDouble(Object o, long pos, double value) {
    long v = Double.doubleToRawLongBits(value);
    if (!Platform.IS_LITTLE_ENDIAN) {
      v = Long.reverseBytes(v);
    }
    Platform.putLong(o, pos, v);
  }
}
