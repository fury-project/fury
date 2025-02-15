using System;
using System.Buffers;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Fury.Buffers;
using Fury.Collections;

namespace Fury.Meta;

internal sealed class MetaStringResolver(IArrayPoolProvider poolProvider)
{
    public const int SmallStringThreshold = sizeof(long) * 2;

    private readonly Dictionary<UInt128, MetaStringBytes> _smallStrings = new();
    private readonly Dictionary<long, MetaStringBytes> _bigStrings = new();

    private readonly PooledList<MetaStringBytes> _readMetaStrings = new(poolProvider);

    public async ValueTask<MetaStringBytes> ReadMetaStringBytesAsync(
        BatchReader reader,
        CancellationToken cancellationToken = default
    )
    {
        var header = (int)await reader.Read7BitEncodedUintAsync(cancellationToken);
        var isMetaStringId = (header & 0b1) != 0;
        if (isMetaStringId)
        {
            var id = header >>> 1;
            if (id > _readMetaStrings.Count || id <= 0)
            {
                ThrowHelper.ThrowBadDeserializationInputException_UnknownMetaStringId(id);
            }
            return _readMetaStrings[id - 1]!;
        }

        var length = header >>> 1;
        MetaStringBytes byteString;
        if (length <= SmallStringThreshold)
        {
            byteString = await ReadSmallMetaStringBytesAsync(reader, length, cancellationToken);
        }
        else
        {
            byteString = await ReadBigMetaStringBytesAsync(reader, length, cancellationToken);
        }
        _readMetaStrings.Add(byteString);
        return byteString;
    }

    private async ValueTask<MetaStringBytes> ReadSmallMetaStringBytesAsync(
        BatchReader reader,
        int length,
        CancellationToken cancellationToken = default
    )
    {
        var encoding = await reader.ReadAsync<byte>(cancellationToken);
        ulong v1;
        ulong v2 = 0;
        if (length <= sizeof(long))
        {
            v1 = await reader.ReadAsAsync<ulong>(length, cancellationToken);
        }
        else
        {
            v1 = await reader.ReadAsync<ulong>(cancellationToken);
            v2 = await reader.ReadAsAsync<ulong>(length - sizeof(long), cancellationToken);
        }
        return GetOrCreateSmallMetaStringBytes(v1, v2, length, encoding);
    }

    private MetaStringBytes GetOrCreateSmallMetaStringBytes(ulong v1, ulong v2, int length, byte encoding)
    {
        var key = new UInt128(v1, v2);
#if NET8_0_OR_GREATER
        ref var byteString = ref CollectionsMarshal.GetValueRefOrAddDefault(_smallStrings, key, out var exists);
#else
        var exists = _smallStrings.TryGetValue(key, out var byteString);
#endif
        if (!exists || byteString is null)
        {
            Span<ulong> data = stackalloc ulong[2];
            data[0] = v1;
            data[1] = v2;
            var bytes = MemoryMarshal.Cast<ulong, byte>(data);
            HashHelper.MurmurHash3_x64_128(bytes, 47, out var out1, out _);
            var hashCode = Math.Abs((long)out1);
            hashCode = (hashCode & unchecked((long)0xffff_ffff_ffff_ff00L)) | encoding;
            byteString = new MetaStringBytes(bytes.Slice(0, length).ToArray(), hashCode);
#if !NET8_0_OR_GREATER
            _smallStrings.Add(key, byteString);
#endif
        }

        return byteString;
    }

    private async ValueTask<MetaStringBytes> ReadBigMetaStringBytesAsync(
        BatchReader reader,
        int length,
        CancellationToken cancellationToken = default
    )
    {
        var hashCode = await reader.ReadAsync<long>(cancellationToken);
        var readResult = await reader.ReadAtLeastAsync(length, cancellationToken);
        var byteString = GetOrCreateBigMetaStringBytes(readResult.Buffer, length, hashCode);
        reader.AdvanceTo(length);
        return byteString;
    }

    private MetaStringBytes GetOrCreateBigMetaStringBytes(ReadOnlySequence<byte> buffer, int length, long hashCode)
    {
#if NET8_0_OR_GREATER
        ref var byteString = ref CollectionsMarshal.GetValueRefOrAddDefault(_bigStrings, hashCode, out var exists);
#else
        var exists = _bigStrings.TryGetValue(hashCode, out var byteString);
#endif
        if (!exists || byteString is null)
        {
            var bytes = buffer.Slice(0, length).ToArray();
            byteString = new MetaStringBytes(bytes, hashCode);
#if !NET8_0_OR_GREATER
            _bigStrings.Add(hashCode, byteString);
#endif
        }

        return byteString;
    }
}
