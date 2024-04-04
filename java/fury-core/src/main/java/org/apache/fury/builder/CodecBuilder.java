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

package org.apache.fury.builder;

import static org.apache.fury.codegen.Expression.Invoke.inlineInvoke;
import static org.apache.fury.type.TypeUtils.CLASS_TYPE;
import static org.apache.fury.type.TypeUtils.OBJECT_ARRAY_TYPE;
import static org.apache.fury.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_BYTE_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_CHAR_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_DOUBLE_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_FLOAT_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_LONG_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_SHORT_TYPE;
import static org.apache.fury.type.TypeUtils.PRIMITIVE_VOID_TYPE;
import static org.apache.fury.type.TypeUtils.getRawType;

import com.google.common.reflect.TypeToken;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.fury.Fury;
import org.apache.fury.codegen.CodegenContext;
import org.apache.fury.codegen.Expression;
import org.apache.fury.codegen.Expression.Cast;
import org.apache.fury.codegen.Expression.Inlineable;
import org.apache.fury.codegen.Expression.Invoke;
import org.apache.fury.codegen.Expression.ListExpression;
import org.apache.fury.codegen.Expression.Literal;
import org.apache.fury.codegen.Expression.Reference;
import org.apache.fury.codegen.Expression.StaticInvoke;
import org.apache.fury.collection.Tuple2;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.resolver.ClassInfo;
import org.apache.fury.resolver.ClassInfoHolder;
import org.apache.fury.type.Descriptor;
import org.apache.fury.type.FinalObjectTypeStub;
import org.apache.fury.util.GraalvmSupport;
import org.apache.fury.util.Platform;
import org.apache.fury.util.Preconditions;
import org.apache.fury.util.ReflectionUtils;
import org.apache.fury.util.StringUtils;
import org.apache.fury.util.function.Functions;
import org.apache.fury.util.record.RecordComponent;
import org.apache.fury.util.record.RecordUtils;
import sun.misc.Unsafe;

