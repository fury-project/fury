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

package org.apache.fury.serializer;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fury.Fury;
import org.apache.fury.annotation.FieldInfo;
import org.apache.fury.builder.MetaSharedCodecBuilder;
import org.apache.fury.collection.Tuple2;
import org.apache.fury.collection.Tuple3;
import org.apache.fury.config.CompatibleMode;
import org.apache.fury.config.FuryBuilder;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.memory.Platform;
import org.apache.fury.meta.ClassDef;
import org.apache.fury.reflect.FieldAccessor;
import org.apache.fury.resolver.ClassInfoHolder;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.resolver.RefResolver;
import org.apache.fury.type.Descriptor;
import org.apache.fury.type.DescriptorGrouper;
import org.apache.fury.type.Generics;
import org.apache.fury.util.Preconditions;
import org.apache.fury.util.record.RecordInfo;
import org.apache.fury.util.record.RecordUtils;

/**
 * A meta-shared compatible deserializer builder based on {@link ClassDef}. This serializer will
 * compare fields between {@link ClassDef} and class fields, then create serializer to read and
 * set/skip corresponding fields to support type forward/backward compatibility. Serializer are
 * forward to {@link ObjectSerializer} for now. We can consolidate fields between peers to create
 * better serializers to serialize common fields between peers for efficiency.
 *
 * <p>With meta context share enabled and compatible mode, the {@link ObjectSerializer} will take
 * all non-inner final types as non-final, so that fury can write class definition when write class
 * info for those types.
 *
 * @see CompatibleMode
 * @see FuryBuilder#withMetaShare
 * @see MetaSharedCodecBuilder
 * @see ObjectSerializer
 */
@SuppressWarnings({"unchecked"})
public class MetaSharedSerializer<T> extends AbstractObjectSerializer<T> {
  private final ObjectSerializer.FinalTypeField[] finalFields;

  /**
   * Whether write class def for non-inner final types.
   *
   * @see ClassResolver#isMonomorphic(Class)
   */
  private final boolean[] isFinal;

  private final ObjectSerializer.GenericTypeField[] otherFields;
  private final ObjectSerializer.GenericTypeField[] containerFields;
  private final RecordInfo recordInfo;
  private Serializer<T> serializer;
  private final ClassInfoHolder classInfoHolder;

