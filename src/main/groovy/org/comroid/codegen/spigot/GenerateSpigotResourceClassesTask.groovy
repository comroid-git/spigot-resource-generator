package org.comroid.codegen.spigot

import lombok.Getter
import lombok.RequiredArgsConstructor
import org.comroid.api.Polyfill
import org.comroid.api.attr.Described
import org.comroid.api.attr.Named
import org.comroid.api.func.ext.Wrap
import org.comroid.api.java.gen.JavaSourcecodeWriter
import org.comroid.api.model.minecraft.model.DefaultPermissionValue
import org.comroid.api.model.minecraft.model.PluginLoadTime
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.impldep.org.intellij.lang.annotations.Language
import org.gradle.internal.impldep.org.intellij.lang.annotations.MagicConstant
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.yaml.snakeyaml.Yaml

import javax.lang.model.element.ElementKind
import java.lang.reflect.Modifier
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Collectors

import static java.lang.reflect.Modifier.*

abstract class GenerateSpigotResourceClassesTask extends DefaultTask {
    private final Set<String> terminalNodes = new HashSet<>()
    private final Set<String> writtenConstants = new HashSet<>()

    @TaskAction
    void generate() {
        def resourceDirectory = "$project.parent.projectDir/src/spigot/main/resources"
        logger.info("Generating Spigot Resources into source root " + resourceDirectory)

        try (var pluginYml = new FileInputStream(new File(resourceDirectory, "plugin.yml"))) {
            Map<String, Object> yml = new Yaml().load(pluginYml)
            var main = (String) yml.get("main")
            var lio = main.lastIndexOf('.')
            var pkg = main.substring(0, lio).replace('spigot', 'generated')

            var pkgDir = new File("${project.layout.buildDirectory.get().asFile.absolutePath}/generated/sources/r/${pkg.replace('.', '/')}")
            if (!pkgDir.exists() && !pkgDir.mkdirs())
                throw new RuntimeException("Unable to create package directory")


            def pluginYmlJava = new File(pkgDir, "PluginYml.java")
            try (
                    var sourcecode = new FileWriter(pluginYmlJava)
                    var java = new JavaSourcecodeWriter(sourcecode)
            ) {
                java.writePackage(pkg)
                        .writeImport(Named.class, Described.class, PluginLoadTime.class, DefaultPermissionValue.class, Getter.class, RequiredArgsConstructor.class)
                        .writeAnnotation(SuppressWarnings.class, Map.of("value", "\"unused\""))
                        .beginClass().modifiers(PUBLIC).kind(ElementKind.INTERFACE).name("PluginYml").and()
                //.beginMethod().modifiers(PRIVATE).name("ctor").and().end();

                generateBaseFields(java, yml)
                generateCommandsEnum(java, Polyfill.uncheckedCast(yml.getOrDefault("commands", Map.of())))
                generatePermissions(java, Polyfill.uncheckedCast(yml.getOrDefault("permissions", new HashMap<>())))
            } catch (Throwable t) {
                if (System.getenv('DEBUG') == 'true')
                    pluginYmlJava.delete()
                logger.error("Could not generate PluginYml.java", t);
            }
        } catch (FileNotFoundException | SecurityException e) {
            throw new RuntimeException("Unable to read plugin.yml", e)
        } catch (IOException e) {
            throw new RuntimeException("Unable to generate plugin.yml resource class", e)
        }
    }

    private static void generateBaseFields(JavaSourcecodeWriter java, Map<String, Object> yml) throws IOException {
        generateField(java, 0, String.class, "mainClassName", fromString(yml, "main"), toStringExpr())
        generateField(java, 0, String.class, "loggingPrefix", fromString(yml, "prefix"), toStringExpr())
        generateField(java, 0, String.class, "apiVersion", fromString(yml, "api-version"), toStringExpr())
        generateField(java, 0, PluginLoadTime.class, "load", fromString(yml, "load"), toEnumConstExpr(PluginLoadTime.class.simpleName))
        generateField(java, 0, String.class, "name", fromString(yml, "name"), toStringExpr())
        generateField(java, 0, String.class, "version", fromString(yml, "version"), toStringExpr())
        generateField(java, 0, String.class, "description", fromString(yml, "description"), toStringExpr())
        generateField(java, 0, String.class, "website", fromString(yml, "website"), toStringExpr())
        generateField(java, 0, String.class, "author", fromString(yml, "author"), toStringExpr())
        generateField(java, 0, String[].class, "authors", fromStringList(yml, "authors"), toStringArrayExpr())
        generateField(java, 0, String[].class, "depend", fromStringList(yml, "depend"), toStringArrayExpr())
        generateField(java, 0, String[].class, "softdepend", fromStringList(yml, "softdepend"), toStringArrayExpr())
        generateField(java, 0, String[].class, "loadbefore", fromStringList(yml, "loadbefore"), toStringArrayExpr())
        generateField(java, 0, String[].class, "libraries", fromStringList(yml, "libraries"), toStringArrayExpr())
    }

