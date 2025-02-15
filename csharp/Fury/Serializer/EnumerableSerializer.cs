using System;
using System.Collections.Generic;
using System.Linq;
using Fury.Serializer.Provider;
#if NET8_0_OR_GREATER
using System.Collections.Immutable;
using System.Runtime.InteropServices;
#endif

namespace Fury.Serializer;

// TEnumerable is required, because we need to assign the serializer to ISerializer<TEnumerable>.

public class EnumerableSerializer<TElement, TEnumerable>(ISerializer<TElement>? elementSerializer)
    : AbstractSerializer<TEnumerable>
    where TElement : notnull
    where TEnumerable : IEnumerable<TElement>
{
    private ISerializer<TElement>? _elementSerializer = elementSerializer;

    // ReSharper disable once UnusedMember.Global
    /// <summary>
    /// This constructor is required for Activator.CreateInstance. See <see cref="EnumerableSerializerProvider.TryCreateSerializer"/>.
    /// </summary>
    public EnumerableSerializer()
        : this(null) { }

    public override void Write(SerializationContext context, in TEnumerable value)
    {
        var count = value.Count();
        context.Writer.WriteCount(count);
        if (count <= 0)
        {
            return;
        }
        var typedElementSerializer = _elementSerializer;
        if (typedElementSerializer is null)
        {
            if (TypeHelper<TElement>.IsSealed)
            {
                typedElementSerializer = (ISerializer<TElement>)context.GetSerializer<TElement>();
                _elementSerializer = typedElementSerializer;
            }
        }
        if (TryGetSpan(value, out var elements))
        {
            foreach (ref readonly var element in elements)
            {
                context.Write(in element, typedElementSerializer);
            }
            return;
        }

        foreach (var element in value)
        {
            context.Write(in element, typedElementSerializer);
        }
    }

    protected virtual bool TryGetSpan(TEnumerable value, out ReadOnlySpan<TElement> span)
    {
        switch (value)
        {
            case TElement[] elements:
                span = elements;
                return true;
#if NET8_0_OR_GREATER
            case List<TElement> elements:
                span = CollectionsMarshal.AsSpan(elements);
                return true;
            case ImmutableArray<TElement> elements:
                span = ImmutableCollectionsMarshal.AsArray(elements);
                return true;
#endif
            default:
                span = ReadOnlySpan<TElement>.Empty;
                return false;
        }
    }
}

public class NullableEnumerableSerializer<TElement, TEnumerable>(ISerializer<TElement>? elementSerializer)
    : AbstractSerializer<TEnumerable>
    where TElement : struct
    where TEnumerable : IEnumerable<TElement?>
{
    private ISerializer<TElement>? _elementSerializer = elementSerializer;

    // ReSharper disable once UnusedMember.Global
    /// <summary>
    /// This constructor is required for Activator.CreateInstance. See <see cref="EnumerableSerializerProvider.TryCreateSerializer"/>.
    /// </summary>
    public NullableEnumerableSerializer()
        : this(null) { }

    public override void Write(SerializationContext context, in TEnumerable value)
    {
        var count = value.Count();
        context.Writer.WriteCount(count);
        if (count <= 0)
        {
            return;
        }
        var typedElementSerializer = _elementSerializer;
        if (typedElementSerializer is null)
        {
            if (TypeHelper<TElement>.IsSealed)
            {
                typedElementSerializer = (ISerializer<TElement>)context.GetSerializer<TElement>();
                _elementSerializer = typedElementSerializer;
            }
        }
        if (TryGetSpan(value, out var elements))
        {
            foreach (ref readonly var element in elements)
            {
                context.Write(in element, typedElementSerializer);
            }
            return;
        }

        foreach (var element in value)
        {
            context.Write(in element, typedElementSerializer);
        }
    }

    protected virtual bool TryGetSpan(TEnumerable value, out ReadOnlySpan<TElement?> span)
    {
        switch (value)
        {
            case TElement?[] elements:
                span = elements;
                return true;
#if NET8_0_OR_GREATER
            case List<TElement?> elements:
                span = CollectionsMarshal.AsSpan(elements);
                return true;
            case ImmutableArray<TElement?> elements:
                span = ImmutableCollectionsMarshal.AsArray(elements);
                return true;
#endif
            default:
                span = ReadOnlySpan<TElement?>.Empty;
                return false;
        }
    }
}
