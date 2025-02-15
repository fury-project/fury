using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowArgumentOutOfRangeException(
        string paramName,
        object? actualValue = null,
        string? message = null
    )
    {
        throw new ArgumentOutOfRangeException(paramName, actualValue, message);
    }
}
