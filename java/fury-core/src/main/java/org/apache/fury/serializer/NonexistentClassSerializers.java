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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.fury.Fury;
import org.apache.fury.annotation.FieldInfo;
import org.apache.fury.collection.IdentityObjectIntMap;
import org.apache.fury.collection.LongMap;
import org.apache.fury.collection.MapEntry;
import org.apache.fury.collection.Tuple2;
import org.apache.fury.collection.Tuple3;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.meta.ClassDef;
import org.apache.fury.resolver.ClassInfo;
import org.apache.fury.resolver.ClassInfoHolder;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.resolver.MetaContext;
import org.apache.fury.resolver.MetaStringResolver;
import org.apache.fury.resolver.RefResolver;
import org.apache.fury.serializer.NonexistentClass.NonexistentEnum;
import org.apache.fury.type.Descriptor;
import org.apache.fury.type.DescriptorGrouper;
import org.apache.fury.type.Generics;
import org.apache.fury.util.Preconditions;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class NonexistentClassSerializers {

  private static final class ClassFieldsInfo {
    private final ObjectSerializer.FinalTypeField[] finalFields;
    private final boolean[] isFinal;
    private final ObjectSerializer.GenericTypeField[] otherFields;
    private final ObjectSerializer.GenericTypeField[] containerFields;
    private final int classVersionHash;

    private ClassFieldsInfo(
        ObjectSerializer.FinalTypeField[] finalFields,
        boolean[] isFinal,
        ObjectSerializer.GenericTypeField[] otherFields,
        ObjectSerializer.GenericTypeField[] containerFields,
        int classVersionHash) {
      this.finalFields = finalFields;
      this.isFinal = isFinal;
      this.otherFields = otherFields;
      this.containerFields = containerFields;
      this.classVersionHash = classVersionHash;
    }
  }

  public static final class NonexistentClassSerializer extends Serializer {
    private final ClassDef classDef;
    private final ClassInfoHolder classInfoHolder;
    private final LongMap<ClassFieldsInfo> fieldsInfoMap;

    public NonexistentClassSerializer(Fury fury, ClassDef classDef) {
      super(fury, NonexistentClass.NonexistentMetaShared.class);
      this.classDef = classDef;
      classInfoHolder = fury.getClassResolver().nilClassInfoHolder();
      fieldsInfoMap = new LongMap<>();
      Preconditions.checkArgument(fury.getConfig().isMetaShareEnabled());
    }

    /**
     * Multiple un existed class will correspond to this `NonexistentMetaSharedClass`. When querying
     * classinfo by `class`, it may dispatch to same `NonexistentClassSerializer`, so we can't use
     * `classDef` in this serializer, but use `classDef` in `NonexistentMetaSharedClass` instead.
     */
    private void writeClassDef(MemoryBuffer buffer, NonexistentClass.NonexistentMetaShared value) {
      // Register NotFoundClass ahead to skip write meta shared info,
      // then revert written class id to write class info here,
      // since it's the only place to hold class def for not found class.
      buffer.increaseWriterIndex(-2);
      MetaContext metaContext = fury.getSerializationContext().getMetaContext();
      IdentityObjectIntMap classMap = metaContext.classMap;
      int newId = classMap.size;
      // class not exist, use class def id for identity.
      int id = classMap.putOrGet(value.classDef.getId(), newId);
      if (id >= 0) {
        buffer.writeVarUint32(id << 1 | 0b1);
      } else {
        buffer.writeVarUint32(newId << 1 | 0b1);
        metaContext.writingClassDefs.add(value.classDef);
      }
    }

    @Override
    public void write(MemoryBuffer buffer, Object v) {
      NonexistentClass.NonexistentMetaShared value = (NonexistentClass.NonexistentMetaShared) v;
      writeClassDef(buffer, value);
      ClassDef classDef = value.classDef;
      ClassFieldsInfo fieldsInfo = getClassFieldsInfo(classDef);
      Fury fury = this.fury;
      RefResolver refResolver = fury.getRefResolver();
      ClassResolver classResolver = fury.getClassResolver();
      if (fury.checkClassVersion()) {
        buffer.writeInt32(fieldsInfo.classVersionHash);
      }
      // write order: primitive,boxed,final,other,collection,map
      ObjectSerializer.FinalTypeField[] finalFields = fieldsInfo.finalFields;
      boolean[] isFinal = fieldsInfo.isFinal;
      for (int i = 0; i < finalFields.length; i++) {
        ObjectSerializer.FinalTypeField fieldInfo = finalFields[i];
        Object fieldValue = value.get(fieldInfo.qualifiedFieldName);
        ClassInfo classInfo = fieldInfo.classInfo;
        if (classResolver.isPrimitive(fieldInfo.classId)) {
          classInfo.getSerializer().write(buffer, fieldValue);
        } else {
          if (isFinal[i]) {
            // whether tracking ref is recorded in `fieldInfo.serializer`, so it's still
            // consistent with jit serializer.
            Serializer<Object> serializer = classInfo.getSerializer();
            fury.writeRef(buffer, fieldValue, serializer);
          } else {
            fury.writeRef(buffer, fieldValue, classInfo);
          }
        }
      }
      for (ObjectSerializer.GenericTypeField fieldInfo : fieldsInfo.otherFields) {
        Object fieldValue = value.get(fieldInfo.qualifiedFieldName);
        if (fieldInfo.trackingRef) {
          fury.writeRef(buffer, fieldValue, fieldInfo.classInfoHolder);
        } else {
          fury.writeNullable(buffer, fieldValue, fieldInfo.classInfoHolder);
        }
      }
      Generics generics = fury.getGenerics();
      for (ObjectSerializer.GenericTypeField fieldInfo : fieldsInfo.containerFields) {
        Object fieldValue = value.get(fieldInfo.qualifiedFieldName);
        ObjectSerializer.writeContainerFieldValue(
            fury, refResolver, classResolver, generics, fieldInfo, buffer, fieldValue);
      }
    }

    private ClassFieldsInfo getClassFieldsInfo(ClassDef classDef) {
      ClassFieldsInfo fieldsInfo = fieldsInfoMap.get(classDef.getId());
      if (fieldsInfo == null) {
        // Use `NonexistentSkipClass` since it doesn't have any field.
        Collection<Descriptor> descriptors =
            MetaSharedSerializer.consolidateFields(
                fury.getClassResolver(), NonexistentClass.NonexistentSkip.class, classDef);
        DescriptorGrouper descriptorGrouper =
            DescriptorGrouper.createDescriptorGrouper(
                fury.getClassResolver()::isMonomorphic,
                descriptors,
                false,
                fury.compressInt(),
                fury.compressLong());
        Tuple3<
                Tuple2<ObjectSerializer.FinalTypeField[], boolean[]>,
                ObjectSerializer.GenericTypeField[],
                ObjectSerializer.GenericTypeField[]>
            tuple = AbstractObjectSerializer.buildFieldInfos(fury, descriptorGrouper);
        int classVersionHash = 0;
        if (fury.checkClassVersion()) {
          classVersionHash = ObjectSerializer.computeVersionHash(descriptors);
        }
        fieldsInfo =
            new ClassFieldsInfo(tuple.f0.f0, tuple.f0.f1, tuple.f1, tuple.f2, classVersionHash);
        fieldsInfoMap.put(classDef.getId(), fieldsInfo);
      }
      return fieldsInfo;
    }

    @Override
    public Object read(MemoryBuffer buffer) {
      NonexistentClass.NonexistentMetaShared obj =
          new NonexistentClass.NonexistentMetaShared(classDef);
      Fury fury = this.fury;
      RefResolver refResolver = fury.getRefResolver();
      ClassResolver classResolver = fury.getClassResolver();
      refResolver.reference(obj);
      List<MapEntry> entries = new ArrayList<>();
      // read order: primitive,boxed,final,other,collection,map
      ClassFieldsInfo fieldsInfo = getClassFieldsInfo(classDef);
      ObjectSerializer.FinalTypeField[] finalFields = fieldsInfo.finalFields;
      boolean[] isFinal = fieldsInfo.isFinal;
      for (int i = 0; i < finalFields.length; i++) {
        ObjectSerializer.FinalTypeField fieldInfo = finalFields[i];
        FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
        Object fieldValue;
        if (fieldInfo.classInfo == null) {
          // TODO(chaokunyang) support registered serializer in peer with ref tracking disabled.
          fieldValue = fury.readRef(buffer, classInfoHolder);
        } else {
          if (classResolver.isPrimitive(fieldInfo.classId)) {
            fieldValue = fieldInfo.classInfo.getSerializer().read(buffer);
          } else {
            fieldValue =
                ObjectSerializer.readFinalObjectFieldValue(
                    fury,
                    refResolver,
                    classResolver,
                    fieldInfo,
                    isFinal[i],
                    buffer,
                    fieldInfoAnnotation);
          }
        }
        entries.add(new MapEntry(fieldInfo.qualifiedFieldName, fieldValue));
      }
      for (ObjectSerializer.GenericTypeField fieldInfo : fieldsInfo.otherFields) {
        FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
        Object fieldValue =
            ObjectSerializer.readOtherFieldValue(fury, fieldInfo, buffer, fieldInfoAnnotation);
        entries.add(new MapEntry(fieldInfo.qualifiedFieldName, fieldValue));
      }
      Generics generics = fury.getGenerics();
      for (ObjectSerializer.GenericTypeField fieldInfo : fieldsInfo.containerFields) {
        FieldInfo fieldInfoAnnotation = fieldInfo.fieldInfo;
        Object fieldValue =
            ObjectSerializer.readContainerFieldValue(
                fury, generics, fieldInfo, buffer, fieldInfoAnnotation);
        entries.add(new MapEntry(fieldInfo.qualifiedFieldName, fieldValue));
      }
      obj.setEntries(entries);
      return obj;
    }
  }

  public static final class NonexistentEnumClassSerializer extends Serializer {
    private final NonexistentEnum[] enumConstants;
    private final MetaStringResolver metaStringResolver;

    public NonexistentEnumClassSerializer(Fury fury) {
      super(fury, NonexistentEnum.class);
      metaStringResolver = fury.getMetaStringResolver();
      enumConstants = NonexistentEnum.class.getEnumConstants();
    }

    @Override
    public Object read(MemoryBuffer buffer) {
      if (fury.getConfig().serializeEnumByName()) {
        metaStringResolver.readMetaStringBytes(buffer);
        return NonexistentEnum.UNKNOWN;
      }

      int ordinal = buffer.readVarUint32Small7();
      if (ordinal >= enumConstants.length) {
        ordinal = enumConstants.length - 1;
      }
      return enumConstants[ordinal];
    }
  }

  public static Serializer getSerializer(Fury fury, String className, Class<?> cls) {
    if (cls.isArray()) {
      return new ArraySerializers.NonexistentArrayClassSerializer(fury, className, cls);
    } else {
      if (cls.isEnum()) {
        return new NonexistentEnumClassSerializer(fury);
      } else {
        if (fury.getConfig().isMetaShareEnabled()) {
          throw new IllegalStateException(
              String.format(
                  "Serializer of class %s should be set in ClassResolver#getMetaSharedClassInfo",
                  className));
        } else {
          return new CompatibleSerializer(fury, cls);
        }
      }
    }
  }
}
