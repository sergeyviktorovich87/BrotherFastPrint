// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

namespace fastprint {
// RGBA -> MSB-first, 1 = black, 0 = white. Coordinates keep Bayer phase across strips.
void pack_rgba(const uint8_t *rgba, int width, int rows, size_t stride,
               int absolute_y, bool premultiplied, uint8_t *mono);
}
