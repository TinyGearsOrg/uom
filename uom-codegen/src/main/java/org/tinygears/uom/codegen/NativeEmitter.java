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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.tinygears.uom.engine.Converter;
import org.tinygears.uom.engine.Dimension;
import org.tinygears.uom.engine.Unit;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.engine.parse.Definition;
import org.tinygears.uom.engine.parse.Definitions;

/**
 * Writes the sources of the native API (uom): per quantity type an interface with its factories, system unit
 * and typed products, and its class; the type registry; and the catalogs with typed unit constants over engine units.
 * Templates spell classes qualified and {@link Imports#resolve} shortens them.
 */
public final class NativeEmitter {
    static final String BASE = "org.tinygears.uom";
    static final String QUANTITY = BASE + ".quantity";
    static final String SYSTEMS = BASE + ".systems";
    private static final String BASE_UNITS = BASE + ".internal.BaseUnits";
    private static final String UNITS = BASE + ".internal.Units";
    private static final Pattern FACTORY_UNIT = Pattern
            .compile("org\\.tinygears\\.uom\\.jsr\\.quantity\\.(\\w+)Factory\\.SYSTEM_UNIT");
    private static final Map<String, String> BASE_ATOMS = Map.of(
            "m",
            "METRE",
            "kg",
            "KILOGRAM",
            "s",
            "SECOND",
            "A",
            "AMPERE",
            "K",
            "KELVIN",
            "mol",
            "MOLE",
            "cd",
            "CANDELA");
    private static final Map<String, Unit> BASE_CONSTANTS = Map.of(
            "METRE",
            Unit.base(UnitAtom.METRE, "metre"),
            "KILOGRAM",
            Unit.base(UnitAtom.KILOGRAM, "kilogram"),
            "SECOND",
            Unit.base(UnitAtom.SECOND, "second"),
            "AMPERE",
            Unit.base(UnitAtom.AMPERE, "ampere"),
            "KELVIN",
            Unit.base(UnitAtom.KELVIN, "kelvin"),
            "MOLE",
            Unit.base(UnitAtom.MOLE, "mole"),
            "CANDELA",
            Unit.base(UnitAtom.CANDELA, "candela"),
            "ONE",
            Unit.ONE);
    private static final int MAX_DEPTH = 32;
    private static final String INDENT = "                    ";

    private static final String INTERFACE = """
            /**
             * $A of the native API.$R
             */
            public interface $T extends org.tinygears.uom.Quantity<$T> {

                /** The system unit of {@code $T}, {@code $S}. */
                org.tinygears.uom.Unit<$T> SYSTEM_UNIT = org.tinygears.uom.internal.Units.of($T.class, $U);

                /**
                 * An absolute double quantity.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @return the quantity
                 */
                static $T of(double value, org.tinygears.uom.Unit<$T> unit) {
                    return of(value, unit, org.tinygears.uom.Scale.ABSOLUTE);
                }

                /**
                 * A double quantity.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @return the quantity
                 */
                static $T of(double value, org.tinygears.uom.Unit<$T> unit, org.tinygears.uom.Scale scale) {
                    return new $TValue(value, null, null, unit, scale);
                }

                /**
                 * An absolute decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @return the quantity
                 */
                static $T of(java.math.BigDecimal value, org.tinygears.uom.Unit<$T> unit) {
                    return of(value, unit, org.tinygears.uom.Scale.ABSOLUTE, java.math.MathContext.DECIMAL128);
                }

                /**
                 * A decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @return the quantity
                 */
                static $T of(java.math.BigDecimal value, org.tinygears.uom.Unit<$T> unit,
                        org.tinygears.uom.Scale scale) {
                    return of(value, unit, scale, java.math.MathContext.DECIMAL128);
                }

                /**
                 * A decimal quantity with context {@code mc}; the value is kept as given, not rounded. A scale of the value
                 * beyond 100000, a value of more than 50000 digits or a precision of {@code mc} over 50000 throws
                 * ArithmeticException.
                 *
                 * @param value the value
                 * @param unit the unit
                 * @param scale the scale
                 * @param mc the context that rounds results
                 * @return the quantity
                 */
                static $T of(java.math.BigDecimal value, org.tinygears.uom.Unit<$T> unit,
                        org.tinygears.uom.Scale scale, java.math.MathContext mc) {
                    return new $TValue(0, java.util.Objects.requireNonNull(value, "value"), mc, unit, scale);
                }
            $M}
            """;

