# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
the project follows [semantic versioning](https://semver.org/) from 1.0.0 (see [docs/DESIGN.md](docs/DESIGN.md), section 8).

## [Unreleased]

## [0.1.0] - 2026-10-18

The first release, 0.1.0.

### Added

- Three published modules: `uom-engine` (the unit algebra, the `.units` parser, the quantity arithmetic and the logic of
  formats and systems), `uom` (the native API, module `org.tinygears.uom`) and `uom-jsr` (the JSR-385 implementation,
  module `org.tinygears.uom.jsr`). Java 17 or later; the only third-party runtime dependency is
  `javax.measure:unit-api` 2.2, in `uom-jsr`.
- The native API ([docs/NATIVE.md](docs/NATIVE.md)): `Quantity` and `Unit<Q>` typed with the library's 33 quantity
  types (`Unit<Length> km = SI.KILOMETRE`), typed products and quotients from declared relations (`Length / Time` is a
  `Speed`, `Force · Length` an `Energy`), `Untyped` with `as(Class)` otherwise.
- The JSR-385 implementation ([docs/JSR.md](docs/JSR.md)), unit-api 2.2: typed interfaces that extend the standard ones,
  `UomQuantity` with `as(Class)`, `isCloseTo` and `toSystem`, `X.from(Quantity<?>)`, the service provider `TinyGears`
  that works whichever provider is current, and `Jsr` to convert to and from the native API. The JSR-385 TCK
  (`unit-tck` 2.2) passes 113 of 113 tests.
- `double` and `BigDecimal` quantities for all 33 types. A decimal quantity carries a `MathContext` and rounds each
  result once; mixing a `double` and a decimal gives a decimal, the `double` read as its shortest decimal, so
  `0.1 m + 0.2 m` is exactly `0.3 m`. Values and contexts have at most 50000 digits.
- Units as exact products of atoms with rational factors, powers of pi and roots; structural equality (`km` equals
  `m·1000`); alternate units (`rad`, `Hz`, `Bq`, ...); a named unit's system unit is that of its quantity type (`Pa`
  for `kPa`).
- Offset units with points and differences: `ABSOLUTE` quantities follow the `Quantity` Javadoc rule, `RELATIVE` ones
  are differences without the offset, and offset units take part in products by their factor (`°C/m` is `K/m`)
  ([docs/NATIVE.md](docs/NATIVE.md), "Offset units").
- Strict `equals`, `isEquivalentTo` (exact for decimals, relative `1e-12` with a `double`), `isCloseTo` with a chosen
  tolerance, and ordering by amount.
- Eight catalogs generated from `.units` data files: `SI` (128 units, SI Brochure 9th edition), `USCustomary`,
  `Imperial`, `CGS`, `ESU`, `EMU`, `Gaussian` and `Information` (bit and byte with decimal and binary prefixes, IEC
  80000-13), each with a preferred unit for every quantity type and `quantity.toSystem(system)`
  ([docs/UNITS.md](docs/UNITS.md)).
- Unit and quantity formats: symbols, metric and binary prefixes, formulas with superscript exponents, a faithful text
  for offset units (`K + 5`), labels per format, locale-aware numbers, and a parse that keeps decimal digits and scale.
- User `.units` files loaded at run time and added to a format, with errors that name file and line
  ([docs/CUSTOM-UNITS.md](docs/CUSTOM-UNITS.md)).
- Java serialization of units and quantities through proxies that re-check every invariant on reading
  ([SECURITY.md](SECURITY.md)).

[Unreleased]: https://github.com/TinyGearsOrg/uom/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/TinyGearsOrg/uom/releases/tag/v0.1.0
