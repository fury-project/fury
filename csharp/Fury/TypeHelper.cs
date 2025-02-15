using System;
using System.Diagnostics.CodeAnalysis;
using System.Reflection;
using System.Runtime.CompilerServices;

namespace Fury;

internal static class TypeHelper<T>
{
    public static readonly bool IsSealed = typeof(T).IsSealed;
    public static readonly bool IsValueType = typeof(T).IsValueType;
    public static readonly int Size = Unsafe.SizeOf<T>();
    public static readonly bool IsReferenceOrContainsReferences = TypeHelper.CheckIsReferenceOrContainsReferences<T>();
}

internal static class TypeHelper
{
    public static bool TryGetUnderlyingElementType(
        Type arrayType,
        [NotNullWhen(true)] out Type? elementType,
        out int rank
    )
    {
        // TODO: Multi-dimensional arrays are not supported yet.
        rank = 0;
        var currentType = arrayType;
        while (currentType.IsArray)
        {
            elementType = currentType.GetElementType();
            if (elementType is null)
            {
                return false;
            }
            currentType = elementType;
            rank++;
        }
        elementType = currentType;
        return true;
    }

    public static bool CheckIsReferenceOrContainsReferences(Type type)
    {
        if (!type.IsValueType)
        {
            return true;
        }

        if (type.IsPrimitive || type.IsEnum)
        {
            return false;
        }

        foreach (var field in type.GetFields(BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public))
        {
            if (CheckIsReferenceOrContainsReferences(field.FieldType))
            {
                return true;
            }
        }

        return false;
    }

    public static bool CheckIsReferenceOrContainsReferences<T>()
    {
#if NET8_0_OR_GREATER
        return RuntimeHelpers.IsReferenceOrContainsReferences<T>();
#else
        return CheckIsReferenceOrContainsReferences(typeof(T));
#endif
    }
}
