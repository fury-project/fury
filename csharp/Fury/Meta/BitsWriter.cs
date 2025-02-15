using System;

namespace Fury.Meta;

public ref struct BitsWriter(Span<byte> bytes)
{
    private const int BitsOfByte = sizeof(byte) * 8;

    private readonly Span<byte> _bytes = bytes;

    private int _currentBitIndex;
    private int CurrentByteIndex => _currentBitIndex / BitsOfByte;

    internal int BytesUsed => (_currentBitIndex + BitsOfByte - 1) / BitsOfByte;
    internal int UnusedBitCountInLastUsedByte => (BitsOfByte - _currentBitIndex % BitsOfByte) % BitsOfByte;

    internal byte UnusedBitInLastUsedByte
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

    internal bool TryWriteBits(int bitCount, byte bits)
    {
        if (!HasNext(bitCount))
        {
            return false;
        }
        bits = (byte)(bits & BitUtility.GetBitMask32(bitCount));
        var currentByteIndex = CurrentByteIndex;
        if (currentByteIndex >= _bytes.Length)
        {
            return false;
        }

        var bitOffsetInCurrentByte = _currentBitIndex % BitsOfByte;
        var bitsLeftInCurrentByte = BitsOfByte - bitOffsetInCurrentByte;
        byte currentByte;
        if (bitsLeftInCurrentByte >= bitCount)
        {
            currentByte = BitUtility.ClearLowBits(_bytes[currentByteIndex], bitsLeftInCurrentByte);
            _bytes[currentByteIndex] = (byte)(currentByte | (bits << (bitsLeftInCurrentByte - bitCount)));
            return true;
        }

        if (currentByteIndex + 1 >= _bytes.Length)
        {
            return false;
        }

        var bitsToWriteInCurrentByte = bits >>> (bitCount - bitsLeftInCurrentByte);
        var bitsToWriteInNextByte = bits & BitUtility.GetBitMask32(bitCount - bitsLeftInCurrentByte);
        currentByte = BitUtility.ClearLowBits(_bytes[currentByteIndex], bitsLeftInCurrentByte);
        _bytes[currentByteIndex] = (byte)(currentByte | bitsToWriteInCurrentByte);
        _bytes[currentByteIndex + 1] = (byte)(bitsToWriteInNextByte << (BitsOfByte - bitCount + bitsLeftInCurrentByte));

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
        set
        {
            var byteIndex = bitIndex / BitsOfByte;
            var bitOffset = bitIndex % BitsOfByte;
            if (value)
            {
                _bytes[byteIndex] |= (byte)(1 << (BitsOfByte - bitOffset - 1));
            }
            else
            {
                _bytes[byteIndex] &= (byte)~(1 << (BitsOfByte - bitOffset - 1));
            }
        }
    }
}
