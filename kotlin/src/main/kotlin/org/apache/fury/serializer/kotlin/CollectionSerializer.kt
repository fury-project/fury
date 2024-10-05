package org.apache.fury.serializer.kotlin

import org.apache.fury.Fury
import org.apache.fury.memory.MemoryBuffer
import org.apache.fury.serializer.collection.AbstractCollectionSerializer
import kotlin.reflect.KClass

abstract class AbstractKotlinCollectionSerializer<E, T: Iterable<E>>(
    fury: Fury,
    cls: KClass<T>
) : AbstractCollectionSerializer<T>(fury, cls.java) {
    abstract override fun onCollectionWrite(buffer: MemoryBuffer, value: T): Collection<E>

    override fun read(buffer: MemoryBuffer): T {
        val collection = newCollection(buffer)
        val numElements = getAndClearNumElements()
        if (numElements != 0) readElements(fury, buffer, collection, numElements)
        return onCollectionRead(collection)
    }
}

typealias CollectionAdapter<E> = java.util.AbstractCollection<E>

/**
 * An adapter which wraps a kotlin iterable into a [[java.util.Collection]].
 *
 *
 */
private class IterableAdapter<E>(
    coll: Iterable<E>
) : CollectionAdapter<E>() {
    private val mutableList = coll.toMutableList()

    override val size: Int
        get() = mutableList.count()

    override fun iterator(): MutableIterator<E> =
        mutableList.iterator()
}

/**
 * An adapter which wraps a kotlin set into a [[java.util.Collection]].
 *
 *
 */
private class SetAdapter<E>(
    coll: Set<E>
) : CollectionAdapter<E>() {
    private val mutableSet = coll.toMutableSet()

    override val size: Int
        get() = mutableSet.size

    override fun iterator(): MutableIterator<E> =
        mutableSet.iterator()
}

abstract class AbstractKotlinIterableSerializer<E, T: List<E>>(
    fury: Fury,
    cls: KClass<T>
) : AbstractKotlinCollectionSerializer<E, T>(fury, cls) {
    override fun onCollectionWrite(buffer: MemoryBuffer, value: T): Collection<E> {
        val adapter = IterableAdapter<E>(value)
        buffer.writeVarUint32Small7(adapter.size)
        return adapter
    }

    override fun newCollection(buffer: MemoryBuffer): Collection<E> {
        val numElements = buffer.readVarUint32()
        setNumElements(numElements)
        return ListBuilder<E>()
    }
}

abstract class AbstractKotlinSetSerializer<E, T: Set<E>>(
    fury: Fury,
    cls: KClass<T>
) : AbstractKotlinCollectionSerializer<E, T>(fury, cls) {
    override fun onCollectionWrite(buffer: MemoryBuffer, value: T): Collection<E> {
        val adapter = SetAdapter<E>(value)
        buffer.writeVarUint32Small7(adapter.size)
        return adapter
    }

    override fun newCollection(buffer: MemoryBuffer): Collection<E> {
        val numElements = buffer.readVarUint32()
        setNumElements(numElements)
        return SetBuilder<E>()
    }
}
