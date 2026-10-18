# A native API next to the JSR-385 implementation

**Status:** done: the split is complete and merged into `ng` ([DESIGN.md](DESIGN.md) DR-21). Section 1
describes the JSR-385 constraint, sections 2 to 6 the proposal the prototype tested.

**Progress**: the prototype (steps 1 and 2) is done and the split decided (DR-21);
completing it runs in five stages: (1) the 33 types generated with typed products, (2) the catalogs, (3) offset units,
(4) formats and systems, (5) the renames to `uom` and `uom-jsr`, docs, examples and the TCK. All five stages are done; this document keeps the module and package names of the time it describes (see
"Stage 5a" below for the mapping).
- Done: `uom-engine` holds `Factor`, `Dimension`, `UnitAtom` (with the base atoms), `UnitException`, `Converter`
  (affine, exact, with its steps) and `Unit` (atoms, converter, labels, the algebra and the converter cache) and, in
  `internal`, the alternate-unit registry, products of atoms and the decimal, interval, cache and serial-form
  helpers. `UomDimension`, `UomUnit` and the package-private `AffineConverter` are the JSR-385 types over them: they add `asType`, the text of an
  unnamed unit through the provider, the JSR exceptions, and keep the identity of converters and steps.
- Done: the `.units` parser (`org.tinygears.uom.engine.parse`) evaluates to engine units, with its own table of
  metric and binary prefixes; `UomSystemOfUnits` and `UomUnitFormat` wrap its results as `UomUnit`s, and a formula
  that is one looked-up symbol still gives that unit itself. The parser's tests stay in `uom-core`, which has both
  APIs.
- Done: the native slice. `uom-native` (module `org.tinygears.uom.nativeapi`, on the engine only) has
  `Quantity<Q>`, `Unit<Q>`, `Length`, `Time`, `Speed`, the products `Length.divide(Time)` (a `Speed`),
  `Speed.multiply(Time)` (a `Length`) and `Length.divide(Speed)` (a `Time`), and the SI units of the slice;
  `uom-native-jsr` converts both ways between them and the JSR-385 quantities (`Jsr.toJsr`, `Jsr.length`, ...).
- Done: the quantity arithmetic in the engine. `org.tinygears.uom.engine.arithmetic` (exported to `uom-core` and
  `uom-native` only) holds `DoubleArithmetic` and `DecimalArithmetic` (conversion, sums, scalars, products, comparison
  and tolerances, exact and rounded once for decimals) and the precision rules (`Precision`, `Deltas`), as static
  methods on engine units and plain values, so the `double` path allocates nothing. `AbstractDoubleQuantity` and
  `AbstractDecimalQuantity` delegate to it and keep operand reading, the JSR-385 exceptions, result creation,
  `asType`, `toSystem` and serialization; their tests are unchanged. The native quantities use the same kernel:
  one class per type holds a `double` or a decimal with its context, with the precision rule of DR-14, and the
  bridge carries decimals and contexts both ways.

**Findings of the prototype**, against the criteria of section 6:
- Unit declarations: `Unit<Length> km = SI.KILOMETRE` compiles with this library's `Length`; no `var`, `Unit<?>` or
  qualified name is needed, because the native `Unit` is not bound to `Q extends javax.measure.Quantity<Q>`.
- Typed products: `Length.of(42.195, km).divide(Time.of(3, h))` is a `Speed`; each relation is one method on each
  side, so the 33 types need a relation table and generated methods.
- Boundary cost: both APIs wrap the same engine units, so a conversion copies the value and wraps the unit. Measured on
  JDK 25 (warm, 2 million calls): native to JSR-385 27 ns, back 15 ns, against 10 to 15 ns for one `double` `add` on
  either side: the boundary costs about one or two arithmetic operations.
