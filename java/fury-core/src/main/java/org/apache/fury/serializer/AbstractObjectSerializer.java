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

import static org.apache.fury.type.DescriptorGrouper.createDescriptorGrouper;
import static org.apache.fury.type.TypeUtils.getRawType;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fury.Fury;
import org.apache.fury.annotation.FieldInfo;
import org.apache.fury.collection.Tuple2;
import org.apache.fury.collection.Tuple3;
import org.apache.fury.memory.Platform;
import org.apache.fury.reflect.FieldAccessor;
import org.apache.fury.reflect.ReflectionUtils;
import org.apache.fury.reflect.TypeRef;
import org.apache.fury.resolver.ClassInfo;
import org.apache.fury.resolver.ClassInfoHolder;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.resolver.RefResolver;
import org.apache.fury.type.Descriptor;
import org.apache.fury.type.DescriptorGrouper;
import org.apache.fury.type.FinalObjectTypeStub;
import org.apache.fury.type.GenericType;
import org.apache.fury.util.record.RecordComponent;
import org.apache.fury.util.record.RecordInfo;
import org.apache.fury.util.record.RecordUtils;

public abstract class AbstractObjectSerializer<T> extends Serializer<T> {
  protected final RefResolver refResolver;
  protected final ClassResolver classResolver;
  protected final boolean isRecord;
  protected final MethodHandle constructor;
  private InternalFieldInfo[] fieldInfos;
  private RecordInfo copyRecordInfo;

  public AbstractObjectSerializer(Fury fury, Class<T> type) {
    this(
        fury,
        type,
        RecordUtils.isRecord(type)
            ? RecordUtils.getRecordConstructor(type).f1
            : ReflectionUtils.getCtrHandle(type, false));
  }

  public AbstractObjectSerializer(Fury fury, Class<T> type, MethodHandle constructor) {
    super(fury, type);
    this.refResolver = fury.getRefResolver();
    this.classResolver = fury.getClassResolver();
    this.isRecord = RecordUtils.isRecord(type);
    this.constructor = constructor;
  }

  @Override
  public T copy(T originObj) {
    if (immutable) {
      return originObj;
    }
    if (isRecord) {
      return copyRecord(originObj);
    }
    T newObj = newBean();
    if (needToCopyRef) {
      fury.reference(originObj, newObj);
    }
    copyFields(originObj, newObj);
    return newObj;
  }

  private T copyRecord(T originObj) {
    Object[] fieldValues = copyFields(originObj);
    try {
      T t = (T) constructor.invokeWithArguments(fieldValues);
      Arrays.fill(copyRecordInfo.getRecordComponents(), null);
      fury.reference(originObj, t);
      return t;
    } catch (Throwable e) {
      Platform.throwException(e);
    }
    return originObj;
  }

  private Object[] copyFields(T originObj) {
    InternalFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    Object[] fieldValues = new Object[fieldInfos.length];
    for (int i = 0; i < fieldInfos.length; i++) {
      InternalFieldInfo fieldInfo = fieldInfos[i];
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      if (fieldOffset != -1) {
        fieldValues[i] = copyField(originObj, fieldOffset, fieldInfo.classId);
      } else {
        // field in record class has offset -1
        Object fieldValue = fieldAccessor.get(originObj);
        fieldValues[i] = fury.copyObject(fieldValue, fieldInfo.classId);
      }
    }
    return RecordUtils.remapping(copyRecordInfo, fieldValues);
  }

