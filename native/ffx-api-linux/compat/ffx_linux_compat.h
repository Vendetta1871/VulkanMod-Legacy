// Linux compatibility layer for the FidelityFX SDK.
//
// The Vulkan backend of the FidelityFX SDK is written against the Win32 API
// (events, threads, critical sections, QPC, MSVC CRT extensions). Instead of
// rewriting the AMD sources, this header is force-included into every
// translation unit (-include) and provides small, behaviour-compatible
// replacements on top of the C++ standard library.
//
// It is only active on non-Windows platforms, so the file can stay in the
// include path on Windows builds too.

#pragma once

#ifndef _WIN32

#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <new>
#include <pthread.h>
#include <string>
#include <thread>

// ---------------------------------------------------------------------------
// MSVC CRT extensions
// ---------------------------------------------------------------------------

#ifndef _countof
#define _countof(a) (sizeof(a) / sizeof((a)[0]))
#endif

// MSVC's sprintf_s() returns -1 when the output does not fit. The SDK only
// uses it for short, bounded numbers (breadcrumbs), so the truncation case is
// reported the same way but the buffer is always NUL terminated.
inline int sprintf_s(char* buffer, size_t sizeOfBuffer, const char* format, ...)
{
    if (buffer == nullptr || sizeOfBuffer == 0)
        return -1;

    va_list args;
    va_start(args, format);
    const int result = std::vsnprintf(buffer, sizeOfBuffer, format, args);
    va_end(args);

    if (result < 0 || static_cast<size_t>(result) >= sizeOfBuffer)
    {
        buffer[0] = '\0';
        return -1;
    }
    return result;
}

inline int strcpy_s(char* destination, size_t destinationSize, const char* source)
{
    if (destination == nullptr || destinationSize == 0)
        return EINVAL;
    if (source == nullptr)
    {
        destination[0] = '\0';
        return EINVAL;
    }

    const size_t length = std::strlen(source);
    if (length + 1 > destinationSize)
    {
        destination[0] = '\0';
        return ERANGE;
    }

    std::memcpy(destination, source, length + 1);
    return 0;
}

template <size_t N>
inline int strcpy_s(char (&destination)[N], const char* source)
{
    return strcpy_s(destination, N, source);
}

inline int wcscpy_s(wchar_t* destination, size_t destinationSize, const wchar_t* source)
{
    if (destination == nullptr || destinationSize == 0)
        return EINVAL;
    if (source == nullptr)
    {
        destination[0] = L'\0';
        return EINVAL;
    }

    const size_t length = std::wcslen(source);
    if (length + 1 > destinationSize)
    {
        destination[0] = L'\0';
        return ERANGE;
    }

    std::wmemcpy(destination, source, length + 1);
    return 0;
}

template <size_t N>
inline int wcscpy_s(wchar_t (&destination)[N], const wchar_t* source)
{
    return wcscpy_s(destination, N, source);
}

inline int swprintf_s(wchar_t* buffer, size_t sizeOfBuffer, const wchar_t* format, ...)
{
    if (buffer == nullptr || sizeOfBuffer == 0)
        return -1;

    va_list args;
    va_start(args, format);
    const int result = std::vswprintf(buffer, sizeOfBuffer, format, args);
    va_end(args);

    if (result < 0 || static_cast<size_t>(result) >= sizeOfBuffer)
    {
        buffer[0] = L'\0';
        return -1;
    }
    return result;
}

// ---------------------------------------------------------------------------
// Win32 scalar types / constants
// ---------------------------------------------------------------------------

typedef void*          HANDLE;
typedef unsigned long  DWORD;
typedef int            BOOL;
typedef void*          LPVOID;
typedef wchar_t        WCHAR;
typedef const char*    LPCSTR;
typedef char*          LPSTR;

typedef uint64_t UINT64;
typedef uint32_t UINT32;
typedef uint16_t UINT16;
typedef uint8_t  UINT8;
typedef int64_t  INT64;
typedef int32_t  INT32;

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

#ifndef WINAPI
#define WINAPI
#endif

#ifndef INFINITE
#define INFINITE 0xFFFFFFFFu
#endif

