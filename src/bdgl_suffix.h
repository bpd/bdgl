
typedef void* (*bdgl_loadproc)(char*);

// init/parse current GL context
int bdgl_init(bdgl_loadproc loadproc);

void bdgl_get_context_version(uint8_t* major, uint8_t* minor);
int bdgl_min_context(uint8_t major, uint8_t minor);

// load the given function name/pointer arrays (usually from Version/Extension)
const char* bdgl_load(void** funcs, const char* funcNames, bdgl_loadproc loadproc);
// will fail if context version
int bdgl_load_version(bdgl_Version* version, bdgl_loadproc loadproc);
// bdgl_have_ext(name) should be called before loading the extension
int bdgl_load_extension(bdgl_Extension* extension, bdgl_loadproc loadproc);

// load all generated versions
int bdgl_load_all(bdgl_loadproc loadproc);

// fetch/parse extension list (required for other ext functions to work)
void bdgl_ext_init();
uint32_t bdgl_get_ext_count();
int bdgl_have_ext(const char* extName);
// after calling, no extension functions can be called
void bdgl_ext_free();


#ifdef BDGL_IMPL

#include <string.h>
#include <stdlib.h>

static struct {
    uint8_t major;
    uint8_t minor;
} bdgl_ctx;

static struct {
    uint32_t tblSize;
    uint32_t* tbl; // table of entries (offset/len into pool)

    uint8_t* pool; // constant pool of strings
    uint32_t poolSize;
    uint32_t poolCapacity;

    uint32_t extCount; // number of extensions found

} bdgl_exts_tbl;

uint32_t bdgl_get_ext_count() {
    return bdgl_exts_tbl.extCount;
}

void bdgl_get_context_version(uint8_t* major, uint8_t* minor) {
    *major = bdgl_ctx.major;
    *minor = bdgl_ctx.minor;
}

int bdgl_min_context(uint8_t major, uint8_t minor) {
    // handles failed cases:
    //  context: 1.x   version: 2.x
    //  context: 2.2   version: 2.1
    return
        (bdgl_ctx.major == major && bdgl_ctx.minor >= minor)
        || bdgl_ctx.major > major;
}

// TODO return NULL on success, or a pointer to the function name that failed to load
//      (that way we don't have to print an error message, etc)
const char* bdgl_load(void** funcs, const char* funcNames, bdgl_loadproc loadproc) {

    int fnBegin = 0; // index of where current fn begins
    int fnCount = 0; // number of functions found
    int fnIndex = 0; // index within entire fnames buffer

    char last = 0; // last char read

    // read all symbols
    while (1) {
        // read the next symbol
        char c = funcNames[fnIndex];
        if (c == 0) {
            if (last == 0) {
                // null terminator following the null terminator
                // of the previous string indicates end of list
                return 0;
            }
            // string terminator
            //   function name is: fnames[fnBegin:fnIndex)
            //
            // assert( fnBegin < fnIndex );

            const char* funcName = &funcNames[fnBegin];
            void* f = loadproc((char*)funcName); //glXGetProcAddress((char*)funcName);
            if (f == 0) {
                return funcName;
            }

            funcs[fnCount++] = f; // store/advance function pointer
            fnBegin = fnIndex+1;
        }
        last = c;
        fnIndex++;
    }

    return 0; // shouldn't even get here out of while loop
}

