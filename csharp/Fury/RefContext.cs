using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Runtime.InteropServices;
using Fury.Buffers;
using Fury.Collections;

namespace Fury;

internal sealed class RefContext(IArrayPoolProvider poolProvider)
{
    private readonly Dictionary<object, int> _objectsToRefId = new();
    private readonly PooledList<object?> _readObjects = new(poolProvider);
    private readonly HashSet<int> _partiallyProcessedRefIds = [];

    public bool Contains(RefId refId) => refId.IsValid && refId.Value < _readObjects.Count;

    public enum ObjectProcessingState
    {
        FullyProcessed,
        PartiallyProcessed,
        Unprocessed,
    }

    public bool TryGetReadValue<TValue>(RefId refId, [NotNullWhen(true)] out TValue value)
    {
        if (!Contains(refId))
        {
            value = default!;
            return false;
        }

        var writtenValue = _readObjects[refId.Value];
        if (writtenValue is TValue typedValue)
        {
            value = typedValue;
            return true;
        }

        value = default!;
        return false;
    }

    public bool TryGetReadValue(RefId refId, [NotNullWhen(true)] out object? value)
    {
        if (!Contains(refId))
        {
            value = null;
            return false;
        }

        value = _readObjects[refId.Value];
        return value is not null;
    }

    public void PushReferenceableObject(object value)
    {
        var refId = _readObjects.Count;
        _readObjects.Add(value);
        _objectsToRefId[value] = refId;
    }

    /// <summary>
    /// This method pops the last pushed referenceable object.
    /// It is used to support <see cref="ReferenceTrackingPolicy.OnlyCircularDependency"/>.
    /// </summary>
    public void PopReferenceableObject()
    {
        var refId = _readObjects.Count - 1;
        var value = _readObjects[refId];
        _readObjects.RemoveAt(refId);
        _partiallyProcessedRefIds.Remove(refId);
        if (value is not null)
        {
            _objectsToRefId.Remove(value);
        }
    }

    /// <summary>
    /// Gets the existing refId of the specified object or pushes the object and returns a new refId.
    /// </summary>
    /// <param name="value">
    /// The object to get or push.
    /// </param>
    /// <param name="processingState">
    /// The processing state of the object.
    /// <list type="dot">
    /// <item><see cref="ObjectProcessingState.Unprocessed"/>, if the object is newly pushed.</item>
    /// <item><see cref="ObjectProcessingState.PartiallyProcessed"/>, if the object is pushed and being processed.</item>
    /// <item><see cref="ObjectProcessingState.FullyProcessed"/>, if the object is processed completely.</item>
    /// </list>
    /// </param>
    /// <returns></returns>
    public RefId GetOrPushRefId(object value, out ObjectProcessingState processingState)
    {
#if NET8_0_OR_GREATER
        ref var refId = ref CollectionsMarshal.GetValueRefOrAddDefault(_objectsToRefId, value, out var exists);
#else
        var exists = _objectsToRefId.TryGetValue(value, out var refId);
#endif
        if (exists)
        {
            processingState = _partiallyProcessedRefIds.Contains(refId)
                ? ObjectProcessingState.PartiallyProcessed
                : ObjectProcessingState.FullyProcessed;
            return new RefId(refId);
        }

        processingState = ObjectProcessingState.Unprocessed;

        refId = _readObjects.Count;
        _readObjects.Add(value);
#if !NET8_0_OR_GREATER
        _objectsToRefId.Add(value, refId);
#endif
        _partiallyProcessedRefIds.Add(refId);
        return new RefId(refId);
    }

    public void MarkFullyProcessed(RefId refId)
    {
        _partiallyProcessedRefIds.Remove(refId.Value);
    }
}
