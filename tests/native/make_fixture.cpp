// SPDX-License-Identifier: GPL-3.0-or-later
#include "encoder.h"
#include <atomic>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <stdexcept>
#include <unistd.h>
#include <vector>

int main(int argc, char **argv) {
    if (argc != 5 && argc != 6) return 2;
    FILE *out = std::fopen(argv[1], "wb"), *raw = std::fopen(argv[2], "wb"), *page = tmpfile();
    if (!out || !raw || !page) return 2;
    const int pages = std::atoi(argv[3]), height = std::atoi(argv[4]);
    if (pages < 1 || pages > 50 || height < 1 || height > 6816) return 2;
    std::atomic_bool cancelled{false};
    uint32_t random_state = 0x427f631c;
    {
        // Exercise the actual JNI-facing writer and reuse ONE raster file for every page.
        fastprint::MonoJob document(fileno(out), false, argc == 6);
        std::vector<uint8_t> row(604);
        for (int n = 0; n < pages; ++n) {
            std::rewind(page);
            if (ftruncate(fileno(page), 0)) return 2;
            for (int y = 0; y < height; ++y) {
                for (size_t x = 0; x < row.size(); ++x) {
                    random_state ^= random_state << 13; random_state ^= random_state >> 17; random_state ^= random_state << 5;
                    row[x] = y % 11 == 0 ? 0 : y % 13 == 0 ? static_cast<uint8_t>(random_state) :
                             ((x + y + n) % 97 < 20 ? 0xaa : 0);
                }
                if (argc == 6 && !std::strcmp(argv[5], "duplex-blank") && n % 2 == 1)
                    std::fill(row.begin(), row.end(), 0);
                row.back() &= 0xe0; // 4827 printable pixels; five white padding bits.
                if (std::fwrite(row.data(), 1, row.size(), page) != row.size() ||
                    std::fwrite(row.data(), 1, row.size(), raw) != row.size()) return 2;
            }
            if (std::fflush(page)) return 2;
            document.append_page(fileno(page), 4827, height, cancelled);
        }
        document.finish(cancelled);
    }
    int result = std::fclose(out) | std::fclose(raw) | std::fclose(page);
    return result == 0 ? 0 : 1;
}
