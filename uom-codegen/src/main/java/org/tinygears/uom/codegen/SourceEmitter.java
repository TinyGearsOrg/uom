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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.tinygears.uom.engine.parse.Definitions;

/**
 * Writes the generated sources of one quantity type and the type registry; {@code $T} is the type name. Templates
 * spell classes qualified and {@link Imports#resolve} shortens them.
 */
public final class SourceEmitter {
    private static final String PACKAGE = "org.tinygears.uom.jsr.quantity";
    private static final String HEADER = Headers.java("data/quantity-types.txt") + """

            package org.tinygears.uom.jsr.quantity;

            """;

    private static final String INTERFACE = """
            /**
             * A {@link javax.measure.quantity.$T} of this implementation: the JSR-385 quantity with this interface as the
             * return type of its operations, and the additions of {@link org.tinygears.uom.jsr.quantity.UomQuantity}.
             */
            public interface $T extends javax.measure.quantity.$T,
                    org.tinygears.uom.jsr.quantity.UomQuantity<javax.measure.quantity.$T, $T> {

                /**
                 * An absolute double quantity.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @return the quantity
                 */
                static $T of(double value, javax.measure.Unit<javax.measure.quantity.$T> unit) {
                    return $TFactory.INSTANCE.of(value, unit);
                }

                /**
                 * A double quantity.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @return the quantity
                 */
                static $T of(double value, javax.measure.Unit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale) {
                    return $TFactory.INSTANCE.of(value, unit, scale);
                }

                /**
                 * An absolute decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @return the quantity
                 */
                static $T of(java.math.BigDecimal value, javax.measure.Unit<javax.measure.quantity.$T> unit) {
                    return $TFactory.INSTANCE.of(value, unit);
                }

                /**
                 * A decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @return the quantity
                 */
                static $T of(java.math.BigDecimal value, javax.measure.Unit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale) {
                    return $TFactory.INSTANCE.of(value, unit, scale);
                }

                /**
                 * A decimal quantity with context {@code mc}; the value is kept as given, not rounded. A precision over
                 * 50000 or a value of more than 50000 digits throws ArithmeticException.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @param mc the math context
                 * @return the quantity
                 */
                static $T of(java.math.BigDecimal value, javax.measure.Unit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale, java.math.MathContext mc) {
                    return $TFactory.INSTANCE.of(value, unit, scale, mc);
                }

                /**
                 * {@code quantity} as a {@code $T} of this implementation, such as the product of two quantities: the
                 * same value, unit, scale and precision; a quantity that already is one is returned as it is. It narrows
                 * as {@code asType} does, but returns this typed interface, which {@code asType} cannot take.
                 *
                 * @param quantity the quantity
                 * @return the quantity as a {@code $T}
                 * @throws NullPointerException if {@code quantity} is null
                 * @throws ClassCastException if its dimension is not the one of {@code $T}, or it is not a quantity of
                 *     this implementation
                 */
                static $T from(javax.measure.Quantity<?> quantity) {
                    return QuantityTypes.narrow($T.class, javax.measure.quantity.$T.class, quantity);
                }
            }
            """;

    private static final String IMPLEMENTATION = """
            final class $TDouble
                    extends org.tinygears.uom.jsr.quantity.AbstractDoubleQuantity<javax.measure.quantity.$T, $T>
                    implements $T {

                @java.io.Serial
                private static final long serialVersionUID = 1L;

                $TDouble(double value, org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale) {
                    super(value, unit, scale);
                }

                @Override
                protected $T create(double value, org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale) {
                    return new $TDouble(value, unit, scale);
                }

                @Override
                protected $T createDecimal(java.math.BigDecimal value,
                        org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> unit, javax.measure.Quantity.Scale scale, java.math.MathContext mc) {
                    return new $TDecimal(value, unit, scale, mc);
                }

                @Override
                protected Class<javax.measure.quantity.$T> quantityType() {
                    return javax.measure.quantity.$T.class;
                }
            }
            """;

