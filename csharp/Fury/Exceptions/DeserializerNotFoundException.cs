using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

public class DeserializerNotFoundException(Type? objectType, TypeId? typeId, string? message = null)
    : Exception(message)
{
    public Type? TypeOfDeserializedObject { get; } = objectType;
    public TypeId? TypeIdOfDeserializedObject { get; } = typeId;

    public DeserializerNotFoundException(Type objectType, string? message = null)
        : this(objectType, null, message) { }

    public DeserializerNotFoundException(TypeId typeId, string? message = null)
        : this(null, typeId, message) { }
}

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowDeserializerNotFoundException(
        Type? type = null,
        TypeId? typeId = null,
        string? message = null
    )
    {
        throw new DeserializerNotFoundException(type, typeId, message);
    }

    [DoesNotReturn]
    public static void ThrowDeserializerNotFoundException_DeserializerNotFound(Type type)
    {
        throw new DeserializerNotFoundException(type, $"No deserializer found for type '{type.FullName}'.");
    }
}
