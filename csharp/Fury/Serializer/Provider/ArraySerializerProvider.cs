using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury.Serializer.Provider;

internal sealed class ArraySerializerProvider : ISerializerProvider
{
    public bool TryCreateSerializer(TypeResolver resolver, Type type, [NotNullWhen(true)] out ISerializer? serializer)
    {
        serializer = null;
        if (!type.IsArray)
        {
            return false;
        }

        var elementType = type.GetElementType();
        if (elementType is null)
        {
            return false;
        }

        var underlyingType = Nullable.GetUnderlyingType(elementType);
        Type serializerType;
        if (underlyingType is null)
        {
            serializerType = typeof(ArraySerializer<>).MakeGenericType(elementType);
        }
        else
        {
            serializerType = typeof(NullableArraySerializer<>).MakeGenericType(underlyingType);
            elementType = underlyingType;
        }

        ISerializer? elementSerializer = null;
        if (elementType.IsSealed)
        {
            if (!resolver.TryGetOrCreateSerializer(elementType, out elementSerializer))
            {
                return false;
            }
        }

        serializer = (ISerializer?)Activator.CreateInstance(serializerType, elementSerializer);

        return serializer is not null;
    }
}

internal class ArrayDeserializerProvider : IDeserializerProvider
{
    public bool TryCreateDeserializer(
        TypeResolver resolver,
        Type type,
        [NotNullWhen(true)] out IDeserializer? deserializer
    )
    {
        deserializer = null;
        if (!type.IsArray)
        {
            return false;
        }

        var elementType = type.GetElementType();
        if (elementType is null)
        {
            return false;
        }

        var underlyingType = Nullable.GetUnderlyingType(elementType);
        Type deserializerType;
        if (underlyingType is null)
        {
            deserializerType = typeof(ArrayDeserializer<>).MakeGenericType(elementType);
        }
        else
        {
            deserializerType = typeof(NullableArrayDeserializer<>).MakeGenericType(underlyingType);
            elementType = underlyingType;
        }

        IDeserializer? elementDeserializer = null;
        if (elementType.IsSealed)
        {
            if (!resolver.TryGetOrCreateDeserializer(elementType, out elementDeserializer))
            {
                return false;
            }
        }

        deserializer = (IDeserializer?)Activator.CreateInstance(deserializerType, elementDeserializer);

        return deserializer is not null;
    }
}
