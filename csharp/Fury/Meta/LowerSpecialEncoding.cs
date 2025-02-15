using System;
using System.Text;

namespace Fury.Meta;

internal sealed class LowerSpecialEncoding() : AbstractLowerSpecialEncoding(MetaString.Encoding.LowerSpecial)
{
    public static readonly LowerSpecialEncoding Instance = new();

    private static readonly LowerSpecialDecoder SharedDecoder = new();

    public override bool CanEncode(ReadOnlySpan<char> chars)
    {
        foreach (var c in chars)
        {
            if (!TryEncodeChar(c, out _))
            {
                return false;
            }
        }
        return true;
    }

    public override int GetByteCount(ReadOnlySpan<char> chars)
    {
        return GetMaxByteCount(chars.Length);
    }

    public override int GetCharCount(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length == 0)
        {
            return 0;
        }

        var firstByte = bytes[0];
        var stripLastChar = (firstByte & StripLastCharFlagMask) != 0;
        return GetMaxCharCount(bytes.Length) - (stripLastChar ? 1 : 0);
    }

    public override int GetMaxByteCount(int charCount)
    {
        return charCount * BitsPerChar / BitsOfByte + 1;
    }

    public override int GetMaxCharCount(int byteCount)
    {
        return (byteCount * BitsOfByte - 1) / BitsPerChar;
    }

    public override int GetBytes(ReadOnlySpan<char> chars, Span<byte> bytes)
    {
        var bitsWriter = new BitsWriter(bytes);
        var charsReader = new CharsReader(chars);

        bitsWriter.Advance(1);
        while (charsReader.TryReadChar(out var c))
        {
            var charByte = EncodeChar(c);

            if (bitsWriter.TryWriteBits(BitsPerChar, charByte))
            {
                charsReader.Advance();
                bitsWriter.Advance(BitsPerChar);
            }
            else
            {
                break;
            }
        }

        if (charsReader.CharsUsed < chars.Length)
        {
            ThrowHelper.ThrowArgumentException_InsufficientSpaceInTheOutputBuffer(nameof(bytes));
        }

        if (bitsWriter.UnusedBitCountInLastUsedByte >= BitsPerChar)
        {
            bitsWriter[0] = true;
        }

        return bitsWriter.BytesUsed;
    }

    public override int GetChars(ReadOnlySpan<byte> bytes, Span<char> chars)
    {
        SharedDecoder.Convert(bytes, chars, true, out _, out var charsUsed, out _);
        SharedDecoder.Reset();
        return charsUsed;
    }

    internal static void GetChars(
        ReadOnlySpan<byte> bytes,
        Span<char> chars,
        LowerSpecialDecoder decoder,
        out int bytesUsed,
        out int charsUsed
    )
    {
        var bitsReader = new BitsReader(bytes);
        var charsWriter = new CharsWriter(chars);
        if (!decoder.HasState)
        {
            decoder.HasState = true;
            if (bitsReader.TryReadBits(1, out var stripLastCharFlag))
            {
                bitsReader.Advance(1);
                decoder.StripLastChar = stripLastCharFlag != 0;
            }
        }
        else
        {
            if (TryReadLeftOver(decoder, ref bitsReader, BitsPerChar, out var charByte, out var bitsUsedFromBitsReader))
            {
                var decodedChar = DecodeByte(charByte);
                if (charsWriter.TryWriteChar(decodedChar))
                {
                    bitsReader.Advance(bitsUsedFromBitsReader);
                    charsWriter.Advance();

                    if (TryReadLeftOver(decoder, ref bitsReader, BitsPerChar, out charByte, out bitsUsedFromBitsReader))
                    {
                        if (charsWriter.TryWriteChar(decodedChar))
                        {
                            bitsReader.Advance(bitsUsedFromBitsReader);
                            charsWriter.Advance();
                        }
                    }
                }
            }
        }

        while (bitsReader.TryReadBits(BitsPerChar, out var charByte))
        {
            if (bitsReader.GetRemainingCount(BitsPerChar) == 1 && decoder is { MustFlush: true, StripLastChar: true })
            {
                break;
            }
            var decodedChar = DecodeByte(charByte);
            if (charsWriter.TryWriteChar(decodedChar))
            {
                bitsReader.Advance(BitsPerChar);
                charsWriter.Advance();
            }
            else
            {
                break;
            }
        }

        decoder.SetLeftoverData(bitsReader.UnusedBitsInLastUsedByte, bitsReader.UnusedBitCountInLastUsedByte);

        bytesUsed = bitsReader.BytesUsed;
        charsUsed = charsWriter.CharsUsed;
    }

    public override Decoder GetDecoder() => new LowerSpecialDecoder();
}