    private static void generateCommandsEnum(JavaSourcecodeWriter java, Map<String, Object> commands) throws IOException {
        java
                .beginAnnotation().type(Getter.class).and()
                .beginAnnotation().type(RequiredArgsConstructor.class).and()
                .beginClass().kind(ElementKind.ENUM).name("Command")
                .implementsType(Named.class).implementsType(Described.class).and()

        var iter = Polyfill.<Map<String, Map<String, Object>>> uncheckedCast(commands).entrySet().iterator()
        while (iter.hasNext()) {
            var each = iter.next()
            java.beginEnumConstant().name(each.getKey().toUpperCase())
                    .argument(toStringExpr().apply(fromString(each.getValue(), "description").get()))
                    .argument(toStringArrayExpr().apply(fromStringList(each.getValue(), "aliases").get()))
                    .argument(toStringExpr().apply(fromString(each.getValue(), "permission").get()))
                    .argument(toStringExpr().apply(fromString(each.getValue(), "permission-message").get()))
                    .argument(toStringExpr().apply(fromString(each.getValue(), "usage").get()))
                    .and()
            if (iter.hasNext())
                java.comma().lf()
        }
        java.writeLineTerminator().lf()

        generateField(java, PRIVATE | FINAL, String.class, "description")
        generateField(java, PRIVATE | FINAL, String[].class, "aliases")
        generateField(java, PRIVATE | FINAL, String.class, "requiredPermission")
        generateField(java, PRIVATE | FINAL, String.class, "permissionMessage")
        generateField(java, PRIVATE | FINAL, String.class, "usage")

        java.writeGetter(PUBLIC, String.class, "getName()", "toString")

        java.end()
    }

    private void cleanupKeys(Map<String, Map<String, Object>> data) { cleanupKeys(data, "", 0) }

    private void cleanupKeys(Map<String, Map<String, Object>> permissions, String compoundKey, int depth) {
        for (var permissionKey : permissions.keySet().toArray(String[]::new)) {
            // only when path blob count differs from depth
            var count = permissionKey.chars().filter(x -> x == '.').count()
            if (count == depth) continue
            if (count < depth) throw new IllegalArgumentException("Inner permissionKey does not have enough path blobs: " + permissionKey)

            // wrap own permissions
            var children = new HashMap<String, Map<String, Object>>()
            var split = permissionKey.split("\\.")
            var wrap = permissions.getOrDefault(permissionKey, Map.of())
            if (split.length > 1) {
                for (var c = split.length; c > 1; c--) {
                    var intermediateKey = compoundKey
                    for (var i = 0; i < c; i++)
                        intermediateKey += '.' + split[i]
                    if (intermediateKey.startsWith("."))
                        intermediateKey = intermediateKey.substring(1)
                    children.put(intermediateKey, wrap)
                    wrap = new HashMap<String, Map<String, Object>>() {
                        {
                            put("children", children)
                        }
                    }
                }

                // replace old stuff
                mergeKeyIntoObj(permissions, split[0], wrap)
                permissions.remove(permissionKey)
            }

            // recurse into children
            var ck = (compoundKey.isBlank() ? "" : compoundKey + '.') + permissionKey
            cleanupKeys(children, ck, (int) ck.chars().filter(x -> x == '.').count())
        }
    }

    private static void mergeKeyIntoObj(Map<String, Map<String, Object>> original, String key, Map<String, Object> additionalValue) {
        if (!original.containsKey(key))
            original.put(key, additionalValue)
        else original.get(key).putAll(additionalValue)
    }

    private void generatePermissions(JavaSourcecodeWriter java, Map<String, Map<String, Object>> permissions) throws IOException {
        java.beginClass().kind(ElementKind.INTERFACE).name("Permission")
                .implementsType(Named.class).implementsType(Described.class).and()

        terminalNodes.clear()
        cleanupKeys(permissions)
        generatePermissionsNodes(java, "", Polyfill.uncheckedCast(permissions))

        generateField(java, 0, "Permission[]", "TERMINAL_NODES", Wrap.of(terminalNodes),
                ls -> ls.isEmpty() ? "new Permission[0]" : ls.stream().collect(Collectors.joining(",", "new Permission[]{", "}")))
        java.end()
    }

    private void generatePermissionsNodes(JavaSourcecodeWriter java, String parentKey, Map<String, Object> nodes) throws IOException {
        for (var key : nodes.keySet()) {
            var name = !parentKey.isBlank() && key.startsWith(parentKey) ? key.substring(parentKey.length() + 1) : key
            generatePermissionsNode(java, parentKey, name, Polyfill.uncheckedCast(nodes.get(key)))
        }
    }

