using System;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;

namespace Fury;

public ref partial struct BatchWriter
{
    public void Write<T>(T value)
        where T : unmanaged
    {
        var size = Unsafe.SizeOf<T>();
        var buffer = GetSpan(size);
#if NET8_0_OR_GREATER
        MemoryMarshal.Write(buffer, in value);
#else
        MemoryMarshal.Write(buffer, ref value);
#endif
        Advance(size);
    }

    public void Write(Span<byte> values)
    {
        var buffer = GetSpan(values.Length);
        values.CopyTo(buffer);
        Advance(values.Length);
    }

    public void Write<TElement>(Span<TElement> values)
        where TElement : unmanaged
    {
        Write(MemoryMarshal.AsBytes(values));
    }

    public unsafe void Write(ReadOnlySpan<char> value, Encoding encoding, int byteCountHint)
    {
        var buffer = GetSpan(byteCountHint);
        int actualByteCount;

        fixed (char* pChars = value)
        fixed (byte* pBytes = buffer)
        {
            actualByteCount = encoding.GetBytes(pChars, value.Length, pBytes, buffer.Length);
        }

        Advance(actualByteCount);
    }

    public unsafe void Write(ReadOnlySpan<char> value, Encoding encoding)
    {
        const int fastPathBufferSize = 128;

        var possibleMaxByteCount = encoding.GetMaxByteCount(value.Length);
        int bufferLength;
        if (possibleMaxByteCount <= fastPathBufferSize)
        {
            bufferLength = possibleMaxByteCount;
        }
        else
        {
            fixed (char* pChars = value)
            {
                bufferLength = encoding.GetByteCount(pChars, value.Length);
            }
        }

        Write(value, encoding, bufferLength);
    }

    public void Write7BitEncodedInt(int value)
    {
        var zigzag = BitOperations.RotateLeft((uint)value, 1);
        Write7BitEncodedUint(zigzag);
    }

    public void Write7BitEncodedUint(uint value)
    {
        switch (value)
        {
            case < 1u << 7:
                Write((byte)value);
                return;
            case < 1u << 14:
            {
                var buffer = GetSpan(2);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)(value >>> 7);
                Advance(2);
                break;
            }
            case < 1u << 21:
            {
                var buffer = GetSpan(3);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)(value >>> 14);
                Advance(3);
                break;
            }
            case < 1u << 28:
            {
                var buffer = GetSpan(4);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)(value >>> 21);
                Advance(4);
                break;
            }
            default:
                var buffer2 = GetSpan(5);
                buffer2[0] = (byte)(value | ~0x7Fu);
                buffer2[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer2[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer2[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer2[4] = (byte)(value >>> 28);
                Advance(5);
                break;
        }
    }

    public void Write7BitEncodedLong(long value)
    {
        var zigzag = BitOperations.RotateLeft((ulong)value, 1);
        Write7BitEncodedUlong(zigzag);
    }

    public void Write7BitEncodedUlong(ulong value)
    {
        switch (value)
        {
            case < 1ul << 7:
                Write((byte)value);
                return;
            case < 1ul << 14:
            {
                var buffer = GetSpan(2);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)(value >>> 7);
                Advance(2);
                break;
            }
            case < 1ul << 21:
            {
                var buffer = GetSpan(3);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)(value >>> 14);
                Advance(3);
                break;
            }
            case < 1ul << 28:
            {
                var buffer = GetSpan(4);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)(value >>> 21);
                Advance(4);
                break;
            }
            case < 1ul << 35:
            {
                var buffer = GetSpan(5);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)(value >>> 28);
                Advance(5);
                break;
            }
            case < 1ul << 42:
            {
                var buffer = GetSpan(6);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)(value >>> 35);
                Advance(6);
                break;
            }
            case < 1ul << 49:
            {
                var buffer = GetSpan(7);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)((value >>> 35) | ~0x7Fu);
                buffer[6] = (byte)(value >>> 42);
                Advance(7);
                break;
            }
            case < 1ul << 56:
            {
                var buffer = GetSpan(8);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)((value >>> 35) | ~0x7Fu);
                buffer[6] = (byte)((value >>> 42) | ~0x7Fu);
                buffer[7] = (byte)(value >>> 49);
                Advance(8);
                break;
            }
            case < 1ul << 63:
            {
                var buffer = GetSpan(9);
                buffer[0] = (byte)(value | ~0x7Fu);
                buffer[1] = (byte)((value >>> 7) | ~0x7Fu);
                buffer[2] = (byte)((value >>> 14) | ~0x7Fu);
                buffer[3] = (byte)((value >>> 21) | ~0x7Fu);
                buffer[4] = (byte)((value >>> 28) | ~0x7Fu);
                buffer[5] = (byte)((value >>> 35) | ~0x7Fu);
                buffer[6] = (byte)((value >>> 42) | ~0x7Fu);
                buffer[7] = (byte)((value >>> 49) | ~0x7Fu);
                buffer[8] = (byte)(value >>> 56);
                Advance(9);
                break;
            }
        }
    }

    public void WriteCount(int length)
    {
        Write7BitEncodedUint((uint)length);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void Write(ReferenceFlag flag)
    {
        Write((sbyte)flag);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void Write(RefId refId)
    {
        Write7BitEncodedUint((uint)refId.Value);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void Write(TypeId typeId)
    {
        Write7BitEncodedUint((uint)typeId.Value);
    }
}
