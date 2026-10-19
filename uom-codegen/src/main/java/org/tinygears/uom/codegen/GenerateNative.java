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

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.tinygears.uom.engine.parse.Definitions;

/**
 * Generates the sources of the native API (uom) from {@code data/}: the quantity types with the typed
 * products of {@code quantity-relations.txt}, and the catalogs: {@code [--check] <dataDir> <quantitiesOut>
 * <systemsOut>}.
 */
public final class GenerateNative {
    static final String RELATIONS = "quantity-relations.txt";
    private static final String USAGE = "usage: GenerateNative [--check] <dataDir> <quantitiesOut> <systemsOut>";

    /** File name to source text for each output directory, sorted by name. */
    public record Output(Map<String, String> quantities, Map<String, String> systems) {}

    private GenerateNative() {
    }

    /** Runs {@link #run} and throws if it fails, so that {@code exec:java} ends with a BUILD FAILURE. */
    public static void main(String[] args) {
        int status = run(args, System.err);
        if (status != 0) {
            throw new IllegalStateException("generation failed with status " + status);
        }
    }

    /** Returns 0 on success, 1 on a generation error or a {@code --check} difference, 2 on a usage error. */
    public static int run(String[] args, PrintStream err) {
        boolean check = args.length == 4 && args[0].equals("--check");
        if (!check && (args.length != 3 || args[0].startsWith("--"))) {
            err.println(USAGE);
            return 2;
        }
        int i = check ? 1 : 0;
        Path quantitiesOut = Path.of(args[i + 1]);
        Path systemsOut = Path.of(args[i + 2]);
        try {
            Output out = generate(Path.of(args[i]));
            Generate.refuseHandWritten(out.quantities(), quantitiesOut);
            Generate.refuseHandWritten(out.systems(), systemsOut);
            if (check) {
                List<String> problems = new ArrayList<>(Generate.differences(out.quantities(), quantitiesOut));
                problems.addAll(Generate.differences(out.systems(), systemsOut));
                problems.forEach(err::println);
                return problems.isEmpty() ? 0 : 1;
            }
            Generate.write(out.quantities(), quantitiesOut);
            Generate.write(out.systems(), systemsOut);
            return 0;
        } catch (GenerationException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println(e);
            return 1;
        } catch (UncheckedIOException e) {
            err.println(e.getCause());
            return 1;
        }
    }

    /** The native sources generated from {@code dataDir}, formatted as Spotless formats the hand-written ones. */
    public static Output generate(Path dataDir) {
        Map<String, Definitions> parsed = Generate.catalogs(dataDir);
        Definitions si = parsed.get(Generate.CATALOGS.get(0));
        List<QuantityTypeSpec> specs = Generate.types(dataDir, si);
        List<QuantityRelation> relations;
        try (Reader in = Generate.open(dataDir, RELATIONS)) {
            relations = RelationsParser.parse(RELATIONS, in, specs);
        } catch (IOException | UncheckedIOException e) {
            throw new GenerationException(RELATIONS, 0, "cannot read " + RELATIONS, e);
        }
        List<QuantityRelation.Method> methods = RelationsParser.methods(relations);
        Map<String, String> files = new TreeMap<>();
        for (QuantityTypeSpec s : specs) {
            files.put(s.name() + ".java", NativeEmitter.typedInterface(s, methods, si));
            files.put(s.name() + "Value.java", NativeEmitter.valueClass(s, methods));
        }
        files.put("QuantityTypes.java", NativeEmitter.registry(specs));
        Map<String, String> systems = new TreeMap<>();
        List<QuantityTypeSpec> unnamed = specs.stream().filter(QuantityTypeSpec::explicit).toList();
        CatalogBuilder.catalogs(parsed, specs).forEach(
                (file, c) -> systems.put(
                        c.className() + ".java",
                        NativeEmitter.catalog(
                                c,
                                parsed.get(file),
                                file.equals(Generate.CATALOGS.get(0)) ? unnamed : List.of())));
        return new Output(Collections.unmodifiableMap(files), Collections.unmodifiableMap(systems));
    }
}
