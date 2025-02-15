using System;
using System.Diagnostics.CodeAnalysis;
using System.Runtime.CompilerServices;
using Fury.Serializer;

namespace Fury;

// BatchWriter is ref struct, so SerializationContext must be ref struct too

public ref struct SerializationContext
{
    public Fury Fury { get; }
    public BatchWriter Writer;
    private RefContext RefContext { get; }

    internal SerializationContext(Fury fury, BatchWriter writer, RefContext refContext)
    {
        Fury = fury;
        Writer = writer;
        RefContext = refContext;
    }

    public bool TryGetSerializer<TValue>([NotNullWhen(true)] out ISerializer? serializer)
    {
        return Fury.TypeResolver.TryGetOrCreateSerializer(typeof(TValue), out serializer);
    }

    public ISerializer GetSerializer<TValue>()
    {
        if (!TryGetSerializer<TValue>(out var serializer))
        {
            ThrowHelper.ThrowSerializerNotFoundException_SerializerNotFound(typeof(TValue));
        }
        return serializer;
    }

    public void Write<TValue>(in TValue? value, ReferenceTrackingPolicy referenceable, ISerializer? serializer = null)
        where TValue : notnull
    {
        if (value is null)
        {
            Writer.Write(ReferenceFlag.Null);
            return;
        }

        if (TypeHelper<TValue>.IsValueType)
        {
            // Objects declared as ValueType are not possible to be referenced

            Writer.Write(ReferenceFlag.NotNullValue);
            DoWriteValueType(in value, serializer);
            return;
        }

        if (referenceable == ReferenceTrackingPolicy.Enabled)
        {
            var refId = RefContext.GetOrPushRefId(value, out var processingState);
            if (processingState == RefContext.ObjectProcessingState.Unprocessed)
            {
                // A new referenceable object

                Writer.Write(ReferenceFlag.RefValue);
                DoWriteReferenceType(value, serializer);
                RefContext.MarkFullyProcessed(refId);
            }
            else
            {
                // A referenceable object that has been recorded

                Writer.Write(ReferenceFlag.Ref);
                Writer.Write(refId);
            }
        }
        else
        {
            var refId = RefContext.GetOrPushRefId(value, out var processingState);
            if (processingState == RefContext.ObjectProcessingState.PartiallyProcessed)
            {
                // A referenceable object that has been recorded but not fully processed,
                // which means it is the ancestor of the current object.

                // Circular dependency detected
                if (referenceable == ReferenceTrackingPolicy.OnlyCircularDependency)
                {
                    Writer.Write(ReferenceFlag.Ref);
                    Writer.Write(refId);
                }
                else
                {
                    ThrowHelper.ThrowBadSerializationInputException_CircularDependencyDetected();
                }
                return;
            }

            // ProcessingState should not be FullyProcessed,
            // because we pop the referenceable object after writing it

            // For the possible circular dependency in the future,
            // we need to write RefValue instead of NotNullValue

            var flag =
                referenceable == ReferenceTrackingPolicy.OnlyCircularDependency
                    ? ReferenceFlag.RefValue
                    : ReferenceFlag.NotNullValue;
            Writer.Write(flag);
            DoWriteReferenceType(value, serializer);
            RefContext.PopReferenceableObject();
        }
    }

    public void Write<TValue>(in TValue? value, ReferenceTrackingPolicy referenceable, ISerializer? serializer = null)
        where TValue : struct
    {
        if (value is null)
        {
            Writer.Write(ReferenceFlag.Null);
            return;
        }

        // Objects declared as ValueType are not possible to be referenced
        Writer.Write(ReferenceFlag.NotNullValue);
#if NET8_0_OR_GREATER
        DoWriteValueType(in Nullable.GetValueRefOrDefaultRef(in value), serializer);
#else
        DoWriteValueType(value.Value, serializer);
#endif
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void Write<TValue>(in TValue? value, ISerializer? serializer = null)
        where TValue : notnull
    {
        Write(value, Fury.Config.ReferenceTracking, serializer);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void Write<TValue>(in TValue? value, ISerializer? serializer = null)
        where TValue : struct
    {
        Write(value, Fury.Config.ReferenceTracking, serializer);
    }

    private void DoWriteValueType<TValue>(in TValue value, ISerializer? serializer)
        where TValue : notnull
    {
        var type = typeof(TValue);
        var typeInfo = GetOrRegisterTypeInfo(type);
        WriteTypeMeta(typeInfo);

        switch (typeInfo.TypeId) {
            // TODO: Fast path for primitive types
        }

        var typedSerializer = (ISerializer<TValue>)(serializer ?? GetPreferredSerializer(type));
        typedSerializer.Write(this, in value);
    }

    private void DoWriteReferenceType(object value, ISerializer? serializer)
    {
        var type = value.GetType();
        var typeInfo = GetOrRegisterTypeInfo(type);
        WriteTypeMeta(typeInfo);

        switch (typeInfo.TypeId) {
            // TODO: Fast path for string, string array and primitive arrays
        }

        serializer ??= GetPreferredSerializer(type);
        serializer.Write(this, value);
    }

    private TypeInfo GetOrRegisterTypeInfo(Type typeOfSerializedObject)
    {
        if (!Fury.TypeResolver.TryGetTypeInfo(typeOfSerializedObject, out var typeInfo))
        {
            ThrowHelper.ThrowBadSerializationInputException_UnregisteredType(typeOfSerializedObject);
        }

        return typeInfo;
    }

    private void WriteTypeMeta(TypeInfo typeInfo)
    {
        var typeId = typeInfo.TypeId;
        Writer.Write(typeId);
        if (typeId.IsNamed()) { }
    }

    private ISerializer GetPreferredSerializer(Type typeOfSerializedObject)
    {
        if (!Fury.TypeResolver.TryGetOrCreateSerializer(typeOfSerializedObject, out var serializer))
        {
            ThrowHelper.ThrowSerializerNotFoundException_SerializerNotFound(typeOfSerializedObject);
        }
        return serializer;
    }
}
