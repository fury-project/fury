using System;
using System.Buffers;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Fury;

public sealed partial class BatchReader
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static unsafe TValue ReadFixedSized<TValue>(ReadOnlySequence<byte> buffer, int size)
        where TValue : unmanaged
    {
        TValue result = default;
        buffer.Slice(0, size).CopyTo(new Span<byte>(&result, size));
        return result;
    }

    public async ValueTask<T> ReadAsync<T>(CancellationToken cancellationToken = default)
        where T : unmanaged
    {
        var requiredSize = Unsafe.SizeOf<T>();
        var result = await ReadAtLeastAsync(requiredSize, cancellationToken);
        var buffer = result.Buffer;
        if (buffer.Length < requiredSize)
        {
            ThrowHelper.ThrowBadDeserializationInputException_InsufficientData();
        }

        var value = ReadFixedSized<T>(buffer, requiredSize);
        AdvanceTo(requiredSize);
        return value;
    }

    public async ValueTask<T> ReadAsAsync<T>(int size, CancellationToken cancellationToken = default)
        where T : unmanaged
    {
        var result = await ReadAtLeastAsync(size, cancellationToken);
        var buffer = result.Buffer;
        if (buffer.Length < size)
        {
            ThrowHelper.ThrowBadDeserializationInputException_InsufficientData();
        }

        var value = ReadFixedSized<T>(buffer, size);
        AdvanceTo(size);
        return value;
    }

    public async ValueTask ReadMemoryAsync<TElement>(
        Memory<TElement> destination,
        CancellationToken cancellationToken = default
    )
        where TElement : unmanaged
    {
        var requiredSize = destination.Length;
        var result = await ReadAtLeastAsync(requiredSize, cancellationToken);
        var buffer = result.Buffer;
        if (result.IsCompleted && buffer.Length < requiredSize)
        {
            ThrowHelper.ThrowBadDeserializationInputException_InsufficientData();
        }

        buffer.Slice(0, requiredSize).CopyTo(MemoryMarshal.AsBytes(destination.Span));
        AdvanceTo(requiredSize);
    }

    public async ValueTask<string> ReadStringAsync(
        int byteCount,
        Encoding encoding,
        CancellationToken cancellationToken = default
    )
    {
        var result = await ReadAtLeastAsync(byteCount, cancellationToken);
        var buffer = result.Buffer;
        if (result.IsCompleted && buffer.Length < byteCount)
        {
            ThrowHelper.ThrowBadDeserializationInputException_InsufficientData();
        }

        var value = DoReadString(byteCount, buffer, encoding);
        AdvanceTo(byteCount);
        return value;
    }

    private static unsafe string DoReadString(int byteCount, ReadOnlySequence<byte> byteSequence, Encoding encoding)
    {
        const int maxStackBufferSize = StaticConfigs.StackAllocLimit / sizeof(char);
        var decoder = encoding.GetDecoder();
        int writtenChars;
        string result;
        if (byteCount < maxStackBufferSize)
        {
            // Fast path
            Span<char> stringBuffer = stackalloc char[byteCount];
            writtenChars = ReadStringCommon(decoder, byteSequence, stringBuffer);
            result = stringBuffer.Slice(0, writtenChars).ToString();
        }
        else
        {
            var rentedBuffer = ArrayPool<char>.Shared.Rent(byteCount);
            writtenChars = ReadStringCommon(decoder, byteSequence, rentedBuffer);
            result = new string(rentedBuffer, 0, writtenChars);
            ArrayPool<char>.Shared.Return(rentedBuffer);
        }

        return result;
    }

    private static unsafe int ReadStringCommon(
        Decoder decoder,
        ReadOnlySequence<byte> byteSequence,
        Span<char> unwrittenBuffer
    )
    {
        var writtenChars = 0;
        foreach (var byteMemory in byteSequence)
        {
            int charsUsed;
            var byteSpan = byteMemory.Span;
            fixed (char* pUnWrittenBuffer = unwrittenBuffer)
            fixed (byte* pBytes = byteMemory.Span)
            {
                decoder.Convert(
                    pBytes,
                    byteSpan.Length,
                    pUnWrittenBuffer,
                    unwrittenBuffer.Length,
                    false,
                    out _,
                    out charsUsed,
                    out _
                );
            }

            unwrittenBuffer = unwrittenBuffer.Slice(charsUsed);
            writtenChars += charsUsed;
        }

        return writtenChars;
    }

    public async ValueTask<int> Read7BitEncodedIntAsync(CancellationToken cancellationToken = default)
    {
        var result = await Read7BitEncodedUintAsync(cancellationToken);
        return (int)BitOperations.RotateRight(result, 1);
    }

    public async ValueTask<uint> Read7BitEncodedUintAsync(CancellationToken cancellationToken = default)
    {
        var result = await ReadAtLeastAsync(MaxBytesOfVarInt32WithoutOverflow + 1, cancellationToken);
        var buffer = result.Buffer;

        // Fast path
        var value = DoRead7BitEncodedUintFast(buffer.First.Span, out var consumed);
        if (consumed == 0)
        {
            // Slow path
            value = DoRead7BitEncodedUintSlow(buffer, out consumed);
        }

        AdvanceTo(consumed);

        return value;
    }

    private const int MaxBytesOfVarInt32WithoutOverflow = 4;

    private static uint DoRead7BitEncodedUintFast(ReadOnlySpan<byte> buffer, out int consumed)
    {
        if (buffer.Length <= MaxBytesOfVarInt32WithoutOverflow)
        {
            consumed = 0;
            return 0;
        }
        uint result = 0;
        consumed = 0;
        uint readByte;
        for (var i = 0; i < MaxBytesOfVarInt32WithoutOverflow; i++)
        {
            readByte = buffer[i];
            result |= (readByte & 0x7F) << (i * 7);
            if ((readByte & 0x80) == 0)
            {
                consumed = i + 1;
                return result;
            }
        }

        readByte = buffer[MaxBytesOfVarInt32WithoutOverflow];
        if (readByte > 0b_1111u)
        {
            ThrowHelper.ThrowBadDeserializationInputException_VarInt32Overflow();
        }

        result |= readByte << (MaxBytesOfVarInt32WithoutOverflow * 7);
        consumed = MaxBytesOfVarInt32WithoutOverflow + 1;
        return result;
    }

    private static uint DoRead7BitEncodedUintSlow(ReadOnlySequence<byte> buffer, out int consumed)
    {
        uint result = 0;
        var consumedBytes = 0;
        foreach (var memory in buffer)
        {
            var span = memory.Span;
            foreach (uint readByte in span)
            {
                if (consumedBytes < MaxBytesOfVarInt32WithoutOverflow)
                {
                    result |= (readByte & 0x7F) << (7 * consumedBytes);
                    ++consumedBytes;
                    if ((readByte & 0x80) == 0)
                    {
                        consumed = consumedBytes;
                        return result;
                    }
                }
                else
                {
                    if (readByte > 0b_1111u)
                    {
                        ThrowHelper.ThrowBadDeserializationInputException_VarInt32Overflow();
                    }
                    result |= readByte << (7 * MaxBytesOfVarInt32WithoutOverflow);
                    consumed = consumedBytes + 1;
                    return result;
                }
            }
        }
        consumed = 0;
        return result;
    }

    public async ValueTask<long> Read7BitEncodedLongAsync(CancellationToken cancellationToken = default)
    {
        var result = await Read7BitEncodedUlongAsync(cancellationToken);
        return (long)BitOperations.RotateRight(result, 1);
    }

    public async ValueTask<ulong> Read7BitEncodedUlongAsync(CancellationToken cancellationToken = default)
    {
        var result = await ReadAtLeastAsync(MaxBytesOfVarInt64WithoutOverflow + 1, cancellationToken);
        var buffer = result.Buffer;

        // Fast path
        var value = DoRead7BitEncodedUlongFast(buffer.First.Span, out var consumed);
        if (consumed == 0)
        {
            // Slow path
            value = DoRead7BitEncodedUlongSlow(buffer, out consumed);
        }

        AdvanceTo(consumed);

        return value;
    }

    private const int MaxBytesOfVarInt64WithoutOverflow = 8;

    private static ulong DoRead7BitEncodedUlongFast(ReadOnlySpan<byte> buffer, out int consumed)
    {
        if (buffer.Length <= MaxBytesOfVarInt64WithoutOverflow)
        {
            consumed = 0;
            return 0;
        }
        ulong result = 0;
        consumed = 0;
        ulong readByte;
        for (var i = 0; i < MaxBytesOfVarInt64WithoutOverflow; i++)
        {
            readByte = buffer[i];
            result |= (readByte & 0x7F) << (i * 7);
            if ((readByte & 0x80) == 0)
            {
                consumed = i + 1;
                return result;
            }
        }

        readByte = buffer[MaxBytesOfVarInt64WithoutOverflow];
        result |= readByte << (MaxBytesOfVarInt64WithoutOverflow * 7);
        return result;
    }

    private static ulong DoRead7BitEncodedUlongSlow(ReadOnlySequence<byte> buffer, out int consumed)
    {
        ulong result = 0;
        var consumedBytes = 0;
        foreach (var memory in buffer)
        {
            var span = memory.Span;
            foreach (ulong readByte in span)
            {
                if (consumedBytes < MaxBytesOfVarInt64WithoutOverflow)
                {
                    result |= (readByte & 0x7F) << (7 * consumedBytes);
                    ++consumedBytes;
                    if ((readByte & 0x80) == 0)
                    {
                        consumed = consumedBytes;
                        return result;
                    }
                }
                else
                {
                    result |= readByte << (7 * MaxBytesOfVarInt64WithoutOverflow);
                    consumed = consumedBytes + 1;
                    return result;
                }
            }
        }
        consumed = 0;
        return result;
    }

    public async ValueTask<int> ReadCountAsync(CancellationToken cancellationToken)
    {
        return (int)await Read7BitEncodedUintAsync(cancellationToken);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal async ValueTask<ReferenceFlag> ReadReferenceFlagAsync(CancellationToken cancellationToken = default)
    {
        return (ReferenceFlag)await ReadAsync<sbyte>(cancellationToken);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal async ValueTask<TypeId> ReadTypeIdAsync(CancellationToken cancellationToken = default)
    {
        return new TypeId((int)await Read7BitEncodedUintAsync(cancellationToken));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal async ValueTask<RefId> ReadRefIdAsync(CancellationToken cancellationToken = default)
    {
        return new RefId((int)await Read7BitEncodedUintAsync(cancellationToken));
    }
}
