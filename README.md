# uom

A Java library for typed quantities and units of measurement: its own API, with units typed by its quantity types and typed
products (`uom`), and a JSR-385 implementation (`javax.measure`, unit-api 2.2) on the same engine (`uom-jsr`). It is in
development.

Status: milestone 6 done, then the native API (DESIGN DR-21). Both APIs have typed `double` and `BigDecimal` quantities for all 33 quantity types (with typed products in `uom`), eight catalogs with metric and binary prefixes, `quantity.toSystem(system)`, unit and quantity formats with locale-aware numbers, a decimal-preserving parse and a faithful form for offset units, Java serialization of units and quantities and user `.units` files loaded at run time; `uom-jsr` adds the JSR-385 service provider. The systems are `SI` (128 distinct units), `USCustomary` (35), `Imperial` (42), `CGS` (16), `ESU` (22), `EMU` (29), `Gaussian` (26, listed as 27 symbols in [docs/UNITS.md](docs/UNITS.md) because `Fr` and `statC` are the same unit) and `Information` (22: bit and byte with decimal and binary prefixes); `quantity.toSystem(system)` converts to the system's preferred unit for the quantity type. The JSR-385 TCK (`unit-tck` 2.2) passes: 113 of 113 tests; `mvn -q -B clean verify` runs 1344 tests (engine 70, uom 24, uom-jsr 886, codegen 188, tck 113, examples 63). Milestone 7 prepared the release (POM metadata, an inactive `release` profile, the public API pin, the generated unit table, the documentation set, `scripts/verify-release.sh` and a CI workflow on JDK 17, 21 and 25); nothing has been published. Not released.

## Five minutes

```java
Length marathon = Length.of(42.195, SI.KILOMETRE);
System.out.println(marathon.to(USCustomary.MILE));                 // prints: 26.218757456454306 mi
Speed pace = marathon.divide(Time.of(3, SI.HOUR));                  // a typed product: Length / Time is a Speed
System.out.println(pace.to(USCustomary.MILE_PER_HOUR));             // prints: 8.739585818818103 mph
Temperature boiling = Temperature.of(100, SI.DEGREE_CELSIUS);
System.out.println(boiling.toSystem(USCustomary.SYSTEM));           // prints: 212.0 °F
Length exact = Length.of(new BigDecimal("0.1"), SI.METRE).add(Length.of(new BigDecimal("0.2"), SI.METRE));
System.out.println(exact);                                          // prints: 0.3 m
QuantityFormat format = QuantityFormat.standard();
System.out.println(format.parse("20 km").as(Length.class).to(SI.METRE));
// prints: 20000.0 m
```

Quantities are typed (`Length`, `Speed`, ... for the 33 quantity types of JSR-385), in `double` or exact `BigDecimal`
precision, and units are typed with them: `Unit<Length> km = SI.KILOMETRE`. Products and quotients of typed
quantities are typed where a relation between the types is declared (`Length / Time` is a `Speed`, `Force · Length` an
`Energy`; [data/quantity-relations.txt](data/quantity-relations.txt) lists them), else `Untyped`, narrowed with
`as(Speed.class)`. The catalogs `SI`, `USCustomary`, `Imperial`, `CGS`, `ESU`, `EMU`, `Gaussian` and `Information` hold
the units ([docs/UNITS.md](docs/UNITS.md) lists them all), and `quantity.toSystem(system)` converts to the preferred
unit of a system. [docs/NATIVE.md](docs/NATIVE.md) is the guide.

This is the native API of uom, the artifact `uom`. The artifact `uom-jsr` is a full JSR-385 implementation
(`javax.measure`) on the same engine, with the same catalogs, formats and results; `Jsr` converts between the two
(see "JSR-385" below).

## Install

Java 17 or later. Not published yet; after `mvn -q -B install -DskipTests` in this repository:

