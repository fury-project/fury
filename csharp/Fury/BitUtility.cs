using System;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace Fury;

internal static class BitUtility
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static int GetBitMask32(int bitsCount) => (1 << bitsCount) - 1;

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static long GetBitMask64(int bitsCount) => (1L << bitsCount) - 1;

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ClearLowBits(byte value, int lowBitsCount) => (byte)(value & ~GetBitMask32(lowBitsCount));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static long ClearLowBits(long value, int lowBitsCount) => value & ~GetBitMask64(lowBitsCount);

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ClearHighBits(byte value, int highBitsCount) => (byte)(value & GetBitMask32(8 - highBitsCount));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte KeepLowBits(byte value, int lowBitsCount) => (byte)(value & GetBitMask32(lowBitsCount));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte KeepHighBits(byte value, int highBitsCount) => (byte)(value & ~GetBitMask32(8 - highBitsCount));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ReadBits(byte b1, int bitOffset, int bitCount)
    {
        return (byte)((b1 >>> (8 - bitCount - bitOffset)) & GetBitMask32(bitCount));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ReadBits(byte b1, byte b2, int bitOffset, int bitCount)
    {
        var byteFromB1 = b1 << (bitOffset + bitCount - 8);
        var byteFromB2 = b2 >>> (8 * 2 - bitCount - bitOffset);
        return (byte)((byteFromB1 | byteFromB2) & GetBitMask32(bitCount));
    }
}
