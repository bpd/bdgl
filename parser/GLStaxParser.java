import java.io.File;
import java.io.InputStream;
import java.nio.file.StandardOpenOption;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

public class GLStaxParser {

    // note: overall design:
    //       all parseX methods assume XMLStreamReader state is at the START_ELEMENT
    //       of the element the method parses
    //       when the method returns, the reader will be positioned at the END_ELEMENT
    //       of the target element

    Map<String, String> parseEnums(XMLStreamReader reader) throws Exception {

        // forms:
        // <enums namespace="GL" start="0x96F0" end="0x96FF" vendor="ARM" comment="Contact Jan-Harald Fredriksen">

        Map<String, String> enums = new HashMap<>();

        while (reader.hasNext()) {
            reader.next();
            if (reader.isStartElement()) {
                if ( reader.getLocalName().equals("enum") ) {
                    // <enum value="0x96F0" name="GL_SHADER_CORE_COUNT_ARM" group="GetPName"/>
                    String name = reader.getAttributeValue(null, "name");
                    String value = reader.getAttributeValue(null, "value");
                    String group = reader.getAttributeValue(null, "group");

                    enums.put(name, value);
                } else {
                    // <unused start="0x96F7" end="0x96FF" vendor="ARM"/>
                }

            } else if (reader.isEndElement() && reader.getLocalName().equals("enums")) {
                return enums;
            }
        }
        throw new IllegalStateException("Missing end of 'enums' tag");
    }

    static class Proto {
        Type ret;
        String name;

        @Override
        public String toString() {
            return ret.toString() + ' ' + name;
        }
    }

    class Type {
        boolean cst; // const
        String name;
        boolean pointer;
        boolean pointerToPointer;
    }

    Proto parseProto(XMLStreamReader reader) throws Exception {

        var proto = new Proto();

        // advance to first parse event within proto tag
        reader.next();

        proto.ret = parseType(reader);

        if ( !reader.getLocalName().equals("name") ) {
            throw new IllegalArgumentException("expected start 'name'");
        }

        proto.name = reader.getElementText();

        return proto;
    }

    static class Param {
        Type type;
        String len;  // length of this param is defined by this other named param

        String name;

        String group;
        String kind;
    }

    Type parseType(XMLStreamReader reader) throws Exception {

        // forms:
        // void <name>glAccum</name>
        // <ptype>GLenum</ptype> <name>op</name>
        // const <ptype>GLuint</ptype> *<name>programs</name>
        // const void *<name>pointer</name>
        //
        //
        // ^^ biggest issue, 'void' isn't wrapped in <ptype>

        var t = new Type();

        StringBuilder typeBuilder = new StringBuilder();

        if (reader.isCharacters()) {
            // 'void'
            // 'const '
            typeBuilder.append( reader.getText() );
            reader.next();
        }

        if (reader.isStartElement() && reader.getLocalName().equals("ptype")) {

            // assume we're in:
            // <param><ptype>GLintptr</ptype> <name>readOffset</name></param>
            typeBuilder.append( reader.getElementText() );

            assert reader.isEndElement() && reader.getLocalName().equals("ptype");
            reader.next();

            // consume following characters (trailing '*'|'**')
            while (reader.isCharacters()) {
                typeBuilder.append( reader.getText() );
                reader.next();
            }

        } else {
            // assume we're in:
            // <proto>void <name>glBinormal3fvEXT</name></proto>
            // <param len="COMPSIZE(type,stride)">const void *<name>pointer</name></param>
            //
            // meaning, the prefix could contain the entire type without a <ptype>
        }

        String type = typeBuilder.toString();

        if (type.startsWith("const ")) {
            t.cst = true;
            type = type.substring("const ".length());
        }

        if (type.endsWith("*")) {
            t.pointer = true;
            type = type.substring(0, type.length()-1);
        }
        if (type.endsWith("*")) {
            t.pointerToPointer = true;
            type = type.substring(0, type.length()-1);
        }

        t.name = type.trim();

        return t;
    }

