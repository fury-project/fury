using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace Fury.Serializer;

public abstract class CollectionDeserializer<TElement, TEnumerable>(IDeserializer<TElement>? elementSerializer)
    : AbstractDeserializer<TEnumerable>
    where TElement : notnull
    where TEnumerable : class, ICollection<TElement?>
{
    private IDeserializer<TElement>? _elementDeserializer = elementSerializer;

    public override async ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<TEnumerable> box,
        CancellationToken cancellationToken = default
    )
    {
        var instance = box.Value!;
        var count = instance.Count;
        if (count <= 0)
        {
            return;
        }
        var typedElementSerializer = _elementDeserializer;
        if (typedElementSerializer is null)
        {
            if (TypeHelper<TElement>.IsSealed)
            {
                typedElementSerializer = (IDeserializer<TElement>)context.GetDeserializer<TElement>();
                _elementDeserializer = typedElementSerializer;
            }
        }

        for (var i = 0; i < instance.Count; i++)
        {
            var item = await context.ReadAsync<TElement>(typedElementSerializer, cancellationToken);
            instance.Add(item);
        }
    }
}

public abstract class NullableCollectionDeserializer<TElement, TEnumerable>(IDeserializer<TElement>? elementSerializer)
    : AbstractDeserializer<TEnumerable>
    where TElement : struct
    where TEnumerable : class, ICollection<TElement?>
{
    private IDeserializer<TElement>? _elementDeserializer = elementSerializer;

    public override async ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<TEnumerable> box,
        CancellationToken cancellationToken = default
    )
    {
        var instance = box.Value!;
        var count = instance.Count;
        if (count <= 0)
        {
            return;
        }
        var typedElementSerializer = _elementDeserializer;
        if (typedElementSerializer is null)
        {
            if (TypeHelper<TElement>.IsSealed)
            {
                typedElementSerializer = (IDeserializer<TElement>)context.GetDeserializer<TElement>();
                _elementDeserializer = typedElementSerializer;
            }
        }

        for (var i = 0; i < instance.Count; i++)
        {
            var item = await context.ReadNullableAsync<TElement>(typedElementSerializer, cancellationToken);
            instance.Add(item);
        }
    }
}
