#if !NET8_0_OR_GREATER
// ReSharper disable once CheckNamespace
namespace System.Diagnostics.CodeAnalysis;

[AttributeUsage(AttributeTargets.Method, Inherited = false)]
internal class DoesNotReturnAttribute : Attribute;
#endif
