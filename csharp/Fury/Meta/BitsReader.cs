using System;

namespace Fury.Meta;

internal ref struct BitsReader(ReadOnlySpan<byte> bytes)
{
    private const int BitsOfByte = sizeof(byte) * 8;

    private readonly ReadOnlySpan<byte> _bytes = bytes;

    private int _currentBitIndex;
    private int CurrentByteIndex => _currentBitIndex / BitsOfByte;

    internal int BytesUsed => (_currentBitIndex + BitsOfByte - 1) / BitsOfByte;
    internal int UnusedBitCountInLastUsedByte => (BitsOfByte - _currentBitIndex % BitsOfByte) % BitsOfByte;

    internal byte UnusedBitsInLastUsedByte
    {
        get
        {
            var unusedBitCountInLastUsedByte = UnusedBitCountInLastUsedByte;
            if (unusedBitCountInLastUsedByte == 0)
            {
                return 0;
            }

            var currentByte = _bytes[CurrentByteIndex];
            return BitUtility.KeepLowBits(currentByte, unusedBitCountInLastUsedByte);
        }
    }

    internal bool HasNext(int bitCount) => _currentBitIndex + bitCount <= _bytes.Length * BitsOfByte;

    internal int GetRemainingCount(int bitCount) => (_bytes.Length * BitsOfByte - _currentBitIndex) / bitCount;

    internal bool TryReadBits(int bitCount, out byte bits)
    {
        if (!HasNext(bitCount))
        {
            bits = default;
            return false;
        }
        var currentByteIndex = CurrentByteIndex;
        if (currentByteIndex >= _bytes.Length)
        {
            bits = default;
            return false;
        }

        var bitOffsetInCurrentByte = _currentBitIndex % BitsOfByte;
        var bitsLeftInCurrentByte = BitsOfByte - bitOffsetInCurrentByte;
        if (bitsLeftInCurrentByte >= bitCount)
        {
            bits = BitUtility.ReadBits(_bytes[currentByteIndex], bitOffsetInCurrentByte, bitCount);
            return true;
        }

        if (currentByteIndex + 1 >= _bytes.Length)
        {
            bits = default;
            return false;
        }

        bits = BitUtility.ReadBits(
            _bytes[currentByteIndex],
            _bytes[currentByteIndex + 1],
            bitOffsetInCurrentByte,
            bitCount
        );
        return true;
    }

    internal void Advance(int bitCount)
    {
        _currentBitIndex += bitCount;
    }

    internal bool this[int bitIndex]
    {
        get
        {
            var byteIndex = bitIndex / BitsOfByte;
            var bitOffset = bitIndex % BitsOfByte;
            return (_bytes[byteIndex] & (1 << (BitsOfByte - bitOffset - 1))) != 0;
        }
    }
}
