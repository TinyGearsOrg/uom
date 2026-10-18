/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.engine.internal;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.engine.Dimension;
import org.tinygears.uom.engine.Unit;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.engine.UnitException;
import org.tinygears.uom.engine.parse.Definition;
import org.tinygears.uom.engine.parse.DefinitionException;
import org.tinygears.uom.engine.parse.DefinitionParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The registry: built-in reservations, self-alternates and all-or-nothing loads. */
class AlternateUnitsTest {
    private static final Path DATA = Path.of("../data");
    private static final UnitAtom M = UnitAtom.METRE;
    private static final UnitAtom S = UnitAtom.SECOND;

    @Test
    void builtInAlternatesAreTheDataFilesAndReserved() throws IOException {
        Map<String, Map<UnitAtom, Integer>> data = new HashMap<>();
        try (Stream<Path> files = Files.list(DATA)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".units")).sorted().toList()) {
                try (var in = Files.newBufferedReader(f)) {
                    for (Definition def : DefinitionParser
                            .parse(f.getFileName().toString(), in, n -> Files.newBufferedReader(DATA.resolve(n)))
                            .definitions()) {
                        if (def.alternateOf() != null) {
                            data.put(def.symbol(), Atoms.normalize(def.alternateOf().atoms()));
                        }
                    }
                }
            }
        }
        assertEquals(AlternateUnits.BUILT_IN, data.keySet());
        data.forEach((symbol, definition) -> {
            UnitAtom atom = AlternateUnits.register(symbol, definition);
            assertEquals(definition, AlternateUnits.definition(atom), symbol);
        });
        UnitException e = assertThrows(UnitException.class, () -> AlternateUnits.register("Bq", Map.of(M, 1)));
        assertEquals("symbol Bq is already associated to a different unit", e.getMessage());
    }

    @Test
    void anAtomsAlternateUnderItsOwnSymbolIsTheAtom() {
        UnitAtom rad = AlternateUnits.register("rad", Map.of());
        assertSame(rad, AlternateUnits.register("rad", Map.of(rad, 1)));
        assertEquals(Unit.ONE.alternate("rad"), Unit.ONE.alternate("rad").alternate("rad"));
        assertSame(M, AlternateUnits.register("m", Map.of(M, 1)));
        // under another symbol it is a new atom, defined by the first
        UnitAtom radian = AlternateUnits.register("t23-radian", Map.of(rad, 1));
        assertEquals(new UnitAtom("t23-radian", Dimension.NONE), radian);
        assertEquals(Map.of(rad, 1), AlternateUnits.definition(radian));
        assertThrows(UnitException.class, () -> AlternateUnits.register("rad", Map.of(radian, 1)));
    }

    @Test
    void aFailedLoadRemovesOnlyItsOwnRegistrations() {
        AlternateUnits.register("t23-before", Map.of(M, 1));
        int size = AlternateUnits.size();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AlternateUnits.atomically(() -> {
            AlternateUnits.register("t23-before", Map.of(M, 1));
            AlternateUnits.register("t23-failed", Map.of(M, 1));
            // a nested load joins the outer one
            AlternateUnits.atomically(() -> AlternateUnits.register("t23-nested", Map.of(S, 1)));
            assertEquals(size + 2, AlternateUnits.size());
            throw new IllegalStateException("broken");
        }));
        assertEquals("broken", e.getMessage());
        assertEquals(size, AlternateUnits.size());
        assertEquals(new UnitAtom("t23-failed", Dimension.TIME), AlternateUnits.register("t23-failed", Map.of(S, 1)));
        assertEquals(new UnitAtom("t23-nested", Dimension.LENGTH), AlternateUnits.register("t23-nested", Map.of(M, 1)));
        assertEquals(Map.of(M, 1), AlternateUnits.definition(AlternateUnits.register("t23-before", Map.of(M, 1))));
    }

    @Test
    void aSuccessfulLoadKeepsItsRegistrationsAndCheckedExceptionsPass() {
        UnitAtom kept = AlternateUnits.atomically(() -> AlternateUnits.register("t23-kept", Map.of(M, 1)));
        assertEquals(Map.of(M, 1), AlternateUnits.definition(kept));
        IOException e = assertThrows(IOException.class, () -> AlternateUnits.atomically(() -> {
            AlternateUnits.register("t23-io", Map.of(M, 1));
            throw new IOException("unreadable");
        }));
        assertEquals("unreadable", e.getMessage());
        assertEquals(new UnitAtom("t23-io", Dimension.TIME), AlternateUnits.register("t23-io", Map.of(S, 1)));
    }

    @Test
    void aFailedParseCanBeCorrected() {
        String broken = "t23px ~\nt23bad t23undefined\n";
        DefinitionException e = assertThrows(
                DefinitionException.class,
                () -> DefinitionParser.parse("broken.units", new StringReader(broken), null));
        assertEquals("broken.units:2: unknown unit 't23undefined'", e.getMessage());
        Unit metre = Unit.base(M, "metre");
        Unit px = DefinitionParser
                .parse("fixed.units", new StringReader("t23px ~ m\n"), null, n -> n.equals("m") ? metre : null)
                .definitions().get(0).unit();
        assertEquals(Map.of(new UnitAtom("t23px", Dimension.LENGTH), 1), px.atoms());
    }
}
