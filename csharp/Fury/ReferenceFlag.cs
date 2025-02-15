namespace Fury;

internal enum ReferenceFlag : sbyte
{
    Null = -3,

    /// <summary>
    /// This flag indicates that object is a not-null value.
    /// We don't use another byte to indicate REF, so that we can save one byte.
    /// </summary>
    Ref = -2,

    /// <summary>
    /// this flag indicates that the object is a non-null value.
    /// </summary>
    NotNullValue = -1,

    /// <summary>
    /// this flag indicates that the object is a referencable and first write.
    /// </summary>
    RefValue = 0,
}
