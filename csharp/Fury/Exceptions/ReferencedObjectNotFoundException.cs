using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

public class ReferencedObjectNotFoundException(string? message = null) : Exception(message);

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowReferencedObjectNotFoundException(string? message = null)
    {
        throw new ReferencedObjectNotFoundException(message);
    }
}