    private static final String METHOD = """

                /**
                 * $D.
                 * The result is in the system unit of {@code $C}, decimal if either operand is.
                 *
                 * @param $P the $W
                 * @return the $V
                 */
                $C $O($A $P);
            """;

    private static final String CLASS = """
            final class $TValue extends org.tinygears.uom.AbstractQuantity<$T> implements $T {

                @java.io.Serial
                private static final long serialVersionUID = 1L;

                $TValue(double value, java.math.BigDecimal decimal, java.math.MathContext mc,
                        org.tinygears.uom.Unit<$T> unit, org.tinygears.uom.Scale scale) {
                    super(value, decimal, mc, unit, scale);
                }

                @Override
                protected $T create(double value, java.math.BigDecimal decimal, java.math.MathContext mc,
                        org.tinygears.uom.Unit<$T> unit, org.tinygears.uom.Scale scale) {
                    return new $TValue(value, decimal, mc, unit, scale);
                }
            $M}
            """;

    private static final String IMPLEMENTATION = """

                @Override
                public $C $O($A $P) {
                    return product($P, $B, $C.SYSTEM_UNIT, $CValue::new);
                }
            """;

    private static final String REGISTRY = """
            /** The quantity types of the native API: their dimensions and factories. */
            public final class QuantityTypes {
                private static final java.util.Map<Class<?>, org.tinygears.uom.engine.Dimension> DIMENSIONS =
                        java.util.Map.ofEntries($D);

                private QuantityTypes() {
                }

                /**
                 * The dimension of {@code type}.
                 *
                 * @param type the quantity type
                 * @return the dimension, or null if {@code type} is not a quantity type
                 */
                public static org.tinygears.uom.engine.Dimension dimension(Class<?> type) {
                    return DIMENSIONS.get(type);
                }

                /**
                 * The factory of {@code type}.
                 *
                 * @param <Q> the quantity type
                 * @param type the quantity type
                 * @return the factory
                 * @throws IllegalArgumentException if {@code type} is not a quantity type
                 */
                // each entry maps a type to the factory of its own class
                @SuppressWarnings("unchecked")
                public static <Q extends org.tinygears.uom.Quantity<Q>> org.tinygears.uom.AbstractQuantity.Factory<Q> factory(
                        Class<Q> type) {
                    org.tinygears.uom.AbstractQuantity.Factory<?> f = Factories.ALL.get(type);
                    if (f == null) {
                        throw new IllegalArgumentException("not a quantity type: " + type.getName());
                    }
                    return (org.tinygears.uom.AbstractQuantity.Factory<Q>) f;
                }

                /**
                 * The quantity types.
                 *
                 * @return the types
                 */
                public static java.util.Set<Class<?>> types() {
                    return DIMENSIONS.keySet();
                }

                /**
                 * The quantity type whose system unit is the system unit of {@code unit}: atoms included, so
                 * {@code 1/s} is a {@code Frequency} and {@code Bq} a {@code Radioactivity}.
                 *
                 * @param unit the unit
                 * @return the type, or null if no type has its system unit
                 */
                public static Class<?> typeOf(org.tinygears.uom.Unit<?> unit) {
                    return SystemUnits.TYPES.get(unit.systemUnit());
                }

                /**
                 * The system unit of {@code type}.
                 *
                 * @param type the quantity type
                 * @return the system unit, or null if {@code type} is not a quantity type
                 */
                public static org.tinygears.uom.Unit<?> systemUnit(Class<?> type) {
                    return SystemUnits.UNITS.get(type);
                }

                // Apart from DIMENSIONS too: the system units are only complete once every type is initialised.
                private static final class SystemUnits {
                    static final java.util.Map<org.tinygears.uom.Unit<?>, Class<?>> TYPES =
                            java.util.Map.ofEntries($S);
                    static final java.util.Map<Class<?>, org.tinygears.uom.Unit<?>> UNITS = units();

                    private static java.util.Map<Class<?>, org.tinygears.uom.Unit<?>> units() {
                        java.util.Map<Class<?>, org.tinygears.uom.Unit<?>> m = new java.util.HashMap<>();
                        TYPES.forEach((unit, type) -> m.put(type, unit));
                        return java.util.Map.copyOf(m);
                    }
                }

                // Apart from DIMENSIONS, which Units.of reads while the system units initialise.
                private static final class Factories {
                    static final java.util.Map<Class<?>, org.tinygears.uom.AbstractQuantity.Factory<?>> ALL =
                            java.util.Map.ofEntries($F);
                }
            }
            """;

