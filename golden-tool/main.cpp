// Golden-file generator / dumper for cross-validating the Java ZVCR-3D
// implementation against the C++ reference library.
//
//   zvcr_golden gen <out.zvcr3d>   writes a deterministic synthetic file
//   zvcr_golden hexdump <file>     prints the decompressed region container as hex
//
// The synthetic content mirrors zvcr-backup/zvcr-java's GoldenContentTest so a
// byte-level comparison of the *uncompressed containers* is possible (Zstd
// output may differ across implementations; the container layout may not).
#include <zvcr/region/paletted_delta_data.hpp>
#include <zvcr/region/segment.hpp>
#include <zvcr/region/tile_entities.hpp>
#include <zvcr/region/version.hpp>
#include <zvcr/dimension.hpp>
#include <zvcr/io/compression.hpp>
#include <zvcr/io/serialize/serialize.hpp>
#include <zvcr/io/serialize/deserialize.hpp>

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <numeric>
#include <string>
#include <vector>

using namespace zvcr;

namespace {

template<size_t N>
UnpackedData<N> iota(uint16_t start) {
    UnpackedData<N> data{};
    std::iota(data.begin(), data.end(), start);
    return data;
}

template<size_t N>
UnpackedData<N> uniform(SegmentAtom value) {
    UnpackedData<N> data{};
    data.fill(value);
    return data;
}

template<size_t N>
UnpackedData<N> modulo(SegmentAtom base, size_t m) {
    UnpackedData<N> data{};
    for (size_t i = 0; i < N; i++) data[i] = base + static_cast<SegmentAtom>(i % m);
    return data;
}

// Must mirror GoldenContent.build() in the Java tests.
std::shared_ptr<Segment> buildMainSegment() {
    auto segment = std::make_shared<Segment>(DimensionType::OVERWORLD);

    // Section 0: single-value chain
    IGNORE(segment->blockSections.sections[0].insertSnapshot(PackedSnapshot<SECTION_SIZE_BLOCKS>{
            PackedData<SECTION_SIZE_BLOCKS>{0xABCD}, 1000}));

    // Section 1: uniform 7, then position 5 -> 9
    auto s1a = uniform<SECTION_SIZE_BLOCKS>(7);
    auto s1b = s1a;
    s1b[5] = 9;
    IGNORE(segment->blockSections.sections[1].insertSnapshot(PackedSnapshot<SECTION_SIZE_BLOCKS>{
            PackedData<SECTION_SIZE_BLOCKS>::pack(s1a), 1000}));
    IGNORE(segment->blockSections.sections[1].insertSnapshot(PackedSnapshot<SECTION_SIZE_BLOCKS>{
            PackedData<SECTION_SIZE_BLOCKS>::pack(s1b), 2000}));

    // Section 2: 8-bit palette (256 uniques)
    IGNORE(segment->blockSections.sections[2].insertSnapshot(PackedSnapshot<SECTION_SIZE_BLOCKS>{
            PackedData<SECTION_SIZE_BLOCKS>::pack(modulo<SECTION_SIZE_BLOCKS>(0, 256)), 1000}));

    // Section 3: direct mode (4096 uniques)
    IGNORE(segment->blockSections.sections[3].insertSnapshot(PackedSnapshot<SECTION_SIZE_BLOCKS>{
            PackedData<SECTION_SIZE_BLOCKS>::pack(iota<SECTION_SIZE_BLOCKS>(0)), 1000}));

    // Section 4: shares section 1's latest palette (dedup)
    IGNORE(segment->blockSections.sections[4].insertSnapshot(PackedSnapshot<SECTION_SIZE_BLOCKS>{
            PackedData<SECTION_SIZE_BLOCKS>::pack(s1b), 1500}));

    // Biome section 0: 4 uniques
    IGNORE(segment->biomeSections.sections[0].insertSnapshot(PackedSnapshot<SECTION_SIZE_BIOMES>{
            PackedData<SECTION_SIZE_BIOMES>::pack(modulo<SECTION_SIZE_BIOMES>(0, 4)), 1000}));

    // Segment info
    IGNORE(segment->info.insertSnapshot(SegmentState{SegmentStateType::NEW, 1000}));
    IGNORE(segment->info.insertSnapshot(SegmentState{SegmentStateType::OLD, 3000}));

    // Tile entities: a+b @1000; then b changed + c added + a removed @2000
    TileEntity a{10, TileEntityPosition{1, 2, 64}, {1, 2, 3}};
    TileEntity b{20, TileEntityPosition{3, 4, 64}, {4, 5}};
    IGNORE(segment->tileEntities.insertSnapshot(1000, std::vector<TileEntity>{a, b}));

    TileEntity b2{20, TileEntityPosition{3, 4, 64}, {9}};
    TileEntity c{30, TileEntityPosition{5, 6, 70}, {7}};
    IGNORE(segment->tileEntities.insertSnapshot(2000, std::vector<TileEntity>{b2, c}));

    return segment;
}

std::shared_ptr<Segment> buildFarSegment() {
    auto segment = std::make_shared<Segment>(DimensionType::OVERWORLD);
    IGNORE(segment->blockSections.sections[0].insertSnapshot(PackedSnapshot<SECTION_SIZE_BLOCKS>{
            PackedData<SECTION_SIZE_BLOCKS>{1}, 1000}));
    return segment;
}

int cmdGen(const std::string &path) {
    File file{};
    file.protocolVersion = 777;
    file.dimensionType = DimensionType::OVERWORLD;
    file.region.set(0, 0, buildMainSegment());
    file.region.set(31, 31, buildFarSegment());

    // Single compression thread for deterministic output.
    const auto result = writeFile(file, path, 8, 1);
    if (!result) {
        std::cerr << "write failed" << std::endl;
        return 1;
    }
    std::cout << "wrote " << path << " (" << *result << " bytes)" << std::endl;
    return 0;
}

int cmdHexdump(const std::string &path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) {
        std::cerr << "cannot open " << path << std::endl;
        return 1;
    }
    const auto size = in.tellg();
    in.seekg(0);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    in.read(reinterpret_cast<char *>(bytes.data()), size);

