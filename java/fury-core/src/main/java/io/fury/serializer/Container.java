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

package io.fury.serializer;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;

/**
 * A collection container to hold collection elements by array.
 *
 * @author chaokunyang
 */
class CollectionContainer<T> extends AbstractCollection<T> {
  final Object[] elements;
  int size;

  public CollectionContainer(int capacity) {
    elements = new Object[capacity];
  }

  @Override
  public Iterator<T> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean add(Object o) {
    elements[size++] = o;
    return true;
  }
}

/**
 * A sorted collection container to hold collection elements and comparator.
 *
 * @author chaokunyang
 */
class SortedCollectionContainer<T> extends CollectionContainer<T> {
  Comparator<T> comparator;

  public SortedCollectionContainer(Comparator<T> comparator, int capacity) {
    super(capacity);
    this.comparator = comparator;
  }
}

/**
 * A map container to hold map key and value elements by arrays.
 *
 * @author chaokunyang
 */
class MapContainer<K, V> extends AbstractMap<K, V> {
  final Object[] keyArray;
  final Object[] valueArray;
  int size;

  public MapContainer(int capacity) {
    keyArray = new Object[capacity];
    valueArray = new Object[capacity];
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public V put(K key, V value) {
    keyArray[size] = key;
    valueArray[size++] = value;
    return null;
  }
}

/**
 * A sorted map container to hold map data and comparator.
 *
 * @author chaokunyang
 */
class SortedMapContainer<K, V> extends MapContainer<K, V> {

  final Comparator<K> comparator;

  public SortedMapContainer(Comparator<K> comparator, int capacity) {
    super(capacity);
    this.comparator = comparator;
  }
}