    Param parseParam(XMLStreamReader reader) throws Exception {
        // form:
        // <param><ptype>GLuint</ptype> *<name>dataSize</name></param>
        // <param group="AccumOp"><ptype>GLenum</ptype> <name>op</name></param>
        // <param kind="Coord"><ptype>GLfloat</ptype> <name>value</name></param>
        //
        Param param = new Param();
        // TODO group, kind attrs

        // advance to first parse event within param
        reader.next();

        param.type = parseType(reader);

        // after type prefix we should be at 'name'
        if (!reader.getLocalName().equals("name")) {
            throw new IllegalStateException("expected 'name' found " + reader.getLocalName());
        }

        param.name = reader.getElementText();

        while (reader.hasNext()) {
            reader.next();
            if (reader.isEndElement() && reader.getLocalName().equals("param")) {
                return param;
            }
        }
        throw new IllegalStateException("Missing end 'param' tag");
    }

    static class Command {
        Proto proto;
        List<Param> params = new ArrayList<>();
    }

    Command parseCommand(XMLStreamReader reader) throws Exception {
        // <command>
        //    <proto>void <name>glAccum</name></proto>
        //    <param group="AccumOp"><ptype>GLenum</ptype> <name>op</name></param>
        //    <param kind="Coord"><ptype>GLfloat</ptype> <name>value</name></param>
        //    <glx type="render" opcode="137"/>
        // </command>
        var command = new Command();

        while (reader.hasNext()) {

            reader.next();

            if (reader.isStartElement()) {

                switch(reader.getLocalName()) {
                    case "proto" -> {
                        command.proto = parseProto(reader);
                    }
                    case "param" -> {
                        command.params.add( parseParam(reader) );
                    }
                    case "glx" -> {

                    }
                }
            } else if (reader.isEndElement() && reader.getLocalName().equals("command")) {
                return command;
            }
        }
        throw new IllegalStateException("Missing end 'command' tag");
    }

    Map<String, Command> parseCommands(XMLStreamReader reader) throws Exception {
        // form:
        // <commands namespace="GL">
        Map<String, Command> commands = new HashMap<>();

        while (reader.hasNext()) {
            reader.next();

            if (reader.isStartElement() && reader.getLocalName().equals("command")) {

                Command command = parseCommand(reader);

                commands.put(command.proto.name, command);

            } else if (reader.isEndElement() && reader.getLocalName().equals("commands")) {
                return commands;
            }
        }
        throw new IllegalStateException("Missing end 'commands' tag");
    }

    static class Feature {
        String api;
        String name;
        String number;

        // parsed from 'number'
        int numberMajor;
        int numberMinor;

        List<ApiSlice> requires = new ArrayList<>();
        List<ApiSlice> removes = new ArrayList<>();

        Feature previous; // previous feature in API specification, based on major/minor version (set during link phase)
    }

    ApiSlice parseRequire(XMLStreamReader reader) throws Exception {
        var require = new ApiSlice();

        require.profile = reader.getAttributeValue(null, "profile");

        if (require.profile != null) {
            System.out.println("profile: " + require.profile);
        }

        while (reader.hasNext()) {

            reader.next();

            if (reader.isStartElement()) {
                switch(reader.getLocalName()) {
                    case "enum" -> {
                        require.enums.add( reader.getAttributeValue(null, "name") );
                    }
                    case "type" -> {
                        require.types.add( reader.getAttributeValue(null, "name") );
                    }
                    case "command" -> {
                        require.commands.add( reader.getAttributeValue(null, "name") );
                    }
                }
            } else if (reader.isEndElement() && reader.getLocalName().equals("require")) {
                return require;
            }
        }
        throw new IllegalStateException("Missing close 'require' tag");
    }

    ApiSlice parseRemove(XMLStreamReader reader) throws Exception {
        var remove = new ApiSlice();

        remove.profile = reader.getAttributeValue(null, "profile");
        if (!remove.profile.equals("core")) {
            System.out.println("NON-CORE profile: " + remove.profile);
        }

        while (reader.hasNext()) {

            reader.next();

            if ( reader.isStartElement() ) {
                switch(reader.getLocalName()) {
                    case "enum" -> {
                        remove.enums.add( reader.getAttributeValue(null, "name") );
                    }
                    case "type" -> {
                        remove.types.add( reader.getAttributeValue(null, "name") );
                    }
                    case "command" -> {
                        remove.commands.add( reader.getAttributeValue(null, "name") );
                    }
                }
            } else if ( reader.isEndElement() && reader.getLocalName().equals("remove") ) {
                return remove;
            }
        }
        throw new IllegalStateException("Missing close 'remove' tag");
    }

