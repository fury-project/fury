using System;
using System.Threading;
using System.Threading.Tasks;

namespace Fury.Serializer;

public sealed class NotSupportedSerializer<TValue> : ISerializer<TValue>
    where TValue : notnull
{
    public void Write(SerializationContext context, in TValue value)
    {
        throw new NotSupportedException();
    }

    public void Write(SerializationContext context, object value)
    {
        throw new NotSupportedException();
    }
}

public sealed class NotSupportedDeserializer<TValue> : IDeserializer<TValue>
    where TValue : notnull
{
    public ValueTask<TValue> ReadAndCreateAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        throw new NotSupportedException();
    }

    public ValueTask<Box> CreateInstanceAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        throw new NotSupportedException();
    }

    public ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box instance,
        CancellationToken cancellationToken = default
    )
    {
        throw new NotSupportedException();
    }
}
