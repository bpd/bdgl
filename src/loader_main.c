
#define BDGL_IMPL
//#include "bdgl_manual.h"
#include "../generated/gl33core.h"

#include <GLFW/glfw3.h>

#include <stdio.h>
#include <assert.h>
#include <string.h>


int init_gl_context() {
    if (!glfwInit()) {
        printf("glfw init error\n");
        return 3;
    }

    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

    GLFWwindow* window = glfwCreateWindow(800, 600, "GLLoader", NULL, NULL);
    if (window == NULL) {
        printf("error creating window\n");
        return 2;
    }
    glfwMakeContextCurrent(window);

    return 0;
}

#include <stdlib.h>


int main(int argc, char* argv[]) {

    // load functions
    //bdgl_load( (void**)glfp_GL_1_0, glfp_GL_1_0_names, glXGetProcAddress);
    //bdgl_load( (void**)bdgl_GL_VERSION_1_0, bdgl_GL_VERSION_1_0_names, loadproc);

    // design question:
    // * statically define what context versions we want to load
    //   - how to handle failure in loading of optional extensions/functions
    // * dynamically load all functions based on detected context version

    //glfp_glGetString glGetString = (glfp_glGetString)glXGetProcAddress("glGetString");
    //glfp_GL_1_0[0] = glXGetProcAddress("glGetString");

    // printf("got FP\n");

    // create GL context (needed before we can _call_ the functions)
    if ( init_gl_context() ) {
        printf("error creating GL context\n");
        return 2;
    }

    // implicitly called by other loader functions
    // if ( bdgl_init(loadproc) ) {
    //     printf("error parsing GL context version\n");
    //     return 7;
    // }

    if ( bdgl_load_all((bdgl_loadproc)glfwGetProcAddress) ) {
        printf("error loading GL functions \n");
        return 4;
    }

    bdgl_Version* version = &bdgl_GL_VERSION_1_0;
    if (version->loaded) {
        printf("loaded GL version %d.%d \n", version->major, version->minor);
    } else {
        printf("could not load GL version %d.%d \n", version->major, version->minor);
        return 6;
    }

    // if (bdgl_GL_VERSION_3_3.loaded) {
    //     printf("loaded GL version 3.3 \n");
    // }

    uint8_t major, minor;
    bdgl_get_context_version(&major, &minor);
    printf("parsed: %d.%d \n", major, minor);

    // extensions
    bdgl_ext_init();

    if ( bdgl_min_context(1,5) && bdgl_have_ext("GL_ARB_draw_instanced") ) {
        // example of loading extension
        printf("loading extension: GL_ARB_draw_instanced \n");

        // if ( bdgl_load_extension(&bdgl_GL_ARB_draw_instanced, loadproc) ) {
        //     printf("failed to load extension \n");
        //     return 8;
        // }

        // if (bdgl_GL_ARB_draw_instanced.loaded) {
        //     printf("loaded extension \n");
        // }
    }

    // TODO we assume that the list of extensions the GPU supports will be
    //      much larger than the extensions the application is requesting/supporting
    //
    // (my local GPU supports 242 extensions...)
    // factor in driver communcition overhead, and we want to:
    // * iterate extensions via glGetStringi once
    //   - for each named extension
    //     * walk over the extensions the application would like to support
    //
    // max OpenGL 3.3 extension name length: 49
    // (242 extensions * ~40 chars per extension = ~9k just in extension names)
    //

    printf("found %d extensions \n", bdgl_get_ext_count());

    printf("checking extensions table...\n");
    GLint extCount = bdgl_get_ext_count();

    int foundCount=0;
    for (int extIndex=0; extIndex<extCount; extIndex++) {
        const GLubyte* extName = glGetStringi(GL_EXTENSIONS, extIndex);

        printf("ext: %s \n", extName);

        int found = bdgl_have_ext((const char*)extName);
        if (found) {
            foundCount++;
        } else {
            //printf("not found: %s \n", extName);
        }
    }
    printf("foundCount: %d \n", foundCount);

    printf("not found: %d \n", bdgl_have_ext("ext_does_not_exist"));


    return 0;
}
