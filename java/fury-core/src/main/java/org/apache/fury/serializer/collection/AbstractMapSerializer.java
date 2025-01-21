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

package org.apache.fury.serializer.collection;

import static org.apache.fury.type.TypeUtils.MAP_TYPE;

import com.google.common.collect.ImmutableMap.Builder;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.fury.Fury;
import org.apache.fury.collection.IdentityMap;
import org.apache.fury.collection.Tuple2;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.reflect.ReflectionUtils;
import org.apache.fury.reflect.TypeRef;
import org.apache.fury.resolver.ClassInfo;
import org.apache.fury.resolver.ClassInfoHolder;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.resolver.RefResolver;
import org.apache.fury.serializer.Serializer;
import org.apache.fury.type.GenericType;
import org.apache.fury.type.Generics;
import org.apache.fury.type.TypeUtils;
import org.apache.fury.util.Preconditions;

/** Serializer for all map-like objects. */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class AbstractMapSerializer<T> extends Serializer<T> {
  private static final int MAX_CHUNK_SIZE = 127;
  protected MethodHandle constructor;
  protected final boolean supportCodegenHook;
  private Serializer keySerializer;
  private Serializer valueSerializer;
  protected final ClassInfoHolder keyClassInfoWriteCache;
  protected final ClassInfoHolder keyClassInfoReadCache;
  protected final ClassInfoHolder valueClassInfoWriteCache;
  protected final ClassInfoHolder valueClassInfoReadCache;
  // support map subclass whose key or value generics only are available,
  // or one of types is already instantiated in subclass, ex: `Subclass<T> implements Map<String,
  // T>`
  private final IdentityMap<GenericType, Tuple2<GenericType, GenericType>> partialGenericKVTypeMap;
  private final GenericType objType = fury.getClassResolver().buildGenericType(Object.class);
  // For subclass whose kv type are instantiated already, such as
  // `Subclass implements Map<String, Long>`. If declared `Map` doesn't specify
  // instantiated kv type, then the serialization will need to write those kv
  // types. Although we can extract this generics when creating the serializer,
  // we can't do it when jit `Serializer` for some class which contains one of such map
  // field. So we will write those extra kv classes to keep protocol consistency between
  // interpreter and jit mode although it seems unnecessary.
  // With kv header in future, we can write this kv classes only once, the cost won't be too much.
  private int numElements;

  public AbstractMapSerializer(Fury fury, Class<T> cls) {
    this(fury, cls, !ReflectionUtils.isDynamicGeneratedCLass(cls));
  }

  public AbstractMapSerializer(Fury fury, Class<T> cls, boolean supportCodegenHook) {
    super(fury, cls);
    this.supportCodegenHook = supportCodegenHook;
    keyClassInfoWriteCache = fury.getClassResolver().nilClassInfoHolder();
    keyClassInfoReadCache = fury.getClassResolver().nilClassInfoHolder();
    valueClassInfoWriteCache = fury.getClassResolver().nilClassInfoHolder();
    valueClassInfoReadCache = fury.getClassResolver().nilClassInfoHolder();
    partialGenericKVTypeMap = new IdentityMap<>();
  }

  public AbstractMapSerializer(
      Fury fury, Class<T> cls, boolean supportCodegenHook, boolean immutable) {
    super(fury, cls, immutable);
    this.supportCodegenHook = supportCodegenHook;
    keyClassInfoWriteCache = fury.getClassResolver().nilClassInfoHolder();
    keyClassInfoReadCache = fury.getClassResolver().nilClassInfoHolder();
    valueClassInfoWriteCache = fury.getClassResolver().nilClassInfoHolder();
    valueClassInfoReadCache = fury.getClassResolver().nilClassInfoHolder();
    partialGenericKVTypeMap = new IdentityMap<>();
  }

  /**
   * Set key serializer for next serialization, the <code>serializer</code> will be cleared when
   * next serialization finished.
   */
  public void setKeySerializer(Serializer keySerializer) {
    this.keySerializer = keySerializer;
  }

  /**
   * Set value serializer for next serialization, the <code>serializer</code> will be cleared when
   * next serialization finished.
   */
  public void setValueSerializer(Serializer valueSerializer) {
    this.valueSerializer = valueSerializer;
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    Map map = onMapWrite(buffer, value);
    if (fury.isChunkSerializeMapEnabled()) {
      chunkWriteElements(fury, buffer, map);
    } else {
      writeElements(fury, buffer, map);
    }
  }

  @Override
  public void xwrite(MemoryBuffer buffer, T value) {
    Map map = onMapWrite(buffer, value);
    xwriteElements(fury, buffer, map);
  }

  protected final void writeElements(Fury fury, MemoryBuffer buffer, Map map) {
    Serializer keySerializer = this.keySerializer;
    Serializer valueSerializer = this.valueSerializer;
    // clear the elemSerializer to avoid conflict if the nested
    // serialization has collection field.
    // TODO use generics for compatible serializer.
    this.keySerializer = null;
    this.valueSerializer = null;
    if (keySerializer != null && valueSerializer != null) {
      javaWriteWithKVSerializers(fury, buffer, map, keySerializer, valueSerializer);
    } else if (keySerializer != null) {
      ClassResolver classResolver = fury.getClassResolver();
      RefResolver refResolver = fury.getRefResolver();
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        fury.writeRef(buffer, entry.getKey(), keySerializer);
        Object value = entry.getValue();
        writeJavaRefOptimized(
            fury, classResolver, refResolver, buffer, value, valueClassInfoWriteCache);
      }
    } else if (valueSerializer != null) {
      ClassResolver classResolver = fury.getClassResolver();
      RefResolver refResolver = fury.getRefResolver();
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        Object key = entry.getKey();
        writeJavaRefOptimized(
            fury, classResolver, refResolver, buffer, key, keyClassInfoWriteCache);
        fury.writeRef(buffer, entry.getValue(), valueSerializer);
      }
    } else {
      genericJavaWrite(fury, buffer, map);
    }
  }

  protected final void chunkWriteElements(Fury fury, MemoryBuffer buffer, Map map) {
    Serializer keySerializer = this.keySerializer;
    Serializer valueSerializer = this.valueSerializer;
    // clear the elemSerializer to avoid conflict if the nested
    // serialization has collection field.
    // TODO use generics for compatible serializer.
    this.keySerializer = null;
    this.valueSerializer = null;
    if (keySerializer != null && valueSerializer != null) {
      javaChunkWriteWithKVSerializers(buffer, map, keySerializer, valueSerializer);
    } else if (keySerializer != null) {
      javaChunkWriteWithKeySerializers(map, buffer, keySerializer);
    } else if (valueSerializer != null) {
      javaChunkWriteWithValueSerializers(map, buffer, valueSerializer);
    } else {
      genericJavaChunkWrite(fury, buffer, map);
    }
  }

  private void javaChunkWriteWithKeySerializers(
      Map map, MemoryBuffer buffer, Serializer keySerializer) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    boolean prevKeyIsNull = false;
    int header = 0;
    int chunkSize = 0;
    int startOffset = 0;
    boolean hasPreservedByte = false;
    boolean valueIsNotSameType = false;
    Class valueClass = null;
    Serializer valueWriteSerializer = null;
    boolean writeValueClassInfo = false;
    boolean needReset = false;
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      final Object value = entry.getValue();
      if (key == null) {
        prevKeyIsNull = true;
      }
      if (!valueIsNotSameType) {
        if (value != null) {
          if (valueClass == null) {
            valueClass = value.getClass();
          }
          valueIsNotSameType = valueClass != value.getClass();
          if (valueIsNotSameType) {
            needReset = true;
          }
        }
      }
      if ((key == null && chunkSize > 0)
          || (prevKeyIsNull && key != null)
          || (value == null && chunkSize > 0 && !valueHasNull(header))
          || needReset
          || (chunkSize >= MAX_CHUNK_SIZE)) {
        writeHeader(buffer, chunkSize, header, startOffset);
        prevKeyIsNull = false;
        header = 0;
        chunkSize = 0;
        startOffset = 0;
        hasPreservedByte = false;
        valueClass = value == null ? null : value.getClass();
        valueWriteSerializer = null;
        writeValueClassInfo = false;
        needReset = false;
      }
      if (!hasPreservedByte) {
        int writerIndex = buffer.writerIndex();
        // preserve two byte for header and chunk size
        buffer.writerIndex(writerIndex + 2);
        startOffset = writerIndex;
        hasPreservedByte = true;
      }
      // write final key
      boolean trackingKeyRef = keySerializer.needToWriteRef();
      boolean trackingValueRef = fury.trackingRef();
      header =
          updateKVHeader(
              key, trackingKeyRef, value, trackingValueRef, header, false, valueIsNotSameType);
      writeFinalKey(key, buffer, keySerializer, trackingKeyRef);
        if (!trackingValueRef) {
            if (value == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!valueIsNotSameType) {
                    if (valueHasNull(header)) {
                        buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
                    }
                    ClassInfo classInfo = classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
                    if (!writeValueClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeValueClassInfo = true;
                    }
                    if (valueWriteSerializer == null) {
                        valueWriteSerializer = classInfo.getSerializer();
                    }
                    valueWriteSerializer.write(buffer, value);
                } else {
                    fury.writeNullable(
                            buffer,
                            value,
                            classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
                }
            }
        } else {
            if (value == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!valueIsNotSameType) {
                    ClassInfo classInfo = classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
                    if (!writeValueClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeValueClassInfo = true;
                    }
                    if (valueWriteSerializer == null) {
                        valueWriteSerializer = classInfo.getSerializer();
                    }
                    valueWriteSerializer.write(buffer, value);
                    if (!valueHasNull(header)) {
                        writeNoNullRef(valueWriteSerializer, value, buffer, refResolver);
                    } else {
                        fury.writeRef(buffer, value, valueWriteSerializer);
                    }
                } else {
                    if (!refResolver.writeNullFlag(buffer, value)) {
                        fury.writeRef(
                                buffer,
                                value,
                                classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
                    }
                }
            }
        }
      chunkSize++;
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private void javaChunkWriteWithValueSerializers(
      Map map, MemoryBuffer buffer, Serializer valueSerializer) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    boolean prevKeyIsNull = false;
    int header = 0;
    int chunkSize = 0;
    int startOffset = 0;
    boolean hasPreservedByte = false;
    boolean keyIsNotSameType = false;
    Class keyClass = null;
    Serializer keyWriteSerializer = null;
    boolean writeKeyClassInfo = false;
    boolean needReset = false;
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      final Object value = entry.getValue();
      if (key == null) {
        prevKeyIsNull = true;
      }
      if (!keyIsNotSameType) {
        if (key != null) {
          if (keyClass == null) {
            keyClass = key.getClass();
          }
          keyIsNotSameType = keyClass != key.getClass();
          if (keyIsNotSameType) {
            needReset = true;
          }
        }
      }
      if ((key == null && chunkSize > 0)
          || (prevKeyIsNull && key != null)
          || (value == null && chunkSize > 0 && !valueHasNull(header))
          || needReset
          || (chunkSize >= MAX_CHUNK_SIZE)) {
        writeHeader(buffer, chunkSize, header, startOffset);
        prevKeyIsNull = false;
        header = 0;
        chunkSize = 0;
        startOffset = 0;
        hasPreservedByte = false;
        keyClass = key == null ? null : key.getClass();
        keyWriteSerializer = null;
        writeKeyClassInfo = false;
      }
      if (!hasPreservedByte) {
        int writerIndex = buffer.writerIndex();
        // preserve two byte for header and chunk size
        buffer.writerIndex(writerIndex + 2);
        startOffset = writerIndex;
        hasPreservedByte = true;
      }
      boolean trackingKeyRef = fury.trackingRef();
      boolean trackingValueRef = valueSerializer.needToWriteRef();
      header =
          updateKVHeader(
              key, trackingKeyRef, value, trackingValueRef, header, keyIsNotSameType, false);
        if (!trackingKeyRef) {
            if (key == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!keyIsNotSameType) {
                    ClassInfo classInfo = classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
                    if (!writeKeyClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeKeyClassInfo = true;
                    }
                    if (keyWriteSerializer == null) {
                        keyWriteSerializer = classInfo.getSerializer();
                    }
                    keyWriteSerializer.write(buffer, key);
                } else {
                    fury.writeNonRef(
                            buffer, key, classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
                }
            }
        } else {
            if (key == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!keyIsNotSameType) {
                    ClassInfo classInfo = classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
                    if (!writeKeyClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeKeyClassInfo = true;
                    }
                    if (keyWriteSerializer == null) {
                        keyWriteSerializer = classInfo.getSerializer();
                    }
                    writeNoNullRef(keyWriteSerializer, key, buffer, refResolver);
                } else {
                    if (!refResolver.writeNullFlag(buffer, key)) {
                        fury.writeRef(
                                buffer, key, classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
                    }
                }
            }
        }
      writeFinalValue(value, buffer, valueSerializer, trackingValueRef, header);
      chunkSize++;
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private Serializer getSerializer(Object o, ClassInfoHolder classInfoWriteCache) {
    if (o != null) {
      ClassResolver classResolver = fury.getClassResolver();
      ClassInfo classInfo = classResolver.getClassInfo(o.getClass(), classInfoWriteCache);
      return classInfo.getSerializer();
    }
    return null;
  }

  private void javaChunkWriteWithKVSerializers(
      MemoryBuffer buffer, Map map, Serializer keySerializer, Serializer valueSerializer) {
    boolean prevKeyIsNull = false;
    int header = 0;
    int chunkSize = 0;
    int startOffset = 0;
    boolean hasPreservedByte = false;
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key == null) {
        prevKeyIsNull = true;
      }
      // situations which require reset
      // 1. key is null and not the first element of the chunk
      // 2. prevKeyIsNull is null, we use one chunk to save data which key is null
      // 3. value is null and the value is first null of values in this chunk
      // 4. chunk size reach max chunk size
      if ((key == null && chunkSize > 0)
          || (prevKeyIsNull && key != null)
          || (value == null && chunkSize > 0 && !valueHasNull(header))
          || (chunkSize >= MAX_CHUNK_SIZE)) {
        writeHeader(buffer, chunkSize, header, startOffset);
        header = 0;
        chunkSize = 0;
        hasPreservedByte = false;
        prevKeyIsNull = false;
      }
      if (!hasPreservedByte) {
        int writerIndex = buffer.writerIndex();
        // preserve two byte for header and chunk size
        buffer.writerIndex(writerIndex + 2);
        startOffset = writerIndex;
        hasPreservedByte = true;
      }
      boolean trackingKeyRef = keySerializer.needToWriteRef();
      boolean trackingValueRef = valueSerializer.needToWriteRef();
      header = updateKVHeader(key, trackingKeyRef, value, trackingValueRef, header, false, false);
      writeFinalKey(key, buffer, keySerializer, trackingKeyRef);
      writeFinalValue(value, buffer, valueSerializer, trackingValueRef, header);
      chunkSize++;
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private void writeFinalKey(
      Object key, MemoryBuffer buffer, Serializer keySerializer, boolean trackingKeyRef) {
    if (!trackingKeyRef) {
      // map key has one null at most, use one chunk to write
      if (key == null) {
        buffer.writeByte(Fury.NULL_FLAG);
      } else {
        keySerializer.write(buffer, key);
      }
    } else {
      RefResolver refResolver = fury.getRefResolver();
      if (!refResolver.writeRefOrNull(buffer, key)) {
        keySerializer.write(buffer, key);
      }
    }
  }

  private void writeFinalValue(
      Object value,
      MemoryBuffer buffer,
      Serializer valueSerializer,
      boolean trackingValueRef,
      int header) {
    if (!trackingValueRef) {
      if (value == null) {
        buffer.writeByte(Fury.NULL_FLAG);
      } else {
        if (valueHasNull(header)) {
          buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
          valueSerializer.write(buffer, value);
        } else {
          valueSerializer.write(buffer, value);
        }
      }
    } else {
      RefResolver refResolver = fury.getRefResolver();
      if (!refResolver.writeRefOrNull(buffer, value)) {
        valueSerializer.write(buffer, value);
      }
    }
  }

  private int updateKVHeader(
      Object key,
      boolean trackingKeyRef,
      Object value,
      boolean trackingValueRef,
      int header,
      boolean keyIsNotSameType,
      boolean valueIsNotSameType) {
    if (trackingKeyRef) {
      header |= MapFlags.TRACKING_KEY_REF;
    }
    if (key == null) {
      header |= MapFlags.KEY_HAS_NULL;
    }
    if (trackingValueRef) {
      header |= MapFlags.TRACKING_VALUE_REF;
    }
    if (value == null) {
      header |= MapFlags.VALUE_HAS_NULL;
    }
    if (keyIsNotSameType) {
      header |= MapFlags.KEY_NOT_SAME_TYPE;
    }
    if (valueIsNotSameType) {
      header |= MapFlags.VALUE_NOT_SAME_TYPE;
    }
    return header;
  }

  private void javaWriteWithKVSerializers(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      Serializer keySerializer,
      Serializer valueSerializer) {
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      Object value = entry.getValue();
      fury.writeRef(buffer, key, keySerializer);
      fury.writeRef(buffer, value, valueSerializer);
    }
  }

  private void genericJavaWrite(Fury fury, MemoryBuffer buffer, Map map) {
    Generics generics = fury.getGenerics();
    GenericType genericType = generics.nextGenericType();
    if (genericType == null) {
      generalJavaWrite(fury, buffer, map);
    } else {
      GenericType keyGenericType = genericType.getTypeParameter0();
      GenericType valueGenericType = genericType.getTypeParameter1();
      // type parameters count for `Map field` will be 0;
      // type parameters count for `SubMap<V> field` which SubMap is
      // `SubMap<V> implements Map<String, V>` will be 1;
      if (genericType.getTypeParametersCount() < 2) {
        Tuple2<GenericType, GenericType> kvGenericType = getKVGenericType(genericType);
        if (keyGenericType == objType && valueGenericType == objType) {
          generalJavaWrite(fury, buffer, map);
          return;
        }
        keyGenericType = kvGenericType.f0;
        valueGenericType = kvGenericType.f1;
      }
      // Can't avoid push generics repeatedly in loop by stack depth, because push two
      // generic type changed generics stack top, which is depth index, update stack top
      // and depth will have some cost too.
      // Stack depth to avoid push generics repeatedly in loop.
      // Note push two generic type changed generics stack top, which is depth index,
      // stack top should be updated when using for serialization k/v.
      // int depth = fury.getDepth();
      // // depth + 1 to leave a slot for value generics, otherwise value generics will
      // // be overwritten by nested key generics.
      // fury.setDepth(depth + 1);
      // generics.pushGenericType(keyGenericType);
      // fury.setDepth(depth);
      // generics.pushGenericType(valueGenericType);
      boolean keyGenericTypeFinal = keyGenericType.isMonomorphic();
      boolean valueGenericTypeFinal = valueGenericType.isMonomorphic();
      if (keyGenericTypeFinal && valueGenericTypeFinal) {
        javaKVTypesFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
      } else if (keyGenericTypeFinal) {
        javaKeyTypeFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
      } else if (valueGenericTypeFinal) {
        javaValueTypeFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
      } else {
        javaKVTypesNonFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
      }
    }
  }

  private void genericJavaChunkWrite(Fury fury, MemoryBuffer buffer, Map map) {
    Generics generics = fury.getGenerics();
    GenericType genericType = generics.nextGenericType();
    if (genericType == null) {
      generalJavaChunkWrite(fury, buffer, map);
    } else {
      GenericType keyGenericType = genericType.getTypeParameter0();
      GenericType valueGenericType = genericType.getTypeParameter1();
      // type parameters count for `Map field` will be 0;
      // type parameters count for `SubMap<V> field` which SubMap is
      // `SubMap<V> implements Map<String, V>` will be 1;
      if (genericType.getTypeParametersCount() < 2) {
        Tuple2<GenericType, GenericType> kvGenericType = getKVGenericType(genericType);
        if (keyGenericType == objType && valueGenericType == objType) {
          generalJavaChunkWrite(fury, buffer, map);
          return;
        }
        keyGenericType = kvGenericType.f0;
        valueGenericType = kvGenericType.f1;
      }
      // Can't avoid push generics repeatedly in loop by stack depth, because push two
      // generic type changed generics stack top, which is depth index, update stack top
      // and depth will have some cost too.
      // Stack depth to avoid push generics repeatedly in loop.
      // Note push two generic type changed generics stack top, which is depth index,
      // stack top should be updated when using for serialization k/v.
      // int depth = fury.getDepth();
      // // depth + 1 to leave a slot for value generics, otherwise value generics will
      // // be overwritten by nested key generics.
      // fury.setDepth(depth + 1);
      // generics.pushGenericType(keyGenericType);
      // fury.setDepth(depth);
      // generics.pushGenericType(valueGenericType);
      boolean keyGenericTypeFinal = keyGenericType.isMonomorphic();
      boolean valueGenericTypeFinal = valueGenericType.isMonomorphic();
      if (keyGenericTypeFinal && valueGenericTypeFinal) {
        javaKVTypesFinalChunkWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
      } else if (keyGenericTypeFinal) {
        javaKeyTypeFinalChunkWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
      } else if (valueGenericTypeFinal) {
        javaValueTypeFinalChunkWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
      } else {
        javaKVTypesNonFinalChunkWrite(
            fury, buffer, map, keyGenericType, valueGenericType, generics);
      }
    }
  }

  private void javaKVTypesFinalWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      generics.pushGenericType(keyGenericType);
      fury.writeRef(buffer, entry.getKey(), keySerializer);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      fury.writeRef(buffer, entry.getValue(), valueSerializer);
      generics.popGenericType();
    }
  }

  /**
   * kv final write do not need to predict , since key and value is almost same type unless null
   *
   * @param fury
   * @param buffer
   * @param map
   * @param keyGenericType
   * @param valueGenericType
   * @param generics
   */
  private void javaKVTypesFinalChunkWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    boolean prevKeyIsNull = false;
    int header = 0;
    int chunkSize = 0;
    int startOffset = 0;
    boolean hasPreservedByte = false;
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key == null) {
        prevKeyIsNull = true;
      }
      if ((key == null && chunkSize > 0)
          || (prevKeyIsNull && key != null)
          || (value == null && chunkSize > 0 && !valueHasNull(header))
          || (chunkSize >= MAX_CHUNK_SIZE)) {
        writeHeader(buffer, chunkSize, header, startOffset);
        header = 0;
        chunkSize = 0;
        hasPreservedByte = false;
        prevKeyIsNull = false;
      }
      if (!hasPreservedByte) {
        int writerIndex = buffer.writerIndex();
        // preserve two byte for header and chunk size
        buffer.writerIndex(writerIndex + 2);
        startOffset = writerIndex;
        hasPreservedByte = true;
      }
      boolean trackingKeyRef = keySerializer.needToWriteRef();
      boolean trackingValueRef = valueSerializer.needToWriteRef();
      header = updateKVHeader(key, trackingKeyRef, value, trackingValueRef, header, false, false);
      generics.pushGenericType(keyGenericType);
      writeFinalKey(key, buffer, keySerializer, trackingKeyRef);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      writeFinalValue(value, buffer, valueSerializer, trackingValueRef, header);
      generics.popGenericType();
      chunkSize++;
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private void javaKeyTypeFinalWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      generics.pushGenericType(keyGenericType);
      fury.writeRef(buffer, entry.getKey(), keySerializer);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      writeJavaRefOptimized(
          fury,
          classResolver,
          refResolver,
          trackingValueRef,
          buffer,
          entry.getValue(),
          valueClassInfoWriteCache);
      generics.popGenericType();
    }
  }

  private void javaKeyTypeFinalChunkWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
    boolean prevKeyIsNull = false;
    int header = 0;
    int chunkSize = 0;
    int startOffset = 0;
    boolean hasPreservedByte = false;
    boolean valueIsNotSameType = false;
    Serializer valueWriteSerializer = null;
    boolean writeValueClassInfo = false;
    Class valueClass = null;
    boolean needReset = false;
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key == null) {
        prevKeyIsNull = true;
        if (chunkSize > 0) {
          needReset = true;
        }
      }
      if (!valueIsNotSameType) {
        if (value != null) {
          if (valueClass == null) {
            valueClass = value.getClass();
          }
          valueIsNotSameType = valueClass != value.getClass();
        }
        if (valueIsNotSameType) {
          needReset = true;
        }
      }
      if ((key == null && chunkSize > 0)
          || (prevKeyIsNull && key != null)
          || (value == null && chunkSize > 0 && !valueHasNull(header))
          || needReset
          || (chunkSize >= MAX_CHUNK_SIZE)) {
        writeHeader(buffer, chunkSize, header, startOffset);
        prevKeyIsNull = false;
        header = 0;
        chunkSize = 0;
        startOffset = 0;
        hasPreservedByte = false;
        valueWriteSerializer = null;
        writeValueClassInfo = false;
        valueClass = value == null ? null : value.getClass();
        needReset = false;
      }
      if (!hasPreservedByte) {
        int writerIndex = buffer.writerIndex();
        // preserve two byte for header and chunk size
        buffer.writerIndex(writerIndex + 2);
        startOffset = writerIndex;
        hasPreservedByte = true;
      }
      generics.pushGenericType(keyGenericType);
      boolean trackingKeyRef = keySerializer.needToWriteRef();
      header =
          updateKVHeader(
              key, trackingKeyRef, value, trackingValueRef, header, false, valueIsNotSameType);
      writeFinalKey(key, buffer, keySerializer, trackingKeyRef);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
        if (!trackingValueRef) {
            if (value == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!valueIsNotSameType) {
                    if (valueHasNull(header)) {
                        buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
                    }
                    ClassInfo classInfo = classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
                    if (!writeValueClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeValueClassInfo = true;
                    }
                    if (valueWriteSerializer == null) {
                        valueWriteSerializer = classInfo.getSerializer();
                    }
                    valueWriteSerializer.write(buffer, value);
                } else {
                    fury.writeNullable(
                            buffer,
                            value,
                            classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
                }
            }
        } else {
            if (value == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!valueIsNotSameType) {
                    ClassInfo classInfo = classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
                    if (!writeValueClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeValueClassInfo = true;
                    }
                    if (valueWriteSerializer == null) {
                        valueWriteSerializer = classInfo.getSerializer();
                    }
                    valueWriteSerializer.write(buffer, value);
                    if (!valueHasNull(header)) {
                        writeNoNullRef(valueWriteSerializer, value, buffer, refResolver);
                    } else {
                        fury.writeRef(buffer, value, valueWriteSerializer);
                    }
                } else {
                    if (!refResolver.writeNullFlag(buffer, value)) {
                        fury.writeRef(
                                buffer,
                                value,
                                classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
                    }
                }
            }
        }
      generics.popGenericType();
      chunkSize++;
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private void javaValueTypeFinalChunkWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    int header = 0;
    int chunkSize = 0;
    boolean prevKeyIsNull = false;
    boolean keyIsNotSameType = false;
    int startOffset = 0;
    Class keyClass = null;
    Serializer keyWriteSerializer = null;
    boolean writeKeyClassInfo = false;
    boolean needReset = false;
    boolean hasPreservedByte = false;
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
    boolean trackingValueRef = valueSerializer.needToWriteRef();
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key == null) {
        prevKeyIsNull = true;
        if (chunkSize > 0) {
          needReset = true;
        }
      }
      if (!keyIsNotSameType) {
        if (key != null) {
          if (keyClass == null) {
            keyClass = key.getClass();
          }
          keyIsNotSameType = keyClass != key.getClass();
          if (keyIsNotSameType) {
            needReset = true;
          }
        }
      }
      if ((key == null && chunkSize > 0)
          || (prevKeyIsNull && key != null)
          || (value == null && chunkSize > 0 && !valueHasNull(header))
          || needReset
          || (chunkSize >= MAX_CHUNK_SIZE)) {
        writeHeader(buffer, chunkSize, header, startOffset);
        header = 0;
        chunkSize = 0;
        prevKeyIsNull = false;
        startOffset = 0;
        keyClass = key == null ? null : key.getClass();
        keyWriteSerializer = null;
        writeKeyClassInfo = false;
        needReset = false;
        hasPreservedByte = false;
      }
      header =
          updateKVHeader(
              key, trackingKeyRef, value, trackingValueRef, header, false, keyIsNotSameType);
      if (!hasPreservedByte) {
        int writerIndex = buffer.writerIndex();
        // preserve two byte for header and chunk size
        buffer.writerIndex(writerIndex + 2);
        startOffset = writerIndex;
        hasPreservedByte = true;
      }
      generics.pushGenericType(keyGenericType);
      // todo hening key == null提到外面？
        if (!trackingKeyRef) {
            if (key == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!keyIsNotSameType) {
                    ClassInfo classInfo = classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
                    if (!writeKeyClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeKeyClassInfo = true;
                    }
                    if (keyWriteSerializer == null) {
                        keyWriteSerializer = classInfo.getSerializer();
                    }
                    keyWriteSerializer.write(buffer, key);
                } else {
                    fury.writeNonRef(
                            buffer, key, classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
                }
            }
        } else {
            if (key == null) {
                buffer.writeByte(Fury.NULL_FLAG);
            } else {
                if (!keyIsNotSameType) {
                    ClassInfo classInfo = classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
                    if (!writeKeyClassInfo) {
                        classResolver.writeClass(buffer, classInfo);
                        writeKeyClassInfo = true;
                    }
                    if (keyWriteSerializer == null) {
                        keyWriteSerializer = classInfo.getSerializer();
                    }
                    writeNoNullRef(keyWriteSerializer, key, buffer, refResolver);
                } else {
                    if (!refResolver.writeNullFlag(buffer, key)) {
                        fury.writeRef(
                                buffer, key, classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
                    }
                }
            }
        }
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      writeFinalValue(value, buffer, valueSerializer, trackingValueRef, header);
      generics.popGenericType();
      chunkSize++;
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private void javaKVTypesNonFinalChunkWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    int header = 0;
    int startOffset = 0;
    int chunkSize = 0;
    Class<?> keyClass = null;
    Class<?> valueClass = null;
    boolean writeKeyClassInfo = false;
    boolean writeValueClassInfo = false;
    boolean keyIsNotSameType = false;
    boolean valueIsNotSameType = false;
    boolean prevKeyIsNull = false;
    Serializer keyWriteSerializer = null;
    Serializer valueWriteSerializer = null;
    boolean markChunkWriteFinish = false;
    boolean hasPreservedByte = false;
    boolean needReset = false;
    boolean needMarkFinish = false;
    boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
    boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (!markChunkWriteFinish) {
        if (key == null) {
          prevKeyIsNull = true;
          if (chunkSize > 0) {
            needReset = true;
          }
        }
        if (!keyIsNotSameType) {
          if (key != null) {
            if (keyClass == null) {
              keyClass = key.getClass();
            }
            keyIsNotSameType = keyClass != key.getClass();
          }
          if (keyIsNotSameType) {
            if (valueIsNotSameType) {
              needMarkFinish = true;
            } else {
              needReset = true;
            }
          }
        }
        if (!valueIsNotSameType) {
          if (value != null) {
            if (valueClass == null) {
              valueClass = value.getClass();
            }
            valueIsNotSameType = valueClass != value.getClass();
          }
          if (valueIsNotSameType) {
            if (keyIsNotSameType) {
              needMarkFinish = true;
            } else {
              needReset = true;
            }
          }
        }
        if (needMarkFinish) {
          writeHeader(buffer, chunkSize, header, startOffset);
          // set chunk size = 0
          buffer.writeByte(0);
          markChunkWriteFinish = true;
        } else {
          if ((key == null && chunkSize > 0)
              || (prevKeyIsNull && key != null)
              || (value == null && chunkSize > 0 && !valueHasNull(header))
              || needReset
              || (chunkSize >= MAX_CHUNK_SIZE)) {
            writeHeader(buffer, chunkSize, header, startOffset);
            header = 0;
            chunkSize = 0;
            hasPreservedByte = false;
            writeKeyClassInfo = false;
            writeValueClassInfo = false;
            prevKeyIsNull = false;
            keyClass = key == null ? null : key.getClass();
            valueClass = value == null ? null : value.getClass();
            keyWriteSerializer = null;
            valueWriteSerializer = null;
            needReset = false;
          }
        }
      }
      if (markChunkWriteFinish) {
        generics.pushGenericType(keyGenericType);
        writeJavaRefOptimized(
            fury, classResolver, refResolver, trackingKeyRef, buffer, key, keyClassInfoWriteCache);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        writeJavaRefOptimized(
            fury,
            classResolver,
            refResolver,
            trackingValueRef,
            buffer,
            value,
            keyClassInfoWriteCache);
        generics.popGenericType();
      } else {
        if (!hasPreservedByte) {
          int writerIndex = buffer.writerIndex();
          // preserve two byte for header and chunk size
          buffer.writerIndex(writerIndex + 2);
          startOffset = writerIndex;
          hasPreservedByte = true;
        }
        header =
            updateKVHeader(
                key,
                trackingKeyRef,
                value,
                trackingValueRef,
                header,
                keyIsNotSameType,
                valueIsNotSameType);
        generics.pushGenericType(keyGenericType);
        if (!trackingKeyRef) {
          if (key == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!keyIsNotSameType) {
              ClassInfo classInfo =
                  classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
              if (!writeKeyClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeKeyClassInfo = true;
              }
              if (keyWriteSerializer == null) {
                keyWriteSerializer = classInfo.getSerializer();
              }
              keyWriteSerializer.write(buffer, key);
            } else {
              fury.writeNonRef(
                  buffer, key, classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
            }
          }
        } else {
          if (key == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!keyIsNotSameType) {
              // todo key is not null, no need to write no null flag
              ClassInfo classInfo =
                  classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
              if (!writeKeyClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeKeyClassInfo = true;
              }
              if (keyWriteSerializer == null) {
                keyWriteSerializer = classInfo.getSerializer();
              }
              writeNoNullRef(keyWriteSerializer, key, buffer, refResolver);
            } else {
              if (!refResolver.writeNullFlag(buffer, key)) {
                fury.writeRef(
                    buffer,
                    key,
                    classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
              }
            }
          }
        }
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        if (!trackingValueRef) {
          if (value == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!valueIsNotSameType) {
              if (valueHasNull(header)) {
                buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
              }
              ClassInfo classInfo =
                  classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
              if (!writeValueClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeValueClassInfo = true;
              }
              if (valueWriteSerializer == null) {
                valueWriteSerializer = classInfo.getSerializer();
              }
              valueWriteSerializer.write(buffer, value);
            } else {
              fury.writeNullable(
                  buffer,
                  value,
                  classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
            }
          }
        } else {
          if (value == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!valueIsNotSameType) {
              ClassInfo classInfo =
                  classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
              if (!writeValueClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeValueClassInfo = true;
              }
              if (valueWriteSerializer == null) {
                valueWriteSerializer = classInfo.getSerializer();
              }
              if (!valueHasNull(header)) {
                writeNoNullRef(valueWriteSerializer, value, buffer, refResolver);
              } else {
                fury.writeRef(buffer, value, valueWriteSerializer);
              }
            } else {
              if (!refResolver.writeNullFlag(buffer, value)) {
                fury.writeRef(
                    buffer,
                    value,
                    classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
              }
            }
          }
        }
        generics.popGenericType();
        chunkSize++;
      }
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private void javaValueTypeFinalWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      generics.pushGenericType(keyGenericType);
      writeJavaRefOptimized(
          fury,
          classResolver,
          refResolver,
          trackingKeyRef,
          buffer,
          entry.getKey(),
          keyClassInfoWriteCache);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      fury.writeRef(buffer, entry.getValue(), valueSerializer);
      generics.popGenericType();
    }
  }

  private void javaKVTypesNonFinalWrite(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
    boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      generics.pushGenericType(keyGenericType);
      writeJavaRefOptimized(
          fury,
          classResolver,
          refResolver,
          trackingKeyRef,
          buffer,
          entry.getKey(),
          keyClassInfoWriteCache);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      writeJavaRefOptimized(
          fury,
          classResolver,
          refResolver,
          trackingValueRef,
          buffer,
          entry.getValue(),
          valueClassInfoWriteCache);
      generics.popGenericType();
    }
  }

  private void generalJavaWrite(Fury fury, MemoryBuffer buffer, Map map) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      writeJavaRefOptimized(
          fury, classResolver, refResolver, buffer, entry.getKey(), keyClassInfoWriteCache);
      writeJavaRefOptimized(
          fury, classResolver, refResolver, buffer, entry.getValue(), valueClassInfoWriteCache);
    }
  }

  protected void generalJavaChunkWrite(Fury fury, MemoryBuffer buffer, Map map) {
    int header = 0;
    int startOffset = 0;
    int chunkSize = 0;
    Class<?> keyClass = null;
    Class<?> valueClass = null;
    boolean writeKeyClassInfo = false;
    boolean writeValueClassInfo = false;
    boolean keyIsNotSameType = false;
    boolean valueIsNotSameType = false;
    boolean prevKeyIsNull = false;
    Serializer keyWriteSerializer = null;
    Serializer valueWriteSerializer = null;
    boolean markChunkWriteFinish = false;
    boolean hasPreservedByte = false;
    boolean needReset = false;
    boolean needMarkFinish = false;
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    for (Object object : map.entrySet()) {
      Map.Entry entry = (Map.Entry) object;
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (!markChunkWriteFinish) {
        if (key == null) {
          prevKeyIsNull = true;
          if (chunkSize > 0) {
            needReset = true;
          }
        }
        if (!keyIsNotSameType) {
          if (key != null) {
            if (keyClass == null) {
              keyClass = key.getClass();
            }
            keyIsNotSameType = keyClass != key.getClass();
          }
          if (keyIsNotSameType) {
            if (valueIsNotSameType) {
              needMarkFinish = true;
            } else {
              needReset = true;
            }
          }
        }
        if (!valueIsNotSameType) {
          if (value != null) {
            if (valueClass == null) {
              valueClass = value.getClass();
            }
            valueIsNotSameType = valueClass != value.getClass();
          }
          if (valueIsNotSameType) {
            if (keyIsNotSameType) {
              needMarkFinish = true;
            } else {
              needReset = true;
            }
          }
        }
        if (needMarkFinish) {
          writeHeader(buffer, chunkSize, header, startOffset);
          // set chunk size = 0
          buffer.writeByte(0);
          markChunkWriteFinish = true;
        } else {
          if ((key == null && chunkSize > 0)
              || (prevKeyIsNull && key != null)
              || (value == null && chunkSize > 0 && !valueHasNull(header))
              || needReset
              || (chunkSize >= MAX_CHUNK_SIZE)) {
            writeHeader(buffer, chunkSize, header, startOffset);
            header = 0;
            chunkSize = 0;
            hasPreservedByte = false;
            writeKeyClassInfo = false;
            writeValueClassInfo = false;
            prevKeyIsNull = false;
            keyClass = key == null ? null : key.getClass();
            valueClass = value == null ? null : value.getClass();
            keyWriteSerializer = null;
            valueWriteSerializer = null;
            needReset = false;
          }
        }
      }
      if (!markChunkWriteFinish) {
        if (!hasPreservedByte) {
          int writerIndex = buffer.writerIndex();
          // preserve two byte for header and chunk size
          buffer.writerIndex(writerIndex + 2);
          startOffset = writerIndex;
          hasPreservedByte = true;
        }
        //        if (!keyIsNotSameType && keyWriteSerializer == null && !writeKeyClassInfo) {
        //          keyWriteSerializer = getSerializer(key, keyClassInfoWriteCache);
        //        }
        //        if (!valueIsNotSameType && valueWriteSerializer == null && !writeValueClassInfo) {
        //          valueWriteSerializer = getSerializer(value, valueClassInfoWriteCache);
        //        }
        boolean trackingRef = fury.trackingRef();
        header =
            updateKVHeader(
                key, trackingRef, value, trackingRef, header, keyIsNotSameType, valueIsNotSameType);
        if (!trackingRef) {
          if (key == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!keyIsNotSameType) {
              ClassInfo classInfo =
                  classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
              if (!writeKeyClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeKeyClassInfo = true;
              }
              if (keyWriteSerializer == null) {
                keyWriteSerializer = classInfo.getSerializer();
              }
              keyWriteSerializer.write(buffer, key);
            } else {
              fury.writeNonRef(
                  buffer, key, classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
            }
          }
        } else {
          if (key == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!keyIsNotSameType) {
              // todo key is not null, no need to write no null flag
              ClassInfo classInfo =
                  classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
              if (!writeKeyClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeKeyClassInfo = true;
              }
              if (keyWriteSerializer == null) {
                keyWriteSerializer = classInfo.getSerializer();
              }
              writeNoNullRef(keyWriteSerializer, key, buffer, refResolver);
            } else {
              if (!refResolver.writeNullFlag(buffer, key)) {
                fury.writeRef(
                    buffer,
                    key,
                    classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache));
              }
            }
          }
        }
        if (!trackingRef) {
          if (value == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!valueIsNotSameType) {
              if (valueHasNull(header)) {
                buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
              }
              ClassInfo classInfo =
                  classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
              if (!writeValueClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeValueClassInfo = true;
              }
              if (valueWriteSerializer == null) {
                valueWriteSerializer = classInfo.getSerializer();
              }
              valueWriteSerializer.write(buffer, value);
            } else {
              fury.writeNullable(
                  buffer,
                  value,
                  classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
            }
          }
        } else {
          if (value == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            if (!valueIsNotSameType) {
              ClassInfo classInfo =
                  classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
              if (!writeValueClassInfo) {
                classResolver.writeClass(buffer, classInfo);
                writeValueClassInfo = true;
              }
              if (valueWriteSerializer == null) {
                valueWriteSerializer = classInfo.getSerializer();
              }
              if (!valueHasNull(header)) {
                writeNoNullRef(valueWriteSerializer, value, buffer, refResolver);
              } else {
                fury.writeRef(buffer, value, valueWriteSerializer);
              }
            } else {
              if (!refResolver.writeNullFlag(buffer, value)) {
                fury.writeRef(
                    buffer,
                    value,
                    classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache));
              }
            }
          }
        }
        chunkSize++;
      } else {
        writeJavaRefOptimized(
            fury, classResolver, refResolver, buffer, entry.getKey(), keyClassInfoWriteCache);
        writeJavaRefOptimized(
            fury, classResolver, refResolver, buffer, entry.getValue(), valueClassInfoWriteCache);
      }
    }
    writeHeader(buffer, chunkSize, header, startOffset);
  }

  private void writeNoNullRef(
      Serializer serializer, Object o, MemoryBuffer buffer, RefResolver refResolver) {
    if (serializer.needToWriteRef()) {
      if (!refResolver.writeRefOrNull(buffer, o)) {
        serializer.write(buffer, o);
      }
    } else {
      serializer.write(buffer, o);
    }
  }

  private boolean valueHasNull(int header) {
    return (header & MapFlags.VALUE_HAS_NULL) == MapFlags.VALUE_HAS_NULL;
  }

  public void writeHeader(MemoryBuffer memoryBuffer, int chunkSize, int header, int startOffset) {
    if (chunkSize > 0) {
      int currentWriteIndex = memoryBuffer.writerIndex();
      memoryBuffer.writerIndex(startOffset);
      memoryBuffer.writeByte(chunkSize);
      memoryBuffer.writeByte(header);
      memoryBuffer.writerIndex(currentWriteIndex);
    }
  }

  public static void xwriteElements(Fury fury, MemoryBuffer buffer, Map value) {
    Generics generics = fury.getGenerics();
    GenericType genericType = generics.nextGenericType();
    // TODO(chaokunyang) support map subclass whose key or value generics only are available.
    if (genericType == null || genericType.getTypeParametersCount() != 2) {
      for (Object object : value.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        fury.xwriteRef(buffer, entry.getKey());
        fury.xwriteRef(buffer, entry.getValue());
      }
    } else {
      // TODO(chaokunyang) use codegen to remove all branches.
      GenericType keyGenericType = genericType.getTypeParameter0();
      GenericType valueGenericType = genericType.getTypeParameter1();
      Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
      Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
      if (!keyGenericType.hasGenericParameters() && !valueGenericType.hasGenericParameters()) {
        for (Object object : value.entrySet()) {
          Map.Entry entry = (Map.Entry) object;
          xwriteRefByNullableSerializer(fury, buffer, entry.getKey(), keySerializer);
          xwriteRefByNullableSerializer(fury, buffer, entry.getValue(), valueSerializer);
        }
      } else if (valueGenericType.hasGenericParameters()) {
        for (Object object : value.entrySet()) {
          Map.Entry entry = (Map.Entry) object;
          xwriteRefByNullableSerializer(fury, buffer, entry.getKey(), keySerializer);
          generics.pushGenericType(valueGenericType);
          xwriteRefByNullableSerializer(fury, buffer, entry.getValue(), valueSerializer);
          generics.popGenericType();
        }
      } else if (keyGenericType.hasGenericParameters()) {
        for (Object object : value.entrySet()) {
          Map.Entry entry = (Map.Entry) object;
          generics.pushGenericType(keyGenericType);
          xwriteRefByNullableSerializer(fury, buffer, entry.getKey(), keySerializer);
          generics.popGenericType();
          xwriteRefByNullableSerializer(fury, buffer, entry.getValue(), valueSerializer);
        }
      } else {
        for (Object object : value.entrySet()) {
          Map.Entry entry = (Map.Entry) object;
          generics.pushGenericType(keyGenericType);
          xwriteRefByNullableSerializer(fury, buffer, entry.getKey(), keySerializer);
          generics.pushGenericType(valueGenericType);
          xwriteRefByNullableSerializer(fury, buffer, entry.getValue(), valueSerializer);
        }
      }
      generics.popGenericType();
    }
  }

  public static <T> void xwriteRefByNullableSerializer(
      Fury fury, MemoryBuffer buffer, T obj, Serializer<T> serializer) {
    if (serializer == null) {
      fury.xwriteRef(buffer, obj);
    } else {
      fury.xwriteRef(buffer, obj, serializer);
    }
  }

  private Tuple2<GenericType, GenericType> getKVGenericType(GenericType genericType) {
    Tuple2<GenericType, GenericType> genericTypes = partialGenericKVTypeMap.get(genericType);
    if (genericTypes == null) {
      TypeRef<?> typeRef = genericType.getTypeRef();
      if (!MAP_TYPE.isSupertypeOf(typeRef)) {
        Tuple2<GenericType, GenericType> typeTuple = Tuple2.of(objType, objType);
        partialGenericKVTypeMap.put(genericType, typeTuple);
        return typeTuple;
      }
      Tuple2<TypeRef<?>, TypeRef<?>> mapKeyValueType = TypeUtils.getMapKeyValueType(typeRef);
      genericTypes =
          Tuple2.of(
              fury.getClassResolver().buildGenericType(mapKeyValueType.f0.getType()),
              fury.getClassResolver().buildGenericType(mapKeyValueType.f1.getType()));
      partialGenericKVTypeMap.put(genericType, genericTypes);
    }
    return genericTypes;
  }

  @Override
  public T xread(MemoryBuffer buffer) {
    Map map = newMap(buffer);
    xreadElements(fury, buffer, map, numElements);
    return onMapRead(map);
  }

  protected <K, V> void copyEntry(Map<K, V> originMap, Map<K, V> newMap) {
    ClassResolver classResolver = fury.getClassResolver();
    for (Map.Entry<K, V> entry : originMap.entrySet()) {
      K key = entry.getKey();
      if (key != null) {
        ClassInfo classInfo = classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
        if (!classInfo.getSerializer().isImmutable()) {
          key = fury.copyObject(key, classInfo.getClassId());
        }
      }
      V value = entry.getValue();
      if (value != null) {
        ClassInfo classInfo =
            classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
        if (!classInfo.getSerializer().isImmutable()) {
          value = fury.copyObject(value, classInfo.getClassId());
        }
      }
      newMap.put(key, value);
    }
  }

  protected <K, V> void copyEntry(Map<K, V> originMap, Builder<K, V> builder) {
    ClassResolver classResolver = fury.getClassResolver();
    for (Entry<K, V> entry : originMap.entrySet()) {
      K key = entry.getKey();
      if (key != null) {
        ClassInfo classInfo = classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
        if (!classInfo.getSerializer().isImmutable()) {
          key = fury.copyObject(key, classInfo.getClassId());
        }
      }
      V value = entry.getValue();
      if (value != null) {
        ClassInfo classInfo =
            classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
        if (!classInfo.getSerializer().isImmutable()) {
          value = fury.copyObject(value, classInfo.getClassId());
        }
      }
      builder.put(key, value);
    }
  }

  protected <K, V> void copyEntry(Map<K, V> originMap, Object[] elements) {
    ClassResolver classResolver = fury.getClassResolver();
    int index = 0;
    for (Entry<K, V> entry : originMap.entrySet()) {
      K key = entry.getKey();
      if (key != null) {
        ClassInfo classInfo = classResolver.getClassInfo(key.getClass(), keyClassInfoWriteCache);
        if (!classInfo.getSerializer().isImmutable()) {
          key = fury.copyObject(key, classInfo.getClassId());
        }
      }
      V value = entry.getValue();
      if (value != null) {
        ClassInfo classInfo =
            classResolver.getClassInfo(value.getClass(), valueClassInfoWriteCache);
        if (!classInfo.getSerializer().isImmutable()) {
          value = fury.copyObject(value, classInfo.getClassId());
        }
      }
      elements[index++] = key;
      elements[index++] = value;
    }
  }

  @SuppressWarnings("unchecked")
  protected final void chunkReadElements(MemoryBuffer buffer, int size, Map map) {
    Serializer keySerializer = this.keySerializer;
    Serializer valueSerializer = this.valueSerializer;
    // clear the elemSerializer to avoid conflict if the nested
    // serialization has collection field.
    // TODO use generics for compatible serializer.
    this.keySerializer = null;
    this.valueSerializer = null;
    if (keySerializer != null && valueSerializer != null) {
      javaChunkReadWithKVSerializers(buffer, map, size, keySerializer, valueSerializer);
    } else if (keySerializer != null) {
      javaChunkReadWithKeySerializer(buffer, map, size, keySerializer);
    } else if (valueSerializer != null) {
      javaChunkReadWithValueSerializer(buffer, map, size, valueSerializer);
    } else {
      genericJavaChunkRead(fury, buffer, map, size);
    }
  }

  @SuppressWarnings("unchecked")
  protected final void readElements(MemoryBuffer buffer, int size, Map map) {
    Serializer keySerializer = this.keySerializer;
    Serializer valueSerializer = this.valueSerializer;
    // clear the elemSerializer to avoid conflict if the nested
    // serialization has collection field.
    // TODO use generics for compatible serializer.
    this.keySerializer = null;
    this.valueSerializer = null;
    if (keySerializer != null && valueSerializer != null) {
      for (int i = 0; i < size; i++) {
        Object key = fury.readRef(buffer, keySerializer);
        Object value = fury.readRef(buffer, valueSerializer);
        map.put(key, value);
      }
    } else if (keySerializer != null) {
      for (int i = 0; i < size; i++) {
        Object key = fury.readRef(buffer, keySerializer);
        map.put(key, fury.readRef(buffer, keyClassInfoReadCache));
      }
    } else if (valueSerializer != null) {
      for (int i = 0; i < size; i++) {
        Object key = fury.readRef(buffer);
        Object value = fury.readRef(buffer, valueSerializer);
        map.put(key, value);
      }
    } else {
      genericJavaRead(fury, buffer, map, size);
    }
  }

  private void genericJavaRead(Fury fury, MemoryBuffer buffer, Map map, int size) {
    Generics generics = fury.getGenerics();
    GenericType genericType = generics.nextGenericType();
    if (genericType == null) {
      generalJavaRead(fury, buffer, map, size);
    } else {
      GenericType keyGenericType = genericType.getTypeParameter0();
      GenericType valueGenericType = genericType.getTypeParameter1();
      if (genericType.getTypeParametersCount() < 2) {
        Tuple2<GenericType, GenericType> kvGenericType = getKVGenericType(genericType);
        if (keyGenericType == objType && valueGenericType == objType) {
          generalJavaRead(fury, buffer, map, size);
          return;
        }
        keyGenericType = kvGenericType.f0;
        valueGenericType = kvGenericType.f1;
      }
      boolean keyGenericTypeFinal = keyGenericType.isMonomorphic();
      boolean valueGenericTypeFinal = valueGenericType.isMonomorphic();
      if (keyGenericTypeFinal && valueGenericTypeFinal) {
        javaKVTypesFinalRead(fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      } else if (keyGenericTypeFinal) {
        javaKeyTypeFinalRead(fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      } else if (valueGenericTypeFinal) {
        javaValueTypeFinalRead(fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      } else {
        javaKVTypesNonFinalRead(
            fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      }
      generics.popGenericType();
    }
  }

  private void javaChunkReadWithKeySerializer(
      MemoryBuffer buffer, Map map, int size, Serializer keySerializer) {
    final ClassResolver classResolver = fury.getClassResolver();
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      byte header = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      Serializer valueReadSerializer = null;
      for (byte i = 0; i < chunkSize; i++) {
        Object key;
        Object value;
        key = readFinalKey(buffer, header, keySerializer);
        if (!fury.trackingRef()) {
          if (!valueIsNotSameType(header)) {
            if (valueHasNull(header)) {
              byte flag = buffer.readByte();
              if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = valueReadSerializer.read(buffer);
              } else {
                value = null;
              }
            } else {
              if (valueReadSerializer == null) {
                valueReadSerializer =
                    classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
              }
              value = valueReadSerializer.read(buffer);
            }
          } else {
            value = fury.readNullable(buffer, valueClassInfoReadCache);
          }

        } else {
          if (!valueIsNotSameType(header)) {
            if (valueHasNull(header)) {
              byte flag = buffer.readByte();
              if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = fury.readRef(buffer, valueReadSerializer);
              } else {
                value = null;
              }
            } else {
              if (valueReadSerializer == null) {
                valueReadSerializer =
                    classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
              }
              value = readNoNullRef(valueReadSerializer, buffer);
            }
          } else {
            value = fury.readRef(buffer, valueClassInfoReadCache);
          }
        }
        map.put(key, value);
        size--;
      }
    }
  }

  private void javaChunkReadWithValueSerializer(
      MemoryBuffer buffer, Map map, int size, Serializer valueSerializer) {
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      byte header = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      Serializer keyReadSerializer = null;
      for (byte i = 0; i < chunkSize; i++) {
        Object key;
        Object value;
        if (!fury.trackingRef()) {
          if (keyHasNull(header)) {
            byte nullFlag = buffer.readByte();
            Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
            key = null;
          } else {
            if (!keyIsNotSameType(header)) {
              if (keyReadSerializer == null) {
                keyReadSerializer =
                    fury.getClassResolver()
                        .readClassInfo(buffer, keyClassInfoReadCache)
                        .getSerializer();
              }
              key = keyReadSerializer.read(buffer);
            } else {
              key = fury.readNonRef(buffer, keyClassInfoReadCache);
            }
          }
        } else {
          if (keyHasNull(header)) {
            byte nullFlag = buffer.readByte();
            Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
            key = null;
          } else {
            if (!keyIsNotSameType(header)) {
              if (keyReadSerializer == null) {
                keyReadSerializer =
                    fury.getClassResolver()
                        .readClassInfo(buffer, keyClassInfoReadCache)
                        .getSerializer();
              }
              key = readNoNullRef(keyReadSerializer, buffer);
            } else {
              key = fury.readRef(buffer, keyClassInfoReadCache);
            }
          }
        }
        value = readFinalValue(buffer, header, valueSerializer);
        map.put(key, value);
        size--;
      }
    }
  }

  private void javaChunkReadWithKVSerializers(
      MemoryBuffer buffer,
      Map map,
      int size,
      Serializer keySerializer,
      Serializer valueSerializer) {
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      byte header = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      for (byte i = 0; i < chunkSize; i++) {
        Object key;
        Object value;
        key = readFinalKey(buffer, header, keySerializer);
        value = readFinalValue(buffer, header, valueSerializer);
        map.put(key, value);
        size--;
      }
    }
  }

  public Object readFinalKey(MemoryBuffer buffer, int header, Serializer keySerializer) {
    boolean trackingKeyRef = keySerializer.needToWriteRef();
    if (!trackingKeyRef) {
      if (keyHasNull(header)) {
        byte nullFlag = buffer.readByte();
        Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected NULL_FLAG");
        return null;
      } else {
        return keySerializer.read(buffer);
      }
    } else {
      return fury.readRef(buffer, keySerializer);
    }
  }

  public Object readFinalValue(MemoryBuffer buffer, int header, Serializer valueSerializer) {
    boolean trackingValueRef = valueSerializer.needToWriteRef();
    if (!trackingValueRef) {
      if (valueHasNull(header)) {
        byte flag = buffer.readByte();
        if (flag == Fury.NOT_NULL_VALUE_FLAG) {
          return valueSerializer.read(buffer);
        } else {
          return null;
        }
      } else {
        return valueSerializer.read(buffer);
      }
    } else {
      return fury.readRef(buffer, valueSerializer);
    }
  }

  private void genericJavaChunkRead(Fury fury, MemoryBuffer buffer, Map map, int size) {
    Generics generics = fury.getGenerics();
    GenericType genericType = generics.nextGenericType();
    if (genericType == null) {
      generalJavaChunkRead(fury, buffer, map, size);
    } else {
      GenericType keyGenericType = genericType.getTypeParameter0();
      GenericType valueGenericType = genericType.getTypeParameter1();
      if (genericType.getTypeParametersCount() < 2) {
        Tuple2<GenericType, GenericType> kvGenericType = getKVGenericType(genericType);
        if (keyGenericType == objType && valueGenericType == objType) {
          generalJavaChunkRead(fury, buffer, map, size);
          return;
        }
        keyGenericType = kvGenericType.f0;
        valueGenericType = kvGenericType.f1;
      }
      boolean keyGenericTypeFinal = keyGenericType.isMonomorphic();
      boolean valueGenericTypeFinal = valueGenericType.isMonomorphic();
      if (keyGenericTypeFinal && valueGenericTypeFinal) {
        javaKVTypesFinalChunkRead(
            fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      } else if (keyGenericTypeFinal) {
        javaKeyTypeFinalChunkRead(
            fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      } else if (valueGenericTypeFinal) {
        javaValueTypeFinalChunkRead(
            fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      } else {
        javaKVTypesNonFinalChunkRead(
            fury, buffer, map, keyGenericType, valueGenericType, generics, size);
      }
      generics.popGenericType();
    }
  }

  private void javaKVTypesFinalChunkRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      byte header = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      for (byte i = 0; i < chunkSize; i++) {
        Object key;
        Object value;
        generics.pushGenericType(keyGenericType);
        key = readFinalKey(buffer, header, keySerializer);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        value = readFinalValue(buffer, header, valueSerializer);
        generics.popGenericType();
        map.put(key, value);
        size--;
      }
    }
  }

  private void javaKVTypesFinalRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    for (int i = 0; i < size; i++) {
      generics.pushGenericType(keyGenericType);
      Object key = fury.readRef(buffer, keySerializer);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      Object value = fury.readRef(buffer, valueSerializer);
      generics.popGenericType();
      map.put(key, value);
    }
  }

  private void javaKeyTypeFinalChunkRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    ClassResolver classResolver = fury.getClassResolver();
    boolean trackingValueRef = classResolver.needToWriteRef(valueGenericType.getCls());
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      byte header = buffer.readByte();
      Serializer valueReadSerializer = null;
      while (chunkSize > 0) {
        generics.pushGenericType(keyGenericType);
        Object key = readFinalKey(buffer, header, keySerializer);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        Object value;
        if (!trackingValueRef) {
          if (!valueIsNotSameType(header)) {
            if (valueHasNull(header)) {
              byte flag = buffer.readByte();
              if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = valueReadSerializer.read(buffer);
              } else {
                value = null;
              }
            } else {
              if (valueReadSerializer == null) {
                valueReadSerializer =
                    classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
              }
              value = valueReadSerializer.read(buffer);
            }
          } else {
            value = fury.readNullable(buffer, valueClassInfoReadCache);
          }

        } else {
          if (!valueIsNotSameType(header)) {
            if (valueHasNull(header)) {
              byte flag = buffer.readByte();
              if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = fury.readRef(buffer, valueReadSerializer);
              } else {
                value = null;
              }
            } else {
              if (valueReadSerializer == null) {
                valueReadSerializer =
                    classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
              }
              value = readNoNullRef(valueReadSerializer, buffer);
            }
          } else {
            value = fury.readRef(buffer, valueClassInfoReadCache);
          }
        }
        generics.popGenericType();
        chunkSize--;
        size--;
        map.put(key, value);
      }
    }
  }

  private void javaKeyTypeFinalRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    RefResolver refResolver = fury.getRefResolver();
    boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
    Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
    for (int i = 0; i < size; i++) {
      generics.pushGenericType(keyGenericType);
      Object key = fury.readRef(buffer, keySerializer);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      Object value =
          readJavaRefOptimized(
              fury, refResolver, trackingValueRef, buffer, valueClassInfoWriteCache);
      generics.popGenericType();
      map.put(key, value);
    }
  }

  private void javaValueTypeFinalChunkRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      byte header = buffer.readByte();
      Serializer keyReadSerializer = null;
      while (chunkSize > 0) {
        generics.pushGenericType(keyGenericType);
        Object key;
        if (!trackingKeyRef) {
          if (keyHasNull(header)) {
            byte nullFlag = buffer.readByte();
            Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
            key = null;
          } else {
            if (!keyIsNotSameType(header)) {
              if (keyReadSerializer == null) {
                keyReadSerializer =
                    fury.getClassResolver()
                        .readClassInfo(buffer, keyClassInfoReadCache)
                        .getSerializer();
              }
              key = keyReadSerializer.read(buffer);
            } else {
              key = fury.readNonRef(buffer, keyClassInfoReadCache);
            }
          }
        } else {
          if (keyHasNull(header)) {
            byte nullFlag = buffer.readByte();
            Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
            key = null;
          } else {
            if (!keyIsNotSameType(header)) {
              if (keyReadSerializer == null) {
                keyReadSerializer =
                    fury.getClassResolver()
                        .readClassInfo(buffer, keyClassInfoReadCache)
                        .getSerializer();
              }
              key = readNoNullRef(keyReadSerializer, buffer);
            } else {
              key = fury.readRef(buffer, keyClassInfoReadCache);
            }
          }
        }
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        Object value = readFinalValue(buffer, header, valueSerializer);
        generics.popGenericType();
        chunkSize--;
        size--;
        map.put(key, value);
      }
    }
  }

  private void javaValueTypeFinalRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
    Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
    RefResolver refResolver = fury.getRefResolver();
    for (int i = 0; i < size; i++) {
      generics.pushGenericType(keyGenericType);
      Object key =
          readJavaRefOptimized(fury, refResolver, trackingKeyRef, buffer, keyClassInfoWriteCache);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      Object value = fury.readRef(buffer, valueSerializer);
      generics.popGenericType();
      map.put(key, value);
    }
  }

  private void javaKVTypesNonFinalChunkRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    boolean trackingKeyRef = classResolver.needToWriteRef(keyGenericType.getCls());
    boolean trackingValueRef = classResolver.needToWriteRef(valueGenericType.getCls());
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      if (chunkSize == 0) {
        while (size > 0) {
          generics.pushGenericType(keyGenericType);
          Object key =
              readJavaRefOptimized(
                  fury, refResolver, trackingKeyRef, buffer, keyClassInfoReadCache);
          generics.popGenericType();
          generics.pushGenericType(valueGenericType);
          Object value =
              readJavaRefOptimized(
                  fury, refResolver, trackingValueRef, buffer, valueClassInfoReadCache);
          generics.popGenericType();
          map.put(key, value);
          size--;
        }
      } else {
        byte header = buffer.readByte();
        Serializer keyReadSerializer = null;
        Serializer valueReadSerializer = null;
        while (chunkSize > 0) {
          generics.pushGenericType(keyGenericType);
          Object key;
          if (!trackingKeyRef) {
            if (keyHasNull(header)) {
              byte nullFlag = buffer.readByte();
              Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
              key = null;
            } else {
              if (!keyIsNotSameType(header)) {
                if (keyReadSerializer == null) {
                  keyReadSerializer =
                      classResolver.readClassInfo(buffer, keyClassInfoReadCache).getSerializer();
                }
                key = keyReadSerializer.read(buffer);
              } else {
                key = fury.readNonRef(buffer, keyClassInfoReadCache);
              }
            }
          } else {
            if (keyHasNull(header)) {
              byte nullFlag = buffer.readByte();
              Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
              key = null;
            } else {
              if (!keyIsNotSameType(header)) {
                if (keyReadSerializer == null) {
                  keyReadSerializer =
                      classResolver.readClassInfo(buffer, keyClassInfoReadCache).getSerializer();
                }
                key = readNoNullRef(keyReadSerializer, buffer);
              } else {
                key = fury.readRef(buffer, keyClassInfoReadCache);
              }
            }
          }
          generics.popGenericType();
          generics.pushGenericType(valueGenericType);
          Object value;
          if (!trackingValueRef) {
            if (!valueIsNotSameType(header)) {
              if (valueHasNull(header)) {
                byte flag = buffer.readByte();
                if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                  if (valueReadSerializer == null) {
                    valueReadSerializer =
                        classResolver
                            .readClassInfo(buffer, valueClassInfoReadCache)
                            .getSerializer();
                  }
                  value = valueReadSerializer.read(buffer);
                } else {
                  value = null;
                }
              } else {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = valueReadSerializer.read(buffer);
              }
            } else {
              value = fury.readNullable(buffer, valueClassInfoReadCache);
            }

          } else {
            if (!valueIsNotSameType(header)) {
              if (valueHasNull(header)) {
                byte flag = buffer.readByte();
                if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                  if (valueReadSerializer == null) {
                    valueReadSerializer =
                        classResolver
                            .readClassInfo(buffer, valueClassInfoReadCache)
                            .getSerializer();
                  }
                  value = fury.readRef(buffer, valueReadSerializer);
                } else {
                  value = null;
                }
              } else {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = readNoNullRef(valueReadSerializer, buffer);
              }
            } else {
              value = fury.readRef(buffer, valueClassInfoReadCache);
            }
          }
          generics.popGenericType();
          chunkSize--;
          size--;
          map.put(key, value);
        }
      }
    }
  }

  private void javaKVTypesNonFinalRead(
      Fury fury,
      MemoryBuffer buffer,
      Map map,
      GenericType keyGenericType,
      GenericType valueGenericType,
      Generics generics,
      int size) {
    ClassResolver classResolver = fury.getClassResolver();
    RefResolver refResolver = fury.getRefResolver();
    boolean trackingKeyRef = classResolver.needToWriteRef(keyGenericType.getCls());
    boolean trackingValueRef = classResolver.needToWriteRef(valueGenericType.getCls());
    for (int i = 0; i < size; i++) {
      generics.pushGenericType(keyGenericType);
      Object key =
          readJavaRefOptimized(fury, refResolver, trackingKeyRef, buffer, keyClassInfoWriteCache);
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      Object value =
          readJavaRefOptimized(
              fury, refResolver, trackingValueRef, buffer, valueClassInfoWriteCache);
      generics.popGenericType();
      map.put(key, value);
    }
  }

  private void generalJavaChunkRead(Fury fury, MemoryBuffer buffer, Map map, int size) {
    ClassResolver classResolver = fury.getClassResolver();
    boolean trackingRef = fury.trackingRef();
    while (size > 0) {
      byte chunkSize = buffer.readByte();
      Preconditions.checkArgument(
          chunkSize >= 0,
          "chunkSize < 0, which means serialization protocol is not same with deserialization protocol");
      if (chunkSize == 0) {
        while (size > 0) {
          Object key = fury.readRef(buffer, keyClassInfoReadCache);
          Object value = fury.readRef(buffer, keyClassInfoReadCache);
          map.put(key, value);
          size--;
        }
      } else {
        byte header = buffer.readByte();
        Serializer keyReadSerializer = null;
        Serializer valueReadSerializer = null;
        while (chunkSize > 0) {
          Object key;
          if (!trackingRef) {
            if (keyHasNull(header)) {
              byte nullFlag = buffer.readByte();
              Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
              key = null;
            } else {
              if (!keyIsNotSameType(header)) {
                if (keyReadSerializer == null) {
                  keyReadSerializer =
                      classResolver.readClassInfo(buffer, keyClassInfoReadCache).getSerializer();
                }
                key = keyReadSerializer.read(buffer);
              } else {
                key = fury.readNonRef(buffer, keyClassInfoReadCache);
              }
            }
          } else {
            if (keyHasNull(header)) {
              byte nullFlag = buffer.readByte();
              Preconditions.checkArgument(nullFlag == Fury.NULL_FLAG, "unexpected error");
              key = null;
            } else {
              if (!keyIsNotSameType(header)) {
                if (keyReadSerializer == null) {
                  keyReadSerializer =
                      classResolver.readClassInfo(buffer, keyClassInfoReadCache).getSerializer();
                }
                key = readNoNullRef(keyReadSerializer, buffer);
              } else {
                key = fury.readRef(buffer, keyClassInfoReadCache);
              }
            }
          }
          Object value;
          if (!trackingRef) {
            if (!valueIsNotSameType(header)) {
              if (valueHasNull(header)) {
                byte flag = buffer.readByte();
                if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                  if (valueReadSerializer == null) {
                    valueReadSerializer =
                        classResolver
                            .readClassInfo(buffer, valueClassInfoReadCache)
                            .getSerializer();
                  }
                  value = valueReadSerializer.read(buffer);
                } else {
                  value = null;
                }
              } else {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = valueReadSerializer.read(buffer);
              }
            } else {
              value = fury.readNullable(buffer, valueClassInfoReadCache);
            }

          } else {
            if (!valueIsNotSameType(header)) {
              if (valueHasNull(header)) {
                byte flag = buffer.readByte();
                if (flag == Fury.NOT_NULL_VALUE_FLAG) {
                  if (valueReadSerializer == null) {
                    valueReadSerializer =
                        classResolver
                            .readClassInfo(buffer, valueClassInfoReadCache)
                            .getSerializer();
                  }
                  value = fury.readRef(buffer, valueReadSerializer);
                } else {
                  value = null;
                }
              } else {
                if (valueReadSerializer == null) {
                  valueReadSerializer =
                      classResolver.readClassInfo(buffer, valueClassInfoReadCache).getSerializer();
                }
                value = readNoNullRef(valueReadSerializer, buffer);
              }
            } else {
              value = fury.readRef(buffer, valueClassInfoReadCache);
            }
          }
          chunkSize--;
          size--;
          map.put(key, value);
        }
      }
    }
  }

  private boolean keyHasNull(int header) {
    return (header & MapFlags.KEY_HAS_NULL) == MapFlags.KEY_HAS_NULL;
  }

  private boolean keyIsNotSameType(int header) {
    return (header & MapFlags.KEY_NOT_SAME_TYPE) == MapFlags.KEY_NOT_SAME_TYPE;
  }

  private boolean valueIsNotSameType(int header) {
    return (header & MapFlags.VALUE_NOT_SAME_TYPE) == MapFlags.VALUE_NOT_SAME_TYPE;
  }

  private Object readNoNullRef(Serializer serializer, MemoryBuffer memoryBuffer) {
    if (serializer.needToWriteRef()) {
      final RefResolver refResolver = fury.getRefResolver();
      int nextReadRefId = refResolver.tryPreserveRefId(memoryBuffer);
      if (nextReadRefId >= Fury.NOT_NULL_VALUE_FLAG) {
        Object obj = serializer.read(memoryBuffer);
        refResolver.setReadObject(nextReadRefId, obj);
        return obj;
      } else {
        return refResolver.getReadObject();
      }
    } else {
      return serializer.read(memoryBuffer);
    }
  }

  private void generalJavaRead(Fury fury, MemoryBuffer buffer, Map map, int size) {
    for (int i = 0; i < size; i++) {
      Object key = fury.readRef(buffer, keyClassInfoReadCache);
      Object value = fury.readRef(buffer, valueClassInfoReadCache);
      map.put(key, value);
    }
  }

  @SuppressWarnings("unchecked")
  public static void xreadElements(Fury fury, MemoryBuffer buffer, Map map, int size) {
    Generics generics = fury.getGenerics();
    GenericType genericType = generics.nextGenericType();
    if (genericType == null || genericType.getTypeParametersCount() != 2) {
      for (int i = 0; i < size; i++) {
        Object key = fury.xreadRef(buffer);
        Object value = fury.xreadRef(buffer);
        map.put(key, value);
      }
    } else {
      // TODO(chaokunyang) use codegen to remove all branches.
      GenericType keyGenericType = genericType.getTypeParameter0();
      GenericType valueGenericType = genericType.getTypeParameter1();
      Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
      Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
      if (!keyGenericType.hasGenericParameters() && !valueGenericType.hasGenericParameters()) {
        for (int i = 0; i < size; i++) {
          Object key = xreadRefByNullableSerializer(fury, buffer, keySerializer);
          Object value = xreadRefByNullableSerializer(fury, buffer, valueSerializer);
          map.put(key, value);
        }
      } else if (valueGenericType.hasGenericParameters()) {
        for (int i = 0; i < size; i++) {
          Object key = xreadRefByNullableSerializer(fury, buffer, keySerializer);
          generics.pushGenericType(valueGenericType);
          Object value = xreadRefByNullableSerializer(fury, buffer, valueSerializer);
          generics.popGenericType();
          map.put(key, value);
        }
      } else if (keyGenericType.hasGenericParameters()) {
        for (int i = 0; i < size; i++) {
          generics.pushGenericType(keyGenericType);
          Object key = xreadRefByNullableSerializer(fury, buffer, keySerializer);
          generics.popGenericType();
          Object value = xreadRefByNullableSerializer(fury, buffer, valueSerializer);
          map.put(key, value);
        }
      } else {
        for (int i = 0; i < size; i++) {
          // FIXME(chaokunyang) nested generics may be get by mistake.
          generics.pushGenericType(keyGenericType);
          Object key = xreadRefByNullableSerializer(fury, buffer, keySerializer);
          generics.pushGenericType(valueGenericType);
          Object value = xreadRefByNullableSerializer(fury, buffer, valueSerializer);
          map.put(key, value);
        }
      }
      generics.popGenericType();
    }
  }

  public static Object xreadRefByNullableSerializer(
      Fury fury, MemoryBuffer buffer, Serializer<?> serializer) {
    if (serializer == null) {
      return fury.xreadRef(buffer);
    } else {
      return fury.xreadRef(buffer, serializer);
    }
  }

  /**
   * Hook for java serialization codegen, read/write key/value by entrySet.
   *
   * <p>For key/value type which is final, using codegen may get a big performance gain
   *
   * @return true if read/write key/value support calling entrySet method
   */
  public final boolean supportCodegenHook() {
    return supportCodegenHook;
  }

  /**
   * Write data except size and elements.
   *
   * <ol>
   *   In codegen, follows is call order:
   *   <li>write map class if not final
   *   <li>write map size
   *   <li>onCollectionWrite
   *   <li>write keys/values
   * </ol>
   */
  public abstract Map onMapWrite(MemoryBuffer buffer, T value);

  /** Check null first to avoid ref tracking for some types with ref tracking disabled. */
  private void writeJavaRefOptimized(
      Fury fury,
      ClassResolver classResolver,
      RefResolver refResolver,
      MemoryBuffer buffer,
      Object obj,
      ClassInfoHolder classInfoHolder) {
    if (!refResolver.writeNullFlag(buffer, obj)) {
      fury.writeRef(buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoHolder));
    }
  }

  private void writeJavaRefOptimized(
      Fury fury,
      ClassResolver classResolver,
      RefResolver refResolver,
      boolean trackingRef,
      MemoryBuffer buffer,
      Object obj,
      ClassInfoHolder classInfoHolder) {
    if (trackingRef) {
      if (!refResolver.writeNullFlag(buffer, obj)) {
        fury.writeRef(buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoHolder));
      }
    } else {
      if (obj == null) {
        buffer.writeByte(Fury.NULL_FLAG);
      } else {
        buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
        fury.writeNonRef(buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoHolder));
      }
    }
  }

  @Override
  public abstract T read(MemoryBuffer buffer);

  /**
   * Read data except size and elements, return empty map to be filled.
   *
   * <ol>
   *   In codegen, follows is call order:
   *   <li>read map class if not final
   *   <li>newMap: read and set map size, read map header and create map.
   *   <li>read keys/values
   * </ol>
   *
   * <p>Map must have default constructor to be invoked by fury, otherwise created object can't be
   * used to adding elements. For example:
   *
   * <pre>{@code new ArrayList<Integer> {add(1);}}</pre>
   *
   * <p>without default constructor, created list will have elementData as null, adding elements
   * will raise NPE.
   */
  public Map newMap(MemoryBuffer buffer) {
    numElements = buffer.readVarUint32Small7();
    if (constructor == null) {
      constructor = ReflectionUtils.getCtrHandle(type, true);
    }
    try {
      Map instance = (Map) constructor.invoke();
      fury.getRefResolver().reference(instance);
      return instance;
    } catch (Throwable e) {
      throw new IllegalArgumentException(
          "Please provide public no arguments constructor for class " + type, e);
    }
  }

  /** Create a new empty map for copy. */
  public Map newMap(Map map) {
    numElements = map.size();
    if (constructor == null) {
      constructor = ReflectionUtils.getCtrHandle(type, true);
    }
    try {
      return (Map) constructor.invoke();
    } catch (Throwable e) {
      throw new IllegalArgumentException(
          "Please provide public no arguments constructor for class " + type, e);
    }
  }

  /**
   * Get and reset numElements of deserializing collection. Should be called after {@link
   * #newMap(MemoryBuffer buffer)}. Nested read may overwrite this element, reset is necessary to
   * avoid use wrong value by mistake.
   */
  public int getAndClearNumElements() {
    int size = numElements;
    numElements = -1; // nested read may overwrite this element.
    return size;
  }

  public void setNumElements(int numElements) {
    this.numElements = numElements;
  }

  public abstract T onMapCopy(Map map);

  public abstract T onMapRead(Map map);

  private Object readJavaRefOptimized(
      Fury fury,
      RefResolver refResolver,
      boolean trackingRef,
      MemoryBuffer buffer,
      ClassInfoHolder classInfoHolder) {
    if (trackingRef) {
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= Fury.NOT_NULL_VALUE_FLAG) {
        Object obj = fury.readNonRef(buffer, classInfoHolder);
        refResolver.setReadObject(nextReadRefId, obj);
        return obj;
      } else {
        return refResolver.getReadObject();
      }
    } else {
      byte headFlag = buffer.readByte();
      if (headFlag == Fury.NULL_FLAG) {
        return null;
      } else {
        return fury.readNonRef(buffer, classInfoHolder);
      }
    }
  }
}
