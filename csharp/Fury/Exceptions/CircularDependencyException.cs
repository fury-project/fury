using System;

namespace Fury;

public class CircularDependencyException(string? message = null) : Exception(message);

internal static partial class ThrowHelper
{
    public static void ThrowCircularDependencyException(string? message = null)
    {
        throw new CircularDependencyException(message);
    }
}
