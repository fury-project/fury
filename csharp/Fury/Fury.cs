using System.IO.Pipelines;
using System.Threading;
using System.Threading.Tasks;
using Fury.Buffers;
using Fury.Meta;

namespace Fury;

public sealed class Fury(Config config)
{
    public Config Config { get; } = config;

    private const short MagicNumber = 0x62D4;

    public TypeResolver TypeResolver { get; } =
        new(config.SerializerProviders, config.DeserializerProviders, config.ArrayPoolProvider);

    private readonly ObjectPool<RefContext> _refResolverPool =
        new(config.ArrayPoolProvider, () => new RefContext(config.ArrayPoolProvider));

    public void Serialize<T>(PipeWriter writer, in T? value)
        where T : notnull
    {
        var refResolver = _refResolverPool.Rent();
        try
        {
            if (SerializeCommon(new BatchWriter(writer), in value, refResolver, out var context))
            {
                context.Write(in value);
            }
        }
        finally
        {
            _refResolverPool.Return(refResolver);
        }
    }

    public void Serialize<T>(PipeWriter writer, in T? value)
        where T : struct
    {
        var refResolver = _refResolverPool.Rent();
        try
        {
            if (SerializeCommon(new BatchWriter(writer), in value, refResolver, out var context))
            {
                context.Write(in value);
            }
        }
        finally
        {
            _refResolverPool.Return(refResolver);
        }
    }

    private bool SerializeCommon<T>(
        BatchWriter writer,
        in T? value,
        RefContext refContext,
        out SerializationContext context
    )
    {
        writer.Write(MagicNumber);
        var headerFlag = HeaderFlag.LittleEndian | HeaderFlag.CrossLanguage;
        if (value is null)
        {
            headerFlag |= HeaderFlag.NullRootObject;
            writer.Write((byte)headerFlag);
            context = default;
            return false;
        }
        writer.Write((byte)headerFlag);
        writer.Write((byte)Language.Csharp);
        context = new SerializationContext(this, writer, refContext);
        return true;
    }

    public async ValueTask<T?> DeserializeAsync<T>(PipeReader reader, CancellationToken cancellationToken = default)
        where T : notnull
    {
        var refResolver = _refResolverPool.Rent();
        T? result = default;
        try
        {
            var context = await DeserializeCommonAsync(new BatchReader(reader), refResolver);
            if (context is not null)
            {
                result = await context.ReadAsync<T>(cancellationToken: cancellationToken);
            }
        }
        finally
        {
            _refResolverPool.Return(refResolver);
        }

        return result;
    }

    public async ValueTask<T?> DeserializeNullableAsync<T>(
        PipeReader reader,
        CancellationToken cancellationToken = default
    )
        where T : struct
    {
        var refResolver = _refResolverPool.Rent();
        T? result = default;
        try
        {
            var context = await DeserializeCommonAsync(new BatchReader(reader), refResolver);
            if (context is not null)
            {
                result = await context.ReadNullableAsync<T>(cancellationToken: cancellationToken);
            }
        }
        finally
        {
            _refResolverPool.Return(refResolver);
        }

        return result;
    }

    private async ValueTask<DeserializationContext?> DeserializeCommonAsync(BatchReader reader, RefContext refContext)
    {
        var magicNumber = await reader.ReadAsync<short>();
        if (magicNumber != MagicNumber)
        {
            ThrowHelper.ThrowBadDeserializationInputException_InvalidMagicNumber();
            return default;
        }
        var headerFlag = (HeaderFlag)await reader.ReadAsync<byte>();
        if (headerFlag.HasFlag(HeaderFlag.NullRootObject))
        {
            return null;
        }
        if (!headerFlag.HasFlag(HeaderFlag.CrossLanguage))
        {
            ThrowHelper.ThrowBadDeserializationInputException_NotCrossLanguage();
            return default;
        }
        if (!headerFlag.HasFlag(HeaderFlag.LittleEndian))
        {
            ThrowHelper.ThrowBadDeserializationInputException_NotLittleEndian();
            return default;
        }
        await reader.ReadAsync<byte>();
        var metaStringResolver = new MetaStringResolver(Config.ArrayPoolProvider);
        var context = new DeserializationContext(this, reader, refContext, metaStringResolver);
        return context;
    }
}