  public MetaSharedSerializer(Fury fury, Class<T> type, ClassDef classDef) {
    super(fury, type);
    Preconditions.checkArgument(
        !fury.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    Preconditions.checkArgument(
        fury.getConfig().isMetaShareEnabled(), "Meta share must be enabled.");
    Collection<Descriptor> descriptors = consolidateFields(this.classResolver, type, classDef);
    DescriptorGrouper descriptorGrouper =
        DescriptorGrouper.createDescriptorGrouper(
            this.classResolver::isMonomorphic,
            descriptors,
            false,
            fury.compressInt(),
            fury.getConfig().compressLong());
    // d.getField() may be null if not exists in this class when meta share enabled.
    Tuple3<
            Tuple2<ObjectSerializer.FinalTypeField[], boolean[]>,
            ObjectSerializer.GenericTypeField[],
            ObjectSerializer.GenericTypeField[]>
        infos = AbstractObjectSerializer.buildFieldInfos(fury, descriptorGrouper);
    finalFields = infos.f0.f0;
    isFinal = infos.f0.f1;
    otherFields = infos.f1;
    containerFields = infos.f2;
    classInfoHolder = this.classResolver.nilClassInfoHolder();
    if (isRecord) {
      List<String> fieldNames =
          descriptorGrouper.getSortedDescriptors().stream()
              .map(Descriptor::getName)
              .collect(Collectors.toList());
      recordInfo = new RecordInfo(type, fieldNames);
    } else {
      recordInfo = null;
    }
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    if (serializer == null) {
      serializer =
          this.classResolver.createSerializerSafe(type, () -> new ObjectSerializer<>(fury, type));
    }
    serializer.write(buffer, value);
  }

  @Override
  public T read(MemoryBuffer buffer) {
    if (isRecord) {
      Object[] fieldValues =
          new Object[finalFields.length + otherFields.length + containerFields.length];
      readFields(buffer, fieldValues);
      fieldValues = RecordUtils.remapping(recordInfo, fieldValues);
      try {
        T t = (T) constructor.invokeWithArguments(fieldValues);
        Arrays.fill(recordInfo.getRecordComponents(), null);
        return t;
      } catch (Throwable e) {
        Platform.throwException(e);
      }
    }
    T obj = newBean();
    Fury fury = this.fury;
    RefResolver refResolver = this.refResolver;
    ClassResolver classResolver = this.classResolver;
    refResolver.reference(obj);
    // read order: primitive,boxed,final,other,collection,map
    ObjectSerializer.FinalTypeField[] finalFields = this.finalFields;
    for (int i = 0; i < finalFields.length; i++) {
      ObjectSerializer.FinalTypeField fieldInfo = finalFields[i];
      FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
      boolean isFinal = this.isFinal[i];
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        short classId = fieldInfo.classId;
        if (ObjectSerializer.readPrimitiveFieldValueFailed(
                fury, buffer, obj, fieldAccessor, classId, fieldInfoAnnotation)
            && ObjectSerializer.readBasicObjectFieldValueFailed(
                fury, buffer, obj, fieldAccessor, classId, fieldInfoAnnotation)) {
          assert fieldInfo.classInfo != null;
          Object fieldValue =
              ObjectSerializer.readFinalObjectFieldValue(
                  fury,
                  refResolver,
                  classResolver,
                  fieldInfo,
                  isFinal,
                  buffer,
                  fieldInfoAnnotation);
          fieldAccessor.putObject(obj, fieldValue);
        }
      } else {
        if (skipPrimitiveFieldValueFailed(fury, fieldInfo.classId, buffer)) {
          if (fieldInfo.classInfo == null) {
            // TODO(chaokunyang) support registered serializer in peer with ref tracking disabled.
            fury.readRef(buffer, classInfoHolder);
          } else {
            ObjectSerializer.readFinalObjectFieldValue(
                fury, refResolver, classResolver, fieldInfo, isFinal, buffer, fieldInfoAnnotation);
          }
        }
      }
    }
    for (ObjectSerializer.GenericTypeField fieldInfo : otherFields) {
      FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
      Object fieldValue =
          ObjectSerializer.readOtherFieldValue(fury, fieldInfo, buffer, fieldInfoAnnotation);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    Generics generics = fury.getGenerics();
    for (ObjectSerializer.GenericTypeField fieldInfo : containerFields) {
      FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
      Object fieldValue =
          ObjectSerializer.readContainerFieldValue(
              fury, generics, fieldInfo, buffer, fieldInfoAnnotation);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    return obj;
  }

  private void readFields(MemoryBuffer buffer, Object[] fields) {
    int counter = 0;
    Fury fury = this.fury;
    RefResolver refResolver = this.refResolver;
    ClassResolver classResolver = this.classResolver;
    // read order: primitive,boxed,final,other,collection,map
    ObjectSerializer.FinalTypeField[] finalFields = this.finalFields;
    for (int i = 0; i < finalFields.length; i++) {
      ObjectSerializer.FinalTypeField fieldInfo = finalFields[i];
      FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
      boolean isFinal = this.isFinal[i];
      if (fieldInfo.fieldAccessor != null) {
        assert fieldInfo.classInfo != null;
        short classId = fieldInfo.classId;
        // primitive field won't write null flag.
        if (classId >= ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID
            && classId <= ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID) {
          fields[counter++] = Serializers.readPrimitiveValue(fury, buffer, classId);
        } else {
          Object fieldValue =
              ObjectSerializer.readFinalObjectFieldValue(
                  fury,
                  refResolver,
                  classResolver,
                  fieldInfo,
                  isFinal,
                  buffer,
                  fieldInfoAnnotation);
          fields[counter++] = fieldValue;
        }
      } else {
        if (skipPrimitiveFieldValueFailed(fury, fieldInfo.classId, buffer)) {
          if (fieldInfo.classInfo == null) {
            // TODO(chaokunyang) support registered serializer in peer with ref tracking disabled.
            fury.readRef(buffer, classInfoHolder);
          } else {
            ObjectSerializer.readFinalObjectFieldValue(
                fury, refResolver, classResolver, fieldInfo, isFinal, buffer, fieldInfoAnnotation);
          }
        }
        fields[counter++] = null;
      }
    }
    for (ObjectSerializer.GenericTypeField fieldInfo : otherFields) {
      FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
      Object fieldValue =
          ObjectSerializer.readOtherFieldValue(fury, fieldInfo, buffer, fieldInfoAnnotation);
      fields[counter++] = fieldValue;
    }
    Generics generics = fury.getGenerics();
    for (ObjectSerializer.GenericTypeField fieldInfo : containerFields) {
      FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
      Object fieldValue =
          ObjectSerializer.readContainerFieldValue(
              fury, generics, fieldInfo, buffer, fieldInfoAnnotation);
      fields[counter++] = fieldValue;
    }
  }

  /** Skip primitive primitive field value since it doesn't write null flag. */
  static boolean skipPrimitiveFieldValueFailed(Fury fury, short classId, MemoryBuffer buffer) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        buffer.increaseReaderIndex(1);
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        buffer.increaseReaderIndex(1);
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        buffer.increaseReaderIndex(2);
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        buffer.increaseReaderIndex(2);
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fury.compressInt()) {
          buffer.readVarInt32();
        } else {
          buffer.increaseReaderIndex(4);
        }
        return false;
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        buffer.increaseReaderIndex(4);
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        fury.readInt64(buffer);
        return false;
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        buffer.increaseReaderIndex(8);
        return false;
      default:
        {
          return true;
        }
    }
  }

  public static Collection<Descriptor> consolidateFields(
      ClassResolver classResolver, Class<?> cls, ClassDef classDef) {
    return classDef.getDescriptors(classResolver, cls);
  }
}
