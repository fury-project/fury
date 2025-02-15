using System;

namespace Fury.Meta;

internal sealed class MetaStringBytes
{
    private const byte EncodingMask = 0xff;

    private readonly byte[] _bytes;
    private readonly long _hashCode;
    private MetaString.Encoding _encoding;

    public long HashCode => _hashCode;
    public ReadOnlySpan<byte> Bytes => _bytes;

    public MetaStringBytes(byte[] bytes, long hashCode)
    {
        _bytes = bytes;
        _hashCode = hashCode;
        _encoding = (MetaString.Encoding)(hashCode & EncodingMask);
    }
}
