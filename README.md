# uom

A Java library for typed quantities and units of measurement: its own API, with units typed by its quantity types and typed
products (`uom`), and a JSR-385 implementation (`javax.measure`, unit-api 2.2) on the same engine (`uom-jsr`). It is in
development.

Status: milestone 6 done, then the native API (DESIGN DR-21). Both APIs have typed `double` and `BigDecimal` quantities for all 33 quantity types (with typed products in `uom`), eight catalogs with metric and binary prefixes, `quantity.toSystem(system)`, unit and quantity formats with locale-aware numbers, a decimal-preserving parse and a faithful form for offset units, Java serialization of units and quantities and user `.units` files loaded at run time; `uom-jsr` adds the JSR-385 service provider. The systems are `SI` (128 distinct units), `USCustomary` (35), `Imperial` (42), `CGS` (16), `ESU` (22), `EMU` (29), `Gaussian` (26, listed as 27 symbols in [docs/UNITS.md](docs/UNITS.md) because `Fr` and `statC` are the same unit) and `Information` (22: bit and byte with decimal and binary prefixes); `quantity.toSystem(system)` converts to the system's preferred unit for the quantity type. The JSR-385 TCK (`unit-tck` 2.2) passes: 113 of 113 tests; `mvn -q -B clean verify` runs 1334 tests (engine 70, uom 21, uom-jsr 883, codegen 188, tck 113, examples 59). Milestone 7 prepared the release (POM metadata, an inactive `release` profile, the public API pin, the generated unit table, the documentation set, `scripts/verify-release.sh` and a CI workflow on JDK 17, 21 and 25); nothing has been published. Not released.

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
unit of a system.

This is the native API of uom, the artifact `uom`. The artifact `uom-jsr` is a full JSR-385 implementation
(`javax.measure`) on the same engine, with the same catalogs, formats and results; `Jsr` converts between the two
(see "JSR-385" below).

## Typed products

```java
Energy work = Force.of(10, SI.NEWTON).multiply(Length.of(2, SI.METRE));
Power power = work.divide(Time.of(4, SI.SECOND));
System.out.println(power);                                          // prints: 5.0 W
Untyped momentum = Mass.of(2, SI.KILOGRAM).multiply(Speed.of(3, SI.METRE_PER_SECOND));
System.out.println(momentum);                                       // prints: 6.0 kg·m/s
```

## Systems of units

A system is a catalog of units plus a preferred unit for each of the 33 quantity types. Every unit is an exact factor
of SI units over the SI dimensions: `CGS` holds `cm`, `g` and `s`, but they are not base units of their own, so
`systemUnit()` is always the SI unit, and a quantity without a type, such as a product in `kg·m/s`, has no unit in a
system (`toSystem` throws `UnsupportedOperationException`; convert it with `to(unit)`). For `ESU`, `EMU` and `Gaussian`
this means that values convert correctly, but the formulas of those systems do not hold: in Gaussian units a charge has
the dimension g^½·cm^3/2·s^-1 and the B and H fields share a dimension, while here every unit keeps its SI dimension,
so the gauss and the oersted are not compatible. Systems with base units or dimensions of their own are out of scope
([docs/DESIGN.md](docs/DESIGN.md), DR-13).

```java
System.out.println(Force.of(1, SI.NEWTON).toSystem(CGS.SYSTEM));                // prints: 100000.0 dyn
System.out.println(ElectricCharge.of(1, SI.COULOMB).toSystem(Gaussian.SYSTEM)); // prints: 2.99792458E9 Fr
System.out.println(EMU.GAUSS.isCompatible(EMU.OERSTED));                         // prints: false
```

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

- [docs/CUSTOM-UNITS.md](docs/CUSTOM-UNITS.md): define your own units in `.units` files and load them at run time.
- [docs/PROVIDERS.md](docs/PROVIDERS.md): `uom-jsr` next to Indriya or another JSR-385 implementation: which provider
  is current and why uom works with either.
- [docs/INDRIYA.md](docs/INDRIYA.md): where `uom-jsr` and Indriya, the reference implementation, behave differently
  (equality, `isEquivalentTo`, numbers, formats and parsing).
