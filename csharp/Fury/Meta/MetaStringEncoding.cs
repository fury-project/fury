using System;
using System.Text;

namespace Fury.Meta;

internal abstract class MetaStringEncoding(MetaString.Encoding encoding) : Encoding
{
    protected const int BitsOfByte = sizeof(byte) * 8;
    protected const int NumberOfEnglishLetters = 26;
    protected const int StripLastCharFlagMask = 1 << (BitsOfByte - 1);

    public MetaString.Encoding Encoding { get; } = encoding;

    public abstract bool CanEncode(ReadOnlySpan<char> chars);

    protected static void WriteByte(byte input, ref byte b1, int bitOffset, int bitsPerChar)
    {
        var unusedBitsPerChar = BitsOfByte - bitsPerChar;
        b1 |= (byte)(input << (unusedBitsPerChar - bitOffset));
    }

    protected static void WriteByte(byte input, ref byte b1, ref byte b2, int bitOffset, int bitsPerChar)
    {
        var unusedBitsPerChar = BitsOfByte - bitsPerChar;
        b1 |= (byte)(input >>> (bitOffset - unusedBitsPerChar));
        b2 |= (byte)(input << (BitsOfByte + unusedBitsPerChar - bitOffset));
    }

    protected static byte ReadByte(byte b1, int bitOffset, int bitsPerChar)
    {
        var bitMask = (1 << bitsPerChar) - 1;
        var unusedBitsPerChar = BitsOfByte - bitsPerChar;
        return (byte)((b1 >>> (unusedBitsPerChar - bitOffset)) & bitMask);
    }

    protected static byte ReadByte(byte b1, byte b2, int bitOffset, int bitsPerChar)
    {
        var bitMask = (1 << bitsPerChar) - 1;
        var unusedBitsPerChar = BitsOfByte - bitsPerChar;
        return (byte)(
            (b1 << (bitOffset - unusedBitsPerChar) | b2 >>> (BitsOfByte + unusedBitsPerChar - bitOffset)) & bitMask
        );
    }

    protected static bool TryReadLeftOver(
        MetaStringDecoder decoder,
        ref BitsReader bitsReader,
        int bitsPerChar,
        out byte bits,
        out int bitsUsedFromBitsReader
    )
    {
        if (!decoder.HasLeftoverData)
        {
            bits = default;
            bitsUsedFromBitsReader = default;
            return false;
        }
        var (leftOverBits, leftOverBitsCount) = decoder.GetLeftoverData();
        if (leftOverBitsCount >= bitsPerChar)
        {
            leftOverBitsCount -= bitsPerChar;
            bits = BitUtility.KeepLowBits((byte)(leftOverBits >>> leftOverBitsCount), bitsPerChar);
            leftOverBits = BitUtility.KeepLowBits(leftOverBits, leftOverBitsCount);
            decoder.SetLeftoverData(leftOverBits, leftOverBitsCount);
            bitsUsedFromBitsReader = 0;
            return true;
        }

        bitsUsedFromBitsReader = bitsPerChar - leftOverBitsCount;
        if (!bitsReader.TryReadBits(bitsUsedFromBitsReader, out var bitsFromNextByte))
        {
            bits = default;
            bitsUsedFromBitsReader = 0;
            return false;
        }

        var bitsFromLeftOver = leftOverBits << bitsUsedFromBitsReader;
        bits = BitUtility.KeepLowBits((byte)(bitsFromLeftOver | bitsFromNextByte), bitsPerChar);
        decoder.SetLeftoverData(0, 0);
        return true;
    }

    internal static int GetCharCount(ReadOnlySpan<byte> bytes, int bitsPerChar, MetaStringDecoder decoder)
    {
        if (bytes.Length == 0)
        {
            return 0;
        }

        var charCount = 0;
        var currentBit = 0;
        if (!decoder.HasState)
        {
            decoder.StripLastChar = (bytes[0] & StripLastCharFlagMask) != 0;
            currentBit = 1;
        }

        if (decoder.HasLeftoverData)
        {
            var (_, bitCount) = decoder.GetLeftoverData();
            currentBit += bitsPerChar - bitCount;
            charCount++;
        }

        var bitsAvailable = bytes.Length * BitsOfByte - currentBit;
        var charsAvailable = bitsAvailable / bitsPerChar;
        charCount += charsAvailable;
        var leftOverBitCount = bitsAvailable % bitsPerChar;
        decoder.SetLeftoverData(default, leftOverBitCount);
        if (decoder is { MustFlush: true, StripLastChar: true })
        {
            charCount--;
        }

        return charCount;
    }

#if !NET8_0_OR_GREATER
    public abstract int GetByteCount(ReadOnlySpan<char> chars);
    public abstract int GetCharCount(ReadOnlySpan<byte> bytes);
    public abstract int GetBytes(ReadOnlySpan<char> chars, Span<byte> bytes);
    public abstract int GetChars(ReadOnlySpan<byte> bytes, Span<char> chars);
#endif

