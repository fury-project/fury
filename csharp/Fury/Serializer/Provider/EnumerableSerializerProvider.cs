using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;

namespace Fury.Serializer.Provider;

internal sealed class EnumerableSerializerProvider : ISerializerProvider
{
    private static readonly string EnumerableInterfaceName = typeof(IEnumerable<>).Name;

    public bool TryCreateSerializer(TypeResolver resolver, Type type, [NotNullWhen(true)] out ISerializer? serializer)
    {
        serializer = null;
        if (type.IsAbstract)
        {
            return false;
        }
        if (type.GetInterface(EnumerableInterfaceName) is not { } enumerableInterface)
        {
            return false;
        }

        var elementType = enumerableInterface.GetGenericArguments()[0];
        if (elementType.IsGenericParameter)
        {
            return false;
        }

        var underlyingType = Nullable.GetUnderlyingType(elementType);
        Type serializerType;
        if (underlyingType is null)
        {
            serializerType = typeof(EnumerableSerializer<,>).MakeGenericType(elementType, type);
        }
        else
        {
            serializerType = typeof(NullableEnumerableSerializer<,>).MakeGenericType(underlyingType, type);
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