- [docs/OFFSET-UNITS.md](docs/OFFSET-UNITS.md): units with an offset (°C, °F): points and differences, the scale of a
  quantity, and what conversion, sums, scaling and comparison do for each.
- [docs/UNITS.md](docs/UNITS.md): every unit of the built-in catalogs (generated).
- [docs/API.md](docs/API.md): what the public API is, semantic versioning, deprecation.
- [SECURITY.md](SECURITY.md): serialization filter, reporting a vulnerability.
- [CHANGELOG.md](CHANGELOG.md): changes per release.
- [CONTRIBUTING.md](CONTRIBUTING.md): build, regeneration, tests, conventions.
- [AGENTS.md](AGENTS.md): the rules for AI coding agents (`CLAUDE.md` links to it).
- [docs/RELEASING.md](docs/RELEASING.md): the release procedure (for the maintainer).
- [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md), [docs/DESIGN.md](docs/DESIGN.md) and [docs/TODO.md](docs/TODO.md):
  requirements, design and open decisions.
- [docs/NATIVE-API.md](docs/NATIVE-API.md): the native API next to the JSR-385 implementation (decided, DESIGN DR-21).
- [LICENSE](LICENSE): Apache License 2.0.

## Modules

- `uom-engine`: the part without the JSR-385 API: exact factors (`Factor`, with pi and roots), dimensions, unit atoms,
  the alternate-unit registry, converters, the unit algebra, the `.units` parser (`org.tinygears.uom.engine.parse`), the quantity arithmetic in `double` and decimal, and the logic of the unit and quantity formats and the systems (DESIGN section 2; the start of [docs/NATIVE-API.md](docs/NATIVE-API.md)).
- `uom`: the native API (JPMS module `org.tinygears.uom`, on the engine only): `Quantity`, `Unit<Length>` typed with
  this library's types, `Scale`, `Untyped`, formats and systems; package `org.tinygears.uom.quantity`, the 33 generated
  types with the typed products of `data/quantity-relations.txt`; package `org.tinygears.uom.systems`, the generated
  catalogs ([docs/NATIVE-API.md](docs/NATIVE-API.md)).
- `uom-jsr`: the JSR-385 implementation (JPMS module `org.tinygears.uom.jsr`): the units, dimensions and converters over
  the engine, the `double` and `BigDecimal` quantity base classes and `Jsr`, the conversion to and from `uom`; package
  `org.tinygears.uom.jsr.quantity`, the generated typed quantity interfaces, implementations and factories (33 types,
  133 generated files); package `org.tinygears.uom.jsr.systems`, the generated catalogs (`SI`, `USCustomary`,
  `Imperial`, `CGS`, `ESU`, `EMU`, `Gaussian`, `Information`), `UomSystemOfUnits`, unit and quantity formats, the
  `ServiceProvider`.
- `uom-codegen`: the generator (internal, not published).
- `uom-tck`: runs the JSR-385 TCK against `uom-jsr` (test only, not published).
- `uom-examples`: runnable usage examples (not published); see below.