int bdgl_init(bdgl_loadproc loadproc) {
    if (bdgl_ctx.major > 0) {
        // we already parsed the version, return success
        return 0;
    }
    const uint8_t* (*glGetString)(unsigned int);
    *(void**)(&glGetString) = loadproc("glGetString");
    if (glGetString == 0) {
        return 1;
    }

    // use GL context
    const uint8_t* gl_version = glGetString(GL_VERSION); // since GL 2.0

    // parse GL_VERSION prefix
    // forms:
    //  major_number.minor_number
    //  major_number.minor_number.release_number
    // Vendor-specific information may follow the version number.
    // Its format depends on the implementation, but a space always separates the version number and the vendor-specific information.

    if (gl_version == 0) {
        return 1;
    }

    uint8_t major = gl_version[0];

    if (major >= '0' && major <= '9') {
        major -= '0';
    } else {
        return 2;
    }

    if (gl_version[1] != '.') {
        return 3;
    }

    uint8_t minor = gl_version[2];
    if (minor >= '0' && minor <= '9') {
        minor -= '0';
    } else {
        return 4;
    }

    const uint8_t suffix = gl_version[3];
    // suffix should either be:
    //  * 0 (null terminator) if the format is major_number.minor_number and there is no vendor suffix
    //  * ' ' if the format is major_number.minor_number and is followed by a vendor suffix
    //  * '.' if the format is major_number.minor_number.release_number

    // note: we want to verify the suffix to future-proof,
    //       in case (against all odds) we see a future version
    //       like '3.12' and don't parse it as '3.1'
    if (suffix == 0 || suffix == ' ' || suffix == '.') {
        // valid suffix
    } else {
        return 5;
    }

    bdgl_ctx.major = major;
    bdgl_ctx.minor = minor;
    return 0;
}

int bdgl_load_version(bdgl_Version* version, bdgl_loadproc loadproc) {
    if (version->loaded) {
        // we already loaded this version
        return 0;
    }

    if ( bdgl_init(loadproc) ) {
        // note: if context is already parsed, it will return early
        return 1;
    }

    if ( !bdgl_min_context(version->major, version->minor) ) {
        return 1;
    }

    // if ( bdgl_ctx.major < version->major
    //     || (bdgl_ctx.major == version->major && bdgl_ctx.minor < version->minor) ) {

    //     return 1;
    // }
    const char* failed = bdgl_load((void**)version->funcs, version->names, loadproc);
    if (failed != 0) {
        return 1;
    }
    version->loaded = 1; // mark this version as loaded
    return 0;
}

int bdgl_load_extension(bdgl_Extension* extension, bdgl_loadproc loadproc) {
    if (extension->loaded) {
        // we already loaded this extension
        return 0;
    }

    if ( bdgl_init(loadproc) ) {
        // note: if context is already parsed, it will return early
        return 1;
    }

    // FIXME lazily query/parse extension list

    const char* failed = bdgl_load((void**)extension->funcs, extension->names, loadproc);
    if (failed != 0) {
        return 1;
    }
    extension->loaded = 1; // mark this version as loaded
    return 0;
}