/**
 * Base builder for generating code to serialize java bean in row-format or object stream format.
 *
 * <ul>
 *   This builder has following requirements for the class of java bean:
 *   <li>public
 *   <li>For instance inner class, ignore outer class field.
 *   <li>For instance inner class, deserialized outer class field is null
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class CodecBuilder {
  protected static final String ROOT_OBJECT_NAME = "obj";
  // avoid user class has field with name fury.
  protected static final String FURY_NAME = "fury";
  static TypeToken<Object[]> objectArrayTypeToken = TypeToken.of(Object[].class);
  static TypeToken<MemoryBuffer> bufferTypeToken = TypeToken.of(MemoryBuffer.class);
  static TypeToken<ClassInfo> classInfoTypeToken = TypeToken.of(ClassInfo.class);
  static TypeToken<ClassInfoHolder> classInfoHolderTypeToken = TypeToken.of(ClassInfoHolder.class);

  protected final CodegenContext ctx;
  protected final TypeToken<?> beanType;
  protected final Class<?> beanClass;
  protected final boolean isRecord;
  private final Set<String> duplicatedFields;
  protected Reference furyRef = new Reference(FURY_NAME, TypeToken.of(Fury.class));
  public static final Reference recordComponentDefaultValues =
      new Reference("recordComponentDefaultValues", OBJECT_ARRAY_TYPE);
  protected final Map<String, Reference> fieldMap = new HashMap<>();
  protected boolean recordCtrAccessible;

  public CodecBuilder(CodegenContext ctx, TypeToken<?> beanType) {
    this.ctx = ctx;
    this.beanType = beanType;
    this.beanClass = getRawType(beanType);
    isRecord = RecordUtils.isRecord(beanClass);
    if (isRecord) {
      recordCtrAccessible = recordCtrAccessible(beanClass);
    }
    duplicatedFields = Descriptor.getSortedDuplicatedFields(beanClass).keySet();
    // don't ctx.addImport beanClass, because it maybe causes name collide.
    ctx.reserveName(FURY_NAME);
    ctx.reserveName(ROOT_OBJECT_NAME);
    // Don't import other packages to avoid class conflicts.
    // For example user class named as `Date`/`List`/`MemoryBuffer`
  }

  /** Generate codec class code. */
  public abstract String genCode();

  /** Returns an expression that serialize java bean of type {@link CodecBuilder#beanClass}. */
  public abstract Expression buildEncodeExpression();

  protected boolean sourcePublicAccessible(Class<?> cls) {
    return ctx.sourcePublicAccessible(cls);
  }

  protected Expression tryInlineCast(Expression expression, TypeToken<?> targetType) {
    return tryCastIfPublic(expression, targetType, true);
  }

  protected Expression tryCastIfPublic(Expression expression, TypeToken<?> targetType) {
    return tryCastIfPublic(expression, targetType, false);
  }

  protected Expression tryCastIfPublic(
      Expression expression, TypeToken<?> targetType, boolean inline) {
    Class<?> rawType = getRawType(targetType);
    if (rawType == FinalObjectTypeStub.class) {
      // final field doesn't exist in this class, skip cast.
      return expression;
    }
    if (inline) {
      if (sourcePublicAccessible(rawType)) {
        return new Cast(expression, targetType);
      } else {
        return new Cast(expression, ReflectionUtils.getPublicSuperType(TypeToken.of(rawType)));
      }
    }
    return tryCastIfPublic(expression, targetType, "castedValue");
  }

  protected Expression tryCastIfPublic(
      Expression expression, TypeToken<?> targetType, String valuePrefix) {
    Class<?> rawType = getRawType(targetType);
    if (sourcePublicAccessible(rawType)
        && !expression.type().wrap().isSubtypeOf(targetType.wrap())) {
      return new Cast(expression, targetType, valuePrefix);
    }
    return expression;
  }

  // left null check in sub class encode method to reduce data dependence.
  private final boolean fieldNullable = false;

  protected Reference getRecordCtrHandle() {
    String fieldName = "_record_ctr_";
    Reference fieldRef = fieldMap.get(fieldName);
    if (fieldRef == null) {
      StaticInvoke getRecordCtrHandle =
          new StaticInvoke(
              RecordUtils.class,
              "getRecordCtrHandle",
              TypeToken.of(MethodHandle.class),
              beanClassExpr());
      ctx.addField(ctx.type(MethodHandle.class), fieldName, getRecordCtrHandle);
      fieldRef = new Reference(fieldName, TypeToken.of(MethodHandle.class));
      fieldMap.put(fieldName, fieldRef);
    }
    return fieldRef;
  }

  protected Expression buildDefaultComponentsArray() {
    return new StaticInvoke(
        Platform.class, "copyObjectArray", OBJECT_ARRAY_TYPE, recordComponentDefaultValues);
  }

  /** Returns an expression that get field value from <code>bean</code>. */
  protected Expression getFieldValue(Expression inputBeanExpr, Descriptor descriptor) {
    TypeToken<?> fieldType = descriptor.getTypeToken();
    Class<?> rawType = descriptor.getRawType();
    String fieldName = descriptor.getName();
    if (isRecord) {
      return getRecordFieldValue(inputBeanExpr, descriptor);
    }
    if (duplicatedFields.contains(fieldName) || !Modifier.isPublic(beanClass.getModifiers())) {
      return unsafeAccessField(inputBeanExpr, beanClass, descriptor);
    }
    if (!sourcePublicAccessible(rawType)) {
      fieldType = OBJECT_TYPE;
    }
    // public field or non-private non-java field access field directly.
    if (Modifier.isPublic(descriptor.getModifiers())) {
      return new Expression.FieldValue(inputBeanExpr, fieldName, fieldType, fieldNullable, false);
    } else if (descriptor.getReadMethod() != null
        && Modifier.isPublic(descriptor.getReadMethod().getModifiers())) {
      return new Invoke(
          inputBeanExpr, descriptor.getReadMethod().getName(), fieldName, fieldType, fieldNullable);
    } else {
      if (!Modifier.isPrivate(descriptor.getModifiers())) {
        if (AccessorHelper.defineAccessor(descriptor.getField())) {
          return new StaticInvoke(
              AccessorHelper.getAccessorClass(descriptor.getField()),
              fieldName,
              fieldType,
              fieldNullable,
              inputBeanExpr);
        }
      }
      if (descriptor.getReadMethod() != null
          && !Modifier.isPrivate(descriptor.getReadMethod().getModifiers())) {
        if (AccessorHelper.defineAccessor(descriptor.getReadMethod())) {
          return new StaticInvoke(
              AccessorHelper.getAccessorClass(descriptor.getReadMethod()),
              descriptor.getReadMethod().getName(),
              fieldType,
              fieldNullable,
              inputBeanExpr);
        }
      }
      return unsafeAccessField(inputBeanExpr, beanClass, descriptor);
    }
  }

  private Expression getRecordFieldValue(Expression inputBeanExpr, Descriptor descriptor) {
    TypeToken<?> fieldType = descriptor.getTypeToken();
    if (!sourcePublicAccessible(descriptor.getRawType())) {
      fieldType = OBJECT_TYPE;
    }
    String fieldName = descriptor.getName();
    if (Modifier.isPublic(beanClass.getModifiers())) {
      Preconditions.checkNotNull(descriptor.getReadMethod());
      return new Invoke(
          inputBeanExpr, descriptor.getReadMethod().getName(), fieldName, fieldType, fieldNullable);
    } else {
      String key = "_" + fieldName + "_getter_";
      Reference ref = fieldMap.get(key);
      Tuple2<Class<?>, String> methodInfo = Functions.getterMethodInfo(descriptor.getRawType());
      if (ref == null) {
        Class<?> funcInterface = methodInfo.f0;
        TypeToken<?> getterType = TypeToken.of(funcInterface);
        Expression getter =
            new StaticInvoke(
                Functions.class,
                "makeGetterFunction",
                OBJECT_TYPE,
                beanClassExpr(),
                Literal.ofString(fieldName));
        getter = new Cast(getter, getterType);
        ctx.addField(funcInterface, key, getter);
        ref = new Reference(key, getterType);
        fieldMap.put(key, ref);
      }
      if (!fieldType.isPrimitive()) {
        Expression v = inlineInvoke(ref, methodInfo.f1, OBJECT_TYPE, fieldNullable, inputBeanExpr);
        return tryCastIfPublic(v, descriptor.getTypeToken(), fieldName);
      } else {
        return new Invoke(ref, methodInfo.f1, fieldType, fieldNullable, inputBeanExpr);
      }
    }
  }

  /** Returns an expression that get field value> from <code>bean</code> using reflection. */
  private Expression reflectAccessField(
      Expression inputObject, Class<?> cls, Descriptor descriptor) {
    Reference fieldRef = getReflectField(cls, descriptor.getField());
    // boolean fieldNullable = !descriptor.getTypeToken().isPrimitive();
    Invoke getObj = new Invoke(fieldRef, "get", OBJECT_TYPE, fieldNullable, inputObject);
    return new Cast(getObj, descriptor.getTypeToken(), descriptor.getName());
  }

  /** Returns an expression that get field value> from <code>bean</code> using {@link Unsafe}. */
  private Expression unsafeAccessField(
      Expression inputObject, Class<?> cls, Descriptor descriptor) {
    String fieldName = descriptor.getName();
    Expression fieldOffsetExpr = getFieldOffset(cls, descriptor);
    if (descriptor.getTypeToken().isPrimitive()) {
      // ex: Platform.UNSAFE.getFloat(obj, fieldOffset)
      Preconditions.checkArgument(!fieldNullable);
      TypeToken<?> returnType = descriptor.getTypeToken();
      String funcName = "get" + StringUtils.capitalize(descriptor.getRawType().toString());
      return new StaticInvoke(
          Platform.class, funcName, returnType, false, inputObject, fieldOffsetExpr);
    } else {
      // ex: Platform.UNSAFE.getObject(obj, fieldOffset)
      StaticInvoke getObj =
          new StaticInvoke(
              Platform.class,
              "getObject",
              OBJECT_TYPE,
              fieldNullable,
              inputObject,
              fieldOffsetExpr);
      return tryCastIfPublic(getObj, descriptor.getTypeToken(), fieldName);
    }
  }

  private Expression getFieldOffset(Class<?> cls, Descriptor descriptor) {
    Field field = descriptor.getField();
    String fieldName = descriptor.getName();
    // Use Field in case the class has duplicate field name as `fieldName`.
    if (GraalvmSupport.isGraalBuildtime()) {
      return getOrCreateField(
          true,
          long.class,
          fieldName + "_offset_",
          () -> {
            Expression classExpr = beanClassExpr(field.getDeclaringClass());
            new Invoke(classExpr, "getDeclaredField", TypeToken.of(Field.class));
            Expression reflectFieldRef = getReflectField(cls, field, false);
            return new StaticInvoke(
                    Platform.class, "objectFieldOffset", PRIMITIVE_LONG_TYPE, reflectFieldRef)
                .inline();
          });
    } else {
      long fieldOffset = ReflectionUtils.getFieldOffset(field);
      Preconditions.checkArgument(fieldOffset != -1);
      return Literal.ofLong(fieldOffset);
    }
  }

  /**
   * Returns an expression that deserialize data as a java bean of type {@link
   * CodecBuilder#beanClass}.
   */
  public abstract Expression buildDecodeExpression();

  /** Returns an expression that set field <code>value</code> to <code>bean</code>. */
  protected Expression setFieldValue(Expression bean, Descriptor d, Expression value) {
    String fieldName = d.getName();
    if (value instanceof Inlineable) {
      ((Inlineable) value).inline();
    }
    if (duplicatedFields.contains(fieldName) || !sourcePublicAccessible(beanClass)) {
      return unsafeSetField(bean, d, value);
    }
    if (!d.isFinalField() && Modifier.isPublic(d.getModifiers())) {
      return new Expression.SetField(bean, fieldName, value);
    } else if (d.getWriteMethod() != null && Modifier.isPublic(d.getWriteMethod().getModifiers())) {
      return new Invoke(bean, d.getWriteMethod().getName(), value);
    } else {
      if (!d.isFinalField() && !Modifier.isPrivate(d.getModifiers())) {
        if (AccessorHelper.defineSetter(d.getField())) {
          Class<?> accessorClass = AccessorHelper.getAccessorClass(d.getField());
          if (!value.type().equals(d.getTypeToken())) {
            value = new Cast(value, d.getTypeToken());
          }
          return new StaticInvoke(
              accessorClass, d.getName(), PRIMITIVE_VOID_TYPE, false, bean, value);
        }
      }
      if (d.getWriteMethod() != null && !Modifier.isPrivate(d.getWriteMethod().getModifiers())) {
        if (AccessorHelper.defineSetter(d.getWriteMethod())) {
          Class<?> accessorClass = AccessorHelper.getAccessorClass(d.getWriteMethod());
          if (!value.type().equals(d.getTypeToken())) {
            value = new Cast(value, d.getTypeToken());
          }
          return new StaticInvoke(
              accessorClass, d.getWriteMethod().getName(), PRIMITIVE_VOID_TYPE, false, bean, value);
        }
      }
      return unsafeSetField(bean, d, value);
    }
  }

  /**
   * Returns an expression that set field <code>value</code> to <code>bean</code> using reflection.
   */
  private Expression reflectSetField(Expression bean, Field field, Expression value) {
    // Class maybe have getter, but don't have setter, so we can't rely on reflectAccessField to
    // populate fieldMap
    Reference fieldRef = getReflectField(getRawType(bean.type()), field);
    Preconditions.checkNotNull(fieldRef);
    return new Invoke(fieldRef, "set", bean, value);
  }

  /**
   * Returns an expression that set field <code>value</code> to <code>bean</code> using {@link
   * Unsafe}.
   */
  private Expression unsafeSetField(Expression bean, Descriptor descriptor, Expression value) {
    TypeToken<?> fieldType = descriptor.getTypeToken();
    // Use Field in case the class has duplicate field name as `fieldName`.
    Expression fieldOffsetExpr = getFieldOffset(beanClass, descriptor);
    if (descriptor.getTypeToken().isPrimitive()) {
      Preconditions.checkArgument(value.type().equals(fieldType));
      String funcName = "put" + StringUtils.capitalize(getRawType(fieldType).toString());
      return new StaticInvoke(Platform.class, funcName, bean, fieldOffsetExpr, value);
    } else {
      return new StaticInvoke(Platform.class, "putObject", bean, fieldOffsetExpr, value);
    }
  }

  private Reference getReflectField(Class<?> cls, Field field) {
    return getReflectField(cls, field, true);
  }

  private Reference getReflectField(Class<?> cls, Field field, boolean setAccessible) {
    String fieldName = field.getName();
    String fieldRefName;
    if (duplicatedFields.contains(fieldName)) {
      fieldRefName = cls.getName().replaceAll("\\.|\\$", "_") + "_" + fieldName + "_Field";
    } else {
      fieldRefName = fieldName + "_Field";
    }
    return getOrCreateField(
        true,
        Field.class,
        fieldRefName,
        () -> {
          TypeToken<Field> fieldTypeToken = TypeToken.of(Field.class);
          Expression classExpr = beanClassExpr(field.getDeclaringClass());
          Expression fieldExpr;
          if (GraalvmSupport.isGraalBuildtime()) {
            fieldExpr =
                inlineInvoke(
                    classExpr, "getDeclaredField", fieldTypeToken, Literal.ofString(fieldName));
          } else {
            fieldExpr =
                new StaticInvoke(
                    ReflectionUtils.class,
                    "getField",
                    fieldTypeToken,
                    classExpr,
                    Literal.ofString(fieldName));
          }
          if (!setAccessible) {
            return fieldExpr;
          }
          Invoke setAccess = new Invoke(fieldExpr, "setAccessible", Literal.True);
          return new ListExpression(setAccess, fieldExpr);
        });
  }

  private Reference getOrCreateField(
      boolean isStatic, Class<?> type, String fieldName, Supplier<Expression> value) {
    Reference fieldRef = fieldMap.get(fieldName);
    if (fieldRef == null) {
      ctx.addField(isStatic, true, ctx.type(type), fieldName, value.get());
      fieldRef = new Reference(fieldName, TypeToken.of(type));
      fieldMap.put(fieldName, fieldRef);
    }
    return fieldRef;
  }

  /** Returns an Expression that create a new java object of type {@link CodecBuilder#beanClass}. */
  protected Expression newBean() {
    // TODO allow default access-level class.
    if (sourcePublicAccessible(beanClass)) {
      return new Expression.NewInstance(beanType);
    } else {
      return new StaticInvoke(Platform.class, "newInstance", OBJECT_TYPE, beanClassExpr());
    }
  }

  protected void buildRecordComponentDefaultValues() {
    ctx.reserveName(recordComponentDefaultValues.name());
    StaticInvoke expr =
        new StaticInvoke(
            RecordUtils.class,
            "buildRecordComponentDefaultValues",
            OBJECT_ARRAY_TYPE,
            beanClassExpr());
    ctx.addField(Object[].class, recordComponentDefaultValues.name(), expr);
  }

  static boolean recordCtrAccessible(Class<?> cls) {
    // support unexported packages in module
    if (!Modifier.isPublic(cls.getModifiers())) {
      return false;
    }
    for (RecordComponent component : Objects.requireNonNull(RecordUtils.getRecordComponents(cls))) {
      if (!Modifier.isPublic(component.getType().getModifiers())) {
        return false;
      }
    }
    return true;
  }

  protected Expression beanClassExpr(Class<?> cls) {
    if (cls == beanClass) {
      return staticBeanClassExpr();
    }
    if (GraalvmSupport.isGraalBuildtime()) {
      String name = cls.getName().replaceAll("\\.|\\$", "_") + "__class__";
      return getOrCreateField(
          true,
          Class.class,
          name,
          () ->
              new StaticInvoke(
                      ReflectionUtils.class,
                      "loadClass",
                      CLASS_TYPE,
                      Literal.ofString(cls.getName()))
                  .inline());
    }
    throw new UnsupportedOperationException();
  }

  protected Expression beanClassExpr() {
    if (GraalvmSupport.isGraalBuildtime()) {
      return staticBeanClassExpr();
    }
    throw new UnsupportedOperationException();
  }

  protected Expression staticBeanClassExpr() {
    return getOrCreateField(
        true,
        Class.class,
        "__class__",
        () ->
            new StaticInvoke(
                    ReflectionUtils.class,
                    "loadClass",
                    CLASS_TYPE,
                    Literal.ofString(beanClass.getName()))
                .inline());
  }

  /**
   * Build unsafePut operation.
   *
   * @see MemoryBuffer#unsafePut(Object, long, byte)
   */
  protected Expression unsafePut(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePut", base, pos, value);
  }

  protected Expression unsafePutBoolean(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePutBoolean", base, pos, value);
  }

  protected Expression unsafePutChar(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePutChar", base, pos, value);
  }

  protected Expression unsafePutShort(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePutShort", base, pos, value);
  }

  protected Expression unsafePutInt(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePutInt", base, pos, value);
  }

  protected Expression unsafePutLong(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePutLong", base, pos, value);
  }

  protected Expression unsafePutFloat(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePutFloat", base, pos, value);
  }

  /**
   * Build unsafePutDouble operation.
   *
   * @see MemoryBuffer#unsafePutDouble(Object, long, double)
   */
  protected Expression unsafePutDouble(Expression base, Expression pos, Expression value) {
    return new StaticInvoke(MemoryBuffer.class, "unsafePutDouble", base, pos, value);
  }

  /**
   * Build unsafeGet operation.
   *
   * @see MemoryBuffer#unsafeGet(Object, long)
   */
  protected Expression unsafeGet(Expression base, Expression pos) {
    return new StaticInvoke(MemoryBuffer.class, "unsafeGet", PRIMITIVE_BYTE_TYPE, base, pos);
  }

  protected Expression unsafeGetBoolean(Expression base, Expression pos) {
    return new StaticInvoke(
        MemoryBuffer.class, "unsafeGetBoolean", PRIMITIVE_BOOLEAN_TYPE, base, pos);
  }

  protected Expression unsafeGetChar(Expression base, Expression pos) {
    return new StaticInvoke(MemoryBuffer.class, "unsafeGetChar", PRIMITIVE_CHAR_TYPE, base, pos);
  }

  protected Expression unsafeGetShort(Expression base, Expression pos) {
    return new StaticInvoke(MemoryBuffer.class, "unsafeGetShort", PRIMITIVE_SHORT_TYPE, base, pos);
  }

  protected Expression unsafeGetInt(Expression base, Expression pos) {
    return new StaticInvoke(MemoryBuffer.class, "unsafeGetInt", PRIMITIVE_INT_TYPE, base, pos);
  }

  protected Expression unsafeGetLong(Expression base, Expression pos) {
    return new StaticInvoke(MemoryBuffer.class, "unsafeGetLong", PRIMITIVE_LONG_TYPE, base, pos);
  }

  protected Expression unsafeGetFloat(Expression base, Expression pos) {
    return new StaticInvoke(MemoryBuffer.class, "unsafeGetFloat", PRIMITIVE_FLOAT_TYPE, base, pos);
  }

  protected Expression unsafeGetDouble(Expression base, Expression pos) {
    return new StaticInvoke(
        MemoryBuffer.class, "unsafeGetDouble", PRIMITIVE_DOUBLE_TYPE, base, pos);
  }

  protected Expression readChar(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "readCharLE" : "readCharBE";
    return new Invoke(buffer, func, PRIMITIVE_CHAR_TYPE);
  }

  protected Expression readShort(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "readShortLE" : "readShortBE";
    return new Invoke(buffer, func, PRIMITIVE_SHORT_TYPE);
  }

  protected Expression readInt(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "readIntLE" : "readIntBE";
    return new Invoke(buffer, func, PRIMITIVE_INT_TYPE);
  }

  protected Expression readVarInt(Expression buffer) {
    return new Invoke(buffer, "readVarInt", PRIMITIVE_INT_TYPE);
  }

  protected Expression readLong(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "readLongLE" : "readLongBE";
    return new Invoke(buffer, func, PRIMITIVE_LONG_TYPE);
  }

  protected Expression readVarLong(Expression buffer) {
    return new Invoke(buffer, "readVarLong", PRIMITIVE_LONG_TYPE);
  }

  protected Expression readFloat(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "readFloatLE" : "readFloatBE";
    return new Invoke(buffer, func, PRIMITIVE_FLOAT_TYPE);
  }

  protected Expression readDouble(Expression buffer) {
    String func = Platform.IS_LITTLE_ENDIAN ? "readDoubleLE" : "readDoubleBE";
    return new Invoke(buffer, func, PRIMITIVE_DOUBLE_TYPE);
  }

  public static String readIntFunc() {
    return Platform.IS_LITTLE_ENDIAN ? "readIntLE" : "readIntBE";
  }

  public static String readLongFunc() {
    return Platform.IS_LITTLE_ENDIAN ? "readLongLE" : "readLongBE";
  }
}
