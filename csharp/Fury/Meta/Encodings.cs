namespace Fury.Meta;

internal sealed class Encodings
{
    public static readonly HybridMetaStringEncoding CommonEncoding = new('.', '_');
    public static readonly HybridMetaStringEncoding NamespaceEncoding = CommonEncoding;
    public static readonly HybridMetaStringEncoding TypeNameEncoding = new('$', '_');

    private static readonly MetaString.Encoding[] NamespaceEncodings =
    [
        MetaString.Encoding.Utf8,
        MetaString.Encoding.AllToLowerSpecial,
        MetaString.Encoding.LowerUpperDigitSpecial
    ];
    private static readonly MetaString.Encoding[] TypeNameEncodings =
    [
        MetaString.Encoding.Utf8,
        MetaString.Encoding.LowerUpperDigitSpecial,
        MetaString.Encoding.FirstToLowerSpecial,
        MetaString.Encoding.AllToLowerSpecial
    ];
}
