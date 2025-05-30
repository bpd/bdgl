
Writing an OpenGL loader? In 2025?

First: if you're looking for an OpenGL loader you should probably just use [glad](https://github.com/Dav1dde/glad).

This is just a hobby project I wrote to because I was curious about the `gl.xml` spec file from the Khronos registry and how `glad` worked.

## Running

Run: `java parser/GLParser.java`

An example emitted file will be written to the `generated/` directory.  Look at `GLParser.main` to see how to adjust what is emitted.

In your C code, after creating an OpenGL context and making it current, just call `bdgl_load_all` and pass in a loader function for your platform.  For example:

    #define BDGL_IMPL
    #include "../generated/gl33core.h"

    int main(int argc, char* argv[]) {
        init_your_gl_context();

        // parse context, init all generated functions
        bdgl_load_all(glXGetProcAddress);

        uint8_t major, minor;
        bdgl_get_context_version(&major, &minor);
        printf("OpenGL Context: %d.%d \n", major, minor);

      return 0;
    }

Or you can do more fine-grained per-version or per-extension loading (or not parse/load extensions at all).  Look at `src/loader_main.c` for more examples.

You can generate up to the latest OpenGL version (and extensions) that your application supports.  `bdgl` will check the current OpenGL context version, and only load versions (and GL functions) up to that version.  Your code would then check the versions and extensions you care about, through the global per-version symbols, to see what loaded and adjust your OpenGL calling logic accordingly.


## Design Goals
Overall after looking at how `glad` was implemented, my design goals for `bdgl` were:
* minimal emitted symbols/functions
* IDE auto-complete
* efficient extension parsing/handling
* header-only library

### Minimal Emitted Symbols/Functions
One thing I noticed about `glad` is that, for every command (OpenGL function) it emits:
* a typedef for the function pointer
* the function pointer itself (in both the .c and .h file)
* a `#define` so the non-`glad_`-prefixed function calls work

Example for `glCullFace`:
```
# glad.h
typedef void (APIENTRYP PFNGLCULLFACEPROC)(GLenum mode);
GLAPI PFNGLCULLFACEPROC glad_glCullFace;
#define glCullFace glad_glCullFace

# glad.c
PFNGLCULLFACEPROC glad_glCullFace = NULL;

static void load_GL_VERSION_1_0(GLADloadproc load) {
	if(!GLAD_GL_VERSION_1_0) return;
	glad_glCullFace = (PFNGLCULLFACEPROC)load("glCullFace");
	// ... load other functions
}
```

In `bdgl`, we use a macro to emit a real function (not an alias macro):

```
# gl33core.h
bdgl_defv(glCullFace,(GLenum mode),GL_VERSION_1_0,6,(mode))

#ifdef BDGL_IMPL
void* (*bdgl_fp_GL_VERSION_1_0[48])();
bdgl_Version bdgl_GL_VERSION_1_0 = {
  .major = 1,
  .minor = 0,
  .loaded = 0,
  .names =
"glBlendFunc\0"
"glClear\0"
"glClearColor\0"
"glClearDepth\0"
"glClearStencil\0"
"glColorMask\0"
"glCullFace\0"  // <- our function
// [...] other functions in GL 1.0
.funcs = (void**)bdgl_fp_GL_VERSION_1_0,
};
```

Instead of generating a per-version function to load all symbols, `bdgl` uses a manifest of all functions in a particular version that should be loaded, and has a single function that takes that data structure/manifest as a parameter.  The function pointers are stored in an array.

Above, `bdgl_defv` is a macro that expands roughly to:
```
void glCullFace(GLenum mode) {
  ((void (*)(GLenum mode))bdgl_fp_GL_VERSION_1_0[6])(mode);
}
```

(where the `6` index corresponds to the position of that function in the `bdgl_GL_VERSION_1_0` manifest and the corresponding function pointer array)

Any modern compiler will happily inline the function call so it becomes a jmp indirect:

    $ gdb -batch -ex 'set disassembly-flavor intel' -ex 'disassemble/r call_gl_example' ./dist/bdgl-loader

    Dump of assembler code for function call_gl_example:
      0x00000000004013b0 <+0>:	bf 02 1f 00 00     	mov    edi,0x1f02
      0x00000000004013b5 <+5>:	ff 25 c5 2c 00 00  	jmp    QWORD PTR [rip+0x2cc5]        # 0x404080 <glfp_GL_1_0>
    End of assembler dump.

So the runtime overhead is exactly the same as a function pointer invocation.

# IDE Auto-Complete

While it varies by IDE (apparently JetBrains' CLion has better support), many IDEs or LSP-based text editors have trouble with auto-completing arguments for function pointers.

Since `bdgl` emits a real function and not a macro'd alias, any IDE that supports auto-complete will show a useful function definition.

# Efficient Extension Parsing/Handling

One of the functions OpenGL loaders perform is extension parsing/loading.

The current approach to this provided by the OpenGL API is:

    // get the number of extensions
    GLint extCount=0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &extCount);

    // fetch each extension name by index
    for (int extIndex=0; extIndex<extCount; extIndex++) {
      const char* extName = glGetStringi(GL_EXTENSIONS, extIndex);
    }

When examining how `glad` (and a lot of OpenGL loaders) work, they allocate an array of strings (based on the extension count), and then allocate a separate string for each extension name returned by `glGetStringi`.  Then, to determine if a given extension is present/available on the GPU, they just iterate this list of strings, and do a linear search with `strcmp` on each.

The problem is, many modern GPUs have over 200 extensions (mine has 242).  That's about 8k of just extension names.  Several hundred separate small-ish allocations and a linear search over those for each extension check didn't feel right.

So my goal was:
* A simple hash table to to look up extension names
* Two allocations
  - a single allocation for a constant pool that stored all extension names
  - an allocation for the hash table that would index into the constant pool

I used a open address/linear probe w/ robin hood hashing hash table, and store the offset/length of the string in the table entry.  This made for compact representation, and with robin hood hashing the average probe distance in the hash map was 2 (w/ early-out based on entry displacement).  And since the length was stored in the entry, a length comparison could be done before reading from the constant pool.

## The OpenGL Registry gl.xml

The [gl.xml](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/xml/gl.xml) in the official Khronos registry is a train wreck.  It mixes semantic information in tags and text nodes and worst of all: it's not _self contained_.  You can't just parse it and emit a bunch of headers, because it expect you to know (for instance) when profiles were added to certain APIs.

I wrote a stax-based XML parser in Java that would then link together whatever version information it could (it still needs the caller to know about things like profiles ahead of time).  I chose Java because it's self-contained -- if you have java installed it just uses the stdlib.
