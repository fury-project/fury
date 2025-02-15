using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;

namespace Fury.Serializer.Provider;

internal sealed class CollectionDeserializerProvider : IDeserializerProvider
{
    private static readonly string CollectionInterfaceName = typeof(ICollection<>).Name;

    public bool TryCreateDeserializer(
        TypeResolver resolver,
        Type type,
        [NotNullWhen(true)] out IDeserializer? deserializer
    )
    {
        deserializer = null;
        if (type.IsAbstract)
        {
            return false;
        }
        if (type.GetInterface(CollectionInterfaceName) is not { } collectionInterface)
        {
            return false;
        }

        var elementType = collectionInterface.GetGenericArguments()[0];
        if (elementType.IsGenericParameter)
        {
            return false;
        }

        var underlyingType = Nullable.GetUnderlyingType(elementType);
        Type deserializerType;
        if (underlyingType is null)
        {
            deserializerType = typeof(CollectionDeserializer<,>).MakeGenericType(elementType, type);
        }
        else
        {
            deserializerType = typeof(NullableCollectionDeserializer<,>).MakeGenericType(underlyingType, type);
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
