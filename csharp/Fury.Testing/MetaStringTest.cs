using System.Text;
using Bogus;
using Fury.Meta;

namespace Fury.Testing;

public sealed class MetaStringTest
{
    public static readonly IEnumerable<object[]> Lengths = Enumerable.Range(0, 9).Select(i => new object[] { i });

    private static readonly string LowerSpecialChars = Enumerable
        .Range(0, 1 << AbstractLowerSpecialEncoding.BitsPerChar)
        .Select(i => (AbstractLowerSpecialEncoding.TryDecodeByte((byte)i, out var c), c))
        .Where(t => t.Item1)
        .Aggregate(new StringBuilder(), (builder, t) => builder.Append(t.c))
        .ToString();

    private static readonly string AllToLowerSpecialChars = Enumerable
        .Range(0, 1 << AbstractLowerSpecialEncoding.BitsPerChar)
        .Select(i => (AbstractLowerSpecialEncoding.TryDecodeByte((byte)i, out var c), c))
        .Where(t => t.Item1 && t.c != AllToLowerSpecialEncoding.UpperCaseFlag)
        .Aggregate(new StringBuilder(), (builder, t) => builder.Append(t.c).Append(char.ToUpperInvariant(t.c)))
        .ToString();

    private static readonly string TypeNameLowerUpperDigitSpecialChars = Enumerable
        .Range(0, 1 << LowerUpperDigitSpecialEncoding.BitsPerChar)
        .Select(i => (Encodings.TypeNameEncoding.LowerUpperDigit.TryDecodeByte((byte)i, out var c), c))
        .Where(t => t.Item1 && char.IsLetterOrDigit(t.c))
        .Aggregate(new StringBuilder(), (builder, t) => builder.Append(t.c))
        .ToString();

    private static readonly string NamespaceLowerUpperDigitSpecialChars = Enumerable
        .Range(0, 1 << LowerUpperDigitSpecialEncoding.BitsPerChar)
        .Select(i => (Encodings.NamespaceEncoding.LowerUpperDigit.TryDecodeByte((byte)i, out var c), c))
        .Where(t => t.Item1 && char.IsLetterOrDigit(t.c))
        .Aggregate(new StringBuilder(), (builder, t) => builder.Append(t.c))
        .ToString();

    [Theory]
    [MemberData(nameof(Lengths))]
    public void LowerSpecialEncoding_InputString_ShouldReturnTheSame(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, LowerSpecialChars);

        var bufferLength = LowerSpecialEncoding.Instance.GetByteCount(stubString);
        Span<byte> buffer = stackalloc byte[bufferLength];
        LowerSpecialEncoding.Instance.GetBytes(stubString, buffer);
        var output = LowerSpecialEncoding.Instance.GetString(buffer);

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void LowerSpecialEncoding_InputSeparatedBytes_ShouldReturnConcatenatedString(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, LowerSpecialChars);

        var bufferLength = LowerSpecialEncoding.Instance.GetByteCount(stubString);
        Span<byte> bytes = stackalloc byte[bufferLength];
        Span<char> chars = stackalloc char[stubString.Length];
        LowerSpecialEncoding.Instance.GetBytes(stubString, bytes);
        var decoder = LowerSpecialEncoding.Instance.GetDecoder();
        var emptyChars = chars;
        for (var i = 0; i < bytes.Length; i++)
        {
            var slicedBytes = bytes.Slice(i, 1);
            decoder.Convert(slicedBytes, emptyChars, i == bytes.Length - 1, out _, out var charsUsed, out _);
            emptyChars = emptyChars.Slice(charsUsed);
        }

        var output = chars.ToString();

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void FirstToLowerSpecialEncoding_InputString_ShouldReturnTheSame(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, LowerSpecialChars);
        if (stubString.Length > 0 && char.IsLower(stubString[0]))
        {
            Span<char> stubSpan = stackalloc char[stubString.Length];
            stubString.AsSpan().CopyTo(stubSpan);
            stubSpan[0] = char.ToUpperInvariant(stubSpan[0]);
            stubString = stubSpan.ToString();
        }

        var bufferLength = FirstToLowerSpecialEncoding.Instance.GetByteCount(stubString);
        Span<byte> buffer = stackalloc byte[bufferLength];
        FirstToLowerSpecialEncoding.Instance.GetBytes(stubString, buffer);
        var output = FirstToLowerSpecialEncoding.Instance.GetString(buffer);

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void FirstToLowerSpecialEncoding_InputSeparatedBytes_ShouldReturnConcatenatedString(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, LowerSpecialChars);
        if (stubString.Length > 0 && char.IsLower(stubString[0]))
        {
            Span<char> stubSpan = stackalloc char[stubString.Length];
            stubString.AsSpan().CopyTo(stubSpan);
            stubSpan[0] = char.ToUpperInvariant(stubSpan[0]);
            stubString = stubSpan.ToString();
        }

