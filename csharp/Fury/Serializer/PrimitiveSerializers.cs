using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;

namespace Fury.Serializer;

internal sealed class PrimitiveSerializer<T> : AbstractSerializer<T>
    where T : unmanaged
{
    public static PrimitiveSerializer<T> Instance { get; } = new();

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public override void Write(SerializationContext context, in T value)
    {
        context.Writer.Write(value);
    }
}

internal sealed class PrimitiveDeserializer<T> : AbstractDeserializer<T>
    where T : unmanaged
{
    public static PrimitiveDeserializer<T> Instance { get; } = new();

    public override async ValueTask<Box<T>> CreateInstanceAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        return await ReadAndCreateAsync(context, cancellationToken);
    }

    public override ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<T> instance,
        CancellationToken cancellationToken = default
    )
    {
        return TaskHelper.CompletedValueTask;
    }

    public override async ValueTask<T> ReadAndCreateAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        return await context.Reader.ReadAsync<T>(cancellationToken: cancellationToken);
    }
}