    private NativeEmitter() {
    }

    /** The interface of {@code spec} with the methods {@code methods} gives it. */
    public static String typedInterface(QuantityTypeSpec spec, List<QuantityRelation.Method> methods, Definitions si) {
        Imports imports = quantityImports(List.of(spec));
        StringBuilder m = new StringBuilder();
        for (QuantityRelation.Method x : own(spec, methods)) {
            m.append(
                    METHOD.replace("$D", description(x)).replace("$W", words(x.argument()))
                            .replace("$V", words(x.result())).replace("$C", x.result())
                            .replace("$O", x.divide() ? "divide" : "multiply").replace("$A", x.argument())
                            .replace("$P", parameter(x.argument())));
        }
        String unit;
        try {
            unit = systemExpression(spec.systemUnit(), si, imports, 0);
        } catch (GenerationException e) {
            throw new GenerationException(spec.source(), spec.line(), e.getMessage(), e);
        }
        if (spec.systemSymbol() != null) {
            unit = named(unit, spec.systemSymbol(), spec.systemName(), imports);
        }
        String symbol = spec.systemSymbol() != null ? spec.systemSymbol() : spec.systemUnit().toString();
        String body = INTERFACE.replace("$M", m).replace("$A", capitalized(article(words(spec.name()))))
                .replace("$R", relationsSentence(spec, methods)).replace("$T", spec.name()).replace("$S", symbol);
        body = imports.resolve(body).replace("$U", unit);
        return header("data/quantity-types.txt and data/quantity-relations.txt", QUANTITY) + imports.block() + body;
    }

    /** The class of {@code spec}, implementing the methods {@code methods} gives it. */
    public static String valueClass(QuantityTypeSpec spec, List<QuantityRelation.Method> methods) {
        Imports imports = quantityImports(List.of(spec));
        StringBuilder m = new StringBuilder();
        for (QuantityRelation.Method x : own(spec, methods)) {
            m.append(
                    IMPLEMENTATION.replace("$C", x.result()).replace("$O", x.divide() ? "divide" : "multiply")
                            .replace("$A", x.argument()).replace("$P", parameter(x.argument()))
                            .replace("$B", Boolean.toString(x.divide())));
        }
        String body = imports.resolve(CLASS.replace("$M", m).replace("$T", spec.name()));
        return header("data/quantity-types.txt and data/quantity-relations.txt", QUANTITY) + imports.block() + body;
    }

    /** The registry of the types. */
    public static String registry(List<QuantityTypeSpec> specs) {
        String dims = specs.stream().map(s -> "java.util.Map.entry(" + s.name() + ".class, " + dimension(s) + ")")
                .collect(Collectors.joining(",\n" + INDENT, "\n" + INDENT, ""));
        String factories = specs.stream()
                .map(
                        s -> "java.util.Map.entry(" + s.name() + ".class, (org.tinygears.uom.AbstractQuantity.Factory<"
                                + s.name() + ">) " + s.name() + "Value::new)")
                .collect(Collectors.joining(",\n" + INDENT, "\n" + INDENT, ""));
        String units = specs.stream()
                .map(s -> "java.util.Map.entry(" + s.name() + ".SYSTEM_UNIT, " + s.name() + ".class)")
                .collect(Collectors.joining(",\n" + INDENT, "\n" + INDENT, ""));
        Imports imports = quantityImports(specs);
        String body = imports.resolve(REGISTRY.replace("$D", dims).replace("$F", factories).replace("$S", units));
        return header("data/quantity-types.txt", QUANTITY) + imports.block() + body;
    }

