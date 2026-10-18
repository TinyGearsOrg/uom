# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
the project follows [semantic versioning](https://semver.org/) from 1.0.0 (see [docs/API.md](docs/API.md)).

## [Unreleased]

Nothing is released yet; the development version is `0.1.0-SNAPSHOT`.

### Changed

- The modules are `uom-engine` (no JSR-385 types: units, converters, the `.units` parser, the quantity arithmetic and
  the logic of formats and systems), `uom` (the native API, module `org.tinygears.uom`: `Unit<Length>` with this
  library's types, typed products, formats, systems) and `uom-jsr` (the JSR-385 implementation, module
  `org.tinygears.uom.jsr`, packages `org.tinygears.uom.jsr.*`, with `Jsr`, the conversion to and from `uom`). The
  former `uom-core` and the JSR-385 `uom` are merged into `uom-jsr`; the entries below name the modules of their time
  (DESIGN DR-21, docs/NATIVE-API.md).
- Decimal values and context precisions are bounded to 50000 digits (`MAX_PRECISION`, before 100000 and for the
  context only): creation, arithmetic (exact `UNLIMITED` results included) and the serial form throw beyond it, and
  the quantity format refuses a longer number before parsing it. At precision 50000 a result below 1 no longer
  passes the scale bound (DESIGN DR-22).
- Alternate units: a load of `.units` files that fails leaves none of its `~` symbols registered; the seven built-in
  alternate units are reserved from the start, so a conflicting `alternate("Hz")` fails at that call instead of
  breaking the `Frequency` factory; `rad.alternate("rad")` returns `rad`, as `m.alternate("m")` returns `m` (DESIGN
  DR-23).
- The system unit of a named unit is the named system unit of its quantity type: `kPa.getSystemUnit()` is `Pa`, not
  `kg/(m·s^2)` (unnamed products such as `N·m` keep the label of their atoms; DESIGN DR-25). The unit format reads the
  Greek mu as the micro prefix and `l` as the litre (`μm`, `ml`), and writes `µm` and `mL` (DR-26).

### Added

- Units core (`uom-core`): units as exact products of atoms with a rational factor, `pi` and roots; dimensions;
  linear, offset and affine converters with exact and `BigDecimal` conversion; metric prefixes; the `.units`
  definition parser and formula evaluator with file and line in every error.
- Typed `double` quantities for all 33 JSR-385 quantity types (`Length`, `Speed`, ...), generated with factories, the
  strict arithmetic of the typed interfaces, `asType`, and `equals` versus `isEquivalentTo`.
- The `SI` catalog (128 units, from the SI Brochure, 9th edition), metric prefixes, the JSR-385 `ServiceProvider`,
  unit and quantity formats; the JSR-385 TCK (`unit-tck` 2.2) passes 113 of 113 tests.
- `BigDecimal` quantities for all 33 types with a `MathContext` (`DECIMAL128` by default), single rounding, and the
  precision rule (mixing a `double` and a `BigDecimal` quantity gives a `BigDecimal` result; the `double` is read as
  its shortest decimal, so `0.1 m + 0.2 m` is exactly `0.3 m`).
- The catalogs `USCustomary`, `Imperial`, `CGS`, `ESU`, `EMU` and `Gaussian`, the `@system`, `@extends` and `@member`
  directives, and `quantity.toSystem(system)`, which converts to the preferred unit of a system.
- Java serialization of units and quantities through serialization proxies that re-check every invariant.
- `Quantity.Scale.RELATIVE` quantities are differences: they convert, add, scale and compare without the offset of
  units such as °C, and a sum or difference is `RELATIVE` only if both operands are
  ([docs/OFFSET-UNITS.md](docs/OFFSET-UNITS.md)). `ABSOLUTE` quantities keep the literal `Quantity` Javadoc rule.
- `Unit.prefix` no longer invents a symbol (`km²`, `kmin`, `mkg`, `mmm` and collisions such as `cd` are gone): systems
  and formats name prefixed units. `UomUnitFormat` writes a metric prefix on a prefixable unit (`mHz`, `pW`) or a power of ten
  times a unit (`1000·min`), and an unnamed unit prints as the format writes it (`mHz`, `km`), else as its definition (`1000·m`) instead of its system
  label. The parser rejects `@prefixed` on a prefixed unit, and `UomUnitFormat.label` rejects a label that reads as
  another unit.
- The arithmetic and comparison methods of the quantities throw a `NullPointerException` that names the parameter
  (`addend`, `divisor`, `that`, ...), and their Javadoc says so.
- `Speed.from(quantity)`, and the same on every typed interface, narrows a `Quantity<?>` (a product, a parse result) to our
  typed interface, which `asType` cannot take as its type; it narrows by dimension and refuses a quantity of another
  implementation.
- `SI.METRE_PER_SECOND`, `SI.METRE_PER_SECOND_SQUARED` and `SI.ONE`: the system units of `Speed`, `Acceleration` and
  `Dimensionless`, the three that had no constant in `SI`, are the same instances as the factories' units.
- Formulas read superscript exponents (`m/s²`, `s⁻¹`, `kg·m²/s³`, `(m/s)²`; `km²` is `(km)²`) in code and in `.units`
  files, and `UomUnitFormat.withSuperscripts(true)` returns a format that writes them (`m/s²`) instead of `^n`. ASCII
  stays the default ([docs/CUSTOM-UNITS.md](docs/CUSTOM-UNITS.md)).
- `getSystemUnit()` returns a named system unit itself (`SI.NEWTON.getSystemUnit()` is `N`, not a copy labelled
  `kg·m/s^2`). A dimensionless quantity prints and formats as its number alone (`5.0`, not `5.0 1`); a number alone
  already parsed as dimensionless.
- `asType` (unit and quantity) goes by dimension, as the `Unit` and `Quantity` Javadoc say, so `1/s` narrows to
  `Frequency`, `Hz·s` to `Dimensionless` and `rad·m` to `Length`; `Unit.equals` stays structural.
- `UomServiceProvider` is named `TinyGears` (`ServiceProvider.of("TinyGears")`), and `asType` and deserialization find the quantity
  factories of uom whichever provider is current ([docs/PROVIDERS.md](docs/PROVIDERS.md)).
- Offset units take part in unit products, powers, roots and formulas by their factor only, so `°C/m` and
  `J/(kg·°C)` parse and `°C/m` equals `K/m`; a `RELATIVE` operand of a quantity product counts by its factor only.
- Locale-aware quantity formats (`withLocale`, `withGrouping`), a decimal-preserving parse (`withDecimals`), the
  shift grammar for offset units in formulas (`K + 273.15`) and a faithful text form for unnamed offset units.
- User `.units` files loaded at run time (`UomSystemOfUnits.load`) and added to a format with `UomUnitFormat.with`
  ([docs/CUSTOM-UNITS.md](docs/CUSTOM-UNITS.md)).
- Release preparation: POM metadata, an inactive `release` profile (sources, javadoc, signing, Central upload),
  reproducible jars, the public API definition and its pin ([docs/API.md](docs/API.md)), the generated unit table
  ([docs/UNITS.md](docs/UNITS.md)), the user documentation, the release verification script and a CI workflow
  (JDK 17, 21 and 25).
- Pre-commit hooks (`.pre-commit-config.yaml`, for prek or pre-commit): end of file, line endings, trailing
  whitespace, shellcheck, zizmor, Spotless, the regeneration of the generated sources, `check-pom.sh`,
  `check-workflows.sh` and a conventional-prefix check of the commit subject. The CI actions are pinned to commit hashes, and Dependabot proposes an update
  after a cooldown of 7 days.
- Java 17 is the baseline (was 21); releases are still built with JDK 21.
- `UomQuantity`, implemented by every quantity of this library and extended by the typed interfaces: `as(Class)`
  narrows to a typed interface (`length.divide(time).as(Speed.class)`), `isCloseTo` compares within a relative or an
  absolute tolerance, and products, quotients and `UomQuantityFormat.parse` return it.
- Decimal `isEquivalentTo` is exact (`compareTo == 0`); it followed the context precision, so at precision 1 every two
  values of the same sign were equivalent.
- The `Information` catalog: the bit and the byte with decimal and binary prefixes (`kB`, `MiB`, `Kibit`; IEC
  80000-13). `@prefixed` accepts binary prefix symbols (`Ki` to `Yi`), and the unit format reads and writes binary
  prefixes on the units that take them; such a unit takes only the metric prefixes from kilo up (`dB` does not parse).

[Unreleased]: https://github.com/TinyGearsOrg/tinyunits/commits/main
