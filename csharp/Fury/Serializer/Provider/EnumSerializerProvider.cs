using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury.Serializer.Provider;

public sealed class EnumSerializerProvider : ISerializerProvider
{
    public bool TryCreateSerializer(TypeResolver resolver, Type type, [NotNullWhen(true)] out ISerializer? serializer)
    {
        if (!type.IsEnum)
        {
            serializer = null;
            return false;
        }

        var serializerType = typeof(EnumSerializer<>).MakeGenericType(type);
        serializer = (ISerializer?)Activator.CreateInstance(serializerType);

        return serializer is not null;
    }
}

public sealed class EnumDeserializerProvider : IDeserializerProvider
{
    public bool TryCreateDeserializer(
        TypeResolver resolver,
        Type type,
        [NotNullWhen(true)] out IDeserializer? deserializer
    )
    {
        if (!type.IsEnum)
        {
            deserializer = default;
            return false;
        }

        var deserializerType = typeof(EnumDeserializer<>).MakeGenericType(type);
        deserializer = (IDeserializer?)Activator.CreateInstance(deserializerType);

        return deserializer is not null;
    }
}