    /** The native catalog class: a typed constant per unit, the list of units and the preferred unit per type. */
    public static String catalog(
            CatalogBuilder.Catalog catalog,
            Definitions definitions,
            List<QuantityTypeSpec> extras
    ) {
        String className = catalog.className();
        Imports imports = new Imports(SYSTEMS, taken(catalog, extras));
        String unitClass = imports.name(BASE + ".Unit");
        List<String> files = CatalogEmitter.dataFiles(catalog.file(), definitions);
        StringBuilder out = new StringBuilder(
                """
                        /** The $C units of the native API; generated from $J. */
                        public final class $C {

                            private $C() {
                            }
                        """.replace("$J", join(files.stream().map(f -> "{@code data/" + f + "}").toList()))
                        .replace("$C", className));
        for (CatalogEntry e : catalog.entries()) {
            Definition d = e.definition();
            String type = imports
                    .name(e.quantityType() == null ? BASE + ".Untyped" : QUANTITY + "." + e.quantityType());
            String unit;
            if (e.shared() != null) {
                unit = local(nativeRef(e.shared()), className, imports);
            } else {
                try {
                    unit = unitExpression(d.unit(), definitions, imports);
                } catch (GenerationException x) {
                    throw new GenerationException(d.source(), d.line(), x.getMessage(), x);
                }
                unit = imports.name(UNITS) + ".of(" + type + ".class, " + named(unit, d.symbol(), d.name(), imports)
                        + ")";
            }
            out.append("\n    /** The ").append(d.name()).append(", {@code ").append(d.symbol())
                    .append("}. */\n    public static final ").append(unitClass).append('<').append(type).append("> ")
                    .append(e.constantName()).append(" =\n            ").append(unit).append(";\n");
        }
        for (QuantityTypeSpec t : extras) {
            String constant = CatalogEmitter.constantName(t);
            out.append("\n    /** The ").append(t.systemName()).append(", {@code ").append(t.systemSymbol())
                    .append("}, the system unit of ").append(t.name()).append(". */\n    public static final ")
                    .append(unitClass).append('<').append(imports.name(QUANTITY + "." + t.name())).append("> ")
                    .append(constant).append(" =\n            ").append(imports.name(QUANTITY + "." + t.name()))
                    .append(".SYSTEM_UNIT;\n");
        }
        String list = imports.name("java.util.List");
        out.append("\n    /** The units of this catalog. */\n    public static final ").append(list).append('<')
                .append(unitClass).append("<?>> UNITS =\n            ").append(list).append(".<").append(unitClass)
                .append("<?>>of(")
                .append(
                        args(
                                catalog.units().values().stream().map(r -> local(nativeRef(r), className, imports))
                                        .toList()))
                .append(";\n");
        String set = imports.name("java.util.Set");
        out.append("\n    /** The symbols that take a metric prefix in this system. */\n    public static final ")
                .append(set).append("<String> PREFIXABLE =\n            ").append(set).append(".of(")
                .append(args(catalog.prefixable().stream().sorted().map(UnitExpressionEmitter::literal).toList()))
                .append(";\n");
        out.append("\n    /** The symbols that take a binary prefix in this system. */\n    public static final ")
                .append(set).append("<String> BINARY_PREFIXABLE =\n            ").append(set).append(".of(")
                .append(args(catalog.binaryPrefixable().stream().sorted().map(UnitExpressionEmitter::literal).toList()))
                .append(";\n");
        String map = imports.name("java.util.Map");
        List<String> pairs = new ArrayList<>();
        catalog.preferred().forEach(
                (type, ref) -> pairs.add(
                        map + ".entry(" + imports.name(QUANTITY + "." + type) + ".class, "
                                + local(nativeRef(ref), className, imports) + ")"));
        out.append("\n    /** The preferred unit of each quantity type. */\n    public static final ").append(map)
                .append("<Class<?>, ").append(unitClass).append("<?>> PREFERRED =\n            ").append(map)
                .append(".<Class<?>, ").append(unitClass).append("<?>>ofEntries(").append(args(pairs)).append(";\n");
        String system = imports.name(BASE + ".SystemOfUnits");
        out.append("\n    /** This system of units. */\n    public static final ").append(system)
                .append(" SYSTEM =\n            new ").append(system).append('(')
                .append(UnitExpressionEmitter.literal(className))
                .append(", UNITS, PREFERRED, PREFIXABLE, BINARY_PREFIXABLE);\n");
        return header(join(files.stream().map(f -> "data/" + f).toList()), SYSTEMS) + imports.block()
                + out.append("}\n");
    }

