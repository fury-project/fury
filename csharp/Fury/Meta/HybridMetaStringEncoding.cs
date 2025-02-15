using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury.Meta;

internal sealed class HybridMetaStringEncoding(char specialChar1, char specialChar2)
{
    public LowerUpperDigitSpecialEncoding LowerUpperDigit { get; } = new(specialChar1, specialChar2);

    public bool TryGetEncoding(MetaString.Encoding encoding, [NotNullWhen(true)] out MetaStringEncoding? result)
    {
        result = encoding switch
        {
            MetaString.Encoding.LowerSpecial => LowerSpecialEncoding.Instance,
            MetaString.Encoding.FirstToLowerSpecial => FirstToLowerSpecialEncoding.Instance,
            MetaString.Encoding.AllToLowerSpecial => AllToLowerSpecialEncoding.Instance,
            MetaString.Encoding.LowerUpperDigitSpecial => LowerUpperDigit,
            MetaString.Encoding.Utf8 => Utf8Encoding.Instance,
            _ => null
        };

        return result is not null;
    }

    public bool TryGetMetaString(string chars, MetaString.Encoding encoding, out MetaString output)
    {
        if (!TryGetEncoding(encoding, out var e))
        {
            output = default;
            return false;
        }

        var byteCount = e.GetByteCount(chars);
        var bytes = new byte[byteCount];
        e.GetBytes(chars.AsSpan(), bytes);
        output = new MetaString(chars, encoding, specialChar1, specialChar2, bytes);
        return true;
    }

    public bool TryGetString(
        ReadOnlySpan<byte> bytes,
        MetaString.Encoding encoding,
        [NotNullWhen(true)] out string? output
    )
    {
        if (!TryGetEncoding(encoding, out var e))
        {
            output = default;
            return false;
        }

        output = e.GetString(bytes);
        return true;
    }
}
