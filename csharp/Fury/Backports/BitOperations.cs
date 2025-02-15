#if !NET8_0_OR_GREATER
using System.Runtime.CompilerServices;

// ReSharper disable once CheckNamespace
namespace System.Numerics;

internal static class BitOperations
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static ulong RotateLeft(ulong value, int offset) => (value << offset) | (value >> (64 - offset));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static uint RotateLeft(uint value, int offset) => (value << offset) | (value >> (32 - offset));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static ulong RotateRight(ulong value, int offset) => (value >> offset) | (value << (64 - offset));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static uint RotateRight(uint value, int offset) => (value >> offset) | (value << (32 - offset));
}
#endif
