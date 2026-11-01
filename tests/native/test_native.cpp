// SPDX-License-Identifier: GPL-3.0-or-later
#include "encoder.h"
#include "mono.h"
#include <atomic>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

void check(bool condition) { if (!condition) throw std::runtime_error("Test failed"); }

int main() {
    std::vector<uint8_t> rgba(17 * 16 * 4, 255), result(3 * 16);
    fastprint::pack_rgba(rgba.data(), 17, 16, 17 * 4, 0, true, result.data());
    for (auto b : result) check(b == 0);
    for (size_t i = 0; i < rgba.size(); i += 4) rgba[i] = rgba[i+1] = rgba[i+2] = 0;
    fastprint::pack_rgba(rgba.data(), 17, 16, 17 * 4, 0, true, result.data());
    for (int y = 0; y < 16; ++y) check(result[y*3] == 255 && result[y*3+1] == 255 && result[y*3+2] == 128);
    for (auto &b : rgba) b = 128;
    for (size_t i = 3; i < rgba.size(); i += 4) rgba[i] = 255;
    std::vector<uint8_t> whole(3 * 16), stripes(3 * 16);
    fastprint::pack_rgba(rgba.data(), 17, 16, 68, 0, true, whole.data());
    fastprint::pack_rgba(rgba.data(), 17, 7, 68, 0, true, stripes.data());
    fastprint::pack_rgba(rgba.data() + 7*68, 17, 9, 68, 7, true, stripes.data()+7*3);
    check(whole == stripes);
    // Fully transparent black must become white, including premultiplied alpha.
    std::memset(rgba.data(), 0, rgba.size());
    fastprint::pack_rgba(rgba.data(), 17, 16, 68, 0, true, result.data());
    for (auto b : result) check(b == 0);

    FILE *in = tmpfile(), *out = tmpfile();
    check(in && out);
    std::vector<uint8_t> raster(604 * 6816, 0);
    for (int y = 50; y < 200; ++y) std::memset(raster.data()+y*604+10, 0xaa, 400);
    check(std::fwrite(raster.data(), 1, raster.size(), in) == raster.size());
    std::fflush(in);
    std::atomic_bool cancelled{false};
    auto size = fastprint::encode_mono(fileno(in), fileno(out), 4827, 6816, cancelled);
    check(size > 100 && size < 5000000);
    std::rewind(out); std::vector<char> hbp(size);
    check(std::fread(hbp.data(), 1, hbp.size(), out) == hbp.size());
    std::string bytes(hbp.begin(), hbp.end());
    check(bytes.find("@PJL JOB NAME=\"BrotherFastPrint\"") != std::string::npos);
    check(bytes.find("@PJL EOJ NAME=\"BrotherFastPrint\"") != std::string::npos);
    bool truncated = false;
    try { fastprint::encode_mono(fileno(in), fileno(out), 4827, 6815, cancelled); }
    catch (...) { truncated = true; }
    check(truncated);
    // A duplicated input descriptor must not truncate the source before the alias check.
    const int alias = dup(fileno(in));
    check(alias >= 0);
    bool rejected_alias = false;
    try { fastprint::encode_mono(fileno(in), alias, 4827, 6816, cancelled); }
    catch (...) { rejected_alias = true; }
    struct stat preserved{};
    check(!fstat(fileno(in), &preserved) && preserved.st_size == static_cast<long>(raster.size()));
    check(rejected_alias); close(alias);

    {
        fastprint::MonoJob document(fileno(out), true);
        bool empty = false;
        try { document.finish(cancelled); } catch (...) { empty = true; }
        check(empty);
        document.append_page(fileno(in), 4827, 6816, cancelled);
        auto multi_size = document.finish(cancelled);
        check(multi_size > 0);
        bool finished = false;
        try { document.append_page(fileno(in), 4827, 6816, cancelled); } catch (...) { finished = true; }
        check(finished);
    }
    cancelled.store(true); bool stopped = false;
    try { fastprint::encode_mono(fileno(in), fileno(out), 4827, 6816, cancelled); }
    catch (...) { stopped = true; }
    check(stopped);
    std::fclose(in); std::fclose(out);
    std::puts("Native: dithering, padding, stripes, alpha, full A4, EOJ, alias rejection, job lifecycle, truncation, cancellation OK");
}
