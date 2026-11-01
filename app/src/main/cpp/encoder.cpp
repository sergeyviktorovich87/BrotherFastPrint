// SPDX-License-Identifier: GPL-3.0-or-later
#include "encoder.h"
#include "job.h"
#include <cstdio>
#include <fcntl.h>
#include <memory>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace fastprint {
namespace {
struct Input { FILE *file; const std::atomic_bool *cancel; };
thread_local Input *current = nullptr;

bool read_line(std::vector<uint8_t> &line) {
    if (!current || current->cancel->load()) throw std::runtime_error("CANCELLED");
    if (std::fread(line.data(), 1, line.size(), current->file) != line.size())
        throw std::runtime_error("Truncated raster");
    return true;
}
struct FileCloser { void operator()(FILE *f) const { std::fclose(f); } };
using File = std::unique_ptr<FILE, FileCloser>;
File duplicate_file(int fd, const char *mode) {
    int copy = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (copy < 0) throw std::runtime_error("dup failed");
    FILE *f = fdopen(copy, mode);
    if (!f) { close(copy); throw std::runtime_error("fdopen failed"); }
    return File(f);
}
struct ResetInput { ~ResetInput() { current = nullptr; } };
}

struct MonoJob::Impl {
    File output;
    std::unique_ptr<job> document;
    bool economode;
    bool duplex;
    explicit Impl(int fd, bool economy, bool both_sides)
        : output(duplicate_file(fd, "wb")),
          document(std::make_unique<job>(output.get(), "BrotherFastPrint")), economode(economy), duplex(both_sides) {}
    int64_t checked_size() {
        if (std::fflush(output.get()) || std::ferror(output.get())) throw std::runtime_error("HBP write failed");
        const off_t size = ftello(output.get());
        if (size <= 0) throw std::runtime_error("Empty HBP");
        return size;
    }
};

MonoJob::MonoJob(int output_fd, bool economode, bool duplex) {
    if (lseek(output_fd, 0, SEEK_SET) < 0 || ftruncate(output_fd, 0))
        throw std::runtime_error("Output seek/truncate failed");
    impl_ = std::make_unique<Impl>(output_fd, economode, duplex);
}
MonoJob::~MonoJob() = default;

int64_t MonoJob::append_page(int raster_fd, int width, int height, const std::atomic_bool &cancelled) {
    if (!impl_->document) throw std::logic_error("Job already finished");
    if (width < 1 || width > 6000 || height < 1 || height > 8000)
        throw std::invalid_argument("Invalid geometry");
    const int line_bytes = (width + 7) / 8;
    struct stat input_meta{}, output_meta{};
    if (fstat(raster_fd, &input_meta) || fstat(fileno(impl_->output.get()), &output_meta) ||
        input_meta.st_size != static_cast<int64_t>(line_bytes) * height ||
        (input_meta.st_dev == output_meta.st_dev && input_meta.st_ino == output_meta.st_ino))
        throw std::runtime_error("Wrong raster size/FD");
    if (lseek(raster_fd, 0, SEEK_SET) < 0) throw std::runtime_error("Raster seek failed");
    if (cancelled.load()) throw std::runtime_error("CANCELLED");
    auto input = duplicate_file(raster_fd, "rb");
    Input state{input.get(), &cancelled}; current = &state; ResetInput reset;
    page_params params{};
    params.num_copies = 1;
    params.resolution = 600;
    params.duplex = impl_->duplex;
    params.economode = impl_->economode;
    params.sourcetray = "AUTO";
    params.mediatype = "PLAIN";
    params.papersize = "A4";
    impl_->document->encode_page(params, height, line_bytes, read_line);
    if (cancelled.load()) throw std::runtime_error("CANCELLED");
    return impl_->checked_size();
}

int64_t MonoJob::finish(const std::atomic_bool &cancelled) {
    if (!impl_->document || impl_->document->pages() == 0) throw std::logic_error("Empty/finished job");
    if (cancelled.load()) throw std::runtime_error("CANCELLED");
    impl_->document.reset(); // Writes one EOJ after ALL pages.
    return impl_->checked_size();
}

int64_t encode_mono(int raster_fd, int output_fd, int width, int height, const std::atomic_bool &cancelled) {
    if (raster_fd == output_fd) throw std::invalid_argument("Same FD");
    // Validate before truncating output for the legacy standalone entry point.
    struct stat metadata{}, output_meta{};
    if (width < 1 || width > 6000 || height < 1 || height > 8000 ||
        fstat(raster_fd, &metadata) || fstat(output_fd, &output_meta) ||
        (metadata.st_dev == output_meta.st_dev && metadata.st_ino == output_meta.st_ino) ||
        metadata.st_size != static_cast<int64_t>((width + 7) / 8) * height)
        throw std::runtime_error("Wrong raster size");
    if (cancelled.load()) throw std::runtime_error("CANCELLED");
    MonoJob document(output_fd);
    document.append_page(raster_fd, width, height, cancelled);
    return document.finish(cancelled);
}
}