    /** An engine expression for {@code unit}: its system unit transformed by its exact converter. */
    static String unitExpression(Unit unit, Definitions definitions, Imports imports) {
        String system = systemExpression(unit.systemUnit(), definitions, imports, 0);
        Converter c = unit.toSystem();
        return c.equals(Converter.IDENTITY)
                ? system
                : system + ".transform(" + imports.name("org.tinygears.uom.engine.Converter") + ".affine("
                        + FactorEmitter.emit(c.factor(), imports) + ", " + FactorEmitter.emit(c.offset(), imports)
                        + "))";
    }

    /** An engine expression for an unscaled unit: products of base units and alternates. */
    static String systemExpression(Unit unit, Definitions definitions, Imports imports, int depth) {
        if (!unit.toSystem().equals(Converter.IDENTITY)) {
            throw new GenerationException("unit must be an unscaled system unit");
        }
        List<Map.Entry<UnitAtom, Integer>> atoms = unit.atoms().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UnitAtom::symbol))).toList();
        StringBuilder out = new StringBuilder();
        for (Map.Entry<UnitAtom, Integer> e : atoms) {
            if (e.getValue() > 0) {
                String term = power(atom(e.getKey().symbol(), definitions, imports, depth), e.getValue());
                out.append(out.isEmpty() ? term : ".multiply(" + term + ")");
            }
        }
        if (out.isEmpty()) {
            out.append(imports.name(BASE_UNITS)).append(".ONE");
        }
        for (Map.Entry<UnitAtom, Integer> e : atoms) {
            if (e.getValue() < 0) {
                out.append(".divide(")
                        .append(power(atom(e.getKey().symbol(), definitions, imports, depth), -e.getValue()))
                        .append(')');
            }
        }
        return out.toString();
    }

    private static String atom(String symbol, Definitions definitions, Imports imports, int depth) {
        String base = BASE_ATOMS.get(symbol);
        if (base != null) {
            return imports.name(BASE_UNITS) + "." + base;
        }
        Definition d = definitions.definitions().stream()
                .filter(x -> x.symbol().equals(symbol) && x.alternateOf() != null).findFirst()
                .orElseThrow(() -> new GenerationException("no definition for alternate unit '" + symbol + "'"));
        if (depth >= MAX_DEPTH) {
            throw new GenerationException("alternate unit definitions nest too deeply at '" + symbol + "'");
        }
        return systemExpression(d.alternateOf(), definitions, imports, depth + 1) + ".alternate("
                + UnitExpressionEmitter.literal(symbol) + ")";
    }

    private static String power(String term, int n) {
        return n == 1 ? term : term + ".pow(" + n + ")";
    }

    /** {@code expression} renamed, unless it is a {@code BaseUnits} constant that already has this symbol and name. */
    static String named(String expression, String symbol, String name, Imports imports) {
        String prefix = imports.name(BASE_UNITS) + ".";
        Unit base = expression.startsWith(prefix) ? BASE_CONSTANTS.get(expression.substring(prefix.length())) : null;
        if (base != null && symbol.equals(base.symbol()) && name.equals(base.name())) {
            return expression;
        }
        return expression + ".named(" + UnitExpressionEmitter.literal(symbol) + ", "
                + UnitExpressionEmitter.literal(name) + ")";
    }

    /** A JSR-385 factory system unit reference as the native one: {@code SpeedFactory.SYSTEM_UNIT} is {@code Speed.SYSTEM_UNIT}. */
    static String nativeRef(String ref) {
        Matcher m = FACTORY_UNIT.matcher(ref);
        return m.matches() ? QUANTITY + "." + m.group(1) + ".SYSTEM_UNIT" : ref;
    }

    private static List<QuantityRelation.Method> own(QuantityTypeSpec spec, List<QuantityRelation.Method> methods) {
        return methods.stream().filter(m -> m.receiver().equals(spec.name())).toList();
    }

    private static String description(QuantityRelation.Method m) {
        return m.divide()
                ? "This " + words(m.receiver()) + " divided by " + article(words(m.argument()))
                : "This " + words(m.receiver()) + " times " + article(words(m.argument()));
    }

    /** The sentence on the typed products of {@code spec}, or nothing. */
    private static String relationsSentence(QuantityTypeSpec spec, List<QuantityRelation.Method> methods) {
        return own(spec, methods).isEmpty()
                ? ""
                : "\n * Its typed products and quotients follow from {@code data/quantity-relations.txt}.";
    }

    private static String capitalized(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code ElectricCurrent} as {@code electric current}. */
    static String words(String type) {
        return type.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
    }

    private static String article(String words) {
        return ("aeiou".indexOf(words.charAt(0)) >= 0 ? "an " : "a ") + words;
    }

    private static String parameter(String type) {
        return Character.toLowerCase(type.charAt(0)) + type.substring(1);
    }

    private static String dimension(QuantityTypeSpec s) {
        Dimension d = s.systemUnit().dimension();
        return "new org.tinygears.uom.engine.Dimension(" + d.length() + ", " + d.mass() + ", " + d.time() + ", "
                + d.current() + ", " + d.temperature() + ", " + d.amount() + ", " + d.luminosity() + ")";
    }

    private static Imports quantityImports(List<QuantityTypeSpec> specs) {
        List<String> taken = new ArrayList<>(List.of("QuantityTypes", "Factories", "SystemUnits"));
        for (QuantityTypeSpec s : specs) {
            taken.addAll(List.of(s.name(), s.name() + "Value"));
        }
        return new Imports(QUANTITY, taken);
    }

    private static Set<String> taken(CatalogBuilder.Catalog catalog, List<QuantityTypeSpec> extras) {
        Set<String> taken = new TreeSet<>(
                List.of(catalog.className(), "UNITS", "PREFERRED", "PREFIXABLE", "BINARY_PREFIXABLE", "SYSTEM"));
        catalog.entries().forEach(e -> taken.add(e.constantName()));
        extras.forEach(t -> taken.add(CatalogEmitter.constantName(t)));
        catalog.units().values().stream().map(NativeEmitter::nativeRef)
                .filter(r -> r.indexOf('.') == r.lastIndexOf('.'))
                .forEach(r -> taken.add(r.substring(0, r.indexOf('.'))));
        return taken;
    }

    private static String local(String ref, String className, Imports imports) {
        int dot = ref.lastIndexOf('.');
        if (ref.indexOf('.') != dot) {
            return imports.name(ref.substring(0, dot)) + ref.substring(dot);
        }
        return ref.startsWith(className + ".") ? ref.substring(className.length() + 1) : ref;
    }

    private static String args(List<String> items) {
        return items.isEmpty() ? ")" : "\n" + INDENT + String.join(",\n" + INDENT, items) + ")";
    }

    private static String join(List<String> items) {
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String header(String sources, String pkg) {
        return Headers.java(sources) + "\npackage " + pkg + ";\n\n";
    }
}
