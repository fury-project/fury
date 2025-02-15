using System;
using System.Buffers;
using System.Collections;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using Fury.Buffers;

namespace Fury.Collections;

/// <summary>
/// A list that uses pooled arrays to reduce allocations.
/// </summary>
/// <param name="poolProvider">
/// The pool provider to use for array pooling.
/// </param>
/// <typeparam name="TElement">
/// The type of elements in the list.
/// </typeparam>
internal sealed class PooledList<TElement>(IArrayPoolProvider poolProvider) : IList<TElement?>, IDisposable
    where TElement : class
{
    // Use object instead of TElement to improve possibility of reusing pooled objects.
    private readonly ArrayPool<object?> _pool = poolProvider.GetArrayPool<object?>();
    private object?[] _elements = [];
    public int Count { get; private set; }

    public Enumerator GetEnumerator() => new(this);

    public void Add(TElement? item)
    {
        var length = _elements.Length;
        if (Count == length)
        {
            var newLength = Math.Max(length * 2, StaticConfigs.BuiltInListDefaultCapacity);
            var newElements = _pool.Rent(newLength);
            _elements.CopyTo(newElements, 0);
            ClearElements();
            _pool.Return(_elements);
            _elements = newElements;
        }
        _elements[Count++] = item;
    }

    public void Clear()
    {
        ClearElements();
        Count = 0;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private void ClearElements()
    {
        Array.Clear(_elements, 0, _elements.Length);
    }

    public bool Contains(TElement? item) => Array.IndexOf(_elements, item) != -1;

    public void CopyTo(TElement?[] array, int arrayIndex) => _elements.CopyTo(array, arrayIndex);

    public bool Remove(TElement? item)
    {
        var index = Array.IndexOf(_elements, item);
        if (index == -1)
        {
            return false;
        }

        RemoveAt(index);
        return true;
    }

    public bool IsReadOnly => _elements.IsReadOnly;

    public int IndexOf(TElement? item) => Array.IndexOf(_elements, item);

    public void Insert(int index, TElement? item)
    {
        if (index < 0 || index > Count)
        {
            ThrowHelper.ThrowArgumentOutOfRangeException(nameof(index), index);
        }

        var length = _elements.Length;
        if (Count == length)
        {
            var newLength = Math.Max(length * 2, StaticConfigs.BuiltInListDefaultCapacity);
            var newElements = _pool.Rent(newLength);
            _elements.CopyTo(newElements, 0);
            Array.Copy(_elements, 0, newElements, 0, index);
            newElements[index] = item;
            Array.Copy(_elements, index, newElements, index + 1, Count - index);
            ClearElements();
            _pool.Return(_elements);
            _elements = newElements;
        }
        else
        {
            Array.Copy(_elements, index, _elements, index + 1, Count - index);
            _elements[index] = item;
        }
        Count++;
    }

    public void RemoveAt(int index)
    {
        ThrowIfOutOfRange(index, nameof(index));

        if (index < Count - 1)
        {
            Array.Copy(_elements, index + 1, _elements, index, Count - index - 1);
        }
        Count--;
        _elements[Count] = default!;
    }

    public TElement? this[int index]
    {
        get
        {
            ThrowIfOutOfRange(index, nameof(index));
            return Unsafe.As<TElement>(_elements[index]);
        }
        set
        {
            ThrowIfOutOfRange(index, nameof(index));
            _elements[index] = value;
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private void ThrowIfOutOfRange(int index, string paramName)
    {
        if (index < 0 || index >= Count)
        {
            ThrowHelper.ThrowArgumentOutOfRangeException(paramName, index);
        }
    }

    IEnumerator<TElement> IEnumerable<TElement?>.GetEnumerator() => GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    public struct Enumerator(PooledList<TElement> list) : IEnumerator<TElement?>
    {
        private int _count = list.Count;
        private int _current = 0;

        public bool MoveNext()
        {
            return _current++ < _count;
        }

        public void Reset()
        {
            _count = list.Count;
            _current = 0;
        }

        public TElement? Current => Unsafe.As<TElement>(list._elements[_current]);

        object? IEnumerator.Current => Current;

        public void Dispose() { }
    }

    public void Dispose()
    {
        if (_elements.Length <= 0)
        {
            return;
        }

        ClearElements();
        _pool.Return(_elements);
        _elements = [];
    }
}
