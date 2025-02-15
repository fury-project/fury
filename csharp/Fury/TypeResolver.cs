using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Fury.Buffers;
using Fury.Collections;
using Fury.Meta;
using Fury.Serializer;
using Fury.Serializer.Provider;

namespace Fury;

public sealed class TypeResolver
{
    private readonly Dictionary<Type, ISerializer> _typeToSerializers = new();
    private readonly Dictionary<Type, IDeserializer> _typeToDeserializers = new();
    private readonly Dictionary<Type, TypeInfo> _typeToTypeInfos = new();
    private readonly Dictionary<UInt128, TypeInfo> _fullNameHashToTypeInfos = new();
    private readonly PooledList<Type> _types;

    private readonly ISerializerProvider[] _serializerProviders;
    private readonly IDeserializerProvider[] _deserializerProviders;

    internal TypeResolver(
        IEnumerable<ISerializerProvider> serializerProviders,
        IEnumerable<IDeserializerProvider> deserializerProviders,
        IArrayPoolProvider poolProvider
    )
    {
        _serializerProviders = serializerProviders.ToArray();
        _deserializerProviders = deserializerProviders.ToArray();
        _types = new PooledList<Type>(poolProvider);
    }

    public bool TryGetOrCreateSerializer(Type type, [NotNullWhen(true)] out ISerializer? serializer)
    {
#if NET8_0_OR_GREATER
        ref var registeredSerializer = ref CollectionsMarshal.GetValueRefOrAddDefault(
            _typeToSerializers,
            type,
            out var exists
        );
#else
        var exists = _typeToSerializers.TryGetValue(type, out var registeredSerializer);
#endif

        if (!exists || registeredSerializer == null)
        {
            TryCreateSerializer(type, out registeredSerializer);
#if !NET8_0_OR_GREATER
            if (registeredSerializer is not null)
            {
                _typeToSerializers[type] = registeredSerializer;
            }
#endif
        }

        serializer = registeredSerializer;
        return serializer is not null;
    }

    public bool TryGetOrCreateDeserializer(Type type, [NotNullWhen(true)] out IDeserializer? deserializer)
    {
#if NET8_0_OR_GREATER
        ref var registeredDeserializer = ref CollectionsMarshal.GetValueRefOrAddDefault(
            _typeToDeserializers,
            type,
            out var exists
        );
#else
        var exists = _typeToDeserializers.TryGetValue(type, out var registeredDeserializer);
#endif

        if (!exists || registeredDeserializer == null)
        {
            TryCreateDeserializer(type, out registeredDeserializer);
#if !NET8_0_OR_GREATER
            if (registeredDeserializer is not null)
            {
                _typeToDeserializers[type] = registeredDeserializer;
            }
#endif
        }

        deserializer = registeredDeserializer;
        return deserializer is not null;
    }

    public bool TryGetTypeInfo(Type type, out TypeInfo typeInfo)
    {
#if NET8_0_OR_GREATER
        ref var registeredTypeInfo = ref CollectionsMarshal.GetValueRefOrAddDefault(
            _typeToTypeInfos,
            type,
            out var exists
        );
#else
        var exists = _typeToTypeInfos.TryGetValue(type, out var registeredTypeInfo);
#endif

        if (!exists)
        {
            var newId = _types.Count;
            _types.Add(type);
            registeredTypeInfo = new TypeInfo(new TypeId(newId), type);
#if !NET8_0_OR_GREATER
            _typeToTypeInfos[type] = registeredTypeInfo;
#endif
        }

        typeInfo = registeredTypeInfo;
        return true;
    }

    public bool TryGetTypeInfo(TypeId typeId, out TypeInfo typeInfo)
    {
        var id = typeId.Value;
        if (id < 0 || id >= _types.Count)
        {
            typeInfo = default;
            return false;
        }

        typeInfo = new TypeInfo(typeId, _types[id]);
        return true;
    }

    private bool TryCreateSerializer(Type type, [NotNullWhen(true)] out ISerializer? serializer)
    {
        for (var i = _serializerProviders.Length - 1; i >= 0; i--)
        {
            var provider = _serializerProviders[i];
            if (provider.TryCreateSerializer(this, type, out serializer))
            {
                return true;
            }
        }

        serializer = null;
        return false;
    }

    private bool TryCreateDeserializer(Type type, [NotNullWhen(true)] out IDeserializer? deserializer)
    {
        for (var i = _deserializerProviders.Length - 1; i >= 0; i--)
        {
            var provider = _deserializerProviders[i];
            if (provider.TryCreateDeserializer(this, type, out deserializer))
            {
                return true;
            }
        }

        deserializer = null;
        return false;
    }

    internal void GetOrRegisterTypeInfo(TypeId typeId, MetaStringBytes namespaceBytes, MetaStringBytes typeNameBytes)
    {
        var hashCode = new UInt128((ulong)namespaceBytes.HashCode, (ulong)typeNameBytes.HashCode);
#if NET8_0_OR_GREATER
        ref var typeInfo = ref CollectionsMarshal.GetValueRefOrAddDefault(
            _fullNameHashToTypeInfos,
            hashCode,
            out var exists
        );
#else
        var exists = _fullNameHashToTypeInfos.TryGetValue(hashCode, out var typeInfo);
#endif
        if (!exists) { }
    }
}
