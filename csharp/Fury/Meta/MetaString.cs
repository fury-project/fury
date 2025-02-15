namespace Fury.Meta;

internal struct MetaString
{
    public enum Encoding : byte
    {
        Utf8 = 0,
        LowerSpecial = 1,
        LowerUpperDigitSpecial = 2,
        FirstToLowerSpecial = 3,
        AllToLowerSpecial = 4,
    }

    private readonly string _value;
    private readonly Encoding _encoding;
    private readonly char _specialChar1;
    private readonly char _specialChar2;
    private readonly byte[] _bytes;
    private readonly bool _stripLastChar;

    public MetaString(string value, Encoding encoding, char specialChar1, char specialChar2, byte[] bytes)
    {
        _value = value;
        _encoding = encoding;
        _specialChar1 = specialChar1;
        _specialChar2 = specialChar2;
        _bytes = bytes;
        if (encoding != Encoding.Utf8)
        {
            if (bytes.Length <= 0)
            {
                ThrowHelper.ThrowArgumentException(
                    message: "At least one byte must be provided.",
                    paramName: nameof(bytes)
                );
            }
            _stripLastChar = (bytes[0] & 0x80) != 0;
        }
        else
        {
            _stripLastChar = false;
        }
    }
}
