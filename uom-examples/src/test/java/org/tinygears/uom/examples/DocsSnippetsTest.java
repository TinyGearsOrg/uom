/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.examples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import org.tinygears.uom.SystemOfUnits;
import org.tinygears.uom.Unit;
import org.tinygears.uom.engine.parse.DefinitionException;
import org.tinygears.uom.jsr.systems.UomSystemOfUnits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the fenced blocks of the user documents. {@code units} blocks load through both APIs, to the same symbols (an
 * optional second info word names the file, for {@code @include} by other blocks of the same document);
 * {@code units-error} blocks fail on both with the message of their {@code # error: } line; {@code java} blocks
 * compile as a method body against the native API ({@code java jsr}: against the JSR-385 implementation) and, if they
 * have {@code // prints: } comments, run and print exactly those lines.
 */
class DocsSnippetsTest {
    private static final List<Path> DOCS = List.of(
            Path.of("../README.md"),
            Path.of("../docs/NATIVE.md"),
            Path.of("../docs/JSR.md"),
            Path.of("../docs/CUSTOM-UNITS.md"),
            Path.of("../SECURITY.md"));
    private static final String JAVA = "import java.io.*; import java.math.*; import java.nio.file.*;"
            + " import java.util.*;";
    private static final String NATIVE = JAVA + " import org.tinygears.uom.*; import org.tinygears.uom.quantity.*;"
            + " import org.tinygears.uom.systems.*; import org.tinygears.uom.engine.Factor;\n";
    private static final String JSR = JAVA + " import javax.measure.*; import org.tinygears.uom.jsr.quantity.*;"
            + " import org.tinygears.uom.jsr.systems.*;\n";
    private static final String ERROR = "# error: ";
    private static final String PRINTS = "// prints: ";

    record Block(String doc, int line, String tag, String name, String text) {
        @Override
        public String toString() {
            return doc + ":" + line + " " + tag;
        }
    }

    static List<Block> blocks(Path doc) throws IOException {
        List<String> lines = Files.readAllLines(doc);
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("```")) {
                continue;
            }
            String[] info = lines.get(i).substring(3).strip().split("\\s+");
            int fence = i + 1;
            StringBuilder text = new StringBuilder();
            for (i++; !lines.get(i).startsWith("```"); i++) {
                text.append(lines.get(i)).append('\n');
            }
            out.add(
                    new Block(
                            doc.getFileName().toString(),
                            fence,
                            info[0],
                            info.length > 1 ? info[1] : null,
                            text.toString()));
        }
        return out;
    }

    private static List<Block> all() throws IOException {
        List<Block> out = new ArrayList<>();
        for (Path doc : DOCS) {
            out.addAll(blocks(doc));
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> snippets() throws IOException {
        List<Block> blocks = all();
        // named blocks, by document and name: an @include resolves within its own document
        Map<String, String> files = new HashMap<>();
        blocks.stream().filter(b -> b.name() != null).forEach(b -> files.put(b.doc() + "/" + b.name(), b.text()));
        List<DynamicTest> tests = new ArrayList<>();
        for (Block b : blocks) {
            switch (b.tag()) {
                case "units" -> tests.add(DynamicTest.dynamicTest(b.toString(), () -> {
                    UomSystemOfUnits jsr = load(b, files);
                    SystemOfUnits uom = loadNative(b, files);
                    List<String> symbols = new ArrayList<>();
                    jsr.getUnits().forEach(u -> symbols.add(u.getSymbol()));
                    assertEquals(symbols, uom.units().stream().map(Unit::symbol).toList(), b.toString());
                }));
                case "units-error" -> tests.add(DynamicTest.dynamicTest(b.toString(), () -> {
                    String expected = b.text().lines().filter(l -> l.startsWith(ERROR)).findFirst()
                            .orElseThrow(() -> new AssertionError(b + ": no '" + ERROR + "' line"))
                            .substring(ERROR.length());
                    assertEquals(
                            expected,
                            assertThrows(DefinitionException.class, () -> load(b, files)).getMessage(),
                            b.toString());
                    assertEquals(
                            expected,
                            assertThrows(org.tinygears.uom.DefinitionException.class, () -> loadNative(b, files))
                                    .getMessage(),
                            b + " (uom)");
                }));
                case "java" -> tests.add(DynamicTest.dynamicTest(b.toString(), () -> java(b)));
                // not run; any other tag is a typo that would drop a block from this test
                case "", "xml", "text", "bash" -> {
                }
                default -> throw new AssertionError(b + ": unknown fence tag '" + b.tag() + "'");
            }
        }
        return tests.stream();
    }

    @Test
    void theDocumentsCoverTheSyntax() throws IOException {
        List<Block> blocks = all();
        assertTrue(blocks.stream().filter(b -> b.tag().startsWith("units")).count() >= 12, "units blocks");
        assertTrue(blocks.stream().filter(b -> b.tag().equals("java")).count() >= 8, "java blocks");
        String units = String
                .join("\n", blocks.stream().filter(b -> b.tag().equals("units")).map(Block::text).toList());
        for (String feature : List.of(
                "@system ",
                "@include ",
                "@extends ",
                "@member ",
                "@preferred ",
                "@prefixed ",
                " ~",
                " = x",
                " + ",
                "|",
                "pi")) {
            assertTrue(units.contains(feature), "no loading units block uses '" + feature + "'");
        }
        String errors = String
                .join("\n", blocks.stream().filter(b -> b.tag().equals("units-error")).map(Block::text).toList());
        assertTrue(errors.contains(" !"), "no units-error block shows a base unit");
    }

    private static UomSystemOfUnits load(Block b, Map<String, String> files) {
        return UomSystemOfUnits
                .load(b.name() != null ? b.name() : "example.units", new StringReader(b.text()), name -> {
                    String text = files.get(b.doc() + "/" + name);
                    return text == null ? null : new StringReader(text);
                });
    }

    private static SystemOfUnits loadNative(Block b, Map<String, String> files) {
        return SystemOfUnits.load(b.name() != null ? b.name() : "example.units", new StringReader(b.text()), name -> {
            String text = files.get(b.doc() + "/" + name);
            return text == null ? null : new StringReader(text);
        });
    }

    private static void java(Block b) throws Exception {
        if (b.name() != null && !b.name().equals("jsr")) {
            throw new AssertionError(b + ": unknown java info word '" + b.name() + "'");
        }
        StringBuilder imports = new StringBuilder("jsr".equals(b.name()) ? JSR : NATIVE);
        StringBuilder body = new StringBuilder();
        List<String> expected = new ArrayList<>();
        for (String line : b.text().lines().toList()) {
            (line.startsWith("import ") ? imports : body).append(line).append('\n');
            int at = line.indexOf(PRINTS);
            if (at >= 0) {
                expected.add(line.substring(at + PRINTS.length()));
            }
        }
        String name = "Snippet" + b.line();
        Class<?> c = compile(
                name,
                imports + "public class " + name + " {\n public static void run() throws Exception {\n" + body
                        + "}\n}\n",
                b);
        if (expected.isEmpty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = System.out;
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            c.getMethod("run").invoke(null);
        } finally {
            System.setOut(out);
        }
        assertEquals(expected, bytes.toString(StandardCharsets.UTF_8).lines().toList(), b.toString());
    }

    private static Class<?> compile(String name, String source, Block b) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        StringWriter log = new StringWriter();
        try (JavaFileManager files = new ForwardingJavaFileManager<>(javac.getStandardFileManager(null, null, null)) {
            @Override
            public JavaFileObject getJavaFileForOutput(
                    Location location,
                    String className,
                    JavaFileObject.Kind kind,
                    FileObject sibling
            ) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return classes.computeIfAbsent(className, k -> new ByteArrayOutputStream());
                    }
                };
            }
        }) {
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("string:///" + name + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            boolean ok = javac.getTask(
                    log,
                    files,
                    null,
                    List.of("-classpath", System.getProperty("java.class.path")),
                    null,
                    List.of(src)).call();
            assertTrue(ok, b + " does not compile:\n" + log + "\n" + source);
        }
        ClassLoader loader = new ClassLoader(DocsSnippetsTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String className) throws ClassNotFoundException {
                ByteArrayOutputStream bytes = classes.get(className);
                if (bytes == null) {
                    throw new ClassNotFoundException(className);
                }
                return defineClass(className, bytes.toByteArray(), 0, bytes.size());
            }
        };
        return loader.loadClass(name);
    }
}
