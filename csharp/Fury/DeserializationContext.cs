using System;
using System.Diagnostics.CodeAnalysis;
using System.Threading;
using System.Threading.Tasks;
using Fury.Meta;
using Fury.Serializer;

namespace Fury;

// Async methods do not work with ref, so DeserializationContext is a class

public sealed class DeserializationContext
{
    public Fury Fury { get; }
    public BatchReader Reader { get; }
    private readonly RefContext _refContext;
    private readonly MetaStringResolver _metaStringResolver;
    private readonly TypeResolver _typeResolver;

    internal DeserializationContext(
        Fury fury,
        BatchReader reader,
        RefContext refContext,
        MetaStringResolver metaStringResolver
    )
    {
        Fury = fury;
        Reader = reader;
        _refContext = refContext;
        _metaStringResolver = metaStringResolver;
    }

    public bool TryGetDeserializer<TValue>([NotNullWhen(true)] out IDeserializer? deserializer)
    {
        return Fury.TypeResolver.TryGetOrCreateDeserializer(typeof(TValue), out deserializer);
    }

    public IDeserializer GetDeserializer<TValue>()
    {
        if (!TryGetDeserializer<TValue>(out var deserializer))
        {
            ThrowHelper.ThrowDeserializerNotFoundException_DeserializerNotFound(typeof(TValue));
        }
        return deserializer;
    }

    public async ValueTask<TValue?> ReadAsync<TValue>(
        IDeserializer? deserializer = null,
        CancellationToken cancellationToken = default
    )
        where TValue : notnull
    {
        var refFlag = await Reader.ReadReferenceFlagAsync(cancellationToken);
        if (refFlag == ReferenceFlag.Null)
        {
            return default;
        }
        if (refFlag == ReferenceFlag.Ref)
        {
            var refId = await Reader.ReadRefIdAsync(cancellationToken);
            if (!_refContext.TryGetReadValue(refId, out var readObject))
            {
                ThrowHelper.ThrowBadDeserializationInputException_ReferencedObjectNotFound(refId);
            }

            return (TValue)readObject;
        }

        if (refFlag == ReferenceFlag.RefValue)
        {
            return (TValue)await DoReadReferenceableAsync(deserializer, cancellationToken);
        }

        return await DoReadUnreferenceableAsync<TValue>(deserializer, cancellationToken);
    }

    public async ValueTask<TValue?> ReadNullableAsync<TValue>(
        IDeserializer? deserializer = null,
        CancellationToken cancellationToken = default
    )
        where TValue : struct
    {
        var refFlag = await Reader.ReadReferenceFlagAsync(cancellationToken);
        if (refFlag == ReferenceFlag.Null)
        {
            return null;
        }
        if (refFlag == ReferenceFlag.Ref)
        {
            var refId = await Reader.ReadRefIdAsync(cancellationToken);
            if (!_refContext.TryGetReadValue(refId, out var readObject))
            {
                ThrowHelper.ThrowBadDeserializationInputException_ReferencedObjectNotFound(refId);
            }

            return (TValue?)readObject;
        }

        if (refFlag == ReferenceFlag.RefValue)
        {
            return (TValue?)await DoReadReferenceableAsync(deserializer, cancellationToken);
        }

        return await DoReadUnreferenceableAsync<TValue>(deserializer, cancellationToken);
    }

    private async ValueTask<TValue> DoReadUnreferenceableAsync<TValue>(
        IDeserializer? deserializer,
        CancellationToken cancellationToken = default
    )
        where TValue : notnull
    {
        var declaredType = typeof(TValue);
        var typeInfo = await ReadTypeMetaAsync(cancellationToken);
        deserializer ??= GetPreferredDeserializer(typeInfo.Type);
        if (typeInfo.Type == declaredType && deserializer is IDeserializer<TValue> typedDeserializer)
        {
            return await typedDeserializer.ReadAndCreateAsync(this, cancellationToken);
        }
        var newObj = await deserializer.CreateInstanceAsync(this, cancellationToken);
        await deserializer.ReadAndFillAsync(this, newObj, cancellationToken);
        return (TValue)newObj.Value!;
    }

    private async ValueTask<object> DoReadReferenceableAsync(
        IDeserializer? deserializer,
        CancellationToken cancellationToken = default
    )
    {
        var typeInfo = await ReadTypeMetaAsync(cancellationToken);
        deserializer ??= GetPreferredDeserializer(typeInfo.Type);
        var newObj = await deserializer.CreateInstanceAsync(this, cancellationToken);
        _refContext.PushReferenceableObject(newObj);
        await deserializer.ReadAndFillAsync(this, newObj, cancellationToken);
        return newObj;
    }

    private async ValueTask<TypeInfo> ReadTypeMetaAsync(CancellationToken cancellationToken = default)
    {
        var typeId = await Reader.ReadTypeIdAsync(cancellationToken);
        TypeInfo typeInfo;
        if (typeId.IsNamed())
        {
            var namespaceBytes = await _metaStringResolver.ReadMetaStringBytesAsync(Reader, cancellationToken);
            var typeNameBytes = await _metaStringResolver.ReadMetaStringBytesAsync(Reader, cancellationToken);
        }
        switch (typeId)
        {
            // TODO: Read namespace
            default:
                if (!Fury.TypeResolver.TryGetTypeInfo(typeId, out typeInfo))
                {
                    ThrowHelper.ThrowBadDeserializationInputException_TypeInfoNotFound(typeId);
                }
                break;
        }
        return typeInfo;
    }

    private IDeserializer GetPreferredDeserializer(Type typeOfDeserializedObject)
    {
        if (!Fury.TypeResolver.TryGetOrCreateDeserializer(typeOfDeserializedObject, out var deserializer))
        {
            ThrowHelper.ThrowDeserializerNotFoundException_DeserializerNotFound(typeOfDeserializedObject);
        }
        return deserializer;
    }
}