    private static final String DECIMAL = """
            final class $TDecimal
                    extends org.tinygears.uom.jsr.quantity.AbstractDecimalQuantity<javax.measure.quantity.$T, $T>
                    implements $T {

                @java.io.Serial
                private static final long serialVersionUID = 1L;

                $TDecimal(java.math.BigDecimal value, org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale, java.math.MathContext mc) {
                    super(value, unit, scale, mc);
                }

                @Override
                protected $T create(java.math.BigDecimal value,
                        org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> unit, javax.measure.Quantity.Scale scale, java.math.MathContext mc) {
                    return new $TDecimal(value, unit, scale, mc);
                }

                @Override
                protected $T createDouble(double value,
                        org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> unit, javax.measure.Quantity.Scale scale) {
                    return new $TDouble(value, unit, scale);
                }

                @Override
                protected Class<javax.measure.quantity.$T> quantityType() {
                    return javax.measure.quantity.$T.class;
                }
            }
            """;

    private static final String FACTORY = """
            /** Creates {@link $T} quantities. */
            public final class $TFactory
                    implements javax.measure.spi.QuantityFactory<javax.measure.quantity.$T> {

                /** The system unit of {@link $T}. */
                public static final org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> SYSTEM_UNIT =
                        org.tinygears.uom.jsr.UomUnit.unchecked($U);

                /** The only instance. */
                public static final $TFactory INSTANCE = new $TFactory();

                private $TFactory() {
                }

                /**
                 * An absolute double quantity.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @return the quantity
                 */
                public $T of(double value, javax.measure.Unit<javax.measure.quantity.$T> unit) {
                    return of(value, unit, javax.measure.Quantity.Scale.ABSOLUTE);
                }

                /**
                 * A double quantity; a unit of another implementation throws UnconvertibleException.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @return the quantity
                 */
                public $T of(double value, javax.measure.Unit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale) {
                    if (java.util.Objects.requireNonNull(unit, "unit") instanceof org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> u) {
                        return new $TDouble(value, u, scale);
                    }
                    throw new javax.measure.UnconvertibleException("foreign unit implementation: " + unit);
                }

                /**
                 * An absolute decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @return the quantity
                 */
                public $T of(java.math.BigDecimal value, javax.measure.Unit<javax.measure.quantity.$T> unit) {
                    return of(value, unit, javax.measure.Quantity.Scale.ABSOLUTE);
                }

                /**
                 * A decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @return the quantity
                 */
                public $T of(java.math.BigDecimal value, javax.measure.Unit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale) {
                    return of(value, unit, scale, java.math.MathContext.DECIMAL128);
                }

                /**
                 * A decimal quantity with context {@code mc}; the value is kept as given, not rounded. A precision over
                 * 50000 or a value of more than 50000 digits throws ArithmeticException; a unit of another
                 * implementation UnconvertibleException.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @param mc the math context
                 * @return the quantity
                 */
                public $T of(java.math.BigDecimal value, javax.measure.Unit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale, java.math.MathContext mc) {
                    java.util.Objects.requireNonNull(value, "value");
                    if (java.util.Objects.requireNonNull(unit, "unit") instanceof org.tinygears.uom.jsr.UomUnit<javax.measure.quantity.$T> u) {
                        return new $TDecimal(value, u, scale, mc);
                    }
                    throw new javax.measure.UnconvertibleException("foreign unit implementation: " + unit);
                }

                @Override
                public $T create(Number value, javax.measure.Unit<javax.measure.quantity.$T> unit) {
                    return create(value, unit, javax.measure.Quantity.Scale.ABSOLUTE);
                }

                /**
                 * {@code BigDecimal} and {@code BigInteger} values give a decimal quantity with context {@code DECIMAL128},
                 * not rounded; all others a double one.
                 */
                @Override
                public $T create(Number value, javax.measure.Unit<javax.measure.quantity.$T> unit,
                        javax.measure.Quantity.Scale scale) {
                    java.util.Objects.requireNonNull(value, "value");
                    if (value instanceof java.math.BigDecimal d) {
                        return of(d, unit, scale);
                    }
                    if (value instanceof java.math.BigInteger i) {
                        return of(new java.math.BigDecimal(i), unit, scale);
                    }
                    return of(value.doubleValue(), unit, scale);
                }

                @Override
                public javax.measure.Unit<javax.measure.quantity.$T> getSystemUnit() {
                    return SYSTEM_UNIT;
                }
            }
            """;

