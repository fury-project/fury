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

package org.apache.fury.resolver;

import static org.apache.fury.resolver.ClassResolver.NO_CLASS_ID;
import static org.apache.fury.resolver.ClassResolver.PRIMITIVE_LONG_CLASS_ID;
import static org.apache.fury.resolver.FieldResolver.FieldInfoEncodingType.EMBED_TYPES_4;
import static org.apache.fury.resolver.FieldResolver.FieldInfoEncodingType.EMBED_TYPES_9;
import static org.apache.fury.resolver.FieldResolver.FieldInfoEncodingType.EMBED_TYPES_HASH;
import static org.apache.fury.resolver.FieldResolver.FieldInfoEncodingType.SEPARATE_TYPES_HASH;
import static org.apache.fury.type.TypeUtils.getRawType;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.fury.Fury;
import org.apache.fury.annotation.FuryField;
import org.apache.fury.collection.Tuple2;
import org.apache.fury.exception.ClassNotCompatibleException;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.reflect.FieldAccessor;
import org.apache.fury.reflect.ReflectionUtils;
import org.apache.fury.reflect.TypeRef;
import org.apache.fury.serializer.AbstractObjectSerializer;
import org.apache.fury.serializer.PrimitiveSerializers;
import org.apache.fury.serializer.collection.AbstractCollectionSerializer;
import org.apache.fury.serializer.collection.AbstractMapSerializer;
import org.apache.fury.type.Descriptor;
import org.apache.fury.type.TypeUtils;
import org.apache.fury.util.MurmurHash3;
import org.apache.fury.util.Preconditions;

/**
 * A class field resolver for class compatibility.
 *
 * <p>All fields must write field type, otherwise deserialization can't skip field data if the field
 * is missing. Some final field name and type info will be encoded in 4/8 bytes. Other field info
 * will be encoded separately, whose field type will be written using a varint, field name will be
 * written in 4/8 bytes.
 *
 * <p>Field into encoding: bit 0 for class id embedding. If bit 0 is set, and class id is less than
 * 128, then class id and field name will be written together using a 4/8 byte number value.
 *
 * <ul>
 *   <li>bit `1 0`: class id embedding + field name <= 4 byte.
 *   <li>bit `1 1 0`: class id embedding + field name <= 9 byte.
 *   <li>bit `1 1 1`: class id embedding + field name > 9 byte. (64 - 3 - 7) bits of MurmurHash3 for
 *       field name.
 * </ul>
 *
 * <p>bit `0`: write field name and class info separately.
 *
 * <ul>
 *   <li>bit `0 0` + field name(62 bits MurmurHash3 hash) + fieldType + ref info + n-bytes class
 *       name/id.
 *   <li>bit `0 1` + end tag(62 bits) indicate the object end -> {@link #END_TAG}.
 * </ul>
 *
 * <p>Write an 8-byte tag at last to indicate current object data is finished, on the other hand we
 * can read 8-bytes when reading field info without {@link IndexOutOfBoundsException}. The tag will
 * also be used for validating duplicate field names in class hierarchy if field declared class name
 * are not included in the field info.
 *
 * <p>Since most types are primitives and length of field name are less than 10 bytes, which can be
 * represented using 4/8 bytes, the field info read/write/validation for will be more efficient.
 *
 * <p>Note that the field info is writing as int/long which using little-endian encoding, bits
 * read/write should use little-endian and reverse order too. Using little-endian encoding can also
 * make fury to skip reading 8bytes field name/hash when only read 4-bytes value.
 *
 * <p>The FieldResolver will sort fields based on the encoded int/long field into in ascending
 * order. Type compatible serializers should write field with int encoded field info first, then
 * field with long encoded field info, {@link #END_TAG} at last. When reading, if read encoded field
 * info is less than current field, then it will be a field not exists in current class and can be
 * skipped.
 *
 * @see org.apache.fury.serializer.CompatibleSerializerBase
 */
@SuppressWarnings({"rawtypes", "UnstableApiUsage"})
public class FieldResolver {
  /** Max registered class id for embed in field info. */
  public static final short MAX_EMBED_CLASS_ID = 127;

  public static class FieldTypes {
    public static final byte OBJECT = 0;
    public static final byte COLLECTION_ELEMENT_FINAL = 1;
    public static final byte MAP_KEY_FINAL = 2;
    public static final byte MAP_VALUE_FINAL = 3;
    public static final byte MAP_KV_FINAL = 4;
  }

  public enum FieldInfoEncodingType {
    EMBED_TYPES_4,
    EMBED_TYPES_9,
    EMBED_TYPES_HASH,
    SEPARATE_TYPES_HASH,
  }

  private static final Object STUB = new Object();
  private static final Field STUB_FIELD;

