using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
#if !NET8_0_OR_GREATER
// ReSharper disable once CheckNamespace
namespace System.Diagnostics
{
    internal sealed class UnreachableException(string? message = null) : Exception(message);
}
#endif

namespace Fury
{
    internal static partial class ThrowHelper
    {
        [DoesNotReturn]
        public static void ThrowUnreachableException(string? message = null)
        {
            throw new UnreachableException(message);
        }

        [DoesNotReturn]
        [Conditional("DEBUG")]
        public static void ThrowUnreachableExceptionDebugOnly(string? message = null)
        {
            throw new UnreachableException(message);
        }
    }
}
