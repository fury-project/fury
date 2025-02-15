using System.Threading;
using System.Threading.Tasks;

namespace Fury.Serializer;

// This interface is used to support polymorphism.
public interface ISerializer
{
    void Write(SerializationContext context, object value);
}

public interface IDeserializer
{
    // It is very common that the data is not all available at once, so we need to read it asynchronously.

    /// <summary>
    /// Create an instance of the object which will be deserialized.
    /// </summary>
    /// <returns>
    /// An instance of the object which is not deserialized yet.
    /// </returns>
    /// <remarks>
    /// <para>
    /// This method is used to solve the circular reference problem.
    /// When deserializing an object which may be referenced by itself or its child objects,
    /// we need to create an instance before reading its fields.
    /// So that we can reference it before it is fully deserialized.
    /// </para>
    /// <para>
    /// You can read some necessary data from the context to create the instance, e.g. the length of an array.
    /// </para>
    /// <para>
    /// If the object certainly does not have circular references, you can return a fully deserialized object
    /// and keep the <see cref="ReadAndFillAsync"/> method empty.<br/>
    /// Be careful that the default implementation of <see cref="IDeserializer{TValue}.ReadAndCreateAsync"/>
    /// in <see cref="AbstractDeserializer{T}"/> use this method to create an instance.<br/>
    /// If you want to do all the deserialization here, it is recommended to override
    /// <see cref="IDeserializer{TValue}.ReadAndCreateAsync"/> and call it in this method.
    /// </para>
    /// </remarks>
    ValueTask<Box> CreateInstanceAsync(DeserializationContext context, CancellationToken cancellationToken = default);

    /// <summary>
    /// Read the serialized data and populate the given object.
    /// </summary>
    /// <param name="context">
    ///     The context which contains the state of the deserialization process.
    /// </param>
    /// <param name="instance">
    ///     The object which is not deserialized yet. It is created by <see cref="CreateInstanceAsync"/>.
    /// </param>
    /// <param name="cancellationToken"></param>
    /// <returns>
    /// The object which is deserialized from the serialized data. This should be the inputted instance.
    /// </returns>
    ValueTask ReadAndFillAsync(
        DeserializationContext context,
        Box instance,
        CancellationToken cancellationToken = default
    );
}

public interface ISerializer<TValue> : ISerializer
    where TValue : notnull
{
    void Write(SerializationContext context, in TValue value);
}

public interface IDeserializer<TValue> : IDeserializer
    where TValue : notnull
{
    ValueTask<TValue> ReadAndCreateAsync(DeserializationContext context, CancellationToken cancellationToken = default);
}
