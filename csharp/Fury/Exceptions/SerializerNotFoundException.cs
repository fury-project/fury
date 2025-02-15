using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

public class SerializerNotFoundException(Type? objectType, TypeId? typeId, string? message = null) : Exception(message)
{
    public Type? TypeOfSerializedObject { get; } = objectType;
    public TypeId? TypeIdOfSerializedObject { get; } = typeId;

    public SerializerNotFoundException(Type objectType, string? message = null)
        : this(objectType, null, message) { }

    public SerializerNotFoundException(TypeId typeId, string? message = null)
        : this(null, typeId, message) { }
}

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowSerializerNotFoundException(
        Type? type = null,
        TypeId? typeId = null,
        string? message = null
    )
    {
        throw new SerializerNotFoundException(type, typeId, message);
    }

    [DoesNotReturn]
    public static void ThrowSerializerNotFoundException_SerializerNotFound(Type type)
    {
        throw new SerializerNotFoundException(type, $"No serializer found for type '{type.FullName}'.");
    }
}
