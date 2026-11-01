// SPDX-License-Identifier: GPL-3.0-or-later
#include "mono.h"
#include <array>
#include <cstdio>
#include <stdexcept>
#include <vector>

using fastprint::RasterProfile;
void expect(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
int count(const uint8_t *data, size_t bytes) {
    int bits = 0;
    for (size_t i = 0; i < bytes; ++i) for (int b = 0; b < 8; ++b) bits += (data[i] >> b) & 1;
    return bits;
}
std::array<uint8_t, 8> patch(int gray, RasterProfile profile, int alpha = 255, bool premul = true) {
    std::array<uint8_t, 256> rgba{};
    for (int i = 0; i < 64; ++i) {
        rgba[4*i] = rgba[4*i+1] = rgba[4*i+2] = static_cast<uint8_t>(gray);
        rgba[4*i+3] = static_cast<uint8_t>(alpha);
    }
    std::array<uint8_t, 8> output{};
    fastprint::pack_rgba(rgba.data(), 8, 8, 32, 0, premul, output.data(), profile);
    return output;
}
int boundary(const std::array<uint8_t, 8> &p) {
    auto bit = [&](int x, int y) { return (p[y & 7] >> (7 - (x & 7))) & 1; };
    int edges = 0;
    for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x)
        edges += (bit(x, y) != bit(x+1, y)) + (bit(x, y) != bit(x, y+1));
    return edges;
}
int main() {
    for (auto profile : {RasterProfile::Documents, RasterProfile::Photo}) {
        expect(count(patch(0, profile).data(), 8) == 64, "Solid black changed");
        expect(count(patch(255, profile).data(), 8) == 0, "Paper white changed");
        for (bool premul : {false, true})
            expect(count(patch(0, profile, 0, premul).data(), 8) == 0, "Transparent black stained paper");
        int previous = 64;
        for (int gray = 0; gray < 256; ++gray) {
            const auto p = patch(gray, profile);
            const int black = count(p.data(), p.size());
            expect(black <= previous, "Tone curve reversed");
            previous = black;
        }
    }
    // Opt-in photo curve reduces ink coverage in midtones without clipping endpoints.
    for (int gray : {32, 64, 96, 128, 160, 192, 224})
        expect(count(patch(gray, RasterProfile::Photo).data(), 8) < count(patch(gray, RasterProfile::Documents).data(), 8), "Photo curve failed");
    expect(boundary(patch(128, RasterProfile::Photo)) < boundary(patch(128, RasterProfile::Documents)), "Photo dots are dispersed");
    expect(count(patch(16, RasterProfile::Photo).data(), 8) < 64, "Shadow detail clipped");
    expect(count(patch(240, RasterProfile::Photo).data(), 8) > 0, "Highlight detail clipped");

    constexpr int width = 129, height = 239, row = (width+7)/8, stride = width*4+13;
    std::vector<uint8_t> rgba(stride*height, 255), full(row*height), stripes(row*height);
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        const int i = y*stride+x*4;
        rgba[i] = static_cast<uint8_t>(x*7+y); rgba[i+1] = static_cast<uint8_t>(x+y*3); rgba[i+2] = static_cast<uint8_t>(x*2+y);
    }
    fastprint::pack_rgba(rgba.data(), width, height, stride, 15, true, full.data(), RasterProfile::Photo);
    int start = 0;
    for (int rows : {37, 128, 74}) {
        fastprint::pack_rgba(rgba.data()+start*stride, width, rows, stride, 15+start, true, stripes.data()+start*row, RasterProfile::Photo);
        start += rows;
    }
    expect(full == stripes, "Photo screen phase broke across strip boundaries");
    for (int y = 0; y < height; ++y) expect((full[(y+1)*row-1] & 0x7f) == 0, "Trailing bits contain ink");
    bool rejected = false;
    try { fastprint::pack_rgba(rgba.data(), width, 1, stride, 0, true, full.data(), static_cast<RasterProfile>(2)); }
    catch (const std::invalid_argument &) { rejected = true; }
    expect(rejected, "Untrusted raster profile accepted");
    std::puts("Photo halftone: endpoints, all 256 tones, density, clustering, shadows, highlights, stripes, padding, alpha, validation OK");
}
