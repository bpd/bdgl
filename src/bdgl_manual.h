#include "bdgl_prefix.h"

// emulate generated output
void* (*bdgl_fp_GL_VERSION_1_0[25])();

bdgl_Version bdgl_GL_VERSION_1_0 = {
    .major = 1,
    .minor = 0,
    .loaded = 0,
    .names =
        "glGetString\0"
        "glGetStringi\0"
        "glGetIntegerv\0",
    .funcs = (void**)bdgl_fp_GL_VERSION_1_0,
};

// types
typedef unsigned char GLubyte;
typedef unsigned int GLenum;
typedef unsigned int GLuint;
typedef int GLint;

// enums
#define GL_VERSION 0x1F02
#define GL_EXTENSIONS 0x1F03
#define GL_NUM_EXTENSIONS 0x821D

// commands
bdgl_def(glGetString, const GLubyte*, (GLenum name), GL_VERSION_1_0, 0, (name))
bdgl_def(glGetStringi, const GLubyte*, (GLenum name,GLuint index), GL_VERSION_1_0, 1, (name,index))
bdgl_defv(glGetIntegerv, (GLenum pname, GLint* data), GL_VERSION_1_0, 2, (pname,data))

#include "bdgl_suffix.h"

#ifdef BDGL_IMPL
int bdgl_load_all(bdgl_loadproc loadproc) {
    return 0 + bdgl_load_version(&bdgl_GL_VERSION_1_0, loadproc);
}
#else
int bdgl_load_all(bdgl_loadproc loadproc);
#endif
