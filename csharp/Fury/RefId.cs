namespace Fury;

public readonly struct RefId(int value)
{
    public static readonly RefId Invalid = new(-1);

    internal int Value { get; } = value;

    public bool IsValid => Value >= 0;

    public override string ToString() => Value.ToString();
}
