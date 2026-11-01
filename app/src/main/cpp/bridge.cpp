// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <android/bitmap.h>
#include <atomic>
#include <cerrno>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <vector>
#include <memory>
#include <mutex>
#include "encoder.h"
#include "mono.h"

namespace {
std::atomic_bool cancelled{false};
std::mutex document_mutex;
std::unique_ptr<fastprint::MonoJob> document;
void throw_io(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) env->ThrowNew(cls, message);
}
struct Pixels {
    JNIEnv *env;
    jobject bitmap;
    void *data = nullptr;
    Pixels(JNIEnv *e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &data) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Bitmap lock failed");
    }
    ~Pixels() { AndroidBitmap_unlockPixels(env, bitmap); }
};
void write_all(int fd, const uint8_t *data, size_t size) {
    while (size) {
        if (cancelled.load()) throw std::runtime_error("CANCELLED");
        ssize_t n = write(fd, data, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) throw std::runtime_error("Raster write failed");
        data += n;
        size -= n;
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_ru_brotherfastprint_render_NativeBridge_reset(JNIEnv *, jobject) { cancelled.store(false); }

extern "C" JNIEXPORT void JNICALL
Java_ru_brotherfastprint_render_NativeBridge_cancel(JNIEnv *, jobject) { cancelled.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_ru_brotherfastprint_render_NativeBridge_beginDocument(JNIEnv *env, jobject, jint fd, jboolean economy, jboolean duplex) {
    try {
        std::lock_guard<std::mutex> guard(document_mutex);
        if (document) throw std::logic_error("Job already open");
        if (cancelled.load()) throw std::runtime_error("CANCELLED");
        document = std::make_unique<fastprint::MonoJob>(fd, economy, duplex);
    } catch (...) { throw_io(env, "Cannot start HBP job"); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_brotherfastprint_render_NativeBridge_appendPage(JNIEnv *env, jobject, jint fd, jint width, jint height) {
    try {
        std::lock_guard<std::mutex> guard(document_mutex);
        if (!document) throw std::logic_error("No job");
        return document->append_page(fd, width, height, cancelled);
    } catch (...) { throw_io(env, "Cannot encode HBP page"); }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_brotherfastprint_render_NativeBridge_finishDocument(JNIEnv *env, jobject) {
    try {
        std::lock_guard<std::mutex> guard(document_mutex);
        if (!document) throw std::logic_error("No job");
        auto bytes = document->finish(cancelled);
        document.reset();
        return bytes;
    } catch (...) { throw_io(env, "Cannot finish HBP job"); }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_brotherfastprint_render_NativeBridge_abortDocument(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(document_mutex);
    document.reset(); // Partial data stays private and is never sent by the caller.
}

extern "C" JNIEXPORT void JNICALL
Java_ru_brotherfastprint_render_NativeBridge_appendMono(JNIEnv *env, jobject, jobject bitmap,
                                                       jint rows, jint absolute_y, jint fd, jint profile) {
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || rows < 1 || rows > static_cast<int>(info.height) ||
            info.width < 1 || info.width > 6000 || rows > 256)
            throw std::invalid_argument("Invalid bitmap");
        if (cancelled.load()) throw std::runtime_error("CANCELLED");
        std::vector<uint8_t> output(((info.width + 7) / 8) * rows);
        {
            Pixels pixels(env, bitmap);
            bool premul = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
            fastprint::pack_rgba(static_cast<uint8_t *>(pixels.data), info.width, rows, info.stride,
                                 absolute_y, premul, output.data(), static_cast<fastprint::RasterProfile>(profile));
        }
        write_all(fd, output.data(), output.size());
    } catch (const std::exception &) { throw_io(env, "Raster conversion failed"); }
    catch (...) { throw_io(env, "Native raster failure"); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_brotherfastprint_render_NativeBridge_encode(JNIEnv *env, jobject, jint raster_fd,
                                                   jint hbp_fd, jint width, jint height) {
    try { return fastprint::encode_mono(raster_fd, hbp_fd, width, height, cancelled); }
    catch (const std::exception &) { throw_io(env, "HBP conversion failed"); }
    catch (...) { throw_io(env, "Native HBP failure"); }
    return 0;
}