#define WAIT_OBJECT_0 0x00000000u
#define WAIT_TIMEOUT  0x00000102u
#define WAIT_FAILED   0xFFFFFFFFu

#define THREAD_PRIORITY_HIGHEST 2

// CreateEvent() names are only used for cross-process lookups on Windows.
#define TEXT(quote) quote

// LARGE_INTEGER is only ever used through reinterpret_cast<int64_t*> in the
// SDK, so a single 64-bit member keeps the layout identical.
struct LARGE_INTEGER
{
    int64_t QuadPart;
};

// ---------------------------------------------------------------------------
// Kernel objects (events, threads) and their handles
// ---------------------------------------------------------------------------

namespace ffx_compat
{
    struct KernelObject
    {
        enum Kind
        {
            Event,
            Thread,
        } kind;

        explicit KernelObject(Kind k) : kind(k) {}
        virtual ~KernelObject() = default;
        virtual void wait() = 0;
    };

    // Auto-reset or manual-reset event.
    struct Event : KernelObject
    {
        std::mutex              mutex;
        std::condition_variable condition;
        bool                    signaled;
        bool                    manualReset;

        Event(bool manualReset_, bool initialState)
            : KernelObject(KernelObject::Event)
            , signaled(initialState)
            , manualReset(manualReset_)
        {
        }

        void wait() override
        {
            std::unique_lock<std::mutex> lock(mutex);
            condition.wait(lock, [this] { return signaled; });
            if (!manualReset)
                signaled = false;
        }
    };

    // Joinable thread plus completion state, so that
    // WaitForSingleObject(threadHandle, INFINITE) can block until the thread
    // function has returned (which is what the Win32 call does).
    struct Thread : KernelObject
    {
        std::thread             thread;
        std::mutex              mutex;
        std::condition_variable condition;
        bool                    finished;
        bool                    joined;

        Thread() : KernelObject(KernelObject::Thread), finished(false), joined(false) {}

        void join()
        {
            std::unique_lock<std::mutex> lock(mutex);
            if (joined || !thread.joinable())
                return;
            lock.unlock();
            thread.join();
            lock.lock();
            joined = true;
        }

        void wait() override { join(); }
    };

    inline KernelObject* toObject(HANDLE handle)
    {
        return static_cast<KernelObject*>(handle);
    }

    inline std::string toAscii(const wchar_t* text)
    {
        std::string result;
        if (text == nullptr)
            return result;
        for (; *text != L'\0'; ++text)
            result.push_back(*text < 0x80 ? static_cast<char>(*text) : '?');
        return result;
    }
} // namespace ffx_compat

inline HANDLE CreateEvent(void* /*eventAttributes*/, BOOL manualReset, BOOL initialState, const char* /*name*/)
{
    return new ffx_compat::Event(manualReset != FALSE, initialState != FALSE);
}

inline BOOL SetEvent(HANDLE handle)
{
    if (handle == nullptr)
        return FALSE;

    auto* event = static_cast<ffx_compat::Event*>(ffx_compat::toObject(handle));
    {
        std::lock_guard<std::mutex> lock(event->mutex);
        event->signaled = true;
    }
    event->condition.notify_all();
    return TRUE;
}

inline BOOL ResetEvent(HANDLE handle)
{
    if (handle == nullptr)
        return FALSE;

    auto* event = static_cast<ffx_compat::Event*>(ffx_compat::toObject(handle));
    std::lock_guard<std::mutex> lock(event->mutex);
    event->signaled = false;
    return TRUE;
}

inline DWORD WaitForSingleObject(HANDLE handle, DWORD milliseconds)
{
    if (handle == nullptr)
        return WAIT_FAILED;

    ffx_compat::KernelObject* object = ffx_compat::toObject(handle);

    if (object->kind == ffx_compat::KernelObject::Thread)
    {
        // Win32 thread handles are signaled once the thread has terminated;
        // there is no timed wait in the SDK, so always join.
        object->wait();
        return WAIT_OBJECT_0;
    }

    auto* event = static_cast<ffx_compat::Event*>(object);
    std::unique_lock<std::mutex> lock(event->mutex);
    const auto isSignaled = [event] { return event->signaled; };

    if (milliseconds == INFINITE)
    {
        event->condition.wait(lock, isSignaled);
    }
    else if (!event->condition.wait_for(lock, std::chrono::milliseconds(milliseconds), isSignaled))
    {
        return WAIT_TIMEOUT;
    }

    if (!event->manualReset)
        event->signaled = false;

    return WAIT_OBJECT_0;
}