    // Header: magic(6) + version(1) + dimension(1) + protocol(2)
    std::vector<uint8_t> compressed(bytes.begin() + 10, bytes.end());
    std::vector<uint8_t> container;
    if (!decompressZstd(compressed, container)) {
        std::cerr << "decompress failed" << std::endl;
        return 1;
    }
    for (const auto byte : container) {
        printf("%02x", byte);
    }
    printf("\n");
    return 0;
}

int cmdRead(const std::string &path) {
    const auto result = readFile(path, 0);
    if (!result) {
        std::cerr << "read failed: " << result.error().what() << std::endl;
        return 1;
    }
    const File &file = *result;
    std::cout << "version=" << versionName(file.version)
              << " protocol=" << file.protocolVersion
              << " dimension=" << dimensionName(file.dimensionType) << std::endl;

    size_t segments = 0, chains = 0, entries = 0, tileEntities = 0;
    for (size_t i = 0; i < SEGMENTS_PER_REGION; i++) {
        const auto segment = file.region.segments[i];
        if (!segment) continue;
        segments++;
        for (size_t s = 0; s < segment->sectionCount; s++) {
            const auto &blocks = segment->blockSections.sections[s].reverseDeltas;
            const auto &biomes = segment->biomeSections.sections[s].reverseDeltas;
            if (!blocks.empty()) { chains++; entries += blocks.size(); }
            if (!biomes.empty()) { chains++; entries += biomes.size(); }
        }
        tileEntities += segment->tileEntities.reverseDeltas.size();
    }
    std::cout << "segments=" << segments << " chains=" << chains
              << " chainEntries=" << entries
              << " teDeltas=" << tileEntities << std::endl;

    // Reconstruct the latest state of the first present segment's first
    // non-empty block chain and report its atom statistics.
    for (size_t i = 0; i < SEGMENTS_PER_REGION; i++) {
        const auto segment = file.region.segments[i];
        if (!segment) continue;
        for (size_t s = 0; s < segment->sectionCount; s++) {
            const auto &chain = segment->blockSections.sections[s];
            if (chain.reverseDeltas.empty()) continue;
            const auto snapshot = chain.snapshotBefore(
                    chain.reverseDeltas.front().timestamp);
            if (!snapshot) continue;
            size_t nonAir = 0;
            uint16_t maxAtom = 0;
            for (const auto atom : *snapshot) {
                if (atom != 0) nonAir++;
                maxAtom = std::max(maxAtom, atom);
            }
            std::cout << "sample segment " << i / 32 << "," << i % 32
                      << " section " << s << ": entries=" << chain.reverseDeltas.size()
                      << " nonAirAtoms=" << nonAir
                      << " maxAtom=" << maxAtom << std::endl;
            return 0;
        }
    }
    return 0;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 3) {
        std::cerr << "usage: zvcr_golden gen|hexdump|read <file>" << std::endl;
        return 2;
    }
    const std::string mode = argv[1];
    if (mode == "gen") return cmdGen(argv[2]);
    if (mode == "hexdump") return cmdHexdump(argv[2]);
    if (mode == "read") return cmdRead(argv[2]);
    std::cerr << "unknown mode: " << mode << std::endl;
    return 2;
}
