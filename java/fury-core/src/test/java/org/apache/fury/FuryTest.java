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

package org.apache.fury;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fury.annotation.Expose;
import org.apache.fury.annotation.Ignore;
import org.apache.fury.builder.Generated;
import org.apache.fury.config.CompatibleMode;
import org.apache.fury.config.FuryBuilder;
import org.apache.fury.config.Language;
import org.apache.fury.exception.FuryException;
import org.apache.fury.exception.InsecureException;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.memory.MemoryUtils;
import org.apache.fury.memory.Platform;
import org.apache.fury.resolver.MetaContext;
import org.apache.fury.serializer.ArraySerializersTest;
import org.apache.fury.serializer.EnumSerializerTest;
import org.apache.fury.serializer.ObjectSerializer;
import org.apache.fury.serializer.Serializer;
import org.apache.fury.test.bean.BeanA;
import org.apache.fury.test.bean.Struct;
import org.apache.fury.type.Descriptor;
import org.apache.fury.util.DateTimeUtils;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class FuryTest extends FuryTestBase {
  @DataProvider(name = "languageConfig")
  public static Object[][] languageConfig() {
    return new Object[][] {{Language.JAVA}, {Language.PYTHON}};
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void primitivesTest(boolean referenceTracking, Language language) {
    Fury fury1 =
        Fury.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    Fury fury2 =
        Fury.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    assertEquals(true, serDe(fury1, fury2, true));
    assertEquals(Byte.MAX_VALUE, serDe(fury1, fury2, Byte.MAX_VALUE));
    assertEquals(Short.MAX_VALUE, serDe(fury1, fury2, Short.MAX_VALUE));
    assertEquals(Integer.MAX_VALUE, serDe(fury1, fury2, Integer.MAX_VALUE));
    assertEquals(Long.MAX_VALUE, serDe(fury1, fury2, Long.MAX_VALUE));
    assertEquals(Float.MAX_VALUE, serDe(fury1, fury2, Float.MAX_VALUE));
    assertEquals(Double.MAX_VALUE, serDe(fury1, fury2, Double.MAX_VALUE));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void basicTest(boolean referenceTracking) {
    FuryBuilder builder =
        Fury.builder().withRefTracking(referenceTracking).requireClassRegistration(false);
    Fury fury1 = builder.build();
    Fury fury2 = builder.build();
    assertEquals("str", serDe(fury1, fury2, "str"));
    assertEquals("str", serDe(fury1, fury2, new StringBuilder("str")).toString());
    assertEquals("str", serDe(fury1, fury2, new StringBuffer("str")).toString());
    assertEquals(EnumSerializerTest.EnumFoo.A, serDe(fury1, fury2, EnumSerializerTest.EnumFoo.A));
    assertEquals(EnumSerializerTest.EnumFoo.B, serDe(fury1, fury2, EnumSerializerTest.EnumFoo.B));
    assertEquals(
        EnumSerializerTest.EnumSubClass.A, serDe(fury1, fury2, EnumSerializerTest.EnumSubClass.A));
    assertEquals(
        EnumSerializerTest.EnumSubClass.B, serDe(fury1, fury2, EnumSerializerTest.EnumSubClass.B));
    assertEquals(BigInteger.valueOf(100), serDe(fury1, fury2, BigInteger.valueOf(100)));
    assertEquals(BigDecimal.valueOf(100, 2), serDe(fury1, fury2, BigDecimal.valueOf(100, 2)));
    java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
    assertEquals(sqlDate, serDe(fury1, fury2, sqlDate));
    LocalDate localDate = LocalDate.now();
    assertEquals(localDate, serDe(fury1, fury2, localDate));
    Date utilDate = new Date();
    assertEquals(utilDate, serDe(fury1, fury2, utilDate));
    Timestamp timestamp = new Timestamp(System.currentTimeMillis());
    assertEquals(timestamp, serDe(fury1, fury2, timestamp));
    Instant instant = DateTimeUtils.truncateInstantToMicros(Instant.now());
    assertEquals(instant, serDe(fury1, fury2, instant));

    ArraySerializersTest.testPrimitiveArray(fury1, fury2);

    assertEquals(Arrays.asList(1, 2), serDe(fury1, fury2, Arrays.asList(1, 2)));
    List<String> arrayList = Arrays.asList("str", "str");
    assertEquals(arrayList, serDe(fury1, fury2, arrayList));
    assertEquals(new LinkedList<>(arrayList), serDe(fury1, fury2, new LinkedList<>(arrayList)));
    assertEquals(new HashSet<>(arrayList), serDe(fury1, fury2, new HashSet<>(arrayList)));
    TreeSet<String> treeSet = new TreeSet<>(Comparator.naturalOrder());
    treeSet.add("str1");
    treeSet.add("str2");
    assertEquals(treeSet, serDe(fury1, fury2, treeSet));

    HashMap<String, Integer> hashMap = new HashMap<>();
    hashMap.put("k1", 1);
    hashMap.put("k2", 2);
    assertEquals(hashMap, serDe(fury1, fury2, hashMap));
    assertEquals(new LinkedHashMap<>(hashMap), serDe(fury1, fury2, new LinkedHashMap<>(hashMap)));
    TreeMap<String, Integer> treeMap = new TreeMap<>(Comparator.naturalOrder());
    treeMap.putAll(hashMap);
    assertEquals(treeMap, serDe(fury1, fury2, treeMap));
    assertEquals(Collections.EMPTY_LIST, serDe(fury1, fury2, Collections.EMPTY_LIST));
    assertEquals(Collections.EMPTY_SET, serDe(fury1, fury2, Collections.EMPTY_SET));
    assertEquals(Collections.EMPTY_MAP, serDe(fury1, fury2, Collections.EMPTY_MAP));
    assertEquals(
        Collections.singletonList("str"), serDe(fury1, fury2, Collections.singletonList("str")));
    assertEquals(Collections.singleton("str"), serDe(fury1, fury2, Collections.singleton("str")));
    assertEquals(
        Collections.singletonMap("k", 1), serDe(fury1, fury2, Collections.singletonMap("k", 1)));
  }

  @Test(dataProvider = "languageConfig")
  public void testSerializationToBuffer(Language language) {
    Fury fury1 = Fury.builder().withLanguage(language).requireClassRegistration(false).build();
    Fury fury2 = Fury.builder().withLanguage(language).requireClassRegistration(false).build();
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    assertSerializationToBuffer(fury1, fury2, buffer);
  }

  @Test(dataProvider = "languageConfig")
  public void testSerializationSlicedBuffer(Language language) {
    Fury fury1 = Fury.builder().withLanguage(language).requireClassRegistration(false).build();
    Fury fury2 = Fury.builder().withLanguage(language).requireClassRegistration(false).build();
    MemoryBuffer buffer0 = MemoryUtils.buffer(64);
    buffer0.writeInt64(-1);
    buffer0.writeInt64(-1);
    buffer0.readInt64();
    buffer0.readInt64();
    MemoryBuffer buffer = buffer0.slice(8);
    assertSerializationToBuffer(fury1, fury2, buffer);
  }

  public void assertSerializationToBuffer(Fury fury1, Fury fury2, MemoryBuffer buffer) {
    assertEquals(true, serDeCheckIndex(fury1, fury2, buffer, true));
    assertEquals(Byte.MAX_VALUE, serDeCheckIndex(fury1, fury2, buffer, Byte.MAX_VALUE));
    assertEquals(Short.MAX_VALUE, serDeCheckIndex(fury1, fury2, buffer, Short.MAX_VALUE));
    assertEquals("str", serDeCheckIndex(fury1, fury2, buffer, "str"));
    assertEquals("str", serDeCheckIndex(fury1, fury2, buffer, new StringBuilder("str")).toString());
    if (fury1.getLanguage() != Language.JAVA) {
      fury1.register(EnumSerializerTest.EnumFoo.class);
      fury2.register(EnumSerializerTest.EnumFoo.class);
      fury1.register(EnumSerializerTest.EnumSubClass.class);
      fury2.register(EnumSerializerTest.EnumSubClass.class);
    }
    assertEquals(
        EnumSerializerTest.EnumFoo.A,
        serDeCheckIndex(fury1, fury2, buffer, EnumSerializerTest.EnumFoo.A));
    assertEquals(
        EnumSerializerTest.EnumSubClass.A,
        serDeCheckIndex(fury1, fury2, buffer, EnumSerializerTest.EnumSubClass.A));
    assertTrue(
        Arrays.equals(
            new boolean[] {false, true},
            (boolean[]) serDeCheckIndex(fury1, fury2, buffer, new boolean[] {false, true})));
    assertEquals(
        new byte[] {1, 1}, (byte[]) serDeCheckIndex(fury1, fury2, buffer, new byte[] {1, 1}));
    assertEquals(Arrays.asList(1, 2), serDe(fury1, fury2, buffer, Arrays.asList(1, 2)));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void serializeBeanTest(boolean referenceTracking) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    byte[] bytes = fury.serialize(beanA);
    Object o = fury.deserialize(bytes);
    assertEquals(beanA, o);
  }

  @Test
  public void testSerializeException() {
    Fury fury = Fury.builder().withLanguage(Language.JAVA).withRefTracking(true).build();
    fury.serialize(new Exception());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void registerTest(boolean referenceTracking) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    fury.register(BeanA.class);
    BeanA beanA = BeanA.createBeanA(2);
    assertEquals(beanA, serDe(fury, beanA));
  }

  @EqualsAndHashCode
  static class A implements Serializable {
    public Object f1 = 1;
    public Object f2 = 1;
    private Object f3 = "str";

    Object getF3() {
      return f3;
    }

    void setF3(Object f3) {
      this.f3 = f3;
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testOffHeap(boolean referenceTracking) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    long ptr = 0;
    try {
      int size = 1024;
      ptr = Platform.allocateMemory(size);
      MemoryBuffer buffer = fury.serialize(new A(), ptr, size);
      assertNull(buffer.getHeapMemory());

      Object obj = fury.deserialize(ptr, size);
      assertEquals(new A(), obj);
    } finally {
      Platform.freeMemory(ptr);
    }
  }

  public static class Outer {
    private long x;
    private Inner inner;

    private static class Inner {
      int y;
    }
  }

  @Test
  public void testSerializePrivateBean() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build();
    Outer outer = new Outer();
    outer.inner = new Outer.Inner();
    fury.deserialize(fury.serialize(outer));
    assertTrue(fury.getClassResolver().getSerializer(Outer.class) instanceof ObjectSerializer);
    assertTrue(
        fury.getClassResolver().getSerializer(Outer.Inner.class) instanceof ObjectSerializer);
  }

  @Test
  public void testSerializePrivateBeanJIT() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    Outer outer = new Outer();
    outer.inner = new Outer.Inner();
    fury.deserialize(fury.serialize(outer));
    assertTrue(fury.getClassResolver().getSerializer(Outer.class) instanceof Generated);
    assertTrue(fury.getClassResolver().getSerializer(Outer.Inner.class) instanceof Generated);
  }

  @Data
  public static class PackageLevelBean {
    public long f1;
    private long f2;
  }

  @Test
  public void testSerializePackageLevelBean() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build();
    PackageLevelBean o = new PackageLevelBean();
    o.f1 = 10;
    o.f2 = 1;
    serDeCheckSerializer(fury, o, "Object");
  }

  @Test
  public void testSerializePackageLevelBeanJIT() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    PackageLevelBean o = new PackageLevelBean();
    o.f1 = 10;
    o.f2 = 1;
    serDeCheckSerializer(fury, o, "PackageLevelBean");
  }

  static class B {
    int f1;
  }

  static class C extends B {
    int f1;
  }

  @Test(dataProvider = "javaFury")
  public void testDuplicateFields(Fury fury) {
    C c = new C();
    ((B) c).f1 = 100;
    c.f1 = -100;
    assertEquals(((B) c).f1, 100);
    assertEquals(c.f1, -100);
    C newC = (C) serDe(fury, c);
    assertEquals(newC.f1, c.f1);
    assertEquals(((B) newC).f1, ((B) c).f1);
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testGuava(boolean referenceTracking, Language language) {
    Fury fury =
        Fury.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    Assert.assertEquals(serDe(fury, ImmutableList.of(1)), ImmutableList.of(1));
    Assert.assertEquals(serDe(fury, ImmutableList.of(1, 2)), ImmutableList.of(1, 2));
    Assert.assertEquals(serDe(fury, ImmutableList.of(1, 2, "str")), ImmutableList.of(1, 2, "str"));
    Assert.assertEquals(
        serDe(fury, ImmutableMap.of(1, 2, "k", "v")), ImmutableMap.of(1, 2, "k", "v"));
  }

  @Test(dataProvider = "enableCodegen")
  public void testSerializeJDKObject(boolean enableCodegen) {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withJdkClassSerializableCheck(false)
            .requireClassRegistration(false)
            .withCodegen(enableCodegen)
            .build();
    StringTokenizer tokenizer = new StringTokenizer("abc,1,23", ",");
    assertEquals(serDe(fury, tokenizer).countTokens(), tokenizer.countTokens());
  }

  @Test
  public void testJDKSerializableCheck() {
    Fury fury =
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    serDe(fury, ByteBuffer.allocate(32));
    serDe(fury, ByteBuffer.allocateDirect(32));
    assertThrows(InsecureException.class, () -> fury.serialize(new Thread()));
    assertThrows(UnsupportedOperationException.class, () -> fury.serialize(MethodHandles.lookup()));
  }

  @Test
  public void testClassRegistration() {
    Fury fury = Fury.builder().requireClassRegistration(true).build();
    class A {}
    assertThrows(InsecureException.class, () -> fury.serialize(new A()));
    Fury fury1 = Fury.builder().requireClassRegistration(false).build();
    serDe(fury1, new A());
  }

  @Data
  @AllArgsConstructor
  private static class IgnoreFields {
    @Ignore int f1;
    @Ignore long f2;
    long f3;
  }

  @Test
  public void testIgnoreFields() {
    Fury fury = Fury.builder().requireClassRegistration(false).withCodegen(false).build();
      ImmutableMap<String, Integer> map1 = ImmutableMap.of("1", 1);
      ImmutableMap<String, Integer> map2 = ImmutableMap.of("2", 2);
      ExposeFields o = serDe(fury, new ExposeFields(1, 2, 3, map1, map2));
    IgnoreFields o1 = serDe(fury, new IgnoreFields(1, 2, 3));
    assertEquals(0, o.f1);
    assertEquals(0, o.f2);
    assertEquals(3, o.f3);
  }

  @Data
  @AllArgsConstructor
  private static class ExposeFields {
    @Expose int f1;
    @Expose long f2;
    long f3;
    @Expose ImmutableMap<String, Integer> map1;
    ImmutableMap<String, Integer> map2;
  }

  @Test
  public void testExposeFields() {
    Fury fury = Fury.builder().requireClassRegistration(false).build();
    ImmutableMap<String, Integer> map1 = ImmutableMap.of("1", 1);
    ImmutableMap<String, Integer> map2 = ImmutableMap.of("2", 2);
    ExposeFields o = serDe(fury, new ExposeFields(1, 2, 3, map1, map2));
    assertEquals(1, o.f1);
    assertEquals(2, o.f2);
    assertEquals(0, o.f3);
    assertEquals(o.map1, map1);
    assertNull(o.map2);
  }

  @Data
  @AllArgsConstructor
  private static class ExposeFields2 {
    @Expose int f1;
    @Ignore long f2;
    long f3;
  }

  @Test
  public void testExposeFields2() {
    Fury fury = Fury.builder().requireClassRegistration(false).build();
    assertThrows(RuntimeException.class, () -> serDe(fury, new ExposeFields2(1, 2, 3)));
  }

  @Test(timeOut = 60_000)
  public void testClassGC() {
    WeakHashMap<Object, Boolean> map = new WeakHashMap<>();
    furyGC(map);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    Descriptor.clearDescriptorCache();
    TestUtils.triggerOOMForSoftGC(
        () -> {
          System.out.printf("Wait map keys %s gc.\n", map.keySet());
          return !map.isEmpty();
        });
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
  }

  private void furyGC(WeakHashMap<Object, Boolean> map) {
    Fury fury = Fury.builder().requireClassRegistration(false).build();
    Class<?> structClass1 = Struct.createStructClass("TestClassGC", 1, false);
    System.out.println(structClass1.hashCode());
    Object struct1 = Struct.createPOJO(structClass1);
    serDe(fury, struct1);
    Class<? extends Serializer> serializerClass =
        fury.getClassResolver().getSerializerClass(structClass1);
    assertTrue(serializerClass.getName().contains("Codec"));
    map.put(fury, true);
    System.out.println(fury.hashCode());
    map.put(struct1, true);
    map.put(structClass1, true);
    System.out.println(structClass1.hashCode());
  }

  @Test
  public void testSerializeJavaObject() {
    Fury fury = Fury.builder().requireClassRegistration(false).withLanguage(Language.JAVA).build();
    BeanA beanA = BeanA.createBeanA(2);
    assertEquals(fury.deserializeJavaObject(fury.serializeJavaObject(beanA), BeanA.class), beanA);
    assertEquals(
        fury.deserializeJavaObjectAndClass(fury.serializeJavaObjectAndClass(beanA)), beanA);
    assertEquals(
        fury.deserializeJavaObjectAndClass(
            MemoryBuffer.fromByteArray(fury.serializeJavaObjectAndClass(beanA))),
        beanA);
  }

  @Data
  static class DomainObject {
    UUID id;
  }

  static class UUIDSerializer extends Serializer<UUID> {
    public UUIDSerializer(Fury fury) {
      super(fury, UUID.class);
    }

    @Override
    public UUID read(MemoryBuffer buffer) {
      return new UUID(buffer.readInt64(), buffer.readInt64());
    }

    @Override
    public void write(MemoryBuffer buffer, UUID value) {
      buffer.writeInt64(value.getMostSignificantBits());
      buffer.writeInt64(value.getLeastSignificantBits());
    }
  }

  @Test
  public void testRegisterPrivateSerializer() {
    Fury fury = Fury.builder().withRefTracking(true).requireClassRegistration(false).build();
    fury.registerSerializer(UUID.class, new UUIDSerializer(fury));
    DomainObject obj = new DomainObject();
    obj.id = UUID.randomUUID();
    serDeCheckSerializer(fury, obj, "Codec");
  }

  @Test
  public void testCircularReferenceStackOverflowMessage() {
    class A {
      A f;
    }
    A a = new A();
    a.f = a;
    Fury fury = Fury.builder().withRefTracking(false).requireClassRegistration(false).build();
    try {
      fury.serialize(a);
      throw new IllegalStateException("StackOverflowError not raised.");
    } catch (StackOverflowError e) {
      Assert.assertTrue(e.getMessage().contains("reference"));
    }
  }

  @Test
  public void testPkgAccessLevelParentClass() {
    Fury fury = Fury.builder().withRefTracking(true).requireClassRegistration(false).build();
    HashBasedTable<Object, Object, Object> table = HashBasedTable.create(2, 4);
    table.put("r", "c", 100);
    serDeCheckSerializer(fury, table, "Codec");
  }

  @Data
  static class PrintReadObject {
    public PrintReadObject() {
      throw new RuntimeException();
    }

    public PrintReadObject(boolean b) {}
  }

  @Test
  public void testPrintReadObjectsWhenFailed() {
    Fury fury =
        Fury.builder()
            .withRefTracking(true)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build();
    PrintReadObject o = new PrintReadObject(true);
    try {
      serDe(fury, ImmutableList.of(ImmutableList.of("a", "b"), o));
      Assert.fail();
    } catch (FuryException e) {
      Assert.assertTrue(e.getMessage().contains("[a, b]"));
    }
  }

  @Test
  public void testNullObjSerAndDe() {
    Fury fury =
        Fury.builder()
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withMetaShare(true)
            .build();
    MetaContext metaContext = new MetaContext();
    fury.getSerializationContext().setMetaContext(metaContext);
    byte[] bytes = fury.serializeJavaObjectAndClass(null);
    fury.getSerializationContext().setMetaContext(metaContext);
    Object obj = fury.deserializeJavaObjectAndClass(bytes);
    assertNull(obj);
  }

  @Test
  public void testResetBufferToSizeLimit() {
    final int minBufferBytes = 64;
    final int limitInBytes = 1024;
    Fury fury = Fury.builder().withBufferSizeLimitBytes(limitInBytes).build();

    final byte[] smallPayload = new byte[0];
    final byte[] serializedSmall = fury.serialize(smallPayload);
    assertEquals(fury.getBuffer().size(), minBufferBytes);

    fury.deserialize(serializedSmall);
    assertEquals(fury.getBuffer().size(), minBufferBytes);

    final byte[] largePayload = new byte[limitInBytes * 2];
    final byte[] serializedLarge = fury.serialize(largePayload);
    assertEquals(fury.getBuffer().size(), limitInBytes);

    fury.deserialize(serializedLarge);
    assertEquals(fury.getBuffer().size(), limitInBytes);
  }

  static class Struct1 {
    int f1;
    String f2;

    public Struct1(int f1, String f2) {
      this.f1 = f1;
      this.f2 = f2;
    }
  }

  static class Struct2 {
    int f1;
    String f2;
    double f3;
  }

  @Test
  public void testStructMapping() {
    ThreadSafeFury fury1 =
        Fury.builder().withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFury();
    ThreadSafeFury fury2 =
        Fury.builder().withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFury();
    fury1.register(Struct1.class);
    fury2.register(Struct2.class);
    Struct1 struct1 = new Struct1(10, "abc");
    Struct2 struct2 = (Struct2) fury2.deserialize(fury1.serialize(struct1));
    Assert.assertEquals(struct2.f1, struct1.f1);
    Assert.assertEquals(struct2.f2, struct1.f2);
    struct1 = (Struct1) fury1.deserialize(fury2.serialize(struct2));
    Assert.assertEquals(struct1.f1, struct2.f1);
    Assert.assertEquals(struct1.f2, struct2.f2);
  }
}
