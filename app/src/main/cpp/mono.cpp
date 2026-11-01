// SPDX-License-Identifier: GPL-3.0-or-later
#include "mono.h"
#include <algorithm>
#include <cstring>
#include <stdexcept>

namespace fastprint {
void pack_rgba(const uint8_t *rgba, int width, int rows, size_t stride,
               int absolute_y, bool premultiplied, uint8_t *mono) {
    if (!rgba || !mono || width < 1 || width > 6000 || rows < 1 || rows > 256 ||
        stride < static_cast<size_t>(width) * 4 || absolute_y < 0 || absolute_y > 8000)
        throw std::invalid_argument("Invalid raster geometry");
    static constexpr uint8_t bayer[8][8] = {
        {0,48,12,60,3,51,15,63}, {32,16,44,28,35,19,47,31},
        {8,56,4,52,11,59,7,55}, {40,24,36,20,43,27,39,23},
        {2,50,14,62,1,49,13,61}, {34,18,46,30,33,17,45,29},
        {10,58,6,54,9,57,5,53}, {42,26,38,22,41,25,37,21}
    };
    const size_t bytes = (width + 7) / 8;
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
            const int threshold = bayer[(absolute_y + y) & 7][x & 7] * 4 + 2;
            if (lum < threshold) dst[x >> 3] |= static_cast<uint8_t>(0x80 >> (x & 7));
        }
    }
}
}