// https://nullprogram.com/blog/2018/07/31/
static uint32_t bdgl_hash32(uint32_t x)
{
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

static uint32_t bdgl_strhash(const char* s, int* outLen) {
    uint32_t hash = 31;
    int len = 0;
    while (1) {
        char c = s[len];
        if (c == 0) {
            // null terminator, write consumed len and return computed hash
            *outLen = len;
            return hash;
        }
        hash = bdgl_hash32(hash ^ c);
        len++;
    }
}

void bdgl_ext_init() {

    GLint extCount=0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &extCount);

    // find minimum power-of-2 table size
    uint32_t minTableSize = extCount + (extCount/2);
    uint32_t tableSize = 32;
    while (tableSize < minTableSize) {
        tableSize *= 2;
    };

    bdgl_exts_tbl.tbl = (uint32_t*)calloc( tableSize, sizeof(uint32_t) );
    bdgl_exts_tbl.tblSize = tableSize;
    bdgl_exts_tbl.extCount = extCount;

    // find minimum power-of-2 pool capacity
    //  * extension names average 25 characters
    //  * not null terminated in the pool, since we store lengths
    uint32_t minPoolCap = extCount * 25;
    uint32_t poolCap = 256;
    while (poolCap < minPoolCap) {
        poolCap *= 2;
    }
    bdgl_exts_tbl.pool = malloc(poolCap);
    bdgl_exts_tbl.poolCapacity = poolCap;
    bdgl_exts_tbl.poolSize = 0;

    uint32_t* tbl = bdgl_exts_tbl.tbl;
    uint32_t tblSize = bdgl_exts_tbl.tblSize;

    for (int i=0; i<extCount; i++) {
        const GLubyte* extName = glGetStringi(GL_EXTENSIONS, i);

        int extNameLen;
        uint32_t hash = bdgl_strhash((const char*)extName, &extNameLen);

        // ensure pool capacity
        while (extNameLen > (bdgl_exts_tbl.poolCapacity - bdgl_exts_tbl.poolSize)) {
            bdgl_exts_tbl.poolCapacity *= 2;
            bdgl_exts_tbl.pool = realloc(bdgl_exts_tbl.pool, bdgl_exts_tbl.poolCapacity);
        }

        // copy/append string to constant pool
        uint16_t offset = bdgl_exts_tbl.poolSize;
        memcpy(&bdgl_exts_tbl.pool[offset], extName, extNameLen);
        bdgl_exts_tbl.poolSize += extNameLen;

        // entry:  [offset:u16  | probeCount:u8 | len:u8]
        uint32_t newEntry = (((uint32_t)offset) << 16) | (uint32_t)extNameLen;
        int probeCount = 0;
        uint32_t idx = hash;
        while (1) {
            idx = idx % tblSize; // initial mod, plus wrap-around

            uint32_t slotEntry = tbl[idx];

            if (slotEntry == 0) {
                // found an empty slot, set it
                tbl[idx] = newEntry | (((uint32_t)probeCount) << 8);
                break;
            }

            // robin hood: entry with higher probe count gets to keep the slot
            uint8_t slotDisp = (slotEntry >> 8) & 0xff;
            if (probeCount > slotDisp) {
                // steal the slot, and evicted entry is now the probe entry
                // (mask out the existing probe count so we can just OR later)
                // and we're continuing the evicted entry probe count
                tbl[idx] = newEntry | (((uint32_t)probeCount) << 8);
                newEntry = slotEntry & 0xffff00ff;
                probeCount = slotDisp;
            }
            idx++;
            probeCount++;
        }
    }
}

int bdgl_have_ext(const char* extName) {

    int extNameLen;
    uint32_t hash = bdgl_strhash((const char*)extName, &extNameLen);

    uint32_t* tbl = bdgl_exts_tbl.tbl;
    uint32_t tblSize = bdgl_exts_tbl.tblSize;
    uint8_t* pool = bdgl_exts_tbl.pool;

    int probeCount = 0;

    uint32_t idx = hash;
    while (1) {
        idx = idx % tblSize; // initial mod, plus wrap-around

        uint32_t slotEntry = tbl[idx];

        if (slotEntry == 0) {
            // empty slot, not found
            return 0;
        }

        uint8_t slotDisp = (slotEntry >> 8) & 0xff;
        if (probeCount > slotDisp) {
            // we've probed further than the slot's displacement.
            // if the string we're looking for was in the table,
            // it would have stolen this slot
            //   -> since it hasn't we know the string doesn't exist
            return 0;
        }

        uint8_t slotLen = slotEntry & 0xff;
        if (slotLen == extNameLen) {
            // length matches, now compare the
            // string in the constant pool to the string we're looking up
            uint16_t slotOffset = (slotEntry >> 16) & 0xffff;

            if ( memcmp(&pool[slotOffset], extName, extNameLen) == 0 ) {
                // string contents match
                return 1;
            }
        }

        probeCount++;
        idx++;
    }
}

void bdgl_ext_free() {
    free( bdgl_exts_tbl.tbl );
    bdgl_exts_tbl.tbl = NULL;

    free( bdgl_exts_tbl.pool );
    bdgl_exts_tbl.pool = NULL;
}

#endif
