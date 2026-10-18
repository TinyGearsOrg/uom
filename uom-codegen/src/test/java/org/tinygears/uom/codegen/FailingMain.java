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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs a generator's {@code main} that must fail, with {@code System.err} captured so it stays out of the build log. */
final class FailingMain {
    private FailingMain() {
    }

    /**
     * Asserts that {@code main} throws the generator's failure for a usage error.
     *
     * @param main the call of {@code main}
     * @return what {@code main} wrote to {@code System.err}
     */
    static String usageError(Executable main) {
        PrintStream saved = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            assertEquals(
                    "generation failed with status 2",
                    assertThrows(IllegalStateException.class, main).getMessage());
        } finally {
            System.setErr(saved);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
