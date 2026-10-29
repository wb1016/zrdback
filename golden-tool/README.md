# golden-tool

Cross-validation harness for the Java ZVCR-3D port (`../zvcr-java`) against the
C++ reference implementation.

`zvcr_golden gen` writes a deterministic synthetic `.zvcr3d` file; the
`hexdump` subcommand prints the uncompressed region container as hex. The
Java test `space.cobaltworks.zvcr.GoldenCrossTest` compares its own writer's
output against that hex, so a divergence in the container layout fails the
build. Zstd output itself is not compared; the compressed bytes may differ
between implementations while the container may not.

## Building

Requires CMake >= 3.25 and a C++23 compiler.

```sh
cd golden-tool
cmake -B build -S .
cmake --build build -j
```

Dependencies (`abseil`, `zstd`, `fmt`, `result-cpp`) are fetched by CMake.

## Regenerating the golden files

Only needed when the reference implementation changes in a way that alters the
container layout:

```sh
cd golden-tool
./build/zvcr_golden gen ../zvcr-java/src/test/resources/golden/golden.zvcr3d
./build/zvcr_golden hexdump ../zvcr-java/src/test/resources/golden/golden.zvcr3d \
    > ../zvcr-java/src/test/resources/golden/golden.hex
```