        var bufferLength = FirstToLowerSpecialEncoding.Instance.GetByteCount(stubString);
        Span<byte> bytes = stackalloc byte[bufferLength];
        Span<char> chars = stackalloc char[stubString.Length];
        FirstToLowerSpecialEncoding.Instance.GetBytes(stubString, bytes);
        var decoder = FirstToLowerSpecialEncoding.Instance.GetDecoder();
        var emptyChars = chars;
        for (var i = 0; i < bytes.Length; i++)
        {
            var slicedBytes = bytes.Slice(i, 1);
            decoder.Convert(slicedBytes, emptyChars, i == bytes.Length - 1, out _, out var charsUsed, out _);
            emptyChars = emptyChars.Slice(charsUsed);
        }

        var output = chars.ToString();

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void AllToLowerSpecialEncoding_InputString_ShouldReturnTheSame(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, AllToLowerSpecialChars);

        var bufferLength = AllToLowerSpecialEncoding.Instance.GetByteCount(stubString);
        Span<byte> buffer = stackalloc byte[bufferLength];
        AllToLowerSpecialEncoding.Instance.GetBytes(stubString, buffer);
        var output = AllToLowerSpecialEncoding.Instance.GetString(buffer);

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void AllToLowerSpecialEncoding_InputSeparatedBytes_ShouldReturnConcatenatedString(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, AllToLowerSpecialChars);

        var bufferLength = AllToLowerSpecialEncoding.Instance.GetByteCount(stubString);
        Span<byte> bytes = stackalloc byte[bufferLength];
        Span<char> chars = stackalloc char[stubString.Length];
        AllToLowerSpecialEncoding.Instance.GetBytes(stubString, bytes);
        var decoder = AllToLowerSpecialEncoding.Instance.GetDecoder();
        var emptyChars = chars;
        for (var i = 0; i < bytes.Length; i++)
        {
            var slicedBytes = bytes.Slice(i, 1);
            decoder.Convert(slicedBytes, emptyChars, i == bytes.Length - 1, out _, out var charsUsed, out _);
            emptyChars = emptyChars.Slice(charsUsed);
        }

        var output = chars.ToString();

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void TypeNameLowerUpperDigitSpecialEncoding_InputString_ShouldReturnTheSame(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, TypeNameLowerUpperDigitSpecialChars);

        var bufferLength = Encodings.TypeNameEncoding.LowerUpperDigit.GetByteCount(stubString);
        Span<byte> buffer = stackalloc byte[bufferLength];
        Encodings.TypeNameEncoding.LowerUpperDigit.GetBytes(stubString, buffer);
        var output = Encodings.TypeNameEncoding.LowerUpperDigit.GetString(buffer);

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void TypeNameLowerUpperDigitSpecialEncoding_InputSeparatedBytes_ShouldReturnConcatenatedString(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, TypeNameLowerUpperDigitSpecialChars);

        var bufferLength = Encodings.TypeNameEncoding.LowerUpperDigit.GetByteCount(stubString);
        Span<byte> bytes = stackalloc byte[bufferLength];
        Span<char> chars = stackalloc char[stubString.Length];
        Encodings.TypeNameEncoding.LowerUpperDigit.GetBytes(stubString, bytes);
        var decoder = Encodings.TypeNameEncoding.LowerUpperDigit.GetDecoder();
        var emptyChars = chars;
        for (var i = 0; i < bytes.Length; i++)
        {
            var slicedBytes = bytes.Slice(i, 1);
            decoder.Convert(slicedBytes, emptyChars, i == bytes.Length - 1, out _, out var charsUsed, out _);
            emptyChars = emptyChars.Slice(charsUsed);
        }

        var output = chars.ToString();

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void NamespaceLowerUpperDigitSpecialEncoding_InputString_ShouldReturnTheSame(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, NamespaceLowerUpperDigitSpecialChars);

        var bufferLength = Encodings.NamespaceEncoding.LowerUpperDigit.GetByteCount(stubString);
        Span<byte> buffer = stackalloc byte[bufferLength];
        Encodings.NamespaceEncoding.LowerUpperDigit.GetBytes(stubString, buffer);
        var output = Encodings.NamespaceEncoding.LowerUpperDigit.GetString(buffer);

        Assert.Equal(stubString, output);
    }

    [Theory]
    [MemberData(nameof(Lengths))]
    public void NamespaceLowerUpperDigitSpecialEncoding_InputSeparatedBytes_ShouldReturnConcatenatedString(int length)
    {
        var faker = new Faker();
        var stubString = faker.Random.String2(length, NamespaceLowerUpperDigitSpecialChars);

        var bufferLength = Encodings.NamespaceEncoding.LowerUpperDigit.GetByteCount(stubString);
        Span<byte> bytes = stackalloc byte[bufferLength];
        Span<char> chars = stackalloc char[stubString.Length];
        Encodings.NamespaceEncoding.LowerUpperDigit.GetBytes(stubString, bytes);
        var decoder = Encodings.NamespaceEncoding.LowerUpperDigit.GetDecoder();
        var emptyChars = chars;
        for (var i = 0; i < bytes.Length; i++)
        {
            var slicedBytes = bytes.Slice(i, 1);
            decoder.Convert(slicedBytes, emptyChars, i == bytes.Length - 1, out _, out var charsUsed, out _);
            emptyChars = emptyChars.Slice(charsUsed);
        }

        var output = chars.ToString();

        Assert.Equal(stubString, output);
    }
}
