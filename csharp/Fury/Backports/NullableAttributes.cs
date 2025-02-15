#if !NET8_0_OR_GREATER
// ReSharper disable once CheckNamespace
namespace System.Diagnostics.CodeAnalysis;

/// <summary>Specifies that when a method returns <see cref="P:System.Diagnostics.CodeAnalysis.NotNullWhenAttribute.ReturnValue" />, the parameter will not be <see langword="null" /> even if the corresponding type allows it.</summary>
[AttributeUsage(AttributeTargets.Parameter)]
internal sealed class NotNullWhenAttribute : Attribute
{
    /// <summary>Initializes the attribute with the specified return value condition.</summary>
    /// <param name="returnValue">The return value condition. If the method returns this value, the associated parameter will not be <see langword="null" />.</param>
    public NotNullWhenAttribute(bool returnValue) => this.ReturnValue = returnValue;

    /// <summary>Gets the return value condition.</summary>
    /// <returns>The return value condition. If the method returns this value, the associated parameter will not be <see langword="null" />.</returns>
    public bool ReturnValue { get; }
}

/// <summary>Specifies that the method will not return if the associated <see cref="T:System.Boolean" /> parameter is passed the specified value.</summary>
[AttributeUsage(AttributeTargets.Parameter)]
internal sealed class DoesNotReturnIfAttribute : Attribute
{
    /// <summary>Initializes a new instance of the <see cref="T:System.Diagnostics.CodeAnalysis.DoesNotReturnIfAttribute" /> class with the specified parameter value.</summary>
    /// <param name="parameterValue">The condition parameter value. Code after the method is considered unreachable by diagnostics if the argument to the associated parameter matches this value.</param>
    public DoesNotReturnIfAttribute(bool parameterValue) => this.ParameterValue = parameterValue;

    /// <summary>Gets the condition parameter value.</summary>
    /// <returns>The condition parameter value. Code after the method is considered unreachable by diagnostics if the argument to the associated parameter matches this value.</returns>
    public bool ParameterValue { get; }
}

/// <summary>Specifies that the method or property will ensure that the listed field and property members have not-null values.</summary>
[AttributeUsage(AttributeTargets.Method | AttributeTargets.Property, Inherited = false, AllowMultiple = true)]
internal sealed class MemberNotNullAttribute : Attribute
{
    /// <summary>Initializes the attribute with a field or property member.</summary>
    /// <param name="member">
    /// The field or property member that is promised to be not-null.
    /// </param>
    public MemberNotNullAttribute(string member) => Members = new[] { member };

    /// <summary>Initializes the attribute with the list of field and property members.</summary>
    /// <param name="members">
    /// The list of field and property members that are promised to be not-null.
    /// </param>
    public MemberNotNullAttribute(params string[] members) => Members = members;

    /// <summary>Gets field or property member names.</summary>
    public string[] Members { get; }
}
#endif
