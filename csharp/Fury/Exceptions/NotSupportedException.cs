using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowNotSupportedException(string? message = null)
    {
        throw new NotSupportedException(message);
    }

    [DoesNotReturn]
    public static TReturn ThrowNotSupportedException<TReturn>(string? message = null)
    {
        throw new NotSupportedException(message);
    }

    [DoesNotReturn]
    public static TReturn ThrowNotSupportedException_EncoderNotSupportedForThisEncoding<TReturn>(string? encodingName)
    {
        throw new NotSupportedException($"The encoder is not supported for the encoding '{encodingName}'.");
    }
}
