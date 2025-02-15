using System;
using System.Text;

namespace Fury.Meta;

internal sealed class FirstToLowerSpecialEncoding()
    : AbstractLowerSpecialEncoding(MetaString.Encoding.FirstToLowerSpecial)
{
    public static readonly FirstToLowerSpecialEncoding Instance = new();

    private static readonly FirstToLowerSpecialDecoder SharedDecoder = new();

    public override bool CanEncode(ReadOnlySpan<char> chars)
    {
        if (chars.Length == 0)
        {
            return true;
        }

        if (!TryEncodeChar(char.ToLowerInvariant(chars[0]), out _))
        {
            return false;
        }

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
        return LowerSpecialEncoding.Instance.GetByteCount(chars);
    }

    public override int GetCharCount(ReadOnlySpan<byte> bytes)
    {
        return LowerSpecialEncoding.Instance.GetCharCount(bytes);
    }

    public override int GetMaxByteCount(int charCount)
    {
        return LowerSpecialEncoding.Instance.GetMaxByteCount(charCount);
    }

    public override int GetMaxCharCount(int byteCount)
    {
        return LowerSpecialEncoding.Instance.GetMaxCharCount(byteCount);
    }

    public override int GetBytes(ReadOnlySpan<char> chars, Span<byte> bytes)
    {
        var bitsWriter = new BitsWriter(bytes);
        var charsReader = new CharsReader(chars);

        bitsWriter.Advance(1);
        var writtenFirstCharBits = false;
        while (charsReader.TryReadChar(out var c))
        {
            if (!writtenFirstCharBits)
            {
                c = char.ToLowerInvariant(c);
                writtenFirstCharBits = true;
            }

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

    private static bool TryWriteChar(ref CharsWriter writer, byte charByte, FirstToLowerSpecialDecoder decoder)
    {
        var decodedChar = DecodeByte(charByte);
        if (!decoder.WrittenFirstChar)
        {
            decodedChar = char.ToUpperInvariant(decodedChar);
        }

        return writer.TryWriteChar(decodedChar);
    }

    internal static void GetChars(
        ReadOnlySpan<byte> bytes,
        Span<char> chars,
        FirstToLowerSpecialDecoder decoder,
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
                if (TryWriteChar(ref charsWriter, charByte, decoder))
                {
                    decoder.WrittenFirstChar = true;
                    bitsReader.Advance(bitsUsedFromBitsReader);
                    charsWriter.Advance();

                    if (TryReadLeftOver(decoder, ref bitsReader, BitsPerChar, out charByte, out bitsUsedFromBitsReader))
                    {
                        if (TryWriteChar(ref charsWriter, charByte, decoder))
                        {
                            decoder.WrittenFirstChar = true;
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

            if (TryWriteChar(ref charsWriter, charByte, decoder))
            {
                decoder.WrittenFirstChar = true;
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

    public override Decoder GetDecoder() => new FirstToLowerSpecialDecoder();
}
