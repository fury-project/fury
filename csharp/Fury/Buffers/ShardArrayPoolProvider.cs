using System.Buffers;

namespace Fury.Buffers;

internal sealed class ShardArrayPoolProvider : IArrayPoolProvider
{
    public static readonly ShardArrayPoolProvider Instance = new();

    public ArrayPool<TElement> GetArrayPool<TElement>() => ArrayPool<TElement>.Shared;
}
