using System.Threading;
using System.Threading.Tasks;

namespace Fury.Serializer;

internal class ArraySerializer<TElement>(ISerializer<TElement>? elementSerializer) : AbstractSerializer<TElement?[]>
    where TElement : notnull
{
    // ReSharper disable once UnusedMember.Global
    public ArraySerializer()
        : this(null) { }

    public override void Write(SerializationContext context, in TElement?[] value)
    {
        context.Writer.WriteCount(value.Length);
        foreach (var element in value)
        {
            context.Write(element, elementSerializer);
        }
    }
}

internal class NullableArraySerializer<TElement>(ISerializer<TElement>? elementSerializer)
    : AbstractSerializer<TElement?[]>
    where TElement : struct
{
    // ReSharper disable once UnusedMember.Global
    public NullableArraySerializer()
        : this(null) { }

    public override void Write(SerializationContext context, in TElement?[] value)
    {
        context.Writer.WriteCount(value.Length);
        foreach (var element in value)
        {
            context.Write(element, elementSerializer);
        }
    }
}

internal class ArrayDeserializer<TElement>(IDeserializer<TElement>? elementDeserializer)
    : AbstractDeserializer<TElement?[]>
    where TElement : notnull
{
    public ArrayDeserializer()
        : this(null) { }

    public override async ValueTask<Box<TElement?[]>> CreateInstanceAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        var length = await context.Reader.ReadCountAsync(cancellationToken);
        return new TElement?[length];
    }

    public override async ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<TElement?[]> box,
        CancellationToken cancellationToken = default
    )
    {
        var instance = box.Value!;
        for (var i = 0; i < instance.Length; i++)
        {
            instance[i] = await context.ReadAsync<TElement>(elementDeserializer, cancellationToken);
        }
    }

    public override async ValueTask<TElement?[]> ReadAndCreateAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        var length = await context.Reader.ReadCountAsync(cancellationToken);
        var result = new TElement?[length];
        for (var i = 0; i < result.Length; i++)
        {
            result[i] = await context.ReadAsync<TElement>(elementDeserializer, cancellationToken);
        }
        return result;
    }
}

internal class NullableArrayDeserializer<TElement>(IDeserializer<TElement>? elementDeserializer)
    : AbstractDeserializer<TElement?[]>
    where TElement : struct
{
    public NullableArrayDeserializer()
        : this(null) { }

    public override async ValueTask<Box<TElement?[]>> CreateInstanceAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        var length = await context.Reader.ReadCountAsync(cancellationToken);
        return new TElement?[length];
    }

    public override async ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<TElement?[]> box,
        CancellationToken cancellationToken = default
    )
    {
        var instance = box.Value!;
        for (var i = 0; i < instance.Length; i++)
        {
            instance[i] = await context.ReadNullableAsync<TElement>(elementDeserializer, cancellationToken);
        }
    }
}

internal sealed class PrimitiveArraySerializer<TElement> : AbstractSerializer<TElement[]>
    where TElement : unmanaged
{
    public static PrimitiveArraySerializer<TElement> Instance { get; } = new();

    public override void Write(SerializationContext context, in TElement[] value)
    {
        context.Writer.WriteCount(value.Length);
        context.Writer.Write<TElement>(value);
    }
}

internal sealed class PrimitiveArrayDeserializer<TElement> : ArrayDeserializer<TElement>
    where TElement : unmanaged
{
    public static PrimitiveArrayDeserializer<TElement> Instance { get; } = new();

    public override async ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<TElement[]> box,
        CancellationToken cancellationToken = default
    )
    {
        var instance = box.Value!;
        await context.Reader.ReadMemoryAsync<TElement>(instance, cancellationToken);
    }
}