- Shared code: the engine carries the algebra, conversion, parser, registry, serialization and the quantity
  arithmetic (784 lines in `arithmetic`); the native slice with `double` and decimal quantities is 856 lines over it
  with Javadoc (its `AbstractQuantity` 255), the bridge 161. Both APIs give the same decimal results
  (`JsrBridgeTest`). Moving the arithmetic did not change its speed on the JSR-385 side (a `double` add 10 to 11 ns,
  a decimal add 480 to 540 ns, before and after, JDK 25).
- Measured on the way: the JSR-385 quantity product (`Length.divide(Quantity)`) takes about 1.4 µs and a `double`
  add with an offset unit about 0.8 µs, against 10 ns for the native `Length.divide(Time)`, which knows its result
  unit. The JSR-385 side builds the product unit on every call (the engine's `m.divide(s)` alone takes 1.5 µs), and
  the `double` sum through an offset unit inverts the converter to the system unit on every call (1.2 µs with exact
  factors). Both are cached now: `Converter` keeps its inverse, and `Unit.systemProduct`, `systemQuotient` and
  `systemInverse` cache the unit of a product (it depends on the atoms only), so a product, an inverse or a sum
  through an offset unit takes 13 to 24 ns on either side (JDK 25).
- Stages 1 to 3: `GenerateNative` writes, from the data files of the JSR-385 side and
  `data/quantity-relations.txt` (22 relations, 86 typed methods), the 33 native types (interface with factories,
  `SYSTEM_UNIT` and typed products, and a class; 5679 lines with the registry) and the eight catalogs with
  `Unit<Length>` constants built from engine units (2450 lines). The hand-written base (`Quantity`, `Unit`, `Scale`,
  `Untyped`, `AbstractQuantity`, `BaseUnits`) is 1082 lines with Javadoc; offsets and `Scale` follow the JSR-385 rules
  through the shared kernel. `ParityTest` checks that the two sides hold the same 321 catalog units and 33 system units,
  and that sums, differences, products, quotients, scalars, conversions, comparisons, equivalence and closeness give
  the same value, scale, unit and context for lengths and temperatures in both number types and scales. A typed
  `double` add takes 10 to 11 ns, a typed `divide(Time)` 7 ns (JDK 25).
- Stage 4: the unit format (labels, symbols, prefixes, multiples, formulas, superscripts), the number text of the
  quantity format (locales, grouping, decimals), the systems and the loading of `.units` files moved into the engine
  (`org.tinygears.uom.engine.format`, 1320 lines with Javadoc, exported to `uom` and `uom-native` only).
  `UomUnitFormat`, `UomQuantityFormat` and `UomSystemOfUnits` are wrappers over it (850 lines, from 1521), their
  tests unchanged; the native `UnitFormat`, `QuantityFormat`, `SystemOfUnits`, `UnitSystems`, `ParseException` and
  serialization add 930 lines. Parsed units and quantities are typed by their system unit, else `Untyped`;
  `Quantity.toSystem(system)` converts to a system's preferred unit. `FormatParityTest` checks that both sides format
  all 320 catalog units alike (with superscripts too), parse and print formulas and quantities alike in five locales
  with and without grouping and decimals, errors with message and position included, and load the same `.units`
  text to the same system. Native units and quantities serialize through a proxy (type name, engine unit, scale,
  exact value), checked when read.
- Not native: a `ServiceProvider` and the JSR-385 format service, which are part of JSR-385.
- Stage 5a, the renames: `uom-native` is now `uom` (module and packages `org.tinygears.uom`, `.quantity`, `.systems`,
  published); `uom-core`, the former `uom` and `uom-native-jsr` are now one module, `uom-jsr` (module
  `org.tinygears.uom.jsr`, packages `org.tinygears.uom.jsr`, `.jsr.quantity`, `.jsr.systems` and the internal
  `.jsr.spi`), which requires `uom` and holds the bridge (`Jsr`) and the parity tests. The generator works on engine
  units and uses `uom-jsr` in its tests only, which load the sources they compile before it. In the merged module the
  real provider is always present, so the tests of the provider mechanism install `TestServiceProvider` for their
  duration (`TestProviderExtension`); serialized streams name the new classes (the golden files are rewritten). 1318
  tests (engine 64, uom 18, uom-jsr 933, codegen 188, tck 113, examples 2).
- Stage 5b, the user documents: the README, CUSTOM-UNITS.md, OFFSET-UNITS.md and SECURITY.md present `uom` first and
  name the `uom-jsr` classes where they differ; PROVIDERS.md and INDRIYA.md concern `uom-jsr`. `DocsSnippetsTest` runs a
  `java` block against `uom` and a `java jsr` block against `uom-jsr`, and loads every `units` block with both, which
  must agree. `Examples` shows the native API (with typed products and `Jsr`), `JsrExamples` the JSR-385 one; every
  native section prints what its JSR-385 counterpart prints, and the consumer of `verify-release.sh` runs both.
- After the split: the native API names no engine type but the value types `Factor` and `Dimension`. `Unit.converterTo`
  returns a native `Converter`; `Unit.multiply`, `divide` and `shift` take numbers as written (`Factor.valueOf`, the
  rule `uom-jsr` uses too), `scaledBy` and `shiftedBy` keep `Factor` for pi and roots; `SystemOfUnits.load` takes a
  `Function<String, Reader>`; errors are the native `UnitException` and `DefinitionException`. The engine side of units
  (`Unit.of` over an engine unit, the engine unit of a unit, `BaseUnits`) moved to the package `internal`, exported to
  `uom-jsr` only, which the generated code and `Jsr` use.

## 1. The constraint

uom implements JSR-385 (unit-api 2.2). Its generics are `Unit<Q extends Quantity<Q>>` and `Quantity<Q extends
Quantity<Q>>`: the type parameter must be a quantity of itself. The typed interfaces of uom extend the standard ones
(`org.tinygears.uom.quantity.Length extends javax.measure.quantity.Length`), so they inherit
`Quantity<javax.measure.quantity.Length>` and cannot also be a `Quantity` of themselves; Java forbids implementing one
generic interface with two arguments. As a consequence:

- Units are typed with the standard types: `SI.KILOMETRE` is a `UomUnit<javax.measure.quantity.Length>`. Our
  `Length` cannot be a unit's type parameter, and a file that imports our `Length` cannot also import the standard one,
  so units are declared with `var`, `Unit<?>` or the qualified standard name (README, "Typed quantities and unit
  declarations").
- Type tokens are the standard classes (`asType(javax.measure.quantity.Speed.class)`,
  `getQuantityFactory(javax.measure.quantity.Mass.class)`); `as(Speed.class)` and `Speed.from` exist to narrow back
  (DR-10, DR-19).
- The JSR signatures fix what an operation can return: `multiply(Quantity<?>)` returns a quantity of unknown type
  (`UomQuantity<?, ?>` at best), so `Length.divide(Time)` cannot return a `Speed` (typed products, rejected in DR-10).

Inside the JSR-385 API these constraints cannot be lifted. The proposal is a second API that is not bound to it.

## 2. Modules

```
uom-engine   no JSR-385 types: exact factors, atoms and alternate atoms, dimensions, converters, unit algebra,
             the .units parser and formula evaluator, the catalog data
uom          the native API on the engine: its own Unit<Q>, Quantity<Q>, Dimension, formats and systems, where Q
             is one of our typed interfaces (Length, Speed, ... : the 33 JSR-385 types, under their names)
uom-jsr      the JSR-385 implementation on the engine: javax.measure adapters, the ServiceProvider, the TCK
```

`uom` and `uom-jsr` both depend on `uom-engine`; `uom-jsr` may depend on `uom` to convert between the two APIs. The
artifact names are open (section 6).

What the engine would take from `uom-core`, as it is today:

| Today (`uom-core`) | Engine | Native API / JSR adapter |
|---|---|---|
| `Factor`, `internal` (decimals, irrational enclosures, caches, serial form) | as is | |
| `UnitAtom`, `AlternateUnits`, `BaseUnits` | as is, without the JSR types | typed constants |
| `AffineConverter`, `Converters` | the arithmetic | `UomConverter` implements `javax.measure.UnitConverter` only in `uom-jsr` |
| `UomDimension` (a record implementing `javax.measure.Dimension`) | the exponents | a `Dimension` per API |
| `UomUnit` (implements `javax.measure.Unit`) | the atoms, converter, symbol and name | `Unit<Q>` per API |
| `parse` (`DefinitionParser`, `FormulaEvaluator`) | as is, evaluating to engine units | |
| quantity base classes, `UomQuantity`, `spi` | | per API |

## 3. The native API, sketched

```java
// org.tinygears.uom (native)
public interface Quantity<Q extends Quantity<Q>> extends Comparable<Q> {
    Number value();
    Unit<Q> unit();
    Q add(Q other);                       // the same type, not Quantity<Q>
    Q to(Unit<Q> unit);
    boolean isCloseTo(Q other, double relativeTolerance);
    <T extends Quantity<T>> T as(Class<T> type);
}

public interface Length extends Quantity<Length> {
    static Length of(double value, Unit<Length> unit) { ... }
    Speed divide(Time time);              // typed products from declared relations
    Area multiply(Length length);
}

Unit<Length> km = SI.KILOMETRE;           // our type as the type parameter, no name clash
Speed pace = Length.of(42.195, km).divide(Time.of(3, SI.HOUR));
```

- Typed products and quotients need relations between the types (`Speed = Length / Time`, `Energy = Force · Length`),
  declared in a data file next to `quantity-types.txt` and generated; a product without a relation is untyped, as
  now.
- What the current API settled stays: exact factors, offsets and `Scale`, `double` and `BigDecimal` quantities with
  the precision rule (DR-14), exact decimal equivalence (DR-18), the catalogs and the `.units` files.
- Open: whether `Quantity` names follow JSR-385 (`getValue`, `getUnit`) for familiarity or not.

## 4. The JSR-385 side

A class cannot be a `Unit<our Length>` and a `javax.measure.Unit<javax.measure.quantity.Length>` without a second
type parameter, so the JSR module has its own types over the engine:

- Units: a `javax.measure.Unit` implementation over the engine unit (what `UomUnit` is today).
- Quantities: the generated JSR typed classes over the engine, as today, without the native API's typed products.
- Conversion between the APIs at their boundary: `Jsr.of(nativeLength)` gives a `javax.measure.quantity.Length`, and
  `Length.from(jsrQuantity)` the native one; both copy value, unit (by engine identity), scale and context.
- The TCK keeps running against `uom-jsr`.

## 5. Costs and risks

- Two public APIs to design, document, test and keep in step (formats, systems, factories, serialization), and the
  generator emits for both.
- Users of the native API reach other JSR-385 libraries only through the conversion of section 4.
- The size: the engine extraction is a refactor without behaviour change; the native API is a design project. Today
  `uom-core` has about 6,800 lines of main code and `uom` about 16,000, most of them generated.
- Nothing is released, so module boundaries are cheap to change now; after 1.0 this is a major version (API.md).

## 6. Plan

1. Prototype on a branch: extract `uom-engine` from `uom-core` (no behaviour change, all tests green), then the
   native API for a slice (`Length`, `Time`, `Speed`; their SI units; `Length.divide(Time)` returning `Speed`) and the
   JSR adapters for that slice.
2. Judge the prototype by: unit declarations without workarounds, typed products, the cost of a boundary conversion,
   and how much code the two APIs share.
3. Decide, with a decision record: complete the split; keep the single JSR-385 implementation; or ship only the
   native API with `uom-jsr` as an adapter. The artifact names (`uom`, `uom-jsr`, `uom-engine`) are decided then.
