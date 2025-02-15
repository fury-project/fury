using System;

namespace Fury.Meta;

public ref struct CharsReader(ReadOnlySpan<char> chars)
{
    private readonly ReadOnlySpan<char> _chars = chars;

    private int _currentIndex;

    internal int CharsUsed => _currentIndex;

    internal bool TryReadChar(out char c)
    {
        if (_currentIndex >= _chars.Length)
        {
            c = default;
            return false;
        }

        c = _chars[_currentIndex];
        return true;
    }

    internal void Advance()
    {
        _currentIndex++;
    }
}
