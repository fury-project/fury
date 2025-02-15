#if !NET8_0_OR_GREATER
// ReSharper disable once CheckNamespace
namespace System;

public record struct UInt128(ulong Upper, ulong Lower);
#endif
