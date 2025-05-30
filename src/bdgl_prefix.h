#pragma once

#ifdef __gl_h_
#error OpenGL functions have already been defined, remove any other gl.h includes
#endif
#define __gl_h_

#include <stdint.h>


#if defined(_WIN32) && !defined(APIENTRY) && !defined(__CYGWIN__) && !defined(__SCITECH_SNAP__)
#define APIENTRY __stdcall
#endif

#ifndef APIENTRY
#define APIENTRY
#endif

typedef struct {
    uint8_t major;
    uint8_t minor;
    uint8_t loaded; // 1 if version was successfully loaded
    const char* names;
    void** funcs;
} bdgl_Version;

typedef struct {
    uint8_t loaded; // 1 if extension was successfully loaded
    const char* names;
    void** funcs;
} bdgl_Extension;

#ifdef BDGL_IMPL

#define bdgl_def(command, ret, sig, fp, index, call) ret APIENTRY command sig { \
    return ((ret (*)sig)bdgl_fp_##fp[index])call; \
}

#define bdgl_defv(command, sig, fp, index, call) void APIENTRY command sig { \
    ((void (*)sig)bdgl_fp_##fp[index])call; \
}

#else

#define bdgl_def(command, ret, sig, fp, index, call) ret APIENTRY command sig;
#define bdgl_defv(command, sig, fp, index, call) void APIENTRY command sig;

#endif
