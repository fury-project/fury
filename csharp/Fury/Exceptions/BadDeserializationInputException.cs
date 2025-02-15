using System;
using System.Diagnostics.CodeAnalysis;
using Fury.Meta;

namespace Fury;

public class BadDeserializationInputException(string? message = null) : Exception(message);

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException(string? message = null)
    {
        throw new BadDeserializationInputException(message);
    }

    [DoesNotReturn]
    public static TReturn ThrowBadDeserializationInputException<TReturn>(string? message = null)
    {
        throw new BadDeserializationInputException(message);
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_UnrecognizedMetaStringCodePoint(byte codePoint)
    {
        throw new BadDeserializationInputException($"Unrecognized MetaString code point: {codePoint}");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_UpperCaseFlagCannotAppearConsecutively()
    {
        throw new BadDeserializationInputException(
            $"The '{AllToLowerSpecialEncoding.UpperCaseFlag}' cannot appear consecutively"
        );
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_UnknownMetaStringId(int id)
    {
        throw new BadDeserializationInputException($"Unknown MetaString ID: {id}");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_TypeInfoNotFound(TypeId id)
    {
        throw new BadDeserializationInputException($"No type info found for type id '{id}'.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_ReferencedObjectNotFound(RefId refId)
    {
        throw new BadDeserializationInputException($"Referenced object not found for ref id '{refId}'.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_InsufficientData()
    {
        throw new BadDeserializationInputException("Insufficient data.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_VarInt32Overflow()
    {
        throw new BadDeserializationInputException("VarInt32 overflow.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_InvalidMagicNumber()
    {
        throw new BadDeserializationInputException("Invalid magic number.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_NotCrossLanguage()
    {
        throw new BadDeserializationInputException("Not cross language.");
    }

    [DoesNotReturn]
    public static void ThrowBadDeserializationInputException_NotLittleEndian()
    {
        throw new BadDeserializationInputException("Not little endian.");
    }
}
