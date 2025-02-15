using System;

namespace Fury.Meta;

internal ref struct CharsWriter(Span<char> chars)
{
    private readonly Span<char> _chars = chars;

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

    internal bool TryWriteChar(char c)
    {
        if (_currentIndex >= _chars.Length)
        {
            return false;
        }

        _chars[_currentIndex] = c;
        return true;
    }

    internal void Advance()
    {
        _currentIndex++;
    }
}