Build and test everything: `mvn -q -B clean verify` (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## Examples

[`Examples.java`](uom-examples/src/main/java/org/tinygears/uom/examples/Examples.java) shows the native API: typed quantities and conversion, typed products (`speed()`: a length over a time is a `Speed`; `work()`: a force times a length is an `Energy`), offset units, `equals` versus `isEquivalentTo`, `isCloseTo` with a relative or absolute tolerance (`closeness()`), and unit and quantity parsing and formatting. `decimals()` shows `BigDecimal` quantities: `0.1 m + 0.2 m` is exactly `0.3 m`, unlike with `double`. `mixedPrecision()`, `contexts()`, `decimalEquality()` and `decimalFormat()` show the precision rule (the decimal wins: decimal `0.1 m` plus `double` `0.2 m` is exactly `0.3 m`), `MathContext` and `toDecimal`/`toDouble`, `equals` versus `isEquivalentTo` across precisions, and decimal formatting. `systems()` shows `quantity.toSystem(system)`: 1 ft in CGS is `30.48 cm`, 100 °C in US customary is `212.0 °F` (exactly `212 °F` for a `BigDecimal`), the US gallon (3.785411784 L) against the imperial gallon (4.54609 L), and 1 C in Gaussian units. `information()` divides `1.5 GiB` by `100 Mbit/s` (`128.84901888 s`), converts it to `1610.612736 MB` and formats 1024 B as `KiB` but 1000 B as `kB`. `serialization()` round-trips a unit and a quantity through a byte array, `locales()` prints and parses `1,5 km` in German, `offsetUnits()` formats an unnamed shifted kelvin as `K + 5` and parses it back, `decimalParse()` parses `1.50 km` to a `double` by default and, with `withDecimals`, `1.234567890 km` to a `BigDecimal` quantity that keeps its scale (the context rounds only later arithmetic, and a unit without a quantity type gives an untyped decimal quantity), `userUnits()` loads a small `.units` text, parses `2 league` and converts it with `toSystem(system)`, and `bridge()` converts a quantity to the JSR-385 types and back. [`JsrExamples.java`](uom-examples/src/main/java/org/tinygears/uom/examples/JsrExamples.java) shows the same with `uom-jsr`, plus use through the JSR-385 `ServiceProvider`, with the same results. `ExamplesTest` checks the printed output. Run it after a build with:

```
mvn -q -B -pl uom-examples -am install -DskipTests
mvn -q -B -pl uom-examples exec:java -Dexec.mainClass=org.tinygears.uom.examples.Examples
```

## JSR-385

`uom-jsr` implements JSR-385 (unit-api 2.2; the TCK passes 113 of 113 tests) with its own types in
`org.tinygears.uom.jsr.quantity` and `org.tinygears.uom.jsr.systems`, the same catalogs, formats and arithmetic, and
the `javax.measure.spi.ServiceProvider` of uom. Its typed interfaces have the simple names of the standard ones
(`org.tinygears.uom.jsr.quantity.Length` extends `javax.measure.quantity.Length`), so a file that imports ours cannot
also import the standard type; and JSR-385's generics (`Unit<Q extends Quantity<Q>>`) keep our types out of unit types.
Declare units as `Unit<?>` or with `var` (or name the standard type fully qualified), and quantities with our types:

```java jsr
var km = SI.KILOMETRE;                       // UomUnit<javax.measure.quantity.Length>
Length run = Length.of(10, km);
```

Every quantity of `uom-jsr` is a `UomQuantity`: the JSR-385 `Quantity` with our typed interface as the return type
of its operations, plus what the standard has no method for. Products, quotients and `UomQuantityFormat.parse` return a
`UomQuantity`, so:

- `as(Speed.class)` narrows to our typed interface, by dimension as `asType` does: `length.divide(time).as(Speed.class)`.
  `asType` cannot take our interface (`Speed` is not a `Quantity<Speed>`), so it needs the standard class and a cast.
- `isCloseTo(that, 1e-9)` (relative) and `isCloseTo(that, Length.of(1, SI.MILLIMETRE))` (absolute) compare within a
  tolerance you choose. The standard `isEquivalentTo` is exact for decimal quantities and uses a fixed `1e-12` for
  `double` quantities.
- `toSystem(system)`, `toDouble()` and `toDecimal(mc)` convert to a system's preferred unit and between number types.

```java jsr
Angle degrees = Angle.of(BigDecimal.ONE, SI.RADIAN).to(SI.DEGREE);   // rounded to 34 digits
System.out.println(degrees.isEquivalentTo(Angle.of(BigDecimal.ONE, SI.RADIAN))); // prints: false
System.out.println(degrees.isCloseTo(Angle.of(BigDecimal.ONE, SI.RADIAN), 1e-30)); // prints: true
```

A quantity whose static type is the standard `Quantity<?>`, such as one from code written against JSR-385 only, is
narrowed with `X.from`: `Speed.from(quantity)` narrows the same way, returns a quantity of this implementation as it is,
and refuses one of another implementation with a `ClassCastException`.

The native and the JSR-385 quantities share their engine units, so `Jsr` converts between them without converting the
value: `Jsr.toJsr(length)` (or `Jsr.toJsr(length, org.tinygears.uom.jsr.quantity.Length.class)`) and
`Jsr.fromJsr(quantity, Length.class)` keep value, number type, context and scale.

## Formats

`UnitFormat.standard()` parses and formats unit symbols and formulas (`kg·m/s^2`) over all eight systems.
`QuantityFormat.standard()` (or `new QuantityFormat(unitFormat)`) formats a quantity as value, space, unit, in
`Locale.ROOT`; it is immutable. A parsed unit or quantity has the type of its system unit (`90 km/h` is a `Speed`),
else it is `Untyped`. `uom-jsr` has the same formats as `UomUnitFormat` and `UomQuantityFormat`, with the same texts.

- `withLocale(Locale.GERMANY)` writes and reads the number in that locale (`1,5 km`); unit symbols are never localized. Grouping separators are never written and are a parse error unless `withGrouping(true)`.
- `withDecimals(MathContext.DECIMAL64)` parses to `BigDecimal` quantities that keep every digit and the scale of the text (`1.50 km` has scale 2); the default parses to `double`.
- A `jlink` image of an application that formats in a locale other than English needs the module
  `jdk.localedata` (`--add-modules jdk.localedata`); without it, `Locale.GERMANY` formats `1.5 km`.
- An offset unit without a symbol formats as a shifted formula (the kelvin shifted by 5 is `K + 5`, `5|9·K + 459.67` is the degree Fahrenheit) and parses back to an equal unit. Units with a negative or root factor, an irrational shift or a formula over 10,000 characters have no faithful form: `format` throws `IllegalArgumentException`. A glued minus before an integer (`s-1`) is an error: write `s^-1` or `s - 1`.

```java
QuantityFormat german = QuantityFormat.standard().withLocale(Locale.GERMANY);
System.out.println(german.format(Length.of(1.5, SI.KILOMETRE)));          // prints: 1,5 km
QuantityFormat decimals = QuantityFormat.standard().withDecimals(MathContext.DECIMAL64);
Quantity<?> parsed = decimals.parse("1.50 km");
System.out.println(parsed + " " + ((BigDecimal) parsed.value()).scale()); // prints: 1.50 km 2
System.out.println(UnitFormat.standard().format(SI.KELVIN.shift(5))); // prints: K + 5
```

## Serialization

Units and quantities of both APIs are `Serializable` through serialization proxies that re-check every invariant on reading. Never read untrusted streams without an `ObjectInputFilter`: [SECURITY.md](SECURITY.md) has the recommended filter and the details. The serialized form may change before 1.0. Systems and formats are not serializable.

## Your own units

A `.units` file defines units, one per line as `symbol:name formula` (`_` in a name is a space); `SystemOfUnits.load`
(in `uom-jsr`, `UomSystemOfUnits.load`) reads it at run time. A unit gets the quantity type of its system unit, else it
is `Untyped`. [docs/CUSTOM-UNITS.md](docs/CUSTOM-UNITS.md) is the full guide.

```units medieval.units
@system Medieval                # system name (else the file name)
league:league   3 mi            # formulas use the built-in symbols
furlong:furlong 220 yd
rod:rod         1|40 furlong    # exact fraction
@prefixed league k              # adds kleague
@preferred Length furlong       # q.toSystem(system) gives furlongs
```

```java
SystemOfUnits medieval = SystemOfUnits.load(Path.of("medieval.units"));
QuantityFormat format = new QuantityFormat(UnitFormat.standard().with(medieval));
Length d = format.parse("2 league").as(Length.class);
d.toSystem(medieval);                                  // 48.0 furlong
```

## Generated sources

The quantity types and catalogs of both APIs (`uom/src/main/java/org/tinygears/uom/quantity` and `.../systems`,
`uom-jsr/src/main/java/org/tinygears/uom/jsr/quantity` and `.../systems`) and [docs/UNITS.md](docs/UNITS.md) are
generated by `uom-codegen` from `data/` and committed; [CONTRIBUTING.md](CONTRIBUTING.md) has the regeneration command.