    private void generatePermissionsNode(JavaSourcecodeWriter java, String parentKey, String key, Map<String, Object> node) throws IOException {
        var name = !parentKey.isBlank() && key.startsWith(parentKey) ? key.substring(parentKey.length() + 1) : key
        var permissionKey = (parentKey.isBlank() ? "" : parentKey + '.') + key
        if (writtenConstants.add(key))
            generateField(java, PUBLIC | STATIC | FINAL, String.class, name.toUpperCase(), () -> permissionKey, toStringExpr())
        java
                .beginAnnotation().type(Getter.class).and()
                .beginAnnotation().type(RequiredArgsConstructor.class).and()
                .beginClass().modifiers(PUBLIC).kind(ElementKind.ENUM).name(name).implementsType("Permission").and()
        var children = Polyfill.<Map<String, Object>> uncheckedCast(node.getOrDefault("children", Map.of()))
        var constants = new HashMap<String, String>()

        generatePermissionEnumConstant('$self', java, permissionKey, node)
        java.comma().lf()
        generatePermissionEnumConstant('$wildcard', java, permissionKey + ".*", (Map<String, Object>) children.getOrDefault(permissionKey + ".*", Map.of(
                "description", "Auto-generated Wildcard Permission; inherits all child permissions",
                "default", "false"
        )));

        var deepChildren = new HashMap<String, Object>()
        if (!children.isEmpty())
            for (var entry : children.entrySet()) {
                var midKey = parentKey.isBlank() ? key : parentKey + '.' + key
                var eKey = entry.getKey()
                var eName = eKey.startsWith(midKey) ? eKey.substring(midKey.length() + 1) : eKey
                if (eName == '*')
                    continue;
                var subChildren = Polyfill.<Map<String, Object>> uncheckedCast(entry.getValue()).getOrDefault("children", Map.of())
                if (Polyfill.<Map<String, Object>> uncheckedCast(subChildren).isEmpty()) {
                    // no further children; write enum constant
                    java.comma().lf()
                    terminalNodes.add(eKey)
                    constants.put(eName, entry.getKey())
                    generatePermissionEnumConstant(eName, java, eKey, Polyfill.uncheckedCast(entry.getValue()))
                } else deepChildren.put(eKey, entry.getValue())
            }
        java.writeLineTerminator().lf()

        for (final def e in constants.entrySet())
            if (writtenConstants.add(e.getValue()))
                generateField(java, PUBLIC | STATIC | FINAL, String.class, e.getKey().toUpperCase(), e::getValue, toStringExpr())

        generateField(java, PRIVATE | FINAL, String.class, "name")
        generateField(java, PRIVATE | FINAL, String.class, "description")
        generateField(java, PRIVATE | FINAL, DefaultPermissionValue.class, "Default")

        java.writeGetter(PUBLIC, String.class, "name", "toString")

        generatePermissionsNodes(java, permissionKey, deepChildren)
        java.end()
    }

    private static void generatePermissionEnumConstant(
            @NotNull @Language(value = "Java", prefix = "enum x { ", suffix = "; }") String name,
            JavaSourcecodeWriter java,
            String key,
            Map<String, Object> node
    ) throws IOException {
        def defaultStr = fromString(node, "default").get()
        java.writeEnumConstant(name, List.of(
                toStringExpr().apply(key),
                toStringExpr().apply(fromString(node, "description").get()),
                defaultStr == null ? 'null' : toEnumConstExpr(DefaultPermissionValue.class.simpleName).apply(defaultStr.replace(' ', '_').toUpperCase())
        ))
    }

    private static void generateField(
            JavaSourcecodeWriter java,
            @SuppressWarnings("SameParameterValue") @MagicConstant(flagsFromClass = Modifier.class) Integer modifiers,
            Class<?> type,
            @Language(value = "Java", prefix = "var ", suffix = " = null") String name
    ) throws IOException { generateField(java, modifiers, type, name, null, null) }

    private static <T> void generateField(
            JavaSourcecodeWriter java,
            @MagicConstant(flagsFromClass = Modifier.class) Integer modifiers,
            Object type,
            @Language(value = "Java", prefix = "var ", suffix = " = null") String name,
            @Nullable Supplier<@Nullable T> source,
            @Nullable Function<T, String> toExpr
    ) throws IOException {
        java.writeFieldHeader(modifiers, type, name)
        if (source != null && toExpr != null) {
            var value = source.get()
            java.writeDeclaration().writeExpression(value == null ? "null" : toExpr.apply(value))
        }
        java.end()
    }

    private static Supplier<@NotNull Boolean> fromBoolean(Map<String, Object> data, String key) {
        return () -> (boolean) data.getOrDefault(key, false)
    }

    private static Supplier<String> fromString(Map<String, Object> data, String key) {
        return () -> (String) data.getOrDefault(key, null)
    }

    private static Supplier<List<String>> fromStringList(Map<String, Object> data, String key) {
        return () -> {
            var value = data.get(key)
            if (value == null)
                return List.of()
            if (value instanceof List)
                return value
            return List.of((String) value)
        }
    }

    private static <T> Function<T, String> toPlainExpr() { return String::valueOf }

    private static Function<String, String> toStringExpr() { return (args -> args == null || "null" == args ? "null" : "\"%s\"".formatted(args)) }

    private static Function<List<String>, String> toStringArrayExpr() {
        return (ls -> ls.isEmpty() ? "new String[0]" : ("new String[]{%s}".formatted(ls.stream()
                .collect(Collectors.joining("\",\"", "\"", "\"")))))
    }

    @SuppressWarnings("SameParameterValue")
    private static Function<String, String> toEnumConstExpr(String enumTypeName) {
        return (v -> "%s.%s".formatted(enumTypeName, v))
    }
}
