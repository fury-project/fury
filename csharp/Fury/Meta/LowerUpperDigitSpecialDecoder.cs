using System;

namespace Fury.Meta;

internal sealed class LowerUpperDigitSpecialDecoder(LowerUpperDigitSpecialEncoding encoding) : MetaStringDecoder
{
    public override void Convert(
        ReadOnlySpan<byte> bytes,
        Span<char> chars,
        bool flush,
        out int bytesUsed,
        out int charsUsed,
        out bool completed
    )
    {
        MustFlush = flush;
        encoding.GetChars(bytes, chars, this, out bytesUsed, out charsUsed);
        completed = bytesUsed == bytes.Length && !HasLeftoverData;
        if (flush)
        {
            Reset();
        }
    }

    public override int GetCharCount(ReadOnlySpan<byte> bytes, bool flush)
    {
        MustFlush = flush;
        var charCount = encoding.GetCharCount(bytes);
        if (flush)
        {
            Reset();
        }
        return charCount;
    }

    public override int GetChars(ReadOnlySpan<byte> bytes, Span<char> chars, bool flush)
    {
        Convert(bytes, chars, flush, out _, out var charsUsed, out _);
        return charsUsed;
    }
}
