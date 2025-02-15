using System;
using System.Text;

namespace Fury.Meta;

internal sealed class Utf8Encoding() : MetaStringEncoding(MetaString.Encoding.Utf8)
{
    public static readonly Utf8Encoding Instance = new();

    public override bool CanEncode(ReadOnlySpan<char> chars) => true;

    public override int GetMaxByteCount(int charCount) => UTF8.GetMaxByteCount(charCount);

    public override int GetMaxCharCount(int byteCount) => UTF8.GetMaxCharCount(byteCount);

    public override unsafe int GetByteCount(ReadOnlySpan<char> chars)
    {
        fixed (char* p = chars)
        {
            return UTF8.GetByteCount(p, chars.Length);
        }
    }

    public override unsafe int GetCharCount(ReadOnlySpan<byte> bytes)
    {
        fixed (byte* p = bytes)
        {
            return UTF8.GetCharCount(p, bytes.Length);
        }
    }

    public override unsafe int GetBytes(ReadOnlySpan<char> chars, Span<byte> bytes)
    {
        fixed (char* pChars = chars)
        fixed (byte* pBytes = bytes)
        {
            return UTF8.GetBytes(pChars, chars.Length, pBytes, bytes.Length);
        }
    }

    public override unsafe int GetChars(ReadOnlySpan<byte> bytes, Span<char> chars)
    {
        fixed (byte* pBytes = bytes)
        fixed (char* pChars = chars)
        {
            return UTF8.GetChars(pBytes, bytes.Length, pChars, chars.Length);
        }
    }

    public override Encoder GetEncoder()
    {
        return UTF8.GetEncoder();
    }
}
