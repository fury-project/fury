using System.Threading;
using System.Threading.Tasks;

namespace Fury.Serializer;

public abstract class AbstractSerializer<TValue> : ISerializer<TValue>
    where TValue : notnull
{
    public abstract void Write(SerializationContext context, in TValue value);

    public virtual void Write(SerializationContext context, object value)
    {
        var typedValue = (TValue)value;
        Write(context, in typedValue);
    }
}

public abstract class AbstractDeserializer<TValue> : IDeserializer<TValue>
    where TValue : notnull
{
    public abstract ValueTask<Box<TValue>> CreateInstanceAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    );

    public abstract ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<TValue> instance,
        CancellationToken cancellationToken = default
    );

    public virtual async ValueTask<TValue> ReadAndCreateAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        var instance = await CreateInstanceAsync(context, cancellationToken);
        await ReadAndFillAsync(context, instance, cancellationToken);
        return instance.Value!;
    }

    async ValueTask<Box> IDeserializer.CreateInstanceAsync(
        DeserializationContext context,
        CancellationToken cancellationToken
    )
    {
        var instance = await CreateInstanceAsync(context, cancellationToken);
        return instance.AsUntyped();
    }

    async ValueTask IDeserializer.ReadAndFillAsync(
        DeserializationContext context,
        Box instance,
        CancellationToken cancellationToken
    )
    {
        var typedInstance = instance.AsTyped<TValue>();
        await ReadAndFillAsync(context, typedInstance, cancellationToken);
    }
}
