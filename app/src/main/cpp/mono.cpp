// SPDX-License-Identifier: GPL-3.0-or-later
#include "mono.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <stdexcept>

namespace fastprint {
namespace {
struct PhotoScreen {
    std::array<uint8_t, 64> thresholds{};
    std::array<uint8_t, 256> tones{};
    PhotoScreen() {
        struct Point { int score; int index; };
        std::array<Point, 64> points{};
        constexpr double pi = 3.14159265358979323846;
        // Two clustered dots per 8x8 tile: 45 degrees, 600 * sqrt(2) / 8 = 106 lpi.
        // Quantized scores make symmetric ties deterministic on host and Android.
        for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x) {
            const double u = 2 * pi * (x + y + 1) / 8;
            const double v = 2 * pi * (x - y) / 8;
            points[y * 8 + x] = {static_cast<int>(std::lround((std::cos(u) + std::cos(v)) * 1000000)), y * 8 + x};
        }
        std::sort(points.begin(), points.end(), [](const Point &a, const Point &b) {
            return a.score != b.score ? a.score < b.score : a.index < b.index;
        });
        for (int rank = 0; rank < 64; ++rank) thresholds[points[rank].index] = rank * 4 + 2;
        // Conservative starting curve, not a measured ICC/printer calibration.
        for (int i = 0; i < 256; ++i)
            tones[i] = static_cast<uint8_t>(std::lround(255 * std::pow(i / 255.0, 0.8)));
    }
};
const PhotoScreen &photo_screen() { static const PhotoScreen screen; return screen; }
}

void pack_rgba(const uint8_t *rgba, int width, int rows, size_t stride,
               int absolute_y, bool premultiplied, uint8_t *mono, RasterProfile profile) {
    if (!rgba || !mono || width < 1 || width > 6000 || rows < 1 || rows > 256 ||
        stride < static_cast<size_t>(width) * 4 || absolute_y < 0 || absolute_y > 8000 ||
        (profile != RasterProfile::Documents && profile != RasterProfile::Photo))
        throw std::invalid_argument("Invalid raster geometry");
    static constexpr uint8_t bayer[8][8] = {
        {0,48,12,60,3,51,15,63}, {32,16,44,28,35,19,47,31},
        {8,56,4,52,11,59,7,55}, {40,24,36,20,43,27,39,23},
        {2,50,14,62,1,49,13,61}, {34,18,46,30,33,17,45,29},
        {10,58,6,54,9,57,5,53}, {42,26,38,22,41,25,37,21}
    };
    const size_t bytes = (width + 7) / 8;
    const PhotoScreen *photo = profile == RasterProfile::Photo ? &photo_screen() : nullptr;
    std::memset(mono, 0, bytes * rows);
    for (int y = 0; y < rows; ++y) {
        const auto *src = rgba + y * stride;
        auto *dst = mono + y * bytes;
        for (int x = 0; x < width; ++x) {
            const auto *p = src + 4 * x;
            int lum = (77 * p[0] + 150 * p[1] + 29 * p[2] + 128) >> 8;
            const int alpha = p[3];
            lum = premultiplied ? std::min(255, lum + 255 - alpha)
                                : (lum * alpha + 255 * (255 - alpha) + 127) / 255;
            const int phase = (((absolute_y + y) & 7) * 8) + (x & 7);
            const int threshold = photo ? photo->thresholds[phase] : bayer[(absolute_y + y) & 7][x & 7] * 4 + 2;
            if (photo) lum = photo->tones[lum];
            if (lum < threshold) dst[x >> 3] |= static_cast<uint8_t>(0x80 >> (x & 7));
        }
    }
}
}
