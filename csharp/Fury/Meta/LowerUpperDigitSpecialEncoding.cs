using System;
using System.Text;

namespace Fury.Meta;

internal sealed class LowerUpperDigitSpecialEncoding(char specialChar1, char specialChar2)
    : MetaStringEncoding(MetaString.Encoding.LowerUpperDigitSpecial)
{
    internal const int BitsPerChar = 6;
    private const int UnusedBitsPerChar = BitsOfByte - BitsPerChar;
    private const int MaxRepresentableChar = (1 << BitsPerChar) - 1;

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
        const byte bitMask = MaxRepresentableChar;

        var charCount = GetCharCount(bytes);
        if (chars.Length < charCount)
        {
            ThrowHelper.ThrowArgumentException(paramName: nameof(chars));
        }
        for (var i = 0; i < charCount; i++)
        {
            var currentBit = i * BitsPerChar + 1;
            var byteIndex = currentBit / BitsOfByte;
            var bitOffset = currentBit % BitsOfByte;

            byte charByte;
            if (bitOffset <= UnusedBitsPerChar)
            {
                // bitOffset locations   read locations
                // x _ _ _ _ _ _ _       x x x x x x _ _
                // _ x _ _ _ _ _ _       _ x x x x x x _
                // _ _ x _ _ _ _ _       _ _ x x x x x x

                charByte = (byte)((bytes[byteIndex] >>> (UnusedBitsPerChar - bitOffset)) & bitMask);
            }
            else
            {
                // bitOffset locations   read locations
                // _ _ _ x _ _ _ _       _ _ _ x x x x x | x _ _ _ _ _ _ _
                // _ _ _ _ x _ _ _       _ _ _ _ x x x x | x x _ _ _ _ _ _
                // _ _ _ _ _ x _ _       _ _ _ _ _ x x x | x x x _ _ _ _ _
                // _ _ _ _ _ _ x _       _ _ _ _ _ _ x x | x x x x _ _ _ _
                // _ _ _ _ _ _ _ x       _ _ _ _ _ _ _ x | x x x x x _ _ _

                charByte = (byte)(
                    (
                        bytes[byteIndex] << (bitOffset - UnusedBitsPerChar)
                        | bytes[byteIndex + 1] >>> (BitsOfByte + UnusedBitsPerChar - bitOffset)
                    ) & bitMask
                );
            }

            if (!TryDecodeByte(charByte, out var c))
            {
                ThrowHelper.ThrowArgumentOutOfRangeException(nameof(bytes));
            }
            chars[i] = c;
        }

        return charCount;
    }

    internal void GetChars(
        ReadOnlySpan<byte> bytes,
        Span<char> chars,
        LowerUpperDigitSpecialDecoder decoder,
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

    private bool TryEncodeChar(char c, out byte b)
    {
        var success = true;
        if (c == specialChar1)
        {
            b = MaxRepresentableChar - 1;
        }
        else if (c == specialChar2)
        {
            b = MaxRepresentableChar;
        }
        else
        {
            (success, b) = c switch
            {
                >= 'a' and <= 'z' => (true, (byte)(c - 'a')),
                >= 'A' and <= 'Z' => (true, (byte)(c - 'A' + NumberOfEnglishLetters)),
                >= '0' and <= '9' => (true, (byte)(c - '0' + NumberOfEnglishLetters * 2)),
                _ => (false, default),
            };
        }

        return success;
    }

    private byte EncodeChar(char c)
    {
        if (!TryEncodeChar(c, out var b))
        {
            ThrowHelper.ThrowBadSerializationInputException_UnsupportedMetaStringChar(c);
        }

        return b;
    }

    internal bool TryDecodeByte(byte b, out char c)
    {
        (var success, c) = b switch
        {
            < NumberOfEnglishLetters => (true, (char)(b + 'a')),
            < NumberOfEnglishLetters * 2 => (true, (char)(b - NumberOfEnglishLetters + 'A')),
            < NumberOfEnglishLetters * 2 + 10 => (true, (char)(b - NumberOfEnglishLetters * 2 + '0')),
            MaxRepresentableChar - 1 => (true, specialChar1),
            MaxRepresentableChar => (true, specialChar2),
            _ => (false, default),
        };

        return success;
    }

    private char DecodeByte(byte b)
    {
        if (!TryDecodeByte(b, out var c))
        {
            ThrowHelper.ThrowBadDeserializationInputException_UnrecognizedMetaStringCodePoint(b);
        }

        return c;
    }

    public override Decoder GetDecoder() => new LowerUpperDigitSpecialDecoder(this);
}
