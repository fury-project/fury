/*
 * Copyright 2023 The Fury authors
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.serializer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.fury.Fury;
import io.fury.FuryTestBase;
import io.fury.Language;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@SuppressWarnings({"unchecked", "rawtypes"})
public class ChildContainerSerializersTest extends FuryTestBase {
  public static class ChildArrayList<E> extends ArrayList<E> {
    private int state;

    @Override
    public String toString() {
      return "ChildArrayList{" + "state=" + state + ",data=" + super.toString() + '}';
    }
  }

  public static class ChildLinkedList<E> extends LinkedList<E> {}

  public static class ChildArrayDeque<E> extends ArrayDeque<E> {}

  public static class ChildVector<E> extends Vector<E> {}

  public static class ChildHashSet<E> extends HashSet<E> {}

  @DataProvider(name = "furyConfig")
  public static Object[][] furyConfig() {
    return new Object[][] {
      {
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withReferenceTracking(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .disableSecureMode()
            .build()
      },
      {
        Fury.builder()
            .withLanguage(Language.JAVA)
            .withReferenceTracking(false)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .disableSecureMode()
            .build()
      },
    };
  }

  @Test(dataProvider = "furyConfig")
  public void testChildCollection(Fury fury) {
    List<Integer> data = ImmutableList.of(1, 2);
    {
      ChildArrayList<Integer> list = new ChildArrayList<>();
      list.addAll(data);
      list.state = 3;
      ChildArrayList<Integer> newList = (ChildArrayList) serDe(fury, list);
      Assert.assertEquals(newList, list);
      Assert.assertEquals(newList.state, 3);
      Assert.assertEquals(
          fury.getClassResolver().getSerializer(newList.getClass()).getClass(),
          ChildContainerSerializers.ChildArrayListSerializer.class);
      ArrayList<Integer> innerList =
          new ArrayList<Integer>() {
            {
              add(1);
            }
          };
      // innerList captures outer this.
      serDeCheck(fury, innerList);
      Assert.assertEquals(
          fury.getClassResolver().getSerializer(innerList.getClass()).getClass(),
          CollectionSerializers.JDKCompatibleCollectionSerializer.class);
    }
    {
      ChildLinkedList<Integer> list = new ChildLinkedList<>();
      list.addAll(data);
      serDeCheck(fury, list);
    }
    {
      ChildArrayDeque<Integer> list = new ChildArrayDeque<>();
      list.addAll(data);
      Assert.assertEquals(ImmutableList.copyOf((ArrayDeque) (serDe(fury, list))), data);
    }
    {
      ChildVector<Integer> list = new ChildVector<>();
      list.addAll(data);
      serDeCheck(fury, list);
    }
    {
      ChildHashSet<Integer> list = new ChildHashSet<>();
      list.addAll(data);
      serDeCheck(fury, list);
    }
  }

  public static class ChildHashMap<K, V> extends HashMap<K, V> {
    private int state;
  }

  public static class ChildLinkedHashMap<K, V> extends LinkedHashMap<K, V> {}

  public static class ChildConcurrentHashMap<K, V> extends ConcurrentHashMap<K, V> {}

  @Test(dataProvider = "furyConfig")
  public void testChildMap(Fury fury) {
    Map<String, Integer> data = ImmutableMap.of("k1", 1, "k2", 2);
    {
      ChildHashMap<String, Integer> map = new ChildHashMap<>();
      map.putAll(data);
      map.state = 3;
      ChildHashMap<String, Integer> newMap = (ChildHashMap<String, Integer>) serDe(fury, map);
      Assert.assertEquals(newMap, map);
      Assert.assertEquals(newMap.state, 3);
      Assert.assertEquals(
          fury.getClassResolver().getSerializer(newMap.getClass()).getClass(),
          ChildContainerSerializers.ChildMapSerializer.class);
    }
    {
      ChildLinkedHashMap<String, Integer> map = new ChildLinkedHashMap<>();
      map.putAll(data);
      serDeCheck(fury, map);
    }
    {
      ChildConcurrentHashMap<String, Integer> map = new ChildConcurrentHashMap<>();
      map.putAll(data);
      serDeCheck(fury, map);
    }
  }
}
