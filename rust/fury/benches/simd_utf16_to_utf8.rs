// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#[cfg(target_arch = "aarch64")]
use std::arch::is_aarch64_feature_detected;
use std::{env, fs};

use criterion::{black_box, criterion_group, criterion_main, Criterion};
use fury::{utf16_to_utf8, utf16_to_utf8_simd};

#[cfg(target_endian = "big")]
const IS_LITTLE_ENDIAN_LOCAL: bool = false;

#[cfg(target_endian = "little")]
const IS_LITTLE_ENDIAN_LOCAL: bool = true;

fn criterion_benchmark(c: &mut Criterion) {
    let current_dir = env::current_dir()
        .expect("Failed to get current directory")
        .join("benches");
    let path1 = current_dir.join("chinese.utf8.txt");
    let path2 = current_dir.join("czech.utf8.txt");
    let text1 = fs::read_to_string(path1).unwrap();
    let text2 = fs::read_to_string(path2).unwrap();
    let mut text = String::from("");
    for _ in 0..=10 {
        text.push_str(&text1);
        text.push_str(&text2);
    }

    let utf16_bytes = text.encode_utf16().collect::<Vec<u16>>();

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("sse2")
        && is_x86_feature_detected!("ssse3")
        && is_x86_feature_detected!("sse4.1")
    {
        c.bench_function("SIMD sse utf16 to utf8", |b| {
            b.iter(|| unsafe {
                utf16_to_utf8_simd::sse::utf16_to_utf8(
                    black_box(&utf16_bytes),
                    IS_LITTLE_ENDIAN_LOCAL,
                )
            })
        });
    }
    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx")
        && is_x86_feature_detected!("avx2")
        && is_x86_feature_detected!("sse2")
    {
        c.bench_function("SIMD avx utf16 to utf8", |b| {
            b.iter(|| unsafe {
                utf16_to_utf8_simd::avx::utf16_to_utf8(
                    black_box(&utf16_bytes),
                    IS_LITTLE_ENDIAN_LOCAL,
                )
            })
        });
    }

    #[cfg(target_arch = "aarch64")]
    if is_aarch64_feature_detected!("neon") {
        c.bench_function("SIMD neon utf16 to utf8 byte_1", |b| {
            b.iter(|| unsafe {
                utf16_to_utf8_simd::neon::utf16_to_utf8(
                    black_box(&utf16_bytes),
                    IS_LITTLE_ENDIAN_LOCAL,
                )
            })
        });
    }

    c.bench_function("normal utf16 to utf8", |b| {
        b.iter(|| utf16_to_utf8(black_box(&utf16_bytes), IS_LITTLE_ENDIAN_LOCAL))
    });

    c.bench_function("std utf16 to utf8", |b| {
        b.iter(|| {
            String::from_utf16(black_box(&utf16_bytes)).expect("Invalid UTF-16 sequence");
        });
    });
}

criterion_group!(benches, criterion_benchmark);
criterion_main!(benches);
