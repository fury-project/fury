using System;
using System.IO.Pipelines;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace Fury;

// This is used to reduce the virtual call overhead of the PipeWriter

[StructLayout(LayoutKind.Auto)]
public ref partial struct BatchWriter(PipeWriter writer)
{
    private Span<byte> _cachedBuffer = Span<byte>.Empty;
    private int _consumed = 0;

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void Advance(int count)
    {
        _consumed += count;
        _cachedBuffer = _cachedBuffer.Slice(count);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public Span<byte> GetSpan(int sizeHint = 0)
    {
        if (_cachedBuffer.Length < sizeHint)
        {
            writer.Advance(_consumed);
            _consumed = 0;
            _cachedBuffer = writer.GetSpan(sizeHint);
        }

        return _cachedBuffer;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void Flush()
    {
        writer.Advance(_consumed);
        _consumed = 0;
        _cachedBuffer = Span<byte>.Empty;
    }
}
