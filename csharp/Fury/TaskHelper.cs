using System.Threading.Tasks;

namespace Fury;

internal class TaskHelper
{
    // ValueTask.CompletedTask is not available in .NET Standard 2.0

    public static readonly ValueTask CompletedValueTask = default;
}
