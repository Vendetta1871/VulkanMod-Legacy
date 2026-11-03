// mingw-w64 compatibility layer for the FidelityFX SDK (Windows cross build).
//
// The SDK is built with MSVC, where <windows.h> and the CRT headers pull in what the
// sources use without including it. Force-included (-include) into every translation
// unit of the mingw build, next to the inert Linux layer.

#pragma once

#ifdef _WIN32

#include <windows.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <new>

#ifndef _countof
#define _countof(a) (sizeof(a) / sizeof((a)[0]))
#endif

#endif
