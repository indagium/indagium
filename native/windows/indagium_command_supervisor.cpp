#define NOMINMAX
#include <windows.h>

#include <string>
#include <vector>
#include <cwctype>
#include <cwchar>

static void writeStatus(const wchar_t* path, const std::string& status) {
    HANDLE file = CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, nullptr);
    if (file == INVALID_HANDLE_VALUE) return;
    DWORD written = 0;
    WriteFile(file, status.data(), static_cast<DWORD>(status.size()), &written, nullptr);
    CloseHandle(file);
}

static std::wstring quote(const std::wstring& argument) {
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (wchar_t character : argument) {
        if (character == L'\\') {
            ++slashes;
        } else if (character == L'"') {
            result.append(slashes * 2 + 1, L'\\');
            result += L'"';
            slashes = 0;
        } else {
            result.append(slashes, L'\\');
            slashes = 0;
            result += character;
        }
    }
    result.append(slashes * 2, L'\\');
    result += L'"';
    return result;
}

static bool equalsIgnoreCase(const std::wstring& left, const wchar_t* right) {
    if (left.size() != std::wcslen(right)) return false;
    for (size_t index = 0; index < left.size(); ++index) {
        if (std::towlower(left[index]) != std::towlower(right[index])) return false;
    }
    return true;
}

// cmd.exe parses the /s /c payload as shell text, not as CRT argv. Preserve that payload verbatim
// inside the one outer pair of quotes that /s /c removes; generic CRT backslash quoting corrupts
// embedded quotes and changes the shell program.
static bool isCmdShell(const std::vector<std::wstring>& arguments) {
    if (arguments.size() < 5) return false;
    std::wstring executable = arguments[0].substr(arguments[0].find_last_of(L"\\/") == std::wstring::npos
        ? 0 : arguments[0].find_last_of(L"\\/") + 1);
    return equalsIgnoreCase(executable, L"cmd.exe") && equalsIgnoreCase(arguments[1], L"/d") &&
        equalsIgnoreCase(arguments[2], L"/s") && equalsIgnoreCase(arguments[3], L"/c");
}

static std::string lastError(const char* prefix) {
    return std::string("error:") + prefix + ":" + std::to_string(GetLastError());
}

int wmain(int argc, wchar_t** args) {
    if (argc < 5 || std::wstring(args[1]) != L"--status" || std::wstring(args[3]) != L"--" || args[4][0] == L'\0') return 125;
    const wchar_t* statusPath = args[2];
    std::vector<std::wstring> commandArgs;
    commandArgs.reserve(static_cast<size_t>(argc - 4));
    for (int index = 4; index < argc; ++index) commandArgs.emplace_back(args[index]);
    std::wstring commandLine;
    if (isCmdShell(commandArgs)) {
        commandLine = quote(commandArgs[0]) + L" /d /s /c \"" + commandArgs[4] + L"\"";
        for (size_t index = 5; index < commandArgs.size(); ++index) commandLine += L" " + quote(commandArgs[index]);
    } else {
        for (const auto& argument : commandArgs) {
            if (!commandLine.empty()) commandLine += L' ';
            commandLine += quote(argument);
        }
    }
    if (commandLine.size() >= 32760) {
        writeStatus(statusPath, "error:command-line-too-long");
        return 125;
    }

    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    if (job == nullptr) {
        writeStatus(statusPath, lastError("CreateJobObject"));
        return 125;
    }
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
        writeStatus(statusPath, lastError("SetInformationJobObject"));
        CloseHandle(job);
        return 125;
    }

    SIZE_T attributeBytes = 0;
    if (InitializeProcThreadAttributeList(nullptr, 1, 0, &attributeBytes) || GetLastError() != ERROR_INSUFFICIENT_BUFFER) {
        writeStatus(statusPath, lastError("InitializeProcThreadAttributeList(size)"));
        CloseHandle(job);
        return 125;
    }
    std::vector<BYTE> attributeStorage(attributeBytes);
    auto* attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attributeStorage.data());
    if (!InitializeProcThreadAttributeList(attributes, 1, 0, &attributeBytes)) {
        writeStatus(statusPath, lastError("InitializeProcThreadAttributeList"));
        CloseHandle(job);
        return 125;
    }
    HANDLE jobList[] = {job};
    if (!UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST, jobList, sizeof(jobList), nullptr, nullptr)) {
        writeStatus(statusPath, lastError("UpdateProcThreadAttribute(JOB_LIST)"));
        DeleteProcThreadAttributeList(attributes);
        CloseHandle(job);
        return 125;
    }

    STARTUPINFOEXW startup = {};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.StartupInfo.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.StartupInfo.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    startup.lpAttributeList = attributes;
    PROCESS_INFORMATION process = {};
    std::vector<wchar_t> mutableCommand(commandLine.begin(), commandLine.end());
    mutableCommand.push_back(L'\0');
    if (!CreateProcessW(nullptr, mutableCommand.data(), nullptr, nullptr, TRUE,
            CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT, nullptr, nullptr,
            reinterpret_cast<LPSTARTUPINFOW>(&startup), &process)) {
        writeStatus(statusPath, lastError("CreateProcess"));
        DeleteProcThreadAttributeList(attributes);
        CloseHandle(job);
        return 125;
    }
    DeleteProcThreadAttributeList(attributes);
    BOOL assignedToJob = FALSE;
    if (!IsProcessInJob(process.hProcess, job, &assignedToJob)) {
        writeStatus(statusPath, lastError("CreateProcess did not assign child to its kill-on-close job"));
        TerminateProcess(process.hProcess, 125);
        WaitForSingleObject(process.hProcess, INFINITE);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        return 125;
    }
    if (!assignedToJob) {
        writeStatus(statusPath, "error:CreateProcess did not assign child to its kill-on-close job");
        TerminateProcess(process.hProcess, 125);
        WaitForSingleObject(process.hProcess, INFINITE);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        return 125;
    }
    if (ResumeThread(process.hThread) == static_cast<DWORD>(-1)) {
        writeStatus(statusPath, lastError("ResumeThread"));
        TerminateJobObject(job, 125);
        WaitForSingleObject(process.hProcess, INFINITE);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        return 125;
    }
    writeStatus(statusPath, "started");
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD exitCode = 125;
    GetExitCodeProcess(process.hProcess, &exitCode);

    // Closing this kill-on-close job also terminates every child that outlived the command process.
    CloseHandle(job);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return static_cast<int>(exitCode);
}
