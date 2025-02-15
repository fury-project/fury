using System;
using Fury.Collections;

namespace Fury.Buffers;

/// <summary>
/// A simple object pool.
/// </summary>
/// <typeparam name="T"></typeparam>
internal readonly struct ObjectPool<T>(IArrayPoolProvider poolProvider, Func<T> factory)
    where T : class
{
    private readonly PooledList<T> _objects = new(poolProvider);

    public T Rent()
    {
        var lastIndex = _objects.Count - 1;
        if (lastIndex < 0)
        {
            return factory();
        }

        var obj = _objects[lastIndex];
        _objects.RemoveAt(lastIndex);
        return obj;
    }

    public void Return(T obj)
    {
        _objects.Add(obj);
    }
}