    Feature parseFeature(XMLStreamReader reader) throws Exception {
        // example:
        //   <feature api="gl" name="GL_VERSION_1_0" number="1.0">
        //
        //
        //
        // (the assumption being the 'number' is major.minor, and is sortable, and subsequent versions
        //  by default include all elements in previous versions)
        //
        // other feature example (for 'gles2'):
        //   <feature api="gles2" name="GL_ES_VERSION_2_0" number="2.0">
        //
        // note: extensions depend on features by name:
        //   <extension name="GL_QCOM_tiled_rendering" supported="gles1|gles2">

        var feature = new Feature();

        feature.api = reader.getAttributeValue(null, "api");
        feature.name = reader.getAttributeValue(null, "name");
        feature.number = reader.getAttributeValue(null, "number");

        if (feature.number.length() != 3
            || !Character.isDigit(feature.number.charAt(0))
            || feature.number.charAt(1) != '.'
            || !Character.isDigit(feature.number.charAt(2)) ) {
            throw new IllegalStateException("invalid number format: " + feature.number);
        }

        feature.numberMajor = Character.digit(feature.number.charAt(0), 10);
        feature.numberMinor = Character.digit(feature.number.charAt(2), 10);

        while (reader.hasNext()) {

            reader.next();

            if ( reader.isStartElement() ) {

                switch(reader.getLocalName()) {
                    case "require" -> {
                        var require = parseRequire(reader);
                        feature.requires.add(require);
                    }
                    case "remove" -> {
                        var remove = parseRemove(reader);
                        feature.removes.add(remove);
                    }
                    default -> {
                        System.out.println("unknown feature child: " + reader.getLocalName());
                    }
                }

            } else if (reader.isEndElement() && reader.getLocalName().equals("feature")) {
                return feature;
            }
        }
        throw new IllegalArgumentException("Missing close 'feature' tag");
    }

    static class Extension {
        String name;
        String supported; // api1|api2|api3

        List<ApiSlice> requires = new ArrayList<>();
    }

    Extension parseExtension(XMLStreamReader reader) throws Exception {

        var extension = new Extension();

        extension.name = reader.getAttributeValue(null, "name");
        extension.supported = reader.getAttributeValue(null, "supported");

        while (reader.hasNext()) {
            reader.next();

            if ( reader.isStartElement() && reader.getLocalName().equals("require") ) {
                var require = parseRequire(reader);
                extension.requires.add(require);
                reader.next(); // read </require>

            } else if (reader.isEndElement() && reader.getLocalName().equals("extension")) {
                reader.next(); // read </extension>
                return extension;
            }
        }
        throw new IllegalStateException("Missing end 'extension' tag");
    }

    Map<String, Extension> parseExtensions(XMLStreamReader reader) throws Exception {

        // start event reader state: current event: START_ELEMENT 'extensions'
        Map<String, Extension> extensions = new HashMap<>();

        while (reader.hasNext()) {
            reader.next();
            if (reader.isStartElement() && reader.getLocalName().equals("extension")) {
                Extension extension = parseExtension(reader);
                extensions.put(extension.name, extension);
                //reader.next(); // consume </extension>

            } else if (reader.isEndElement() && reader.getLocalName().equals("extensions")) {
                return extensions;
            }
        }
        throw new IllegalStateException("missing end 'extensions' tag");
    }

    static class Registry {
        // gltype -> ctype
        Map<String, String> types = new HashMap<>();

        // enumName => hex value
        Map<String, String> enums = new HashMap<>();

        Map<String, Feature> features = new HashMap<>();

        Map<String, Extension> extensions = new HashMap<>();

        Map<String, Command> commands = new HashMap<>();

        public Registry() {
            types.put("GLboolean", "uint8_t");
            types.put("GLchar", "char");
            types.put("GLbyte", "int8_t");
            types.put("GLubyte", "uint8_t");

            types.put("GLshort", "int16_t");
            types.put("GLushort", "uint16_t");

            types.put("GLenum", "unsigned int");
            types.put("GLuint", "unsigned int");
            types.put("GLint", "int");
            types.put("GLintptr", "int*");
            types.put("GLbitfield", "int");
            types.put("GLsizei", "int");
            types.put("GLsizeiptr", "intptr_t");

            types.put("GLuint64", "uint64_t");
            types.put("GLint64", "int64_t");

            types.put("GLfloat", "float");
            types.put("GLdouble", "double");

            types.put("GLsync", "struct __GLsync*");
        }

