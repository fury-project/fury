using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury.Serializer.Provider;

public interface ISerializerProvider
{
    bool TryCreateSerializer(TypeResolver resolver, Type type, [NotNullWhen(true)] out ISerializer? serializer);
}

public interface IDeserializerProvider
{
    bool TryCreateDeserializer(TypeResolver resolver, Type type, [NotNullWhen(true)] out IDeserializer? deserializer);
}
