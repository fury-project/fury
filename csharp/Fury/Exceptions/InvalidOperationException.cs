using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowInvalidOperationException(string message)
    {
        throw new InvalidOperationException(message);
    }

    [DoesNotReturn]
    public static void ThrowInvalidOperationException_AttemptedToWriteToReadOnlyCollection()
    {
        throw new InvalidOperationException("Attempted to write to a read-only collection.");
    }
}
