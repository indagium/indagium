#define NOMINMAX
#include <windows.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

static bool writeUtf8(const std::wstring& text) {
    int size = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (size <= 0 && !text.empty()) return false;
    std::string encoded(static_cast<size_t>(size), '\0');
    if (size > 0) WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), encoded.data(), size, nullptr, nullptr);
    DWORD written = 0;
    HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
    return WriteFile(output, encoded.data(), static_cast<DWORD>(encoded.size()), &written, nullptr) && written == encoded.size();
}

static std::wstring quote(const std::wstring& argument) {
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (wchar_t character : argument) {
        if (character == L'\\') ++slashes;
        else if (character == L'\"') {
            result.append(slashes * 2 + 1, L'\\');
            result += L'\"';
            slashes = 0;
        } else {
            result.append(slashes, L'\\');
            slashes = 0;
            result += character;
        }
    }
    result.append(slashes * 2, L'\\');
    result += L'\"';
    return result;
}

static int spawnChild(const wchar_t* pidPath, const wchar_t* sentinelPath, bool waitForChild) {
    wchar_t executable[MAX_PATH] = {};
    if (GetModuleFileNameW(nullptr, executable, MAX_PATH) == 0) return 90;
    std::wstring command = quote(executable) + L" --resistant-child " + quote(pidPath) + L" " + quote(sentinelPath);
    std::vector<wchar_t> mutableCommand(command.begin(), command.end());
    mutableCommand.push_back(L'\0');
    STARTUPINFOW startup = {};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process = {};
    if (!CreateProcessW(executable, mutableCommand.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &startup, &process)) return 91;
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    for (int i = 0; i < 100 && GetFileAttributesW(pidPath) == INVALID_FILE_ATTRIBUTES; ++i) Sleep(5);
    if (waitForChild) Sleep(60'000);
    return 0;
}

int wmain(int argc, wchar_t** argv) {
    if (argc < 2) return 89;
    if (std::wstring(argv[1]) == L"--argv") {
        for (int index = 2; index < argc; ++index) {
            if (!writeUtf8(argv[index]) || !writeUtf8(L"\n")) return 88;
        }
        return 0;
    }
    if (std::wstring(argv[1]) == L"--environment") {
        wchar_t cwd[32768] = {};
        if (GetCurrentDirectoryW(32768, cwd) == 0) return 86;
        const wchar_t* value = _wgetenv(L"INDAGIUM_SUPERVISOR_TEST");
        if (!writeUtf8(std::wstring(cwd) + L"|" + (value == nullptr ? L"" : value) + L"\n")) return 88;
        return 0;
    }
    if (std::wstring(argv[1]) == L"--stdin") {
        HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
        HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
        char buffer[4096];
        DWORD count = 0;
        while (ReadFile(input, buffer, sizeof(buffer), &count, nullptr) && count != 0) {
            DWORD written = 0;
            if (!WriteFile(output, buffer, count, &written, nullptr) || written != count) return 88;
        }
        return 0;
    }
    if (std::wstring(argv[1]) == L"--emit" && argc == 3) {
        const long count = std::wcstol(argv[2], nullptr, 10);
        HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
        HANDLE error = GetStdHandle(STD_ERROR_HANDLE);
        const std::string out(static_cast<size_t>(count), 'o');
        const std::string err(static_cast<size_t>(count), 'e');
        DWORD written = 0;
        if (!WriteFile(output, out.data(), static_cast<DWORD>(out.size()), &written, nullptr) || written != out.size()) return 88;
        if (!WriteFile(error, err.data(), static_cast<DWORD>(err.size()), &written, nullptr) || written != err.size()) return 88;
        return 0;
    }
    if (std::wstring(argv[1]) == L"--exit" && argc == 3) return static_cast<int>(std::wcstol(argv[2], nullptr, 10));
    if ((std::wstring(argv[1]) == L"--spawn-resistant-child" || std::wstring(argv[1]) == L"--spawn-and-wait") && argc == 4) {
        return spawnChild(argv[2], argv[3], std::wstring(argv[1]) == L"--spawn-and-wait");
    }
    if (std::wstring(argv[1]) == L"--resistant-child" && argc == 4) {
        FILE* pidFile = nullptr;
        _wfopen_s(&pidFile, argv[2], L"wb");
        if (pidFile != nullptr) {
            fwprintf(pidFile, L"%lu", GetCurrentProcessId());
            fclose(pidFile);
        }
        Sleep(900);
        FILE* sentinel = nullptr;
        _wfopen_s(&sentinel, argv[3], L"wb");
        if (sentinel != nullptr) {
            fputs("survived", sentinel);
            fclose(sentinel);
        }
        return 0;
    }
    return 87;
}
