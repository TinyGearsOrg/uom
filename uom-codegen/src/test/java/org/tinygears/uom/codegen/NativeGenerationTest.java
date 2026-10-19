/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.codegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The native sources: committed ones match the generator, and the generator's command line. */
class NativeGenerationTest {
    private static final Path DATA = Path.of("../data");
    private static final Path ROOT = Path.of("../uom/src/main/java/org/tinygears/uom");
    private static final Path QUANTITIES = ROOT.resolve("quantity");
    private static final Path SYSTEMS = ROOT.resolve("systems");

    @TempDir
    Path tmp;

    @Test
    void committedSourcesMatchTheGenerator() throws IOException {
        GenerateNative.Output out = GenerateNative.generate(DATA);
        assertEquals(67, out.quantities().size());
        assertEquals(
                Set.of(
                        "SI.java",
                        "USCustomary.java",
                        "Imperial.java",
                        "CGS.java",
                        "ESU.java",
                        "EMU.java",
                        "Gaussian.java",
                        "Information.java"),
                out.systems().keySet());
        List<String> problems = new ArrayList<>(drift(out.quantities(), QUANTITIES));
        problems.addAll(drift(out.systems(), SYSTEMS));
        assertEquals(
                List.of(),
                problems,
                "regenerate with mvn -q -B -Pgenerate -pl uom-codegen -am process-classes && mvn -q -B spotless:apply");
    }

    @Test
    void typesCarryTheirRelations() {
        GenerateNative.Output out = GenerateNative.generate(DATA);
        String length = out.quantities().get("Length.java");
        assertTrue(length.startsWith(Headers.java("data/quantity-types.txt and data/quantity-relations.txt")));
        assertTrue(length.contains("\n    Speed divide(Time time);\n"), length);
        assertTrue(length.contains("\n    Unit<Length> SYSTEM_UNIT = Units.of(Length.class, BaseUnits.METRE);\n"));
        // the layout is Spotless's, so the code is compared without whitespace
        assertTrue(
                containsCode(
                        out.quantities().get("LengthValue.java"),
                        "public Speed divide(Time time) { return product(time, true, Speed.SYSTEM_UNIT, SpeedValue::new); }"));
        assertFalse(out.quantities().get("Dimensionless.java").contains("multiply("));
        // the native sources never name the JSR-385 side: its factory system units map to the native types
        out.quantities().forEach((name, text) -> assertFalse(text.contains("org.tinygears.uom.jsr"), name));
        out.systems().forEach((name, text) -> assertFalse(text.contains("org.tinygears.uom.jsr"), name));
        assertTrue(
                containsCode(
                        out.systems().get("SI.java"),
                        "public static final Unit<Speed> METRE_PER_SECOND = Speed.SYSTEM_UNIT;"));
        String energy = out.quantities().get("Energy.java");
        assertTrue(containsCode(energy, "Units.of(Energy.class, BaseUnits.KILOGRAM.multiply("), energy);
    }

    @Test
    void checkModeReportsDriftAndWriteRepairsIt() throws IOException {
        Path q = tmp.resolve("q");
        Path s = tmp.resolve("s");
        assertEquals(0, run(DATA.toString(), q.toString(), s.toString()).status());
        assertEquals(0, run("--check", DATA.toString(), q.toString(), s.toString()).status());
        Files.writeString(q.resolve("Length.java"), Headers.java("x") + "changed\n");
        Files.delete(s.resolve("SI.java"));
        Run r = run("--check", DATA.toString(), q.toString(), s.toString());
        assertEquals(1, r.status());
        assertEquals("differs: " + q.resolve("Length.java") + "\nmissing: " + s.resolve("SI.java") + "\n", r.err());
        assertEquals(0, run(DATA.toString(), q.toString(), s.toString()).status());
        assertEquals(0, run("--check", DATA.toString(), q.toString(), s.toString()).status());
    }

    @Test
    void usageAndDataErrors() throws IOException {
        Run r = run("data", "q");
        assertEquals(2, r.status());
        assertEquals("usage: GenerateNative [--check] <dataDir> <quantitiesOut> <systemsOut>\n", r.err());
        assertEquals(2, run("--force", "data", "q", "s").status());
        Path data = Files.createDirectories(tmp.resolve("data"));
        try (Stream<Path> files = Files.list(DATA)) {
            for (Path p : files.toList()) {
                Files.copy(p, data.resolve(p.getFileName()));
            }
        }
        Files.writeString(data.resolve("quantity-relations.txt"), "Speed = Length * Time\n");
        assertEquals(
                "quantity-relations.txt:1: dimension of Length * Time is L·T, not L·T^-1 of Speed",
                assertThrows(GenerationException.class, () -> GenerateNative.generate(data)).getMessage());
        Files.delete(data.resolve("quantity-relations.txt"));
        r = run(data.toString(), tmp.resolve("q").toString(), tmp.resolve("s").toString());
        assertEquals(1, r.status());
        assertEquals("quantity-relations.txt:0: cannot read quantity-relations.txt\n", r.err());
    }

    @Test
    void mainThrowsOnFailure() {
        assertEquals(
                "usage: GenerateNative [--check] <dataDir> <quantitiesOut> <systemsOut>" + System.lineSeparator(),
                FailingMain.usageError(() -> GenerateNative.main(new String[0])));
    }

    private record Run(int status, String err) {}

    private static Run run(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int status = GenerateNative.run(args, new PrintStream(buf, true, StandardCharsets.UTF_8));
        return new Run(status, buf.toString(StandardCharsets.UTF_8));
    }

    /** Whether the generated source holds the code, whatever its layout. */
    private static boolean containsCode(String source, String code) {
        return source.replaceAll("\\s+", "").contains(code.replaceAll("\\s+", ""));
    }

    private static List<String> drift(Map<String, String> expected, Path dir) throws IOException {
        Map<String, String> actual = new TreeMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (text.startsWith(Headers.LICENSE + Headers.GENERATED)) {
                    actual.put(p.getFileName().toString(), text);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        expected.forEach((name, text) -> {
            if (!actual.containsKey(name) || !Generate.sameCode(text, actual.get(name))) {
                problems.add((actual.containsKey(name) ? "differs: " : "missing: ") + name);
            }
        });
        actual.keySet().stream().filter(n -> !expected.containsKey(n)).forEach(n -> problems.add("extra: " + n));
        return problems;
    }
}