    public
#if NET8_0_OR_GREATER
    sealed override
#endif
    bool TryGetBytes(ReadOnlySpan<char> chars, Span<byte> bytes, out int bytesWritten)
    {
        var byteCount = GetByteCount(chars);
        if (bytes.Length < byteCount)
        {
            bytesWritten = 0;
            return false;
        }

        bytesWritten = GetBytes(chars, bytes);
        return true;
    }

    public
#if NET8_0_OR_GREATER
    sealed override
#endif
    bool TryGetChars(ReadOnlySpan<byte> bytes, Span<char> chars, out int charsWritten)
    {
        var charCount = GetCharCount(bytes);
        if (chars.Length < charCount)
        {
            charsWritten = 0;
            return false;
        }

        charsWritten = GetChars(bytes, chars);
        return true;
    }

    public sealed override unsafe int GetByteCount(char* chars, int count) =>
        GetByteCount(new ReadOnlySpan<char>(chars, count));

    public sealed override int GetByteCount(char[] chars) => GetByteCount(chars.AsSpan());

    public sealed override int GetByteCount(char[] chars, int index, int count) =>
        GetByteCount(chars.AsSpan(index, count));

    public sealed override int GetByteCount(string s) => GetByteCount(s.AsSpan());

    public sealed override unsafe int GetBytes(char* chars, int charCount, byte* bytes, int byteCount) =>
        GetBytes(new ReadOnlySpan<char>(chars, charCount), new Span<byte>(bytes, byteCount));

    public sealed override byte[] GetBytes(char[] chars) => GetBytes(chars, 0, chars.Length);

    public sealed override byte[] GetBytes(char[] chars, int index, int count)
    {
        var span = chars.AsSpan().Slice(index, count);
        var byteCount = GetByteCount(chars);
        var bytes = new byte[byteCount];
        GetBytes(span, bytes);
        return bytes;
    }

    public sealed override int GetBytes(char[] chars, int charIndex, int charCount, byte[] bytes, int byteIndex) =>
        GetBytes(chars.AsSpan(charIndex, charCount), bytes.AsSpan(byteIndex));

    public sealed override byte[] GetBytes(string s)
    {
        var span = s.AsSpan();
        var byteCount = GetByteCount(span);
        var bytes = new byte[byteCount];
        GetBytes(span, bytes);
        return bytes;
    }

    public sealed override int GetBytes(string s, int charIndex, int charCount, byte[] bytes, int byteIndex)
    {
        var span = s.AsSpan().Slice(charIndex, charCount);
        var byteCount = GetByteCount(span);
        if (bytes.Length - byteIndex < byteCount)
        {
            ThrowHelper.ThrowArgumentException(paramName: nameof(bytes));
        }
        return GetBytes(span, bytes.AsSpan(byteIndex));
    }

    public sealed override unsafe int GetCharCount(byte* bytes, int count) =>
        GetCharCount(new ReadOnlySpan<byte>(bytes, count));

    public sealed override int GetCharCount(byte[] bytes) => GetCharCount(bytes.AsSpan());

    public sealed override int GetCharCount(byte[] bytes, int index, int count) =>
        GetCharCount(bytes.AsSpan(index, count));

    public sealed override unsafe int GetChars(byte* bytes, int byteCount, char* chars, int charCount)
    {
        var byteSpan = new ReadOnlySpan<byte>(bytes, byteCount);
        var charSpan = new Span<char>(chars, charCount);
        return GetChars(byteSpan, charSpan);
    }

    public sealed override char[] GetChars(byte[] bytes)
    {
        var charCount = GetCharCount(bytes);
        var chars = new char[charCount];
        GetChars(bytes, chars);
        return chars;
    }

    public sealed override char[] GetChars(byte[] bytes, int index, int count)
    {
        var span = bytes.AsSpan(index, count);
        var charCount = GetCharCount(span);
        var chars = new char[charCount];
        GetChars(span, chars);
        return chars;
    }

    public sealed override int GetChars(byte[] bytes, int byteIndex, int byteCount, char[] chars, int charIndex)
    {
        var byteSpan = bytes.AsSpan(byteIndex, byteCount);
        var charSpan = chars.AsSpan(charIndex);
        return GetChars(byteSpan, charSpan);
    }

#if !NET8_0_OR_GREATER
    public string GetString(ReadOnlySpan<byte> bytes)
    {
        var charCount = GetCharCount(bytes);
        Span<char> chars = stackalloc char[charCount];
        GetChars(bytes, chars);
        return chars.ToString();
    }
#endif

    public sealed override string GetString(byte[] bytes) => GetString(bytes.AsSpan());

    public sealed override string GetString(byte[] bytes, int index, int count) =>
        GetString(bytes.AsSpan().Slice(index, count));
}
