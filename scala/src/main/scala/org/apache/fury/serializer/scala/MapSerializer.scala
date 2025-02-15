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

package org.apache.fury.serializer.scala

import org.apache.fury.Fury
import org.apache.fury.collection.MapEntry
import org.apache.fury.memory.MemoryBuffer
import org.apache.fury.serializer.collection.AbstractMapSerializer

import java.util
import scala.collection.{Factory, mutable}

/**
 * Serializer for scala map.
 *
 * The main processes for map serialization:
 * <li>`onMapWrite`: write map size and scala map factory, then
 * return a [[java.util.Map]] adapter for fury java map framework to invoke.</li>
 * <li>Fury java map framework write all map elements by fury protocol.</li>
 *
 * The main processes for map deserialization:
 * <li>`newMap`: read and set map size, read map factory,
 * create a new [[java.util.Map]] adapter with the map builder
 * by factory for java map framework to invoke.
 * </li>
 * <li>Fury java map framework read all map elements by fury protocol,
 * invoke [[java.util.Map#put]] to add it into builder.</li>
 * <li>`onMapRead`: create scala map from builder.</li>
 */
abstract class AbstractScalaMapSerializer[K, V, T](fury: Fury, cls: Class[T])
  extends AbstractMapSerializer[T](fury, cls) {
  def onMapWrite(buffer: MemoryBuffer, value: T): util.Map[_, _]

  override def read(buffer: MemoryBuffer): T = {
    val map = newMap(buffer)
    val numElements = getAndClearNumElements()
    if (numElements != 0) readElements(buffer, numElements, map)
    onMapRead(map)
  }

  override def newMap(buffer: MemoryBuffer): util.Map[_, _] = {
    val numElements = buffer.readVarUint32()
    setNumElements(numElements)
    val factory = fury.readRef(buffer).asInstanceOf[Factory[(K, V), T]]
    val builder = factory.newBuilder
    builder.sizeHint(numElements)
    new MapBuilder[K, V, T](builder)
  }

  override def onMapCopy(map: util.Map[_, _]): T = {
    map.asInstanceOf[MapBuilder[K, V, T]].builder.result()
  }

  override def onMapRead(map: util.Map[_, _]): T = {
    map.asInstanceOf[MapBuilder[K, V, T]].builder.result()
  }
}

/**
 * A [[util.Map]] adapter to wrap scala map as a java [[util.Map]].
 *
 *
 */
private class MapAdapter[K, V](var map: scala.collection.Map[K, V])
  extends util.AbstractMap[K, V] {
  override def entrySet(): util.Set[util.Map.Entry[K, V]] = new util.AbstractSet[util.Map.Entry[K, V]] {
    override def size(): Int = map.size

    override def iterator(): util.Iterator[util.Map.Entry[K, V]] = new util.Iterator[util.Map.Entry[K, V]] {
      private val it = map.iterator

      override def hasNext: Boolean = it.hasNext

      override def next(): util.Map.Entry[K, V] = {
        val e = it.next()
        new MapEntry[K, V](e._1, e._2)
      }
    }
  }
}

/**
 * A [[util.Map]] adapter to build scala [[scala.collection.Map]] from elements.
 *
 *
 */
private class MapBuilder[K, V, T](val builder: mutable.Builder[(K, V), T])
  extends util.AbstractMap[K, V] {
  override def entrySet(): util.Set[util.Map.Entry[K, V]] = ???

  override def put(key: K, value: V): V = {
    builder.addOne((key, value))
    value
  }
}

/**
 * Serializer for scala map.
 *
 *
 */
class ScalaMapSerializer[K, V, T <: scala.collection.Map[K, V]](fury: Fury, cls: Class[T])
  extends AbstractScalaMapSerializer[K, V, T](fury, cls) {

  override def onMapWrite(buffer: MemoryBuffer, value: T): util.Map[_, _] = {
    buffer.writeVarUint32Small7(value.size)
    val factory = value.mapFactory.mapFactory[Any, Any].asInstanceOf[Factory[Any, Any]]
    fury.writeRef(buffer, factory)
    new MapAdapter[K, V](value)
  }
}

/**
 * Serializer for scala sorted map.
 *
 *
 */
class ScalaSortedMapSerializer[K, V, T <: scala.collection.SortedMap[K, V]](fury: Fury, cls: Class[T])
  extends AbstractScalaMapSerializer[K, V, T](fury, cls) {
  override def onMapWrite(buffer: MemoryBuffer, value: T): util.Map[_, _] = {
    buffer.writeVarUint32Small7(value.size)
    val factory = value.sortedMapFactory.sortedMapFactory[Any, Any](
      value.ordering.asInstanceOf[Ordering[Any]]).asInstanceOf[Factory[Any, Any]]
    fury.writeRef(buffer, factory)
    new MapAdapter[K, V](value)
  }
}

abstract class AbstractScalaMapSerializer[K, V, T](fury: Fury, cls: Class[T])
  extends AbstractMapSerializer[T](fury, cls) {
  
  // Existing methods

  // Add copy method
  def copy(value: T): T = {
    val builder = value.mapFactory.newBuilder[K, V]
    builder ++= value
    builder.result()
  }
}

class ScalaMapSerializer[K, V, T <: scala.collection.Map[K, V]](fury: Fury, cls: Class[T])
  extends AbstractScalaMapSerializer[K, V, T](fury, cls) {

  override def onMapWrite(buffer: MemoryBuffer, value: T): util.Map[_, _] = {
    buffer.writeVarUint32Small7(value.size)
    val factory = value.mapFactory.mapFactory[Any, Any].asInstanceOf[Factory[Any, Any]]
    fury.writeRef(buffer, factory)
    new MapAdapter[K, V](value)
  }

  // Implement copy method
  override def copy(value: T): T = super.copy(value)
}

class ScalaSortedMapSerializer[K, V, T <: scala.collection.SortedMap[K, V]](fury: Fury, cls: Class[T])
  extends AbstractScalaMapSerializer[K, V, T](fury, cls) {
  override def onMapWrite(buffer: MemoryBuffer, value: T): util.Map[_, _] = {
    buffer.writeVarUint32Small7(value.size)
    val factory = value.sortedMapFactory.sortedMapFactory[Any, Any](
      value.ordering.asInstanceOf[Ordering[Any]]).asInstanceOf[Factory[Any, Any]]
    fury.writeRef(buffer, factory)
    new MapAdapter[K, V](value)
  }

  // Implement copy method
  override def copy(value: T): T = super.copy(value)
}
