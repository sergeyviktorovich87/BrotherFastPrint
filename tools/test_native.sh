#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
python3 tools/verify_vendor.py
build_dir="build/host-tests"
mkdir -p "$build_dir"
compiler="${CXX:-g++}"
flags=(-std=c++17 -O2 -Wall -Wextra -Ithird_party/brlaser/src -Iapp/src/main/cpp)
for name in line block job; do
    sources=("third_party/brlaser/test/test_${name}.cc")
    if [[ "$name" == line || "$name" == job ]]; then sources+=(third_party/brlaser/src/line.cc); fi
    if [[ "$name" == job ]]; then sources+=(third_party/brlaser/src/job.cc); fi
    "$compiler" "${flags[@]}" "${sources[@]}" -o "$build_dir/test_$name"
    "$build_dir/test_$name"
done
"$compiler" "${flags[@]}" tests/native/test_native.cpp app/src/main/cpp/encoder.cpp app/src/main/cpp/mono.cpp third_party/brlaser/src/job.cc third_party/brlaser/src/line.cc -o "$build_dir/test_native"
"$build_dir/test_native"
"$compiler" "${flags[@]}" tests/native/make_fixture.cpp app/src/main/cpp/encoder.cpp third_party/brlaser/src/job.cc third_party/brlaser/src/line.cc -o "$build_dir/make_fixture"
python3 tests/native/roundtrip.py "$build_dir/make_fixture"
