// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
namespace fastprint {
class MonoJob {
public:
    explicit MonoJob(int output_fd, bool economode = false, bool duplex = false);
    ~MonoJob();
    MonoJob(const MonoJob &) = delete;
    MonoJob &operator=(const MonoJob &) = delete;
    int64_t append_page(int raster_fd, int width, int height, const std::atomic_bool &cancelled);
    int64_t finish(const std::atomic_bool &cancelled);
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
// Caller owns both seekable FDs. Throws on truncation, cancellation or I/O failure.
int64_t encode_mono(int raster_fd, int output_fd, int width, int height, const std::atomic_bool &cancelled);
}
