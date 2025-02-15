using System;
using System.Collections.Generic;

namespace Fury;

public readonly struct TypeId : IEquatable<TypeId>
{
    internal int Value { get; }

    internal TypeId(int value)
    {
        Value = value;
    }

    public bool Equals(TypeId other)
    {
        return Value == other.Value;
    }

    public override bool Equals(object? obj)
    {
        return obj is TypeId other && Equals(other);
    }

    public override int GetHashCode()
    {
        return Value;
    }

    public static bool operator ==(TypeId left, TypeId right)
    {
        return left.Equals(right);
    }

    public static bool operator !=(TypeId left, TypeId right)
    {
        return !left.Equals(right);
    }

    /// <summary>
    /// bool: a boolean value (true or false).
    /// </summary>
    public static readonly TypeId Bool = new(1);

    /// <summary>
    /// int8: an 8-bit signed integer.
    /// </summary>
    public static readonly TypeId Int8 = new(2);

    /// <summary>
    /// int16: a 16-bit signed integer.
    /// </summary>
    public static readonly TypeId Int16 = new(3);

    /// <summary>
    /// int32: a 32-bit signed integer.
    /// </summary>
    public static readonly TypeId Int32 = new(4);

    /// <summary>
    /// var\_int32: a 32-bit signed integer which uses Fury var\_int32 encoding.
    /// </summary>
    public static readonly TypeId VarInt32 = new(5);

    /// <summary>
    /// int64: a 64-bit signed integer.
    /// </summary>
    public static readonly TypeId Int64 = new(6);

    /// <summary>
    /// var\_int64: a 64-bit signed integer which uses Fury PVL encoding.
    /// </summary>
    public static readonly TypeId VarInt64 = new(7);

    /// <summary>
    /// sli\_int64: a 64-bit signed integer which uses Fury SLI encoding.
    /// </summary>
    public static readonly TypeId SliInt64 = new(8);

    /// <summary>
    /// float16: a 16-bit floating point number.
    /// </summary>
    public static readonly TypeId Float16 = new(9);

    /// <summary>
    /// float32: a 32-bit floating point number.
    /// </summary>
    public static readonly TypeId Float32 = new(10);

    /// <summary>
    /// float64: a 64-bit floating point number including NaN and Infinity.
    /// </summary>
    public static readonly TypeId Float64 = new(11);

    /// <summary>
    /// string: a text string encoded using Latin1/UTF16/UTF-8 encoding.
    /// </summary>
    public static readonly TypeId String = new(12);

    /// <summary>
    /// enum: a data type consisting of a set of named values.
    /// Rust enum with non-predefined field values are not supported as an enum.
    /// </summary>
    public static readonly TypeId Enum = new(13);

    /// <summary>
    /// named_enum: an enum whose value will be serialized as the registered name.
    /// </summary>
    public static readonly TypeId NamedEnum = new(14);

    /// <summary>
    /// a morphic (sealed) type serialized by Fury Struct serializer. i.e. it doesn't have subclasses.
    /// Suppose we're deserializing <see cref="List{T}"/>, we can save dynamic serializer dispatch since <c>T</c> is morphic (sealed).
    /// </summary>
    public static readonly TypeId Struct = new(15);

    /// <summary>
    /// a type which is polymorphic (not sealed). i.e. it has subclasses.
    /// Suppose we're deserializing <see cref="List{T}"/>, we must dispatch serializer dynamically since <c>T</c> is polymorphic (non-sealed).
    /// </summary>
    public static readonly TypeId PolymorphicStruct = new(16);

    /// <summary>
    /// a morphic (sealed) type serialized by Fury compatible Struct serializer.
    /// </summary>
    public static readonly TypeId CompatibleStruct = new(17);

    /// <summary>
    /// a non-morphic (non-sealed) type serialized by Fury compatible Struct serializer.
    /// </summary>
    public static readonly TypeId PolymorphicCompatibleStruct = new(18);

    /// <summary>
    /// a <see cref="Struct"/> whose type mapping will be encoded as a name.
    /// </summary>
    public static readonly TypeId NamedStruct = new(19);

    /// <summary>
    /// a <see cref="PolymorphicStruct"/> whose type mapping will be encoded as a name.
    /// </summary>
    public static readonly TypeId NamedPolymorphicStruct = new(20);

    /// <summary>
    /// a <see cref="CompatibleStruct"/> whose type mapping will be encoded as a name.
    /// </summary>
    public static readonly TypeId NamedCompatibleStruct = new(21);

    /// <summary>
    /// a <see cref="PolymorphicCompatibleStruct"/> whose type mapping will be encoded as a name.
    /// </summary>
    public static readonly TypeId NamedPolymorphicCompatibleStruct = new(22);

    /// <summary>
    /// a type which will be serialized by a customized serializer.
    /// </summary>
    public static readonly TypeId Ext = new(23);

    /// <summary>
    /// an <see cref="Ext"/> type which is not morphic (not sealed).
    /// </summary>
    public static readonly TypeId PolymorphicExt = new(24);

    /// <summary>
    /// an <see cref="Ext"/> type whose type mapping will be encoded as a name.
    /// </summary>
    public static readonly TypeId NamedExt = new(25);

    /// <summary>
    /// an <see cref="PolymorphicExt"/> type whose type mapping will be encoded as a name.
    /// </summary>
    public static readonly TypeId NamedPolymorphicExt = new(26);

    /// <summary>
    /// a sequence of objects.
    /// </summary>
    public static readonly TypeId List = new(27);

    /// <summary>
    /// an unordered set of unique elements.
    /// </summary>
    public static readonly TypeId Set = new(28);

    /// <summary>
    /// a map of key-value pairs. Mutable types such as <c>list/map/set/array/tensor/arrow</c> are not allowed as key of map.
    /// </summary>
    public static readonly TypeId Map = new(29);

    /// <summary>
    /// an absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
    /// </summary>
    public static readonly TypeId Duration = new(30);

    /// <summary>
    /// timestamp: a point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is relative to an epoch at UTC midnight on January 1, 1970.
    /// </summary>
    public static readonly TypeId Timestamp = new(31);

    /// <summary>
    /// a naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1, 1970.
    /// </summary>
    public static readonly TypeId LocalDate = new(32);

    /// <summary>
    /// exact decimal value represented as an integer value in two's complement.
    /// </summary>
    public static readonly TypeId Decimal = new(33);

    /// <summary>
    /// a variable-length array of bytes.
    /// </summary>
    public static readonly TypeId Binary = new(34);

    /// <summary>
    /// a multidimensional array which every sub-array can have different sizes but all have same type. only allow numeric components.
    /// Other arrays will be taken as List. The implementation should support the interoperability between array and list.
    /// </summary>
    public static readonly TypeId Array = new(35);

    /// <summary>
    /// one dimensional int16 array.
    /// </summary>
    public static readonly TypeId BoolArray = new(36);

    /// <summary>
    /// one dimensional int8 array.
    /// </summary>
    public static readonly TypeId Int8Array = new(37);

    /// <summary>
    /// one dimensional int16 array.
    /// </summary>
    public static readonly TypeId Int16Array = new(38);

    /// <summary>
    /// one dimensional int32 array.
    /// </summary>
    public static readonly TypeId Int32Array = new(39);

    /// <summary>
    /// one dimensional int64 array.
    /// </summary>
    public static readonly TypeId Int64Array = new(40);

    /// <summary>
    /// one dimensional half\_float\_16 array.
    /// </summary>
    public static readonly TypeId Float16Array = new(41);

    /// <summary>
    /// one dimensional float32 array.
    /// </summary>
    public static readonly TypeId Float32Array = new(42);

    /// <summary>
    /// one dimensional float64 array.
    /// </summary>
    public static readonly TypeId Float64Array = new(43);

    /// <summary>
    /// an <a href="https://arrow.apache.org/docs/cpp/tables.html#record-batches">arrow record batch</a> object.
    /// </summary>
    public static readonly TypeId ArrowRecordBatch = new(44);

    /// <summary>
    /// an <a href="https://arrow.apache.org/docs/cpp/tables.html#tables">arrow table</a> object.
    /// </summary>
    public static readonly TypeId ArrowTable = new(45);

    /// <summary>
    /// Checks if this type is a struct type.
    /// </summary>
    /// <returns>
    /// True if this type is a struct type; otherwise, false.
    /// </returns>
    public bool IsStructType()
    {
        return this == Struct
            || this == PolymorphicStruct
            || this == CompatibleStruct
            || this == PolymorphicCompatibleStruct
            || this == NamedStruct
            || this == NamedPolymorphicStruct
            || this == NamedCompatibleStruct
            || this == NamedPolymorphicCompatibleStruct;
    }

    internal bool IsNamed()
    {
        return this == NamedEnum
            || this == NamedStruct
            || this == NamedPolymorphicStruct
            || this == NamedCompatibleStruct
            || this == NamedPolymorphicCompatibleStruct
            || this == NamedExt
            || this == NamedPolymorphicExt;
    }
}