```xml
<dependency>
  <groupId>org.tinygears</groupId>
  <artifactId>uom</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`uom` depends only on `uom-engine`; on the module path, `requires org.tinygears.uom;`. For JSR-385 (`javax.measure`),
depend on `uom-jsr` instead: it brings `uom`, `uom-engine` and `javax.measure:unit-api` 2.2, the only third-party
runtime dependency, and on the module path `requires org.tinygears.uom.jsr;` (which requires `org.tinygears.uom`,
`org.tinygears.uom.engine` and `java.measure` transitively).

## Documentation

- [docs/NATIVE.md](docs/NATIVE.md): the native API `uom`: quantities, typed products, units, decimals and precision,
  equality, offset units (°C, points and differences), systems of units, formats, your own units, serialization.
- [docs/JSR.md](docs/JSR.md): the JSR-385 implementation `uom-jsr`: typing within JSR-385, conversion to and from the
  native API, the service provider next to other implementations, and the differences from Indriya.
- [docs/CUSTOM-UNITS.md](docs/CUSTOM-UNITS.md): define your own units in `.units` files and load them at run time.
- [docs/UNITS.md](docs/UNITS.md): every unit of the built-in catalogs (generated).
- [docs/DESIGN.md](docs/DESIGN.md): requirements, architecture, public API and versioning, and the design decisions;
  [docs/TODO.md](docs/TODO.md): open work.
- [SECURITY.md](SECURITY.md): serialization filter, reporting a vulnerability.
- [CHANGELOG.md](CHANGELOG.md): changes per release.
- [CONTRIBUTING.md](CONTRIBUTING.md): build, regeneration, tests, conventions; [AGENTS.md](AGENTS.md): the rules for
  AI coding agents (`CLAUDE.md` links to it); [docs/RELEASING.md](docs/RELEASING.md): the release procedure.
- [LICENSE](LICENSE): Apache License 2.0.

## Modules

- `uom-engine`: the part without the JSR-385 API: exact factors (`Factor`, with pi and roots), dimensions, unit atoms,
  the alternate-unit registry, converters, the unit algebra, the `.units` parser (`org.tinygears.uom.engine.parse`),
  the quantity arithmetic in `double` and decimal, and the logic of the unit and quantity formats and the systems.
- `uom`: the native API (JPMS module `org.tinygears.uom`, on the engine only): `Quantity`, `Unit<Length>` typed with
  this library's types, `Scale`, `Untyped`, formats and systems; package `org.tinygears.uom.quantity`, the 33 generated
  types with the typed products of `data/quantity-relations.txt`; package `org.tinygears.uom.systems`, the generated
  catalogs ([docs/NATIVE.md](docs/NATIVE.md)).
- `uom-jsr`: the JSR-385 implementation (JPMS module `org.tinygears.uom.jsr`): the units, dimensions and converters over
  the engine, the `double` and `BigDecimal` quantity base classes and `Jsr`, the conversion to and from `uom`; package
  `org.tinygears.uom.jsr.quantity`, the generated typed quantity interfaces, implementations and factories (33 types,
  133 generated files); package `org.tinygears.uom.jsr.systems`, the generated catalogs (`SI`, `USCustomary`,
  `Imperial`, `CGS`, `ESU`, `EMU`, `Gaussian`, `Information`), `UomSystemOfUnits`, unit and quantity formats, the
  `ServiceProvider` ([docs/JSR.md](docs/JSR.md)).
- `uom-codegen`: the generator (internal, not published).
- `uom-tck`: runs the JSR-385 TCK against `uom-jsr` (test only, not published).
- `uom-examples`: runnable usage examples (not published); see below.

Build and test everything: `mvn -q -B clean verify` (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## Examples

[`Examples.java`](uom-examples/src/main/java/org/tinygears/uom/examples/Examples.java) runs the native API through
typed quantities and products, offset units, equality and closeness, decimals and the precision rule, systems,
information units, serialization, locales, the decimal parse, user units and the conversion to JSR-385;
[`JsrExamples.java`](uom-examples/src/main/java/org/tinygears/uom/examples/JsrExamples.java) does the same with
`uom-jsr` and the JSR-385 `ServiceProvider`, with the same results. `ExamplesTest` checks the printed output. Run it
after a build with:

```
mvn -q -B -pl uom-examples -am install -DskipTests
mvn -q -B -pl uom-examples exec:java -Dexec.mainClass=org.tinygears.uom.examples.Examples
```

## JSR-385

`uom-jsr` implements JSR-385 (unit-api 2.2; the TCK passes 113 of 113 tests) on the same engine, with the same
catalogs, formats and results. Its typed interfaces extend the standard ones, so its quantities work with any JSR-385
code; units keep the standard types, so declare them with `var` or `Unit<?>` ([docs/JSR.md](docs/JSR.md)):

```java jsr
var km = SI.KILOMETRE;                       // UomUnit<javax.measure.quantity.Length>
Length run = Length.of(10, km);
Speed pace = run.divide(Time.of(1, SI.HOUR)).as(Speed.class);
System.out.println(pace.to(USCustomary.MILE_PER_HOUR));     // prints: 6.21371192237334 mph
```

## Generated sources

The quantity types and catalogs of both APIs (`uom/src/main/java/org/tinygears/uom/quantity` and `.../systems`,
`uom-jsr/src/main/java/org/tinygears/uom/jsr/quantity` and `.../systems`) and [docs/UNITS.md](docs/UNITS.md) are
generated by `uom-codegen` from `data/` and committed; [CONTRIBUTING.md](CONTRIBUTING.md) has the regeneration command.
