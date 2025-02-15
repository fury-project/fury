using System.Buffers;
using System.IO.Pipelines;
using System.Threading;
using System.Threading.Tasks;

namespace Fury;

public sealed partial class BatchReader(PipeReader reader)
{
    private ReadOnlySequence<byte> _cachedBuffer;
    private bool _isCanceled;
    private bool _isCompleted;

    public async ValueTask<ReadResult> ReadAtLeastAsync(int minimumSize, CancellationToken cancellationToken = default)
    {
        if (_cachedBuffer.Length < minimumSize)
        {
            reader.AdvanceTo(_cachedBuffer.Start);
            var result = await reader.ReadAtLeastAsync(minimumSize, cancellationToken);
            _cachedBuffer = result.Buffer;
            _isCanceled = result.IsCanceled;
            _isCompleted = result.IsCompleted;
        }

        return new ReadResult(_cachedBuffer, _isCanceled, _isCompleted);
    }

    public void AdvanceTo(int consumed)
    {
        _cachedBuffer = _cachedBuffer.Slice(consumed);
    }

    public void Complete()
    {
        reader.AdvanceTo(_cachedBuffer.Start);
        _cachedBuffer = default;
        _isCompleted = true;
        reader.Complete();
    }
}
