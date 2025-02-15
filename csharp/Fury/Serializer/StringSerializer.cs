using System;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Fury.Serializer;

internal sealed class StringSerializer : AbstractSerializer<string>
{
    public static StringSerializer Instance { get; } = new();

    public override void Write(SerializationContext context, in string value)
    {
        // TODO: write encoding flags
        var byteCount = Encoding.UTF8.GetByteCount(value);
        context.Writer.WriteCount(byteCount);
        context.Writer.Write(value.AsSpan(), Encoding.UTF8, byteCount);
    }
}

internal sealed class StringDeserializer : AbstractDeserializer<string>
{
    public static StringDeserializer Instance { get; } = new();

    public override async ValueTask<Box<string>> CreateInstanceAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        return await ReadAndCreateAsync(context, cancellationToken);
    }

    public override ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box<string> instance,
        CancellationToken cancellationToken = default
    )
    {
        return TaskHelper.CompletedValueTask;
    }

    public override async ValueTask<string> ReadAndCreateAsync(
        DeserializationContext context,
        CancellationToken cancellationToken = default
    )
    {
        // TODO: read encoding flags
        var byteCount = await context.Reader.ReadCountAsync(cancellationToken);
        return await context.Reader.ReadStringAsync(byteCount, Encoding.UTF8, cancellationToken);
    }
}
