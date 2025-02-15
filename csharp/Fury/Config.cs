using System.Collections.Generic;
using Fury.Buffers;
using Fury.Serializer.Provider;

namespace Fury;

public sealed record Config(
    ReferenceTrackingPolicy ReferenceTracking,
    IEnumerable<ISerializerProvider> SerializerProviders,
    IEnumerable<IDeserializerProvider> DeserializerProviders,
    IArrayPoolProvider ArrayPoolProvider
);

/// <summary>
/// Specifies how reference information will be written when serializing referenceable objects.
/// </summary>
public enum ReferenceTrackingPolicy
{
    /// <summary>
    /// All referenceable objects will be written as referenceable serialization data.
    /// </summary>
    Enabled,

    /// <summary>
    /// All referenceable objects will be written as unreferenceable serialization data.
    /// Referenceable objects may be written multiple times.
    /// Throws <see cref="CircularDependencyException"/> if a circular dependency is detected.
    /// </summary>
    Disabled,

    /// <summary>
    /// Similar to <see cref="Disabled"/> but all referenceable objects will be written as referenceable serialization data.
    /// When a circular dependency is detected, only the reference information will be written.
    /// This policy may be slower than <see cref="Disabled"/> when deserializing because reference tracking is still needed.
    /// </summary>
    OnlyCircularDependency
}