  private void copyFields(T originObj, T newObj) {
    InternalFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    for (InternalFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      // record class won't go to this path;
      assert fieldOffset != -1;
      switch (fieldInfo.classId) {
        case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
          Platform.putByte(newObj, fieldOffset, Platform.getByte(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
          Platform.putChar(newObj, fieldOffset, Platform.getChar(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
          Platform.putShort(newObj, fieldOffset, Platform.getShort(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_INT_CLASS_ID:
          Platform.putInt(newObj, fieldOffset, Platform.getInt(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
          Platform.putLong(newObj, fieldOffset, Platform.getLong(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
          Platform.putFloat(newObj, fieldOffset, Platform.getFloat(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
          Platform.putDouble(newObj, fieldOffset, Platform.getDouble(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
          Platform.putBoolean(newObj, fieldOffset, Platform.getBoolean(originObj, fieldOffset));
          break;
        case ClassResolver.BOOLEAN_CLASS_ID:
        case ClassResolver.BYTE_CLASS_ID:
        case ClassResolver.CHAR_CLASS_ID:
        case ClassResolver.SHORT_CLASS_ID:
        case ClassResolver.INTEGER_CLASS_ID:
        case ClassResolver.FLOAT_CLASS_ID:
        case ClassResolver.LONG_CLASS_ID:
        case ClassResolver.DOUBLE_CLASS_ID:
        case ClassResolver.STRING_CLASS_ID:
          Platform.putObject(newObj, fieldOffset, Platform.getObject(originObj, fieldOffset));
          break;
        default:
          Platform.putObject(
              newObj, fieldOffset, fury.copyObject(Platform.getObject(originObj, fieldOffset)));
      }
    }
  }

  public static void copyFields(
      Fury fury, InternalFieldInfo[] fieldInfos, Object originObj, Object newObj) {
    for (InternalFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      // record class won't go to this path;
      assert fieldOffset != -1;
      switch (fieldInfo.classId) {
        case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
          Platform.putByte(newObj, fieldOffset, Platform.getByte(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
          Platform.putChar(newObj, fieldOffset, Platform.getChar(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
          Platform.putShort(newObj, fieldOffset, Platform.getShort(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_INT_CLASS_ID:
          Platform.putInt(newObj, fieldOffset, Platform.getInt(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
          Platform.putLong(newObj, fieldOffset, Platform.getLong(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
          Platform.putFloat(newObj, fieldOffset, Platform.getFloat(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
          Platform.putDouble(newObj, fieldOffset, Platform.getDouble(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
          Platform.putBoolean(newObj, fieldOffset, Platform.getBoolean(originObj, fieldOffset));
          break;
        case ClassResolver.BOOLEAN_CLASS_ID:
        case ClassResolver.BYTE_CLASS_ID:
        case ClassResolver.CHAR_CLASS_ID:
        case ClassResolver.SHORT_CLASS_ID:
        case ClassResolver.INTEGER_CLASS_ID:
        case ClassResolver.FLOAT_CLASS_ID:
        case ClassResolver.LONG_CLASS_ID:
        case ClassResolver.DOUBLE_CLASS_ID:
        case ClassResolver.STRING_CLASS_ID:
          Platform.putObject(newObj, fieldOffset, Platform.getObject(originObj, fieldOffset));
          break;
        default:
          Platform.putObject(
              newObj, fieldOffset, fury.copyObject(Platform.getObject(originObj, fieldOffset)));
      }
    }
  }

  private Object copyField(Object targetObject, long fieldOffset, short classId) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        return Platform.getBoolean(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        return Platform.getByte(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        return Platform.getChar(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        return Platform.getShort(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        return Platform.getInt(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        return Platform.getFloat(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        return Platform.getLong(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        return Platform.getDouble(targetObject, fieldOffset);
      case ClassResolver.BOOLEAN_CLASS_ID:
      case ClassResolver.BYTE_CLASS_ID:
      case ClassResolver.CHAR_CLASS_ID:
      case ClassResolver.SHORT_CLASS_ID:
      case ClassResolver.INTEGER_CLASS_ID:
      case ClassResolver.FLOAT_CLASS_ID:
      case ClassResolver.LONG_CLASS_ID:
      case ClassResolver.DOUBLE_CLASS_ID:
      case ClassResolver.STRING_CLASS_ID:
        return Platform.getObject(targetObject, fieldOffset);
      default:
        return fury.copyObject(Platform.getObject(targetObject, fieldOffset));
    }
  }

  private InternalFieldInfo[] buildFieldsInfo() {
    List<Descriptor> descriptors = new ArrayList<>();
    if (RecordUtils.isRecord(type)) {
      RecordComponent[] components = RecordUtils.getRecordComponents(type);
      assert components != null;
      try {
        for (RecordComponent component : components) {
          Field field = type.getDeclaredField(component.getName());
          descriptors.add(
              new Descriptor(
                  field, TypeRef.of(field.getGenericType()), component.getAccessor(), null));
        }
      } catch (NoSuchFieldException e) {
        // impossible
        Platform.throwException(e);
      }
    } else {
      for (Field field : ReflectionUtils.getFields(type, true)) {
        if (!Modifier.isStatic(field.getModifiers())) {
          descriptors.add(new Descriptor(field, TypeRef.of(field.getGenericType()), null, null));
        }
      }
    }
    DescriptorGrouper descriptorGrouper =
        createDescriptorGrouper(
            fury.getClassResolver()::isMonomorphic,
            descriptors,
            false,
            fury.compressInt(),
            fury.compressLong());
    Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]> infos =
        buildFieldInfos(fury, descriptorGrouper);
    fieldInfos = new InternalFieldInfo[descriptors.size()];
    System.arraycopy(infos.f0.f0, 0, fieldInfos, 0, infos.f0.f0.length);
    System.arraycopy(infos.f1, 0, fieldInfos, infos.f0.f0.length, infos.f1.length);
    System.arraycopy(infos.f2, 0, fieldInfos, fieldInfos.length - infos.f2.length, infos.f2.length);
    if (isRecord) {
      List<String> fieldNames =
          Arrays.stream(fieldInfos)
              .map(f -> f.fieldAccessor.getField().getName())
              .collect(Collectors.toList());
      copyRecordInfo = new RecordInfo(type, fieldNames);
    }
    return fieldInfos;
  }

  public static InternalFieldInfo[] buildFieldsInfo(Fury fury, List<Field> fields) {
    List<Descriptor> descriptors = new ArrayList<>();
    for (Field field : fields) {
      if (!Modifier.isTransient(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
        descriptors.add(new Descriptor(field, TypeRef.of(field.getGenericType()), null, null));
      }
    }
    DescriptorGrouper descriptorGrouper =
        createDescriptorGrouper(
            fury.getClassResolver()::isMonomorphic,
            descriptors,
            false,
            fury.compressInt(),
            fury.compressLong());
    Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]> infos =
        buildFieldInfos(fury, descriptorGrouper);
    InternalFieldInfo[] fieldInfos = new InternalFieldInfo[descriptors.size()];
    System.arraycopy(infos.f0.f0, 0, fieldInfos, 0, infos.f0.f0.length);
    System.arraycopy(infos.f1, 0, fieldInfos, infos.f0.f0.length, infos.f1.length);
    System.arraycopy(infos.f2, 0, fieldInfos, fieldInfos.length - infos.f2.length, infos.f2.length);
    return fieldInfos;
  }

  protected T newBean() {
    if (constructor != null) {
      try {
        return (T) constructor.invoke();
      } catch (Throwable e) {
        Platform.throwException(e);
      }
    }
    return Platform.newInstance(type);
  }

  static Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]>
      buildFieldInfos(Fury fury, DescriptorGrouper grouper) {
    // When a type is both Collection/Map and final, add it to collection/map fields to keep
    // consistent with jit.
    Collection<Descriptor> primitives = grouper.getPrimitiveDescriptors();
    Collection<Descriptor> boxed = grouper.getBoxedDescriptors();
    Collection<Descriptor> finals = grouper.getFinalDescriptors();
    FinalTypeField[] finalFields =
        new FinalTypeField[primitives.size() + boxed.size() + finals.size()];
    int cnt = 0;
    for (Descriptor d : primitives) {
      finalFields[cnt++] = buildFinalTypeField(fury, d);
    }
    for (Descriptor d : boxed) {
      finalFields[cnt++] = buildFinalTypeField(fury, d);
    }
    // TODO(chaokunyang) Support Pojo<T> generics besides Map/Collection subclass
    //  when it's supported in BaseObjectCodecBuilder.
    for (Descriptor d : finals) {
      finalFields[cnt++] = buildFinalTypeField(fury, d);
    }
    boolean[] isFinal = new boolean[finalFields.length];
    for (int i = 0; i < isFinal.length; i++) {
      ClassInfo classInfo = finalFields[i].classInfo;
      isFinal[i] = classInfo != null && fury.getClassResolver().isMonomorphic(classInfo.getCls());
    }
    cnt = 0;
    GenericTypeField[] otherFields = new GenericTypeField[grouper.getOtherDescriptors().size()];
    for (Descriptor descriptor : grouper.getOtherDescriptors()) {
      GenericTypeField genericTypeField =
          new GenericTypeField(
              descriptor.getRawType(),
              descriptor.getDeclaringClass() + "." + descriptor.getName(),
              descriptor.getField() != null
                  ? FieldAccessor.createAccessor(descriptor.getField())
                  : null,
              fury,
              descriptor.getField() != null
                  ? descriptor.getField().getAnnotation(FieldInfo.class)
                  : null);
      otherFields[cnt++] = genericTypeField;
    }
    cnt = 0;
    Collection<Descriptor> collections = grouper.getCollectionDescriptors();
    Collection<Descriptor> maps = grouper.getMapDescriptors();
    GenericTypeField[] containerFields = new GenericTypeField[collections.size() + maps.size()];
    for (Descriptor d : collections) {
      containerFields[cnt++] = buildContainerField(fury, d);
    }
    for (Descriptor d : maps) {
      containerFields[cnt++] = buildContainerField(fury, d);
    }
    return Tuple3.of(Tuple2.of(finalFields, isFinal), otherFields, containerFields);
  }

  private static FinalTypeField buildFinalTypeField(Fury fury, Descriptor d) {
    return new FinalTypeField(
        d.getRawType(),
        d.getDeclaringClass() + "." + d.getName(),
        // `d.getField()` will be null when peer class doesn't have this field.
        d.getField() != null ? FieldAccessor.createAccessor(d.getField()) : null,
        fury,
        d.getField() != null ? d.getField().getAnnotation(FieldInfo.class) : null);
  }

  private static GenericTypeField buildContainerField(Fury fury, Descriptor d) {
    return new GenericTypeField(
        d.getTypeRef(),
        d.getDeclaringClass() + "." + d.getName(),
        d.getField() != null ? FieldAccessor.createAccessor(d.getField()) : null,
        fury,
        d.getField() != null ? d.getField().getAnnotation(FieldInfo.class) : null);
  }

  public static class InternalFieldInfo {
    protected final short classId;
    protected final String qualifiedFieldName;
    protected final FieldAccessor fieldAccessor;
    protected FieldInfo fieldInfo;

    private InternalFieldInfo(
        short classId,
        String qualifiedFieldName,
        FieldAccessor fieldAccessor,
        FieldInfo fieldInfo) {
      this.classId = classId;
      this.qualifiedFieldName = qualifiedFieldName;
      this.fieldAccessor = fieldAccessor;
      this.fieldInfo = fieldInfo;
    }

    @Override
    public String toString() {
      return "InternalFieldInfo{"
          + "classId="
          + classId
          + ", fieldName="
          + qualifiedFieldName
          + ", field="
          + (fieldAccessor != null ? fieldAccessor.getField() : null)
          + ", fieldInfo="
          + fieldInfo
          + '}';
    }
  }

  static final class FinalTypeField extends InternalFieldInfo {
    final ClassInfo classInfo;

    private FinalTypeField(
        Class<?> type, String fieldName, FieldAccessor accessor, Fury fury, FieldInfo fieldInfo) {
      super(getRegisteredClassId(fury, type), fieldName, accessor, fieldInfo);
      // invoke `copy` to avoid ObjectSerializer construct clear serializer by `clearSerializer`.
      if (type == FinalObjectTypeStub.class) {
        // `FinalObjectTypeStub` has no fields, using its `classInfo`
        // will make deserialization failed.
        classInfo = null;
      } else {
        classInfo = fury.getClassResolver().getClassInfo(type);
      }
    }
  }

  static final class GenericTypeField extends InternalFieldInfo {
    final GenericType genericType;
    final ClassInfoHolder classInfoHolder;
    final boolean trackingRef;

    private GenericTypeField(
        Class<?> cls,
        String qualifiedFieldName,
        FieldAccessor accessor,
        Fury fury,
        FieldInfo fieldInfo) {
      super(getRegisteredClassId(fury, cls), qualifiedFieldName, accessor, fieldInfo);
      // TODO support generics <T> in Pojo<T>, see ComplexObjectSerializer.getGenericTypes
      genericType = fury.getClassResolver().buildGenericType(cls);
      classInfoHolder = fury.getClassResolver().nilClassInfoHolder();
      trackingRef = fury.getClassResolver().needToWriteRef(cls);
    }

    private GenericTypeField(
        TypeRef<?> typeRef,
        String qualifiedFieldName,
        FieldAccessor accessor,
        Fury fury,
        FieldInfo fieldInfo) {
      super(
          getRegisteredClassId(fury, getRawType(typeRef)), qualifiedFieldName, accessor, fieldInfo);
      // TODO support generics <T> in Pojo<T>, see ComplexObjectSerializer.getGenericTypes
      genericType = fury.getClassResolver().buildGenericType(typeRef);
      classInfoHolder = fury.getClassResolver().nilClassInfoHolder();
      trackingRef = fury.getClassResolver().needToWriteRef(getRawType(typeRef));
    }

    @Override
    public String toString() {
      return "GenericTypeField{"
          + "genericType="
          + genericType
          + ", classId="
          + classId
          + ", qualifiedFieldName="
          + qualifiedFieldName
          + ", field="
          + (fieldAccessor != null ? fieldAccessor.getField() : null)
          + ", fieldInfo="
          + fieldInfo
          + '}';
    }
  }

  private static short getRegisteredClassId(Fury fury, Class<?> cls) {
    Short classId = fury.getClassResolver().getRegisteredClassId(cls);
    return classId == null ? ClassResolver.NO_CLASS_ID : classId;
  }
}