  static {
    try {
      STUB_FIELD = FieldResolver.class.getDeclaredField("STUB");
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  public static class ClassField {

    private final String name;
    private final Class<?> type;
    private final Class<?> declaringClass;
    private final Field field;

    public ClassField(Field field) {
      this(field, field.getName(), field.getType(), field.getDeclaringClass());
    }

    public ClassField(String name, Class<?> type, Class<?> declaringClass) {
      this(STUB_FIELD, name, type, declaringClass);
    }

    public ClassField(Field field, String name, Class<?> type, Class<?> declaringClass) {
      this.name = name;
      this.type = type;
      this.declaringClass = declaringClass;
      this.field = field;
    }

    String getName() {
      return name;
    }

    Class<?> getType() {
      return type;
    }

    Class<?> getDeclaringClass() {
      return declaringClass;
    }

    public Field getField() {
      return field;
    }
  }

  public static final byte EMBED_CLASS_TYPE_FLAG = 0b1;
  public static final byte EMBED_TYPES_4_FLAG = 0b01;
  public static final byte EMBED_TYPES_9_FLAG = 0b011;
  public static final byte EMBED_TYPES_HASH_FLAG = 0b111;
  public static final byte SEPARATE_TYPES_HASH_FLAG = 0b00;
  public static final byte OBJECT_END_FLAG = 0b10;

  /**
   * end tag should be greater than all long encoded fields, so if a field info is less than this
   * value, then there will be more field values for reading.
   */
  private static final long END_TAG = Long.MAX_VALUE & ~0b11L | OBJECT_END_FLAG;

  public static FieldResolver of(Fury fury, Class<?> cls) {
    return of(fury, cls, true, false);
  }

  public static FieldResolver of(
      Fury fury, Class<?> type, boolean resolveParent, boolean ignoreCollectionType) {
    // all fields of class and super classes should be a consistent order between jvm process.
    SortedMap<Field, Descriptor> allFieldsMap =
        fury.getClassResolver().getAllDescriptorsMap(type, resolveParent);
    Set<String> duplicatedFields;
    if (resolveParent) {
      duplicatedFields = Descriptor.getSortedDuplicatedFields(type).keySet();
    } else {
      duplicatedFields = new HashSet<>();
    }
    List<ClassField> allFields =
        allFieldsMap.keySet().stream().map(ClassField::new).collect(Collectors.toList());
    return new FieldResolver(fury, type, ignoreCollectionType, allFields, duplicatedFields);
  }

  private final Class<?> cls;
  private final Fury fury;
  private final RefResolver refResolver;
  private final ClassResolver classResolver;
  private final ClassInfoHolder classInfoHolder;
  private final int numFields;
  private final Set<String> duplicatedFields;
  private final FieldInfo[] embedTypes4Fields;
  private final FieldInfo[] embedTypes9Fields;
  private final FieldInfo[] embedTypesHashFields;
  private final FieldInfo[] separateTypesHashFields;
  private final short minPrimitiveClassId;
  private final short maxPrimitiveClassId;
  private final PrimitiveSerializers.IntSerializer intSerializer;
  private final PrimitiveSerializers.LongSerializer longSerializer;

  public FieldResolver(
      Fury fury,
      Class<?> type,
      boolean ignoreCollectionType,
      List<ClassField> allFields,
      Set<String> duplicatedFields) {
    this.cls = type;
    this.fury = fury;
    this.refResolver = fury.getRefResolver();
    this.classResolver = fury.getClassResolver();
    this.duplicatedFields = duplicatedFields;
    this.numFields = allFields.size();
    this.minPrimitiveClassId =
        classResolver.getRegisteredClassId(TypeUtils.getSortedPrimitiveClasses().get(0));
    this.maxPrimitiveClassId =
        classResolver.getRegisteredClassId(TypeUtils.getSortedPrimitiveClasses().get(8));
    intSerializer = (PrimitiveSerializers.IntSerializer) classResolver.getSerializer(int.class);
    longSerializer = (PrimitiveSerializers.LongSerializer) classResolver.getSerializer(long.class);
    classInfoHolder = classResolver.nilClassInfoHolder();
    // Using `comparingLong` to avoid  overflow in f1.getEncodedFieldInfo() -
    // f2.getEncodedFieldInfo().
    Comparator<FieldInfo> fieldInfoComparator =
        Comparator.comparingLong(FieldInfo::getEncodedFieldInfo);
    SortedSet<FieldInfo> embedTypes4FieldsSet = new TreeSet<>(fieldInfoComparator);
    SortedSet<FieldInfo> embedTypes9FieldsSet = new TreeSet<>(fieldInfoComparator);
    SortedSet<FieldInfo> embedTypesHashFieldsSet = new TreeSet<>(fieldInfoComparator);
    SortedSet<FieldInfo> separateTypesHashFieldsSet = new TreeSet<>(fieldInfoComparator);
    Preconditions.checkState(maxPrimitiveClassId < MAX_EMBED_CLASS_ID);
    for (ClassField classField : allFields) {
      String fieldName = classField.getName();
      Class<?> fieldType = classField.getType();
      if (duplicatedFields.contains(fieldName)) {
        fieldName = classField.getDeclaringClass().getName() + "#" + fieldName;
      }
      int fieldNameLen = encodingBytesLength(fieldName);
      Short classId = classResolver.getRegisteredClassId(fieldType);
      // try to encode 6 bit for a char if field name is ascii.
      // then 7 byte can encode 9 char, remains 2 bits can be used as flag bits or just left.
      if (ReflectionUtils.isMonomorphic(fieldType)
          && classId != null
          && classId < MAX_EMBED_CLASS_ID) {
        if (fieldNameLen <= 3 && classId <= 63) { // at most 4 chars
          // little-endian reversed bits: 24 bits field name + 6 bits class id + bit `1 0`.
          int encodedFieldInfo = (int) encodeFieldNameAsLong(fieldName);
          encodedFieldInfo = encodedFieldInfo << 8 | classId.byteValue() << 2 | EMBED_TYPES_4_FLAG;
          FieldInfo fieldInfo =
              new FieldInfo(
                  fury,
                  fieldName,
                  fieldType,
                  classField.getField(),
                  FieldTypes.OBJECT,
                  EMBED_TYPES_4,
                  encodedFieldInfo,
                  classId);
          embedTypes4FieldsSet.add(fieldInfo);
        } else if (fieldNameLen <= 7) { // at most 9 chars
          // little-endian reversed bits: 54bits field name + 7 bits class id + bit `1 1 0`.
          long encodedFieldInfo = encodeFieldNameAsLong(fieldName);
          encodedFieldInfo = encodedFieldInfo << 10 | (classId << 3) | EMBED_TYPES_9_FLAG;
          FieldInfo fieldInfo =
              new FieldInfo(
                  fury,
                  fieldName,
                  fieldType,
                  classField.getField(),
                  FieldTypes.OBJECT,
                  EMBED_TYPES_9,
                  encodedFieldInfo,
                  classId);
          embedTypes9FieldsSet.add(fieldInfo);
        } else {
          // Truncate 7-bytes of MurmurHash3 128 bits hash.
          // Truncate is OK, see docs in org.apache.commons.codec.digest.MurmurHash3
          // little-endian: bit `1 1 1` + 7 bits class id + 54bits field name hash.
          long encodedFieldInfo = computeStringHash(fieldName);
          encodedFieldInfo = encodedFieldInfo << 10 | classId << 3 | EMBED_TYPES_HASH_FLAG;
          FieldInfo fieldInfo =
              new FieldInfo(
                  fury,
                  fieldName,
                  fieldType,
                  classField.getField(),
                  FieldTypes.OBJECT,
                  EMBED_TYPES_HASH,
                  encodedFieldInfo,
                  classId);
          embedTypesHashFieldsSet.add(fieldInfo);
        }
      } else { // write field name and class info separately.
        // bit `0 0` + field name(62 bits MurmurHash3 hash) + fieldType + ref info + n-bytes class
        // name/id
        long encodedFieldInfo = computeStringHash(fieldName) << 2;
        FieldInfo fieldInfo =
            FieldInfo.of(
                fury,
                fieldName,
                fieldType,
                classField.getField(),
                SEPARATE_TYPES_HASH,
                encodedFieldInfo,
                ignoreCollectionType);
        separateTypesHashFieldsSet.add(fieldInfo);
      }
    }
    embedTypes4Fields = embedTypes4FieldsSet.toArray(new FieldInfo[0]);
    embedTypes9Fields = embedTypes9FieldsSet.toArray(new FieldInfo[0]);
    embedTypesHashFields = embedTypesHashFieldsSet.toArray(new FieldInfo[0]);
    separateTypesHashFields = separateTypesHashFieldsSet.toArray(new FieldInfo[0]);
    Preconditions.checkArgument(
        embedTypes4Fields.length
                + embedTypes9Fields.length
                + embedTypesHashFields.length
                + separateTypesHashFields.length
            == allFields.size());
  }

  public boolean hasDuplicatedFields() {
    return !duplicatedFields.isEmpty();
  }

  /** Encode every char using 6 bits. Every char should be english alphabet and digit only. */
  static long encodeFieldNameAsLong(String fieldName) {
    long fieldNameEncoded = 0;
    for (int i = 0; i < fieldName.length(); i++) {
      char c = fieldName.charAt(i);
      if (c >= '0' && c <= '9') {
        fieldNameEncoded = fieldNameEncoded << 6 | ((c - '0') & 0b00111111);
      } else if (c >= 'A' && c <= 'Z') {
        fieldNameEncoded = fieldNameEncoded << 6 | ((c - 'A' + 10) & 0b00111111);
      } else {
        Preconditions.checkArgument(c >= 'a' && c <= 'z', "%s should b in range a~z", c);
        fieldNameEncoded = fieldNameEncoded << 6 | ((c - 'a' + 10 + 26) & 0b00111111);
      }
    }
    return fieldNameEncoded;
  }

  static String decodeLongAsString(long encodedStr, int numBits) {
    StringBuilder stringBuilder = new StringBuilder();
    for (int i = 0; i < numBits; i += 6) {
      byte x = (byte) (encodedStr & 0b00111111);
      if (x < 10) {
        stringBuilder.append((char) ('0' + x));
      } else if (x < 36) {
        stringBuilder.append((char) ('A' + x - 10));
      } else {
        Preconditions.checkArgument(x < 62);
        stringBuilder.append((char) ('a' + x - 10 - 26));
      }
      encodedStr >>>= 6;
    }
    return stringBuilder.reverse().toString();
  }

  /**
   * If <code>fieldName</code> contains english alphabet and digits only, then every char can be
   * encoded using 6 bits. In this way 3 byte can encode 4 chars, and 7 bytes can encode 9 chars.
   */
  static int encodingBytesLength(String fieldName) {
    Preconditions.checkArgument(fieldName.length() > 0);
    for (int i = 0; i < fieldName.length(); i++) {
      char c = fieldName.charAt(i);
      if (c < 48) {
        return 8;
      }
      if (c > 57 && c < 65) {
        return 8;
      }
      if (c > 90 && c < 97) {
        return 8;
      }
      if (c > 122) {
        return 8;
      }
    }
    // Every char range: 10 + 26 * 2 = 62, which can be represented by 6 bits(0~63(0b111111))
    return (int) Math.ceil(fieldName.length() * 6.0 / 8);
  }

  static long computeStringHash(String str) {
    // Use a positive number as the seed: https://github.com/google/guava/issues/3493
    // Hashing.murmur3_128(47).hashString(str, StandardCharsets.UTF_8).asLong();
    // MurmurHash3.murmurhash3_x64_128 is faster than guava hash.
    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
    return MurmurHash3.murmurhash3_x64_128(bytes, 0, bytes.length, 47)[0];
  }

  /**
   * Skip field data and return true if object data ends.
   *
   * @return true if object ends.
   */
  public long skipDataBy4(MemoryBuffer buffer, int partFieldInfo) {
    if ((partFieldInfo & 0b1) == EMBED_CLASS_TYPE_FLAG) {
      // write class id and field name together.
      byte classId;
      if ((partFieldInfo & 0b11)
          == EMBED_TYPES_4_FLAG) { // class id embedding + field name <= 4 byte.
        classId = (byte) ((partFieldInfo & 0xff) >> 2);
      } else {
        // bit `1 1 0`: class id embedding + field name <= 9 byte.
        // bit `1 1 1`: class id embedding + field name > 9 byte. (64 - 3 - 7) bits of MurmurHash3
        // for field name.
        classId = (byte) ((partFieldInfo & 0b1111111111) >>> 3);
        buffer.increaseReaderIndex(4);
      }
      ClassInfo classInfo = classResolver.getClassInfo(classId);
      if (classId >= minPrimitiveClassId && classId <= maxPrimitiveClassId) {
        fury.readData(buffer, classInfo);
      } else {
        fury.readRef(buffer, classInfo.getSerializer());
      }
    } else {
      long encodedFieldInfo = buffer.readInt32();
      encodedFieldInfo = encodedFieldInfo << 32 | (partFieldInfo & 0x00000000ffffffffL);
      if ((encodedFieldInfo & 0b11) == SEPARATE_TYPES_HASH_FLAG) {
        // bit `0 0` + field name(62 bits MurmurHash3 hash) + field type + ref + n-bytes class
        // name/id.
        skipObjectField(buffer);
      } else {
        // bit `0 1` + end tag(62 bits) indicate the object end.
        if (encodedFieldInfo != END_TAG) {
          throw new ClassNotCompatibleException(
              String.format(
                  "Class %s end tag should be %d but got %d, maybe peer has different duplicate fields in "
                      + "class hierarchy",
                  cls, END_TAG, encodedFieldInfo));
        }
        return END_TAG;
      }
    }
    return partFieldInfo;
  }

  /**
   * Skip field data and return true if object data ends.
   *
   * @return true if object ends.
   */
  public long skipDataBy8(MemoryBuffer buffer, long partFieldInfo) {
    if ((partFieldInfo & 0b1) == EMBED_CLASS_TYPE_FLAG) {
      // write class id and field name together.
      byte classId;
      if ((partFieldInfo & 0b11)
          == EMBED_TYPES_4_FLAG) { // class id embedding + field name <= 4 byte.
        classId = (byte) ((partFieldInfo & 0xff) >> 2);
        buffer.increaseReaderIndex(-4);
      } else {
        // bit `1 1 0`: class id embedding + field name <= 9 byte.
        // bit `1 1 1`: class id embedding + field name > 9 byte. (64 - 3 - 7) bits of MurmurHash3
        // for field name.
        classId = (byte) ((partFieldInfo & 0b1111111111) >>> 3);
      }
      ClassInfo classInfo = classResolver.getClassInfo(classId);
      if (classId >= minPrimitiveClassId && classId <= maxPrimitiveClassId) {
        if (classId == ClassResolver.PRIMITIVE_INT_CLASS_ID) {
          intSerializer.read(buffer);
        } else if (classId == PRIMITIVE_LONG_CLASS_ID) {
          longSerializer.read(buffer);
        } else {
          fury.readData(buffer, classInfo);
        }
      } else {
        fury.readRef(buffer, classInfo.getSerializer());
      }
    } else {
      if ((partFieldInfo & 0b11) == SEPARATE_TYPES_HASH_FLAG) {
        skipObjectField(buffer);
      } else {
        // bit `0 1` + end tag(62 bits) indicate the object end.
        if (partFieldInfo != END_TAG) {
          throw new ClassNotCompatibleException(
              String.format(
                  "Class %s end tag should be %d but got %d, maybe peer has different duplicate fields in "
                      + "class hierarchy",
                  cls, END_TAG, partFieldInfo));
        }
        return END_TAG;
      }
    }
    return partFieldInfo;
  }

  public void skipObjectField(MemoryBuffer buffer) {
    int nextReadRefId = refResolver.tryPreserveRefId(buffer);
    if (nextReadRefId >= Fury.NOT_NULL_VALUE_FLAG) {
      byte fieldType = buffer.readByte();
      Object o;
      if (fieldType == FieldTypes.OBJECT) {
        ClassInfo classInfo = classResolver.readClassInfo(buffer, classInfoHolder);
        o = fury.readData(buffer, classInfo);
      } else {
        o = readObjectWithFinal(buffer, fieldType);
      }
      refResolver.setReadObject(nextReadRefId, o);
    }
  }

  public void skipEndFields(MemoryBuffer buffer, long partFieldInfo) {
    long endTag = getEndTag();
    while (partFieldInfo < endTag) {
      if (skipDataBy8(buffer, partFieldInfo) != partFieldInfo) {
        return;
      }
      partFieldInfo = buffer.readInt64();
    }
    if (partFieldInfo != endTag) {
      throw new IllegalStateException(
          String.format("Object should end with %d but got %d.", endTag, partFieldInfo));
    }
  }

  public void checkFieldType(byte fieldType, byte expectType) {
    if (fieldType != expectType) {
      throw new IllegalArgumentException(
          String.format("Expect byte type %d but got %d.", expectType, fieldType));
    }
  }

  public Object readObjectField(MemoryBuffer buffer, FieldInfo fieldInfo) {
    int nextReadRefId = refResolver.tryPreserveRefId(buffer);
    if (nextReadRefId >= Fury.NOT_NULL_VALUE_FLAG) {
      byte fieldType = buffer.readByte();
      checkFieldType(fieldType, fieldInfo.fieldType);
      Object o;
      if (fieldType == FieldTypes.OBJECT) {
        o =
            fury.readData(
                buffer, classResolver.readClassInfo(buffer, fieldInfo.getClassInfoHolder()));
      } else {
        o = readObjectWithFinal(buffer, fieldType, fieldInfo);
      }
      refResolver.setReadObject(nextReadRefId, o);
      return o;
    } else {
      return refResolver.getReadObject();
    }
  }

  private Object readObjectWithFinal(MemoryBuffer buffer, byte fieldType) {
    Object o;
    if (fieldType == FieldTypes.COLLECTION_ELEMENT_FINAL) {
      ClassInfo elementClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      AbstractCollectionSerializer collectionSerializer =
          (AbstractCollectionSerializer) classInfo.getSerializer();
      try {
        collectionSerializer.setElementSerializer(elementClassInfo.getSerializer());
        o = collectionSerializer.read(buffer);
      } finally {
        // Some collectionSerializer may overwrite write/read method, then clear element serializer
        // may not got invoked.
        collectionSerializer.setElementSerializer(null);
      }
    } else if (fieldType == FieldTypes.MAP_KV_FINAL) {
      ClassInfo keyClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo valueClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      AbstractMapSerializer mapSerializer = (AbstractMapSerializer) classInfo.getSerializer();
      try {
        mapSerializer.setKeySerializer(keyClassInfo.getSerializer());
        mapSerializer.setValueSerializer(valueClassInfo.getSerializer());
        o = mapSerializer.read(buffer);
      } finally {
        // Some mmapSerializer may overwrite write/read method, then clear serializer
        // may not got invoked.
        mapSerializer.setKeySerializer(null);
        mapSerializer.setValueSerializer(null);
      }
    } else if (fieldType == FieldTypes.MAP_KEY_FINAL) {
      ClassInfo keyClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      AbstractMapSerializer mapSerializer = (AbstractMapSerializer) classInfo.getSerializer();
      try {
        mapSerializer.setKeySerializer(keyClassInfo.getSerializer());
        o = mapSerializer.read(buffer);
      } finally {
        // Some mmapSerializer may overwrite write/read method, then clear serializer
        // may not got invoked.
        mapSerializer.setKeySerializer(null);
      }
    } else {
      Preconditions.checkArgument(fieldType == FieldTypes.MAP_VALUE_FINAL);
      ClassInfo valueClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      AbstractMapSerializer mapSerializer = (AbstractMapSerializer) classInfo.getSerializer();
      try {
        mapSerializer.setValueSerializer(valueClassInfo.getSerializer());
        o = mapSerializer.read(buffer);
      } finally {
        // Some mmapSerializer may overwrite write/read method, then clear serializer
        // may not got invoked.
        mapSerializer.setValueSerializer(null);
      }
    }
    return o;
  }

  private Object readObjectWithFinal(MemoryBuffer buffer, byte fieldType, FieldInfo fieldInfo) {
    Object o;
    if (fieldType == FieldTypes.COLLECTION_ELEMENT_FINAL) {
      ClassInfo elementClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, fieldInfo.getClassInfoHolder());
      AbstractCollectionSerializer collectionSerializer =
          (AbstractCollectionSerializer) classInfo.getSerializer();
      try {
        collectionSerializer.setElementSerializer(elementClassInfo.getSerializer());
        o = collectionSerializer.read(buffer);
        // Some collectionSerializer may overwrite write/read method, then clear element serializer
        // may not got invoked.
      } finally {
        collectionSerializer.setElementSerializer(null);
      }
    } else if (fieldType == FieldTypes.MAP_KV_FINAL) {
      ClassInfo keyClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo valueClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, fieldInfo.getClassInfoHolder());
      AbstractMapSerializer mapSerializer = (AbstractMapSerializer) classInfo.getSerializer();
      try {
        mapSerializer.setKeySerializer(keyClassInfo.getSerializer());
        mapSerializer.setValueSerializer(valueClassInfo.getSerializer());
        o = mapSerializer.read(buffer);
      } finally {
        // Some mmapSerializer may overwrite write/read method, then clear serializer
        // may not got invoked.
        mapSerializer.setKeySerializer(null);
        mapSerializer.setValueSerializer(null);
      }
    } else if (fieldType == FieldTypes.MAP_KEY_FINAL) {
      ClassInfo keyClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, fieldInfo.getClassInfoHolder());
      AbstractMapSerializer mapSerializer = (AbstractMapSerializer) classInfo.getSerializer();
      try {
        mapSerializer.setKeySerializer(keyClassInfo.getSerializer());
        o = mapSerializer.read(buffer);
        // Some mmapSerializer may overwrite write/read method, then clear serializer
        // may not got invoked.
      } finally {
        mapSerializer.setKeySerializer(null);
      }
    } else {
      Preconditions.checkArgument(fieldType == FieldTypes.MAP_VALUE_FINAL);
      ClassInfo valueClassInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      ClassInfo classInfo = classResolver.readClassInfo(buffer, fieldInfo.getClassInfoHolder());
      AbstractMapSerializer mapSerializer = (AbstractMapSerializer) classInfo.getSerializer();
      try {
        mapSerializer.setValueSerializer(valueClassInfo.getSerializer());
        o = mapSerializer.read(buffer);
        // Some mmapSerializer may overwrite write/read method, then clear serializer
        // may not got invoked.
      } finally {
        mapSerializer.setValueSerializer(null);
      }
    }
    return o;
  }

  public long getEndTag() {
    return END_TAG;
  }

  public FieldInfo[] getEmbedTypes4Fields() {
    return embedTypes4Fields;
  }

  public FieldInfo[] getEmbedTypes9Fields() {
    return embedTypes9Fields;
  }

  public FieldInfo[] getEmbedTypesHashFields() {
    return embedTypesHashFields;
  }

  public FieldInfo[] getSeparateTypesHashFields() {
    return separateTypesHashFields;
  }

  public int getNumFields() {
    return numFields;
  }

  public List<FieldInfo> getAllFieldsList() {
    List<FieldInfo> fieldInfoList = new ArrayList<>();
    Collections.addAll(fieldInfoList, embedTypes4Fields);
    Collections.addAll(fieldInfoList, embedTypes9Fields);
    Collections.addAll(fieldInfoList, embedTypesHashFields);
    Collections.addAll(fieldInfoList, separateTypesHashFields);
    return fieldInfoList;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{\n"
        + "class: "
        + cls
        + ", classLoader: "
        + cls.getClassLoader()
        + ",\n"
        + "embedTypes4Fields: "
        + Arrays.toString(embedTypes4Fields)
        + ",\n"
        + "embedTypes9Fields: "
        + Arrays.toString(embedTypes9Fields)
        + ",\n"
        + "embedTypesHashFields: "
        + Arrays.toString(embedTypesHashFields)
        + ",\n"
        + "separateTypesHashFields: "
        + Arrays.toString(separateTypesHashFields)
        + "\n"
        + '}';
  }

  public static class FieldInfo {
    private final String name;
    private final Class<?> type;
    private final Field field;
    private final byte fieldType;
    private final FieldInfoEncodingType fieldInfoEncodingType;
    private final short classId;
    private final long encodedFieldInfo;
    protected final ClassResolver classResolver;
    private final FieldAccessor fieldAccessor;
    private final ClassInfoHolder classInfoHolder;
    private final AbstractObjectSerializer.FuryFieldAnnotationInfo furyFieldAnnotationInfo;

    public FieldInfo(
        Fury fury,
        String name,
        Class<?> type,
        Field field,
        byte fieldType,
        FieldInfoEncodingType fieldInfoEncodingType,
        long encodedFieldInfo,
        short classId) {
      this.name = name;
      this.type = type;
      this.field = field;
      this.fieldType = fieldType;
      this.fieldInfoEncodingType = fieldInfoEncodingType;
      this.encodedFieldInfo = encodedFieldInfo;
      this.classId = classId;
      this.classResolver = fury.getClassResolver();
      this.classInfoHolder = classResolver.nilClassInfoHolder();
      if (field == null || field == STUB_FIELD) {
        fieldAccessor = null;
      } else {
        fieldAccessor = FieldAccessor.createAccessor(field);
      }
      this.furyFieldAnnotationInfo =
          new AbstractObjectSerializer.FuryFieldAnnotationInfo(
              field == null ? null : field.getAnnotation(FuryField.class));
    }

    public static FieldInfo of(
        Fury fury,
        String fieldName,
        Class<?> fieldTypeClass,
        Field field,
        FieldInfoEncodingType fieldInfoEncodingType,
        long encodedFieldInfo,
        boolean ignoreCollectionType) {
      if (ignoreCollectionType) {
        return new FieldInfo(
            fury,
            fieldName,
            fieldTypeClass,
            field,
            FieldTypes.OBJECT,
            fieldInfoEncodingType,
            encodedFieldInfo,
            NO_CLASS_ID);
      }
      if (Collection.class.isAssignableFrom(field.getType())) {
        TypeRef<?> elementTypeRef = TypeUtils.getElementType(TypeRef.of(field.getGenericType()));
        byte fieldType =
            ReflectionUtils.isMonomorphic(getRawType(elementTypeRef))
                ? FieldTypes.COLLECTION_ELEMENT_FINAL
                : FieldTypes.OBJECT;
        return new CollectionFieldInfo(
            fury, field, fieldType, fieldInfoEncodingType, encodedFieldInfo, elementTypeRef);
      } else if (Map.class.isAssignableFrom(field.getType())) {
        Tuple2<TypeRef<?>, TypeRef<?>> kvType =
            TypeUtils.getMapKeyValueType(TypeRef.of(field.getGenericType()));
        TypeRef<?> keyTypeRef = kvType.f0;
        TypeRef<?> valueTypeRef = kvType.f1;
        byte fieldType;
        if (ReflectionUtils.isMonomorphic(getRawType(keyTypeRef))
            && ReflectionUtils.isMonomorphic(getRawType(valueTypeRef))) {
          fieldType = FieldTypes.MAP_KV_FINAL;
        } else if (ReflectionUtils.isMonomorphic(getRawType(keyTypeRef))) {
          fieldType = FieldTypes.MAP_KEY_FINAL;
        } else if (ReflectionUtils.isMonomorphic(getRawType(valueTypeRef))) {
          fieldType = FieldTypes.MAP_VALUE_FINAL;
        } else {
          fieldType = FieldTypes.OBJECT;
        }
        return new MapFieldInfo(
            fury,
            field,
            fieldType,
            fieldInfoEncodingType,
            encodedFieldInfo,
            keyTypeRef,
            valueTypeRef);
      } else {
        return new FieldInfo(
            fury,
            fieldName,
            fieldTypeClass,
            field,
            FieldTypes.OBJECT,
            fieldInfoEncodingType,
            encodedFieldInfo,
            NO_CLASS_ID);
      }
    }

    public AbstractObjectSerializer.FuryFieldAnnotationInfo getFuryFieldAnnotationInfo() {
      return furyFieldAnnotationInfo;
    }

    public String getName() {
      return name;
    }

    public Class<?> getType() {
      return type;
    }

    public Field getField() {
      return field;
    }

    public byte getFieldType() {
      return fieldType;
    }

    public FieldInfoEncodingType getFieldInfoEncodingType() {
      return fieldInfoEncodingType;
    }

    public long getEncodedFieldInfo() {
      return encodedFieldInfo;
    }

    public FieldAccessor getFieldAccessor() {
      return fieldAccessor;
    }

    public ClassInfoHolder getClassInfoHolder() {
      return classInfoHolder;
    }

    public ClassInfo getClassInfo(Class<?> cls) {
      return classResolver.getClassInfo(cls, this.classInfoHolder);
    }

    public ClassInfo getClassInfo(short classId) {
      ClassInfo classInfo = this.classInfoHolder.classInfo;
      if (classInfo.classId == NO_CLASS_ID) {
        Preconditions.checkArgument(classId != NO_CLASS_ID);
        this.classInfoHolder.classInfo = classInfo = classResolver.getClassInfo(classId);
      }
      return classInfo;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName()
          + "{"
          + "name="
          + name
          + ", type="
          + type
          + ", encodedFieldInfo="
          + encodedFieldInfo
          + '}';
    }

    /**
     * Returns registered class id if class id is embedded in the encoded field info, otherwise
     * return 0.
     */
    public short getEmbeddedClassId() {
      return classId;
    }
  }

  public static class CollectionFieldInfo extends FieldInfo {
    // TODO support nested generics.
    private final TypeRef<?> elementTypeToken;
    private final Class<?> elementType;
    private final ClassInfoHolder elementClassInfoHolder;

    public CollectionFieldInfo(
        Fury fury,
        Field field,
        byte fieldType,
        FieldInfoEncodingType fieldInfoEncodingType,
        long encodedFieldInfo,
        TypeRef<?> elementTypeRef) {
      super(
          fury,
          field.getName(),
          field.getType(),
          field,
          fieldType,
          fieldInfoEncodingType,
          encodedFieldInfo,
          NO_CLASS_ID);
      Preconditions.checkArgument(field != STUB_FIELD);
      this.elementTypeToken = elementTypeRef;
      this.elementType = getRawType(elementTypeRef);
      elementClassInfoHolder = classResolver.nilClassInfoHolder();
    }

    public ClassInfo getElementClassInfo() {
      return getElementClassInfo(elementType);
    }

    public ClassInfo getElementClassInfo(Class<?> elementType) {
      return classResolver.getClassInfo(elementType, elementClassInfoHolder);
    }

    public TypeRef<?> getElementTypeToken() {
      return elementTypeToken;
    }

    public Class<?> getElementType() {
      return elementType;
    }
  }

  public static class MapFieldInfo extends FieldInfo {
    private final Class<?> keyType;
    private final boolean isKeyTypeFinal;
    // TODO support nested generics.
    private final TypeRef<?> keyTypeToken;
    private final TypeRef<?> valueTypeToken;
    private final ClassInfoHolder keyClassInfoHolder;
    private final Class<?> valueType;
    private final boolean isValueTypeFinal;
    private final ClassInfoHolder valueClassInfoHolder;

    public MapFieldInfo(
        Fury fury,
        Field field,
        byte fieldType,
        FieldInfoEncodingType separateTypesHash,
        long encodedFieldInfo,
        TypeRef<?> keyTypeRef,
        TypeRef<?> valueTypeRef) {
      super(
          fury,
          field.getName(),
          field.getType(),
          field,
          fieldType,
          separateTypesHash,
          encodedFieldInfo,
          NO_CLASS_ID);
      Preconditions.checkArgument(field != STUB_FIELD);
      this.keyTypeToken = keyTypeRef;
      this.valueTypeToken = valueTypeRef;
      keyType = getRawType(keyTypeRef);
      isKeyTypeFinal = ReflectionUtils.isMonomorphic(keyType);
      keyClassInfoHolder = classResolver.nilClassInfoHolder();
      valueType = getRawType(valueTypeRef);
      isValueTypeFinal = ReflectionUtils.isMonomorphic(valueType);
      valueClassInfoHolder = classResolver.nilClassInfoHolder();
    }

    public boolean isKeyTypeFinal() {
      return isKeyTypeFinal;
    }

    public boolean isValueTypeFinal() {
      return isValueTypeFinal;
    }

    public ClassInfo getKeyClassInfo() {
      return getKeyClassInfo(keyType);
    }

    public ClassInfo getKeyClassInfo(Class<?> keyType) {
      return classResolver.getClassInfo(keyType, keyClassInfoHolder);
    }

    public ClassInfo getValueClassInfo() {
      return getValueClassInfo(valueType);
    }

    public ClassInfo getValueClassInfo(Class<?> valueType) {
      return classResolver.getClassInfo(valueType, valueClassInfoHolder);
    }

    public Class<?> getKeyType() {
      return keyType;
    }

    public Class<?> getValueType() {
      return valueType;
    }
  }

  public static void main(String[] args) {
    System.out.println(computeStringHash("list0") << 2);
    System.out.println(computeStringHash("serializeListLast") << 2);
  }
}
