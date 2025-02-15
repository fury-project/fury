using System.Text;

namespace Fury.Meta;

internal abstract class AbstractLowerSpecialEncoding(MetaString.Encoding encoding) : MetaStringEncoding(encoding)
{
    internal const int BitsPerChar = 5;

    public sealed override Encoder GetEncoder() =>
        ThrowHelper.ThrowNotSupportedException_EncoderNotSupportedForThisEncoding<Encoder>(GetType().Name);

    internal static bool TryEncodeChar(char c, out byte b)
    {
        var (success, encoded) = c switch
        {
            >= 'a' and <= 'z' => (true, (byte)(c - 'a')),
            '.' => (true, NumberOfEnglishLetters),
            '_' => (true, NumberOfEnglishLetters + 1),
            '$' => (true, NumberOfEnglishLetters + 2),
            '|' => (true, NumberOfEnglishLetters + 3),
            _ => (false, default)
        };
        b = (byte)encoded;
        return success;
    }

    internal static byte EncodeChar(char c)
    {
        if (!TryEncodeChar(c, out var b))
        {
            ThrowHelper.ThrowBadSerializationInputException_UnsupportedMetaStringChar(c);
        }

        return b;
    }

    internal static bool TryDecodeByte(byte b, out char c)
    {
        (var success, c) = b switch
        {
            < NumberOfEnglishLetters => (true, (char)(b + 'a')),
            NumberOfEnglishLetters => (true, '.'),
            NumberOfEnglishLetters + 1 => (true, '_'),
            NumberOfEnglishLetters + 2 => (true, '$'),
            NumberOfEnglishLetters + 3 => (true, '|'),
            _ => (false, default)
        };
        return success;
    }

    internal static char DecodeByte(byte b)
    {
        if (!TryDecodeByte(b, out var c))
        {
            ThrowHelper.ThrowBadDeserializationInputException_UnrecognizedMetaStringCodePoint(b);
        }

        return c;
    }
}
