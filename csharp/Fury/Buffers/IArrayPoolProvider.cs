using System.Buffers;

namespace Fury.Buffers;

public interface IArrayPoolProvider
{
    ArrayPool<TElement> GetArrayPool<TElement>();
}
