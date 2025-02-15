using Fury.Serializer;

namespace Fury.Testing.Fakes;

public sealed class SinglePrimitiveFieldObject
{
    public int Value { get; set; }

    public sealed class Serializer : AbstractSerializer<SinglePrimitiveFieldObject>
    {
        public override void Write(SerializationContext context, in SinglePrimitiveFieldObject value)
        {
            context.Writer.Write(value.Value);
        }
    }

    public sealed class Deserializer : AbstractDeserializer<SinglePrimitiveFieldObject>
    {
        public override ValueTask<Box<SinglePrimitiveFieldObject>> CreateInstanceAsync(
            DeserializationContext context,
            CancellationToken cancellationToken = default
        )
        {
            return new ValueTask<Box<SinglePrimitiveFieldObject>>(new SinglePrimitiveFieldObject());
        }

        public override async ValueTask ReadAndFillAsync(
            DeserializationContext context,
            Box<SinglePrimitiveFieldObject> instance,
            CancellationToken cancellationToken = default
        )
        {
            instance.Value!.Value = await context.Reader.ReadAsync<int>(cancellationToken);
        }
    }
}
