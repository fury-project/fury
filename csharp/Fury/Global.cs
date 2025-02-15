using System.Runtime.CompilerServices;

// ReSharper disable once CheckNamespace
// ReSharper disable UnusedType.Global
// ReSharper disable UnusedAutoPropertyAccessor.Global

[assembly: InternalsVisibleTo("Fury.Testing")]


#if !NET8_0_OR_GREATER
// ReSharper disable once CheckNamespace
namespace System.Runtime.CompilerServices
{
    internal class IsExternalInit;
}
#endif
