using System;
using System.Text;

namespace Fury.Meta;

internal sealed class AllToLowerSpecialEncoding() : AbstractLowerSpecialEncoding(MetaString.Encoding.AllToLowerSpecial)
{
    public static readonly AllToLowerSpecialEncoding Instance = new();

    internal const char UpperCaseFlag = '|';
    private static readonly byte EncodedUpperCaseFlag = EncodeChar(UpperCaseFlag);

    private static readonly AllToLowerSpecialDecoder SharedDecoder = new();

    public override bool CanEncode(ReadOnlySpan<char> chars)
    {
        foreach (var t in chars)
        {
            var c = char.ToLowerInvariant(t);
            if (!TryEncodeChar(c, out _))
            {
                return false;
            }
        }

        return true;
    }

    public override int GetByteCount(ReadOnlySpan<char> chars)
    {
        var bitCount = 0;
        foreach (var c in chars)
        {
            bitCount += char.IsUpper(c) ? BitsPerChar * 2 : BitsPerChar;
        }
        return bitCount / BitsOfByte + 1;
    }

    public override int GetCharCount(ReadOnlySpan<byte> bytes)
    {
        var charCount = SharedDecoder.GetCharCount(bytes, true);
        SharedDecoder.Reset();
        return charCount;
    }

    public override int GetMaxByteCount(int charCount)
    {
        return charCount * BitsPerChar * 2 / BitsOfByte + 1;
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
        while (charsReader.TryReadChar(out var c))
        {
            if (char.IsUpper(c))
            {
                if (bitsWriter.TryWriteBits(BitsPerChar, EncodedUpperCaseFlag))
                {
                    bitsWriter.Advance(BitsPerChar);
                }
                else
                {
                    break;
                }
                c = char.ToLowerInvariant(c);
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
        GetChars(bytes, chars, SharedDecoder, out _, out var charsUsed);
        SharedDecoder.Reset();
        return charsUsed;
    }

    private static bool TryWriteChar(
        ref CharsWriter writer,
        byte charByte,
        AllToLowerSpecialDecoder decoder,
        out bool writtenChar
    )
    {
        if (charByte == EncodedUpperCaseFlag)
        {
            if (decoder.WasLastCharUpperCaseFlag)
            {
                ThrowHelper.ThrowBadDeserializationInputException_UpperCaseFlagCannotAppearConsecutively();
            }
            writtenChar = false;
            return true;
        }

        var decodedChar = DecodeByte(charByte);
        if (decoder.WasLastCharUpperCaseFlag)
        {
            decodedChar = char.ToUpperInvariant(decodedChar);
        }

        writtenChar = writer.TryWriteChar(decodedChar);
        return writtenChar;
    }

    internal static void GetChars(
        ReadOnlySpan<byte> bytes,
        Span<char> chars,
        AllToLowerSpecialDecoder decoder,
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
                if (TryWriteChar(ref charsWriter, charByte, decoder, out var writtenChar))
                {
                    decoder.WasLastCharUpperCaseFlag = charByte == EncodedUpperCaseFlag;
                    bitsReader.Advance(bitsUsedFromBitsReader);
                    if (writtenChar)
                    {
                        charsWriter.Advance();
                    }

                    if (TryReadLeftOver(decoder, ref bitsReader, BitsPerChar, out charByte, out bitsUsedFromBitsReader))
                    {
                        if (TryWriteChar(ref charsWriter, charByte, decoder, out writtenChar))
                        {
                            decoder.WasLastCharUpperCaseFlag = charByte == EncodedUpperCaseFlag;
                            bitsReader.Advance(bitsUsedFromBitsReader);
                            if (writtenChar)
                            {
                                charsWriter.Advance();
                            }
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
            if (TryWriteChar(ref charsWriter, charByte, decoder, out var writtenChar))
            {
                decoder.WasLastCharUpperCaseFlag = charByte == EncodedUpperCaseFlag;
                bitsReader.Advance(BitsPerChar);
                if (writtenChar)
                {
                    charsWriter.Advance();
                }
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

    internal static int GetCharCount(ReadOnlySpan<byte> bytes, AllToLowerSpecialDecoder decoder)
    {
        var bitsReader = new BitsReader(bytes);
        var charCount = 0;
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
                bitsReader.Advance(bitsUsedFromBitsReader);
                if (charByte != EncodedUpperCaseFlag)
                {
                    charCount++;
                }

                if (TryReadLeftOver(decoder, ref bitsReader, BitsPerChar, out charByte, out bitsUsedFromBitsReader))
                {
                    bitsReader.Advance(bitsUsedFromBitsReader);
                    if (charByte != EncodedUpperCaseFlag)
                    {
                        charCount++;
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
            bitsReader.Advance(BitsPerChar);
            if (charByte != EncodedUpperCaseFlag)
            {
                charCount++;
            }
        }

        decoder.SetLeftoverData(bitsReader.UnusedBitsInLastUsedByte, bitsReader.UnusedBitCountInLastUsedByte);
        return charCount;
    }

    public override Decoder GetDecoder() => new AllToLowerSpecialDecoder();
}
