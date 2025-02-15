using System;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace Fury;

internal static class HashHelper
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static ulong FinalizationMix(ulong k)
    {
        k ^= k >> 33;
        k *= 0xff51afd7ed558ccd;
        k ^= k >> 33;
        k *= 0xc4ceb9fe1a85ec53;
        k ^= k >> 33;
        return k;
    }

    public static void MurmurHash3_x64_128(ReadOnlySpan<byte> key, uint seed, out ulong out1, out ulong out2)
    {
        const int blockSize = sizeof(ulong) * 2;

        const ulong c1 = 0x87c37b91114253d5;
        const ulong c2 = 0x4cf5ad432745937f;

        var length = key.Length;

        ulong h1 = seed;
        ulong h2 = seed;

        ulong k1;
        ulong k2;

        var blocks = MemoryMarshal.Cast<byte, uint>(key);
        var nBlocks = length / blockSize;
        for (var i = 0; i < nBlocks; i++)
        {
            k1 = blocks[i * 2];
            k2 = blocks[i * 2 + 1];

            k1 *= c1;
            k1 = BitOperations.RotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = BitOperations.RotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = BitOperations.RotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = BitOperations.RotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        var tail = key.Slice(nBlocks * blockSize);

        k1 = 0;
        k2 = 0;

        switch (length & 15)
        {
            case 15:
                k2 ^= (ulong)tail[14] << 48;
                goto case 14;
            case 14:
                k2 ^= (ulong)tail[13] << 40;
                goto case 13;
            case 13:
                k2 ^= (ulong)tail[12] << 32;
                goto case 12;
            case 12:
                k2 ^= (ulong)tail[11] << 24;
                goto case 11;
            case 11:
                k2 ^= (ulong)tail[10] << 16;
                goto case 10;
            case 10:
                k2 ^= (ulong)tail[9] << 8;
                goto case 9;
            case 9:
                k2 ^= tail[8];
                k2 *= c2;
                k2 = BitOperations.RotateLeft(k2, 33);
                k2 *= c1;
                h2 ^= k2;
                goto case 8;
            case 8:
                k1 ^= (ulong)tail[7] << 56;
                goto case 7;
            case 7:
                k1 ^= (ulong)tail[6] << 48;
                goto case 6;
            case 6:
                k1 ^= (ulong)tail[5] << 40;
                goto case 5;
            case 5:
                k1 ^= (ulong)tail[4] << 32;
                goto case 4;
            case 4:
                k1 ^= (ulong)tail[3] << 24;
                goto case 3;
            case 3:
                k1 ^= (ulong)tail[2] << 16;
                goto case 2;
            case 2:
                k1 ^= (ulong)tail[1] << 8;
                goto case 1;
            case 1:
                k1 ^= tail[0];
                k1 *= c1;
                k1 = BitOperations.RotateLeft(k1, 31);
                k1 *= c2;
                h1 ^= k1;
                break;
        }

        h1 ^= (ulong)length;
        h2 ^= (ulong)length;

        h1 += h2;
        h2 += h1;

        h1 = FinalizationMix(h1);
        h2 = FinalizationMix(h2);

        h1 += h2;
        h2 += h1;

        out1 = h1;
        out2 = h2;
    }
}
