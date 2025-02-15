namespace Fury;

/// <summary>
/// Type forward/backward compatibility config.
/// </summary>
public enum CompatibleMode
{
    /// <summary>
    /// Class schema must be consistent between serialization peer and deserialization peer.
    /// </summary>
    SchemaConsistent,

    /// <summary>
    /// Class schema can be different between serialization peer and deserialization peer. They can
    /// add/delete fields independently.
    /// </summary>
    Compatible
}