    private static final String REGISTRY = """
            /** The quantity factories of the generated types; each factory initialises only when its type is requested. */
            public final class QuantityTypes {
                private static final java.util.Set<Class<?>> TYPES = java.util.Set.of(
            $S);

                private QuantityTypes() {
                }

                /**
                 * The factory of {@code type}; a type without one throws IllegalArgumentException.
                 *
                 * @param <Q> the quantity type
                 * @param type the quantity type
                 * @return the factory
                 */
                @SuppressWarnings("unchecked")
                public static <Q extends javax.measure.Quantity<Q>> javax.measure.spi.QuantityFactory<Q> factory(Class<Q> type) {
                    java.util.Objects.requireNonNull(type, "type");
            $F        throw new IllegalArgumentException("no quantity factory for " + type.getName());
                }

                /** {@code quantity} narrowed by {@code asType} to {@code standard}, as the typed interface {@code ours} of it. */
                static <S extends javax.measure.Quantity<S>, T extends javax.measure.Quantity<S>> T narrow(Class<T> ours,
                        Class<S> standard, javax.measure.Quantity<?> quantity) {
                    java.util.Objects.requireNonNull(quantity, "quantity");
                    javax.measure.Quantity<S> narrowed = quantity.asType(standard);
                    if (!ours.isInstance(narrowed)) {
                        throw new ClassCastException((narrowed == null ? "null" : narrowed.getClass().getName())
                                + " is not a quantity of this implementation");
                    }
                    return ours.cast(narrowed);
                }

                /**
                 * The quantity types with a factory.
                 *
                 * @return the types
                 */
                public static java.util.Set<Class<?>> types() {
                    return TYPES;
                }
            }
            """;

    private SourceEmitter() {
    }

    public static String typedInterface(QuantityTypeSpec spec) {
        return emit(INTERFACE, spec);
    }

    public static String doubleImplementation(QuantityTypeSpec spec) {
        return emit(IMPLEMENTATION, spec);
    }

    public static String decimalImplementation(QuantityTypeSpec spec) {
        return emit(DECIMAL, spec);
    }

    public static String factory(QuantityTypeSpec spec, Definitions definitions) {
        Imports imports = imports(List.of(spec));
        String body = imports.resolve(FACTORY.replace("$T", spec.name()));
        String unit;
        try {
            unit = UnitExpressionEmitter.emit(spec.systemUnit(), definitions, imports);
        } catch (GenerationException e) {
            throw new GenerationException(spec.source(), spec.line(), e.getMessage(), e);
        }
        if (spec.systemSymbol() != null) {
            unit = UnitExpressionEmitter.named(unit, spec.systemSymbol(), spec.systemName(), imports);
        }
        return HEADER + imports.block() + body.replace("$U", unit);
    }

    public static String registry(List<QuantityTypeSpec> specs) {
        String types = specs.stream().map(s -> "            javax.measure.quantity." + s.name() + ".class")
                .collect(Collectors.joining(",\n"));
        String branches = specs.stream()
                .map(
                        s -> "        if (type == javax.measure.quantity." + s.name() + ".class) {\n"
                                + "            return (javax.measure.spi.QuantityFactory<Q>) " + s.name()
                                + "Factory.INSTANCE;\n" + "        }\n")
                .collect(Collectors.joining());
        Imports imports = imports(specs);
        String body = imports.resolve(REGISTRY.replace("$S", types).replace("$F", branches));
        return HEADER + imports.block() + body;
    }

    private static String emit(String template, QuantityTypeSpec spec) {
        Imports imports = imports(List.of(spec));
        String body = imports.resolve(template.replace("$T", spec.name()));
        return HEADER + imports.block() + body;
    }

    /** The names of the generated types of {@code specs} are taken: each clashes with its standard type. */
    private static Imports imports(List<QuantityTypeSpec> specs) {
        List<String> taken = new ArrayList<>(List.of("QuantityTypes"));
        for (QuantityTypeSpec s : specs) {
            taken.addAll(List.of(s.name(), s.name() + "Double", s.name() + "Decimal", s.name() + "Factory"));
        }
        return new Imports(PACKAGE, taken);
    }
}