        public boolean hasType(String typeName) {
            return typeName.equals("void") || types.containsKey(typeName);
        }
    }

    Registry parseRegistry(XMLStreamReader reader) throws Exception {

        var registry = new Registry();

        // consume all registry tags
        while (reader.hasNext()) {

            reader.next();
            if (reader.isStartElement()) {
                switch(reader.getLocalName()) {
                    case "types" -> {

                    }
                    case "enums" -> {
                        registry.enums.putAll( parseEnums(reader) );
                    }
                    case "commands" -> {
                        registry.commands.putAll( parseCommands(reader) );
                    }
                    case "extensions" -> {
                        registry.extensions.putAll( parseExtensions(reader) );
                    }
                    case "feature" -> {
                        Feature feature = parseFeature(reader);
                        registry.features.put(feature.name, feature);
                    }
                    default -> {
                        //System.out.println("unknown registry tag: " + start.getName().getLocalPart());
                    }
                }
            } else if (reader.isEndElement()) {
                if (reader.getLocalName().equals("registry")) {
                    return registry;
                }
            }
        }
        throw new IllegalStateException("Missing 'registry' end tag");
    }

    public Registry parse(File file) throws Exception {

        XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(is);
            try {
                while (reader.hasNext()) {

                    reader.next();
                    if (reader.isStartElement()) {
                        if ( reader.getLocalName().equals("registry") ) {
                            return parseRegistry(reader);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        }
        throw new IllegalStateException("no 'registry' tag found");
    }

    static class Api {
        final String name;
        public Api(String name) {
            this.name = name;
        }

        List<Feature> features = new ArrayList<>();

        // all extensions that have a 'supported' attribute that match this Api
        List<Extension> extensions = new ArrayList<>();
    }

    static class ApiVersion {
        Registry registry; // registry ApiVersion is associated with
        ApiVersion previous; // preceding version, or null if this is the first version of an api
        Feature feature;
        ApiSlice profile = new ApiSlice();
    }

    static class ApiSlice {

        String profile;  // null (for no profile) or the named profile

        public ApiSlice() {
            // default profile
        }

        public ApiSlice(String profile) {
            this.profile = profile;
        }

        Set<String> types = new HashSet<>();
        Set<String> enums = new HashSet<>();
        Set<String> commands = new HashSet<>();

        public void addAll(ApiSlice other) {
            // don't allow overwriting already-defined types
            for (String typeName : other.types) {
                if (!this.types.contains(typeName)) {
                    this.types.add(typeName);
                }
                //this.types.addAll(other.types);
            }
            this.enums.addAll(other.enums);
            this.commands.addAll(other.commands);
        }

        public void removeAll(ApiSlice other) {
            this.types.removeAll(other.types);
            this.enums.removeAll(other.enums);
            this.commands.removeAll(other.commands);
        }
    }


    static ApiVersion applyVersion(Feature feature, ApiSlice removes, String profile) {
        // 'removes' comes from parent version
        //  -- essentially, "these are forbidden by future versions, so don't add them"
        ApiVersion version = new ApiVersion();
        version.feature = feature;

        ApiSlice slice = version.profile;
        slice.profile = "core";

        // add feature elements, filtered through 'removes'
        // (note: this will temporarily add elements that have been removed from parent versions)
        for (var require : feature.requires) {
            if (require.profile == null || require.profile.equals(profile)) {
                slice.addAll(require);
            }
        }

        // remove any entries prohibited by parent versions
        slice.removeAll(removes);

        // accumulate 'removes' for children
        for (var remove : feature.removes) {
            if (remove.profile == null || remove.profile.equals(profile)) {
                removes.addAll(remove);
            }
        }

        // walk backwards, apply removals to previous versions
        // also builds a linked list of previous pruned versions
        if (feature.previous != null) {
            version.previous = applyVersion(feature.previous, removes, profile);

            // remove any symbols defined in this version that were already
            // defined in previous versions (they all pull from the same definition pool)
            // so we don't have duplicate symbols in any generated output
            ApiVersion previous = version.previous;
            while (previous != null) {

                slice.removeAll(previous.profile);

                previous = previous.previous;
            }
        }
        return version;
    }

    static Map<String, Api> link(Registry registry) {

        Map<String, Api> apis = new HashMap<>();

        registry.features.forEach((name, feature) -> {
            Api api = apis.computeIfAbsent(feature.api, n -> new Api(feature.api));
            api.features.add(feature);
        });

        // sort features within each api, add extensions
        apis.forEach((name,api) -> {
            api.features.sort( (a,b) -> {
                int aVersion = (a.numberMajor << 8) | a.numberMinor;
                int bVersion = (b.numberMajor << 8) | b.numberMinor;
                return aVersion - bVersion;
            } );

            // build linked list of previous versions
            Feature previous = null;
            for (int i=0; i<api.features.size(); i++) {
                Feature feature = api.features.get(i);
                feature.previous = previous;

                previous = feature;
            }

            // find all extensions that support this api
            registry.extensions.forEach((extName, extension) -> {

                // is the extension supported by this API?
                if ( extension.supported.equals(api.name)
                    || extension.supported.endsWith("|"+api.name)
                    || extension.supported.contains(api.name+"|") ) {

                    api.extensions.add(extension);
                }
            });

        });

        return apis;
    }

    static class ApiExtension {
        String name;
        ApiSlice requires;
    }

    static List<ApiExtension> linkExtensions(Api api, String profile, Set<String> extensionFilter) {
        List<ApiExtension> apiExts = new ArrayList<>();

        api.extensions.forEach(extension -> {

            if (extensionFilter != null && !extensionFilter.contains(extension.name)) {
                // extension filter present and this isn't extension isn't in it
                return;
            }

            var apiExt = new ApiExtension();
            apiExt.name = extension.name;
            apiExt.requires = new ApiSlice();

            for (var require : extension.requires) {
                if (require.profile == null || require.profile.equals(profile)) {
                    apiExt.requires.addAll(require);
                }
            }

            apiExts.add(apiExt);
        });

        return apiExts;
    }

    static ApiVersion linkApi(Api api, Feature feature, String profile) {
        ApiSlice emptyRemoves = new ApiSlice();
        ApiVersion apiVersion = applyVersion(feature, emptyRemoves, profile);

        return apiVersion;
    }

    static ApiVersion linkApi(Api api, String version, String profile) {

        for (Feature feature : api.features) {

            if (feature.number.equals(version)) {
                return linkApi(api, feature, profile);
            }
        }
        return null;
    }

    // link all versions of an API with the given profile
    static List<ApiVersion> linkApi(Api api, String profile) {

        List<ApiVersion> apiVersions = new ArrayList<>();

        for (Feature feature : api.features) {
            ApiVersion apiVersion = linkApi(api, feature, profile);
            apiVersions.add(apiVersion);
        }
        return apiVersions;
    }

    public static void generateType(Type type, StringBuilder buffer) {
        if (type.cst) {
            buffer.append("const ");
        }
        buffer.append(type.name);
        if (type.pointer) {
            buffer.append('*');
        }
        if (type.pointerToPointer) {
            buffer.append('*');
        }
    }

    static void generateCommand(Command command, String fpName, int commandIndex, StringBuilder buffer) {
        boolean isVoid = command.proto.ret.name.equals("void")
            && !command.proto.ret.pointer;

        if (isVoid) {
            buffer.append("bdgl_defv(").append(command.proto.name);
        } else {
            buffer.append("bdgl_def(").append(command.proto.name).append(',');
            generateType(command.proto.ret, buffer);
        }
        buffer.append(',');

        // sig
        buffer.append('(');
        for (int i=0; i<command.params.size(); i++) {
            if (i > 0) {
                buffer.append(',');
            }
            Param param = command.params.get(i);
            generateType(param.type, buffer);
            buffer.append(' ').append(param.name);
        }
        buffer.append(')');
        buffer.append(',');
        buffer.append(fpName);
        buffer.append(',').append(commandIndex).append(',');

        // call
        buffer.append('(');
        for (int i=0; i<command.params.size(); i++) {
            if (i > 0) {
                buffer.append(',');
            }
            Param param = command.params.get(i);
            buffer.append(param.name);
        }
        buffer.append(')');

        buffer.append(')');
        buffer.append('\n');
    }

    public static void generateCHeader(Registry registry, ApiVersion version, StringBuilder buffer) {

        if (version == null) {
            throw new IllegalArgumentException("version null");
        }

        if (version.previous != null) {
            // generate previous versions before we emit this one
            generateCHeader(registry, version.previous, buffer);
        } else {
            // oldest version, so we're at the top of the output
            registry.types.forEach((glTypeName, typeName) -> {

                if (glTypeName != null) {
                    buffer.append("typedef ").append(typeName).append(" ").append(glTypeName).append(";\n");
                } else {
                    // TODO log unknown type
                }
            });
        }

        buffer.append("\n//").append(version.feature.name).append('\n');

        // enums
        for (String enumName : version.profile.enums) {
            String enumValue = registry.enums.get(enumName);
            buffer.append("#define ").append(enumName).append(" ").append(enumValue).append('\n');
        }

        // need a deterministic/indexable command list
        List<String> commandNames = new ArrayList<>();
        version.profile.commands.forEach(cmdName -> commandNames.add(cmdName));
        commandNames.sort(String.CASE_INSENSITIVE_ORDER);

        // declare per-version function pointer table
        buffer.append("\n#ifdef BDGL_IMPL\n");
        buffer.append("void* (*bdgl_fp_").append(version.feature.name).append("[").append(version.profile.commands.size()).append("])();\n");

        buffer.append("bdgl_Version bdgl_").append(version.feature.name).append(" = {\n");
        buffer.append("  .major = ").append(version.feature.numberMajor).append(",\n");
        buffer.append("  .minor = ").append(version.feature.numberMinor).append(",\n");
        buffer.append("  .loaded = 0,\n");
        buffer.append("  .names = ");
        for (String commandName : commandNames) {
            // e.g. "glGetString\0"
            buffer.append('\n').append('"').append(commandName).append("\\0\"");
        }
        buffer.append(",\n .funcs = (void**)bdgl_fp_").append(version.feature.name).append(",\n");
        buffer.append("};\n");

        buffer.append("#else\n");
        buffer.append("extern bdgl_Version bdgl_").append(version.feature.name).append(";\n");
        buffer.append("#endif\n");

        // example:
        // #define bdgl_glClear(f) void f(GLbitfield mask)
        for (int commandIndex=0; commandIndex<commandNames.size(); commandIndex++) {
            String commandName = commandNames.get(commandIndex);
            Command command = registry.commands.get(commandName);

            generateCommand(command, version.feature.name, commandIndex, buffer);
        }
    }

    public static void generateCHeaderImpl(Registry registry, ApiVersion version, List<ApiExtension> extensions, StringBuilder buffer) throws Exception {

        // prefix
        buffer.append( Files.readString(new File("src/bdgl_prefix.h").toPath()) );

        generateCHeader(registry, version, buffer);

        // TODO better type parsing -- a lot of extensions reference custom types
        //      parse <name> child tag from <type> parent
        //      then just conver the whole element to text to get the typedef
        //

        for (var apiExt : extensions) {

            buffer.append("\n//").append(apiExt.name).append('\n');

            for (String enumName : apiExt.requires.enums) {
                String enumValue = registry.enums.get(enumName);
                buffer.append("#define ").append(enumName).append(" ").append(enumValue).append('\n');
            }

            // need a deterministic/indexable command list
            List<String> commandNames = new ArrayList<>();
            apiExt.requires.commands.forEach(cmdName -> commandNames.add(cmdName));
            commandNames.sort(String.CASE_INSENSITIVE_ORDER);

            buffer.append("\n#ifdef BDGL_IMPL\n");

            // declare per-extension function pointer table
            int commandCount = apiExt.requires.commands.size();
            if (commandCount > 0) {
                // only write the FP table if we have commands
                // (if not, we'll use a null pointer below)
                buffer.append("void* (*bdgl_fp_").append(apiExt.name).append("[").append(commandCount).append("])();\n");
            }

            buffer.append("bdgl_Extension bdgl_").append(apiExt.name).append(" = {\n");
            buffer.append("  .loaded = 0,\n");
            buffer.append("  .names = ");
            if (commandCount > 0) {
                for (String commandName : commandNames) {
                    // e.g. "glGetString\0"
                    buffer.append('\n').append('"').append(commandName).append("\\0\"");
                }
                buffer.append(",\n  .funcs = (void**)bdgl_fp_").append(apiExt.name).append(",\n");
            } else {
                // if we don't have any commands, we need an empty 'names' string
                // and a null pointer to the function pointers array
                buffer.append("\"\"");
                buffer.append(",\n  .funcs = 0,\n");
            }
            buffer.append("};\n");

            buffer.append("#else\n");
            buffer.append("extern bdgl_Extension bdgl_").append(apiExt.name).append(";\n");
            buffer.append("#endif\n");

            for (int commandIndex=0; commandIndex<commandNames.size(); commandIndex++) {
                String commandName = commandNames.get(commandIndex);

                Command command = registry.commands.get(commandName);
                if (command == null) {
                    throw new IllegalStateException("Extension '"+apiExt.name+"' reference non-existent command: " + commandName);
                }

                generateCommand(command, apiExt.name, commandIndex, buffer);
            }
        }

        buffer.append( Files.readString(new File("src/bdgl_suffix.h").toPath()) );

        buffer.append("#ifdef BDGL_IMPL\n");
        buffer.append("int bdgl_load_all(bdgl_loadproc loadproc) {\n");
        buffer.append("  return 0 \n");

        ApiVersion versionRef = version;
        while (versionRef != null) {

            buffer.append("    + bdgl_load_version(&bdgl_").append(versionRef.feature.name)
                .append(",loadproc)\n");

            versionRef = versionRef.previous;
        }

        buffer.append(";\n}\n");
        buffer.append("#endif\n");
    }

    public static void dump(Registry registry, Map<Api, List<ApiVersion>> apiVersionMap) {

        apiVersionMap.forEach((api, apiVersions) -> {

            StringBuilder buffer = new StringBuilder();
            System.out.println("//== " + api.name);

            for (var version : apiVersions) {
                generateCHeader(registry, version, buffer);
            }
            System.out.println(buffer.toString());
        });

    }

    public static Set<String> extractProfiles(Api api) {
        Set<String> profiles = new HashSet<>();
        for (Feature feature : api.features) {
            for (ApiSlice require : feature.requires) {
                if (require.profile != null) {
                    profiles.add(require.profile);
                }
            }
            for (ApiSlice remove : feature.removes) {
                if (remove.profile != null) {
                    profiles.add(remove.profile);
                }
            }
        }
        return profiles;
    }



    public static void main(String[] args) throws Exception {

        var parser = new GLStaxParser();
        Registry registry = parser.parse(new File("gl.xml"));

        Map<String, Api> apis = GLStaxParser.link(registry);

        System.out.println("Discovered APIs:");
        apis.forEach((apiName, api) -> {
            System.out.println(" " + apiName);
            System.out.println(" profiles: " + extractProfiles(api));
        });

        Api gl = apis.get("gl");

        ApiVersion gl33 = linkApi(gl, "3.3", "core");

        // extensions
        Set<String> extensionFilter = new HashSet<>();
        // extensionFilter.add("GL_ARB_draw_instanced");
        // extensionFilter.add("GL_ARB_draw_indirect");

        // note: some extensions are just enums (no commands), and the presence of the
        //       extension just indicates that another command accepts different enum args
        // so:
        // * there will be a bdgl_Extension
        // * but its name list will be empty
        List<ApiExtension> extensions = linkExtensions(gl, "core", extensionFilter);

        System.out.println("compatible extensions: ");
        for (var apiExt : extensions) {
            System.out.println(apiExt.name);
            System.out.println(" enums: " + apiExt.requires.enums);
            System.out.println(" commands: " + apiExt.requires.commands);
        }

        StringBuilder buffer = new StringBuilder();
        generateCHeaderImpl(registry, gl33, extensions, buffer);
        Files.writeString(new File("generated/gl33core.h").toPath(), buffer, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // List<ApiVersion> allVersionsCoreProfile = linkApi(gl, "core");
        // for (ApiVersion apiVersion : allVersionsCoreProfile) {
        //     dumpApiVersion(registry, apiVersion);
        // }
    }
}