inline BOOL CloseHandle(HANDLE handle)
{
    if (handle == nullptr)
        return FALSE;

    ffx_compat::KernelObject* object = ffx_compat::toObject(handle);
    if (object->kind == ffx_compat::KernelObject::Thread)
    {
        auto* thread = static_cast<ffx_compat::Thread*>(object);
        thread->join();
    }
    delete object;
    return TRUE;
}

inline HANDLE CreateThread(void* /*threadAttributes*/,
                           size_t /*stackSize*/,
                           DWORD(WINAPI* startAddress)(LPVOID),
                           LPVOID parameter,
                           DWORD /*creationFlags*/,
                           DWORD* threadId)
{
    auto* thread = new ffx_compat::Thread();

    // The handle is captured instead of the thread object so that the
    // completion flag can be published before the caller observes it.
    ffx_compat::Thread* handle = thread;
    thread->thread             = std::thread([handle, startAddress, parameter]() {
        startAddress(parameter);
        {
            std::lock_guard<std::mutex> lock(handle->mutex);
            handle->finished = true;
        }
        handle->condition.notify_all();
    });

    if (threadId != nullptr)
        *threadId = 0;

    return thread;
}

// Priority cannot be raised portably without privileges; keep the call a
// no-op so that the presenter thread simply runs at the default priority.
inline BOOL SetThreadPriority(HANDLE /*handle*/, int /*priority*/)
{
    return TRUE;
}

// Map the (purely diagnostic) Win32 thread description onto pthread_setname_np.
inline BOOL SetThreadDescription(HANDLE handle, const wchar_t* description)
{
    if (handle == nullptr)
        return FALSE;

    auto* thread = static_cast<ffx_compat::Thread*>(ffx_compat::toObject(handle));
    if (!thread->thread.joinable())
        return FALSE;

    const std::string name = ffx_compat::toAscii(description);
    if (!name.empty())
        pthread_setname_np(thread->thread.native_handle(), name.c_str());
    return TRUE;
}

inline void Sleep(DWORD milliseconds)
{
    std::this_thread::sleep_for(std::chrono::milliseconds(milliseconds));
}

// ---------------------------------------------------------------------------
// Critical sections
// ---------------------------------------------------------------------------
//
// The SDK only ever uses CRITICAL_SECTION objects that are members of
// C++ objects, so the mutex is constructed/destroyed together with its owner
// and Initialize/DeleteCriticalSection have nothing left to do. A recursive
// mutex is required because Win32 critical sections are reentrant.
struct CRITICAL_SECTION
{
    std::recursive_mutex mutex;
};

inline void InitializeCriticalSection(CRITICAL_SECTION* /*criticalSection*/) {}
inline void DeleteCriticalSection(CRITICAL_SECTION* /*criticalSection*/) {}
inline void EnterCriticalSection(CRITICAL_SECTION* criticalSection) { criticalSection->mutex.lock(); }
inline void LeaveCriticalSection(CRITICAL_SECTION* criticalSection) { criticalSection->mutex.unlock(); }

// ---------------------------------------------------------------------------
// Performance counter
// ---------------------------------------------------------------------------

inline BOOL QueryPerformanceCounter(LARGE_INTEGER* counter)
{
    counter->QuadPart = static_cast<int64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                                                 std::chrono::steady_clock::now().time_since_epoch())
                                                 .count());
    return TRUE;
}

inline BOOL QueryPerformanceFrequency(LARGE_INTEGER* frequency)
{
    // Matches the nanosecond resolution of QueryPerformanceCounter() above.
    frequency->QuadPart = 1000000000LL;
    return TRUE;
}

#endif // !_WIN32
