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

use std::ptr;

// Swapping the high 8 bits and the low 8 bits of a 16-bit value
#[inline]
fn swap_endian(value: u16) -> u16 {
    (value << 8) | (value >> 8)
}

#[cfg(target_endian = "big")]
const IS_LITTLE_ENDIAN_LOCAL: bool = false;

#[cfg(target_endian = "little")]
const IS_LITTLE_ENDIAN_LOCAL: bool = true;

// normal algorithm without SIMD
pub fn utf16_to_utf8(utf16: &[u16], is_little_endian: bool) -> Result<String, String> {
    // Pre-allocating capacity to avoid dynamic resizing
    // Longest case: 1 u16 to 3 u8
    let mut utf8_bytes: Vec<u8> = Vec::with_capacity(utf16.len() * 3);
    // For unsafe write to Vec
    let ptr: *mut u8 = utf8_bytes.as_mut_ptr();
    let mut offset = 0;
    let mut iter = utf16.iter();
    while let Some(&wc) = iter.next() {
        let wc = if is_little_endian {
            wc
        } else {
            swap_endian(wc)
        };
        match wc {
            code_point if code_point & 0xFF80 == 0 => {
                // 1-byte UTF-8
                // [0000|0000|0ccc|cccc] => [0ccc|cccc]
                unsafe {
                    ptr.add(offset).write(code_point as u8);
                }
                offset += 1;
            }
            code_point if code_point & 0xF800 == 0 => {
                // 2-byte UTF-8
                // [0000|0bbb|bbcc|cccc] => [110|bbbbb], [10|cccccc]
                let bytes = [
                    (code_point >> 6 | 0b1100_0000) as u8,
                    (code_point & 0b11_1111) as u8 | 0b1000_0000,
                ];
                unsafe {
                    ptr::copy_nonoverlapping(bytes.as_ptr(), ptr.add(offset), 2);
                }
                offset += 2;
            }
            code_point if code_point & 0xF800 != 0xD800 => {
                // 3-byte UTF-8, 1 u16 -> 3 u8
                // [aaaa|bbbb|bbcc|cccc] => [1110|aaaa], [10|bbbbbb], [10|cccccc]
                // Need 16 bit suffix of wc, as same as wc itself
                let bytes = [
                    (wc >> 12 | 0b1110_0000) as u8,
                    (wc >> 6 & 0b11_1111) as u8 | 0b1000_0000,
                    (wc & 0b11_1111) as u8 | 0b1000_0000,
                ];
                unsafe {
                    ptr::copy_nonoverlapping(bytes.as_ptr(), ptr.add(offset), 3);
                }
                offset += 3;
            }
            wc1 if (0xD800..=0xDBFF).contains(&wc1) => {
                // Surrogate pair (4-byte UTF-8)
                // Need extra u16, 2 u16 -> 4 u8
                if let Some(&wc2) = iter.next() {
                    let wc2 = if is_little_endian {
                        wc2
                    } else {
                        swap_endian(wc2)
                    };
                    if !(0xDC00..=0xDFFF).contains(&wc2) {
                        return Err("Invalid UTF-16 string: wrong surrogate pair".to_string());
                    }
                    // utf16 to unicode
                    let code_point =
                        ((((wc1 as u32) - 0xD800) << 10) | ((wc2 as u32) - 0xDC00)) + 0x10000;
                    // 11110??? 10?????? 10?????? 10??????
                    // Need 21 bit suffix of code_point
                    let bytes = [
                        (code_point >> 18 | 0b1111_0000) as u8,
                        (code_point >> 12 & 0b11_1111) as u8 | 0b1000_0000,
                        (code_point >> 6 & 0b11_1111) as u8 | 0b1000_0000,
                        (code_point & 0b11_1111) as u8 | 0b1000_0000,
                    ];
                    unsafe {
                        ptr::copy_nonoverlapping(bytes.as_ptr(), ptr.add(offset), 4);
                    }
                    offset += 4;
                } else {
                    return Err("Invalid UTF-16 string: missing surrogate pair".to_string());
                }
            }
            _ => unreachable!(),
        }
    }
    unsafe {
        // As ptr.write don't change the length
        utf8_bytes.set_len(offset);
    }
    Ok(String::from_utf8(utf8_bytes).unwrap())
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]

pub mod utf16_to_utf8_simd {

    pub mod sse {
        use super::super::{swap_endian, IS_LITTLE_ENDIAN_LOCAL};
        use lazy_static::lazy_static;
        use std::arch::x86_64::{
            __m128i, _mm_and_si128, _mm_andnot_si128, _mm_blendv_epi8, _mm_cmpeq_epi16,
            _mm_loadu_si128, _mm_maddubs_epi16, _mm_movemask_epi8, _mm_or_si128, _mm_packus_epi16,
            _mm_set1_epi16, _mm_setr_epi16, _mm_setr_epi8, _mm_setzero_si128, _mm_shuffle_epi8,
            _mm_slli_epi16, _mm_srli_epi16, _mm_storeu_si128, _mm_testz_si128, _mm_unpackhi_epi16,
            _mm_unpacklo_epi16, _mm_xor_si128,
        };
        // 128/16=8
        const CHUNK_UTF16_USAGE: usize = 8;
        lazy_static! {
            // shuffle mask for swap endian
            // [0,1,2,3,4,5] -> [1,0,3,2,5,4]
            static ref ENDIAN_SWAP_MASK: __m128i =
                unsafe { _mm_setr_epi8(1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14) };
            static ref M_FF80: __m128i = unsafe { _mm_set1_epi16(0xFF80u16 as i16) };
            static ref M_F800: __m128i = unsafe { _mm_set1_epi16(0xF800u16 as i16) };
            static ref M_0000: __m128i = unsafe { _mm_setzero_si128() };
            static ref M_D800: __m128i = unsafe { _mm_set1_epi16(0xd800u16 as i16) };
            // 0b1100_0000_1000_0000
            static ref M_C080: __m128i = unsafe {_mm_set1_epi16(0xC080u16 as i16)};
            // 0b0001_1111_0000_0000
            static ref M_1F00: __m128i = unsafe {_mm_set1_epi16(0x1F00)};
            // 0b0000_0000_0011_1111
            static ref M_003F: __m128i = unsafe {_mm_set1_epi16(0x003F)};

            static ref DUP_EVEN:__m128i = unsafe {_mm_setr_epi16(
                0x0000, 0x0202, 0x0404, 0x0606, 0x0808, 0x0a0a, 0x0c0c, 0x0e0e,
            )};
        }
        #[allow(dead_code)]
        fn print_m128i_16(var: __m128i) {
            let mut val = [0i16; 8];
            unsafe {
                _mm_storeu_si128(val.as_mut_ptr() as *mut __m128i, var);
            }
            println!(
                "m128i: {:?}",
                val.iter()
                    .map(|&v| format!("{:04X}", v))
                    // .map(|&v| format!("{:016b}", v))
                    .collect::<Vec<_>>()
            );
        }

        #[inline]
        unsafe fn one_to_one(
            chunk: &__m128i,
            ptr_16: *const u16,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            len_16: usize,
            is_little_endian: bool,
        ) -> Option<__m128i> {
            let m_ff80 = _mm_set1_epi16(0xFF80u16 as i16);
            if *offset_16 + 2 * CHUNK_UTF16_USAGE <= len_16 {
                let next_chunk =
                    _mm_loadu_si128(ptr_16.add(*offset_16 + CHUNK_UTF16_USAGE) as *const __m128i);
                let next_chunk = if is_little_endian == super::super::IS_LITTLE_ENDIAN_LOCAL {
                    next_chunk
                } else {
                    _mm_shuffle_epi8(next_chunk, *ENDIAN_SWAP_MASK)
                };
                // check next_chunk's all u16 less than 0x80
                if _mm_testz_si128(next_chunk, m_ff80) != 0 {
                    // store chunk and next_chunk
                    // chunk(0087 0065) next_chunk(0043 0021) -> 8765 4321
                    let utf8_packed = _mm_packus_epi16(*chunk, next_chunk);
                    _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_packed);
                    *offset_8 += CHUNK_UTF16_USAGE * 2;
                    *offset_16 += CHUNK_UTF16_USAGE * 2;
                    None
                } else {
                    // next_chunk has some u16 not less than 0x80
                    // only store chunk
                    let utf8_packed = _mm_packus_epi16(*chunk, *chunk);
                    // chunk(0043 0021) -> 4321 4321
                    // although store 2byte, length only +1
                    _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_packed);
                    *offset_8 += CHUNK_UTF16_USAGE;
                    *offset_16 += CHUNK_UTF16_USAGE;
                    // keep next_chunk for next step convert
                    Some(next_chunk)
                }
            } else {
                // the last and odd chunk
                let utf8_packed = _mm_packus_epi16(*chunk, *chunk);
                _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_packed);
                *offset_8 += CHUNK_UTF16_USAGE;
                *offset_16 += CHUNK_UTF16_USAGE;
                None
            }
        }

        #[inline]
        unsafe fn one_to_one_two(
            chunk: &__m128i,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            one_byte_bytemask: &__m128i,
            one_byte_bitmask: u16,
        ) {
            // input 16-bit word : [0000|0aaa|aabb|bbbb] x 8
            // expected output   : [110a|aaaa|10bb|bbbb] x 8
            // t0 = [000a|aaaa|bbbb|bb00]
            let t0 = _mm_slli_epi16::<2>(*chunk);
            // t1 = [000a|aaaa|0000|0000]
            let t1 = _mm_and_si128(t0, *M_1F00);
            // t2 = [0000|0000|00bb|bbbb]
            let t2 = _mm_and_si128(*chunk, *M_003F);
            // t3 = [000a|aaaa|00bb|bbbb]
            let t3 = _mm_or_si128(t1, t2);
            // t4 = [110a|aaaa|10bb|bbbb]
            let t4 = _mm_or_si128(t3, *M_C080);
            // _mm_blendv_epi8: if mask[i] {b[i]} else {a[i]}
            // if less than 0x80, use chunk(one byte but has extra 0x00), else use t4(two bytes)
            let utf8_unpacked = _mm_blendv_epi8(t4, *chunk, *one_byte_bytemask);
            // start removing unneeded 0x00
            // one_byte_bitmask = hhggffeeddccbbaa -- the bits are doubled (h - MSB, a - LSB)
            // m0 = 0h0g0f0e0d0c0b0a
            let m0 = one_byte_bitmask & 0x5555;
            // m1 = 00000000h0g0f0e0
            let m1 = m0 >> 7;
            // m2 =         hdgcfbea
            let m2 = ((m0 | m1) & 0xFF) as usize;
            // use table to shuffle
            let row = super::super::PACK_1_2_UTF8_BYTES[m2].as_ptr();
            let len = *row;
            let shuffle_mask = _mm_loadu_si128(row.add(1) as *const __m128i);
            let utf8_packed = _mm_shuffle_epi8(utf8_unpacked, shuffle_mask);
            _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_packed);
            // set real length
            *offset_8 += len as usize;
            *offset_16 += CHUNK_UTF16_USAGE;
        }

        #[inline]
        unsafe fn one_to_one_two_three(
            chunk: &__m128i,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            one_byte_bitmask: u16,
            one_or_two_bytes_bitmask: u16,
            one_or_two_bytes_bytemask: &__m128i,
        ) {
            // case: code units from register produce either 1, 2 or 3 UTF-8 bytes

            // [aaaa|bbbb|bbcc|cccc] => [bbcc|cccc|bbcc|cccc]
            let t0 = _mm_shuffle_epi8(*chunk, *DUP_EVEN);
            // [bbcc|cccc|bbcc|cccc] => [00cc|cccc|0bcc|cccc]
            let t1 = _mm_and_si128(t0, _mm_set1_epi16(0b0011111101111111));
            // [00cc|cccc|0bcc|cccc] => [10cc|cccc|0bcc|cccc]
            let t2 = _mm_or_si128(t1, _mm_set1_epi16(0b1000000000000000u16 as i16));
            // [aaaa|bbbb|bbcc|cccc] =>  [0000|aaaa|bbbb|bbcc]
            let s0 = _mm_srli_epi16(*chunk, 4);
            // [0000|aaaa|bbbb|bbcc] => [0000|aaaa|bbbb|bb00]
            let s1 = _mm_and_si128(s0, _mm_set1_epi16(0b0000111111111100));
            // [0000|aaaa|bbbb|bb00] => [00bb|bbbb|0000|aaaa]
            let s2 = _mm_maddubs_epi16(s1, _mm_set1_epi16(0x0140));
            // [00bb|bbbb|0000|aaaa] => [11bb|bbbb|1110|aaaa]
            let s3 = _mm_or_si128(s2, _mm_set1_epi16(0b1100000011100000u16 as i16));
            let m0 = _mm_andnot_si128(
                *one_or_two_bytes_bytemask,
                _mm_set1_epi16(0b0100000000000000),
            );
            let s4 = _mm_xor_si128(s3, m0);
            // expand code units 16-bit => 32-bit
            let out0 = _mm_unpacklo_epi16(t2, s4);
            let out1 = _mm_unpackhi_epi16(t2, s4);
            // compress 32-bit code units into 1, 2 or 3 bytes -- 2 x shuffle
            let mask = (one_byte_bitmask & 0x5555) | (one_or_two_bytes_bitmask & 0xaaaa);
            if mask == 0 {
                // We only have three-byte code units. Use fast path.
                let shuffle =
                    _mm_setr_epi8(2, 3, 1, 6, 7, 5, 10, 11, 9, 14, 15, 13, -1, -1, -1, -1);
                let utf8_0 = _mm_shuffle_epi8(out0, shuffle);
                let utf8_1 = _mm_shuffle_epi8(out1, shuffle);
                _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_0);
                *offset_8 += 12;
                _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_1);
                *offset_8 += 12;
                *offset_16 += CHUNK_UTF16_USAGE;
                return;
            }
            // we not only have three-byte code units
            let mask0 = mask as u8;
            let row0 = super::super::PACK_1_2_3_UTF8_BYTES[mask0 as usize].as_ptr();
            let len0 = *row0 as usize;
            let shuffle0 = _mm_loadu_si128(row0.add(1) as *const __m128i);
            let utf8_0 = _mm_shuffle_epi8(out0, shuffle0);

            let mask1 = (mask >> 8) as u8;
            let row1 = super::super::PACK_1_2_3_UTF8_BYTES[mask1 as usize].as_ptr();
            let len1 = *row1 as usize;
            let shuffle1 = _mm_loadu_si128(row1.add(1) as *const __m128i);
            let utf8_1 = _mm_shuffle_epi8(out1, shuffle1);

            _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_0);
            *offset_8 += len0;
            _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_1);
            *offset_8 += len1;
            *offset_16 += CHUNK_UTF16_USAGE;
        }

        #[inline]
        unsafe fn call_fallback(
            ptr_16: *const u16,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            len_16: usize,
            is_little_endian: bool,
        ) -> Option<String> {
            // check if one surrogates pair is separated by tow chunk
            let tmp = *ptr_16.add(*offset_16 + CHUNK_UTF16_USAGE - 1);
            let tmp = if is_little_endian != IS_LITTLE_ENDIAN_LOCAL {
                swap_endian(tmp)
            } else {
                tmp
            };
            let mut sub_chunk_len = CHUNK_UTF16_USAGE;
            if (0xD800..=0xDBFF).contains(&tmp) {
                if *offset_16 + CHUNK_UTF16_USAGE < len_16 {
                    sub_chunk_len += 1;
                } else {
                    return Some("Invalid UTF-16 string: missing surrogate pair".to_string());
                }
            }
            let sub_chunk = std::slice::from_raw_parts(ptr_16.add(*offset_16), sub_chunk_len);
            let res = super::super::utf16_to_utf8_fallback(
                sub_chunk,
                ptr_8.add(*offset_8),
                is_little_endian,
            );
            if res.is_err() {
                return Some(res.err().unwrap());
            }
            *offset_8 += res.unwrap();
            *offset_16 += sub_chunk_len;
            None
        }

        /// # Safety
        /// This function is unsafe because it assumes that:
        /// target machine supports sse2, ssse3 and sse4.1.
        #[target_feature(enable = "sse2", enable = "ssse3", enable = "sse4.1")]
        pub unsafe fn utf16_to_utf8(
            utf16: &[u16],
            is_little_endian: bool,
        ) -> Result<String, String> {
            let mut utf8_bytes: Vec<u8> = Vec::with_capacity(utf16.len() * 3);
            let ptr_8 = utf8_bytes.as_mut_ptr();
            let ptr_16 = utf16.as_ptr();
            let mut offset_8 = 0;
            let mut offset_16 = 0;
            let len_16 = utf16.len();
            while offset_16 + CHUNK_UTF16_USAGE <= len_16 {
                let mut chunk = _mm_loadu_si128(ptr_16.add(offset_16) as *const __m128i);

                chunk = if is_little_endian == super::super::IS_LITTLE_ENDIAN_LOCAL {
                    chunk
                } else {
                    _mm_shuffle_epi8(chunk, *ENDIAN_SWAP_MASK)
                };
                // check chunk's all u16 less than 0x80
                if _mm_testz_si128(chunk, *M_FF80) != 0 {
                    let res = one_to_one(
                        &chunk,
                        ptr_16,
                        ptr_8,
                        &mut offset_16,
                        &mut offset_8,
                        len_16,
                        is_little_endian,
                    );
                    if res.is_none() {
                        continue;
                    }
                    chunk = res.unwrap()
                }
                // dealing with all u16 < 0x800, 1 utf16 can convert to 1 or 2 bytes utf8
                // get if each u16 < 0x80
                let one_byte_bytemask = _mm_cmpeq_epi16(_mm_and_si128(chunk, *M_FF80), *M_0000);
                let one_byte_bitmask = _mm_movemask_epi8(one_byte_bytemask) as u16;
                // get if each u16 < 0x800
                let one_or_two_bytes_bytemask =
                    _mm_cmpeq_epi16(_mm_and_si128(chunk, *M_F800), *M_0000);
                let one_or_two_bytes_bitmask = _mm_movemask_epi8(one_or_two_bytes_bytemask) as u16;
                if one_or_two_bytes_bitmask == 0xFFFF {
                    one_to_one_two(
                        &chunk,
                        ptr_8,
                        &mut offset_16,
                        &mut offset_8,
                        &one_byte_bytemask,
                        one_byte_bitmask,
                    );
                    continue;
                }
                // check chunk's all u16 less than 0x800
                // dealing with 1 utf16 can convert to 1 or 2 or 3 bytes utf8
                // get if each u16 not a high surrogate(D800 ~ DBFF,1101_1xxx==dxxx)
                let surrogates_bytemask = _mm_cmpeq_epi16(_mm_and_si128(chunk, *M_F800), *M_D800);
                let surrogates_bitmask = _mm_movemask_epi8(surrogates_bytemask) as u16;
                // no surrogate
                if surrogates_bitmask == 0x0000 {
                    one_to_one_two_three(
                        &chunk,
                        ptr_8,
                        &mut offset_16,
                        &mut offset_8,
                        one_byte_bitmask,
                        one_or_two_bytes_bitmask,
                        &one_or_two_bytes_bytemask,
                    );
                    continue;
                }
                // have surrogate, use fallback algorithm
                let res = call_fallback(
                    ptr_16,
                    ptr_8,
                    &mut offset_16,
                    &mut offset_8,
                    len_16,
                    is_little_endian,
                );
                if let Some(err_msg) = res {
                    return Err(err_msg);
                }
            }
            // dealing with remaining u16 not enough to form a chunk.
            if offset_16 < len_16 {
                let suffix_utf16 =
                    std::slice::from_raw_parts(ptr_16.add(offset_16), len_16 - offset_16);
                let res = super::super::utf16_to_utf8_fallback(
                    suffix_utf16,
                    ptr_8.add(offset_8),
                    is_little_endian,
                );
                if res.is_err() {
                    return Err(res.err().unwrap());
                }
                offset_8 += res.unwrap();
            }
            utf8_bytes.set_len(offset_8);
            Ok(String::from_utf8(utf8_bytes).unwrap())
        }
    }

    pub mod avx {
        use super::super::{swap_endian, IS_LITTLE_ENDIAN_LOCAL};
        use lazy_static::lazy_static;
        use std::arch::x86_64::{
            __m128i, __m256i, _mm256_and_si256, _mm256_andnot_si256, _mm256_blendv_epi8,
            _mm256_castsi256_si128, _mm256_cmpeq_epi16, _mm256_extractf128_si256,
            _mm256_loadu_si256, _mm256_maddubs_epi16, _mm256_movemask_epi8, _mm256_or_si256,
            _mm256_set1_epi16, _mm256_setr_epi16, _mm256_setr_epi8, _mm256_setr_m128i,
            _mm256_setzero_si256, _mm256_shuffle_epi8, _mm256_slli_epi16, _mm256_srli_epi16,
            _mm256_testz_si256, _mm256_unpackhi_epi16, _mm256_unpacklo_epi16, _mm256_xor_si256,
            _mm_loadu_si128, _mm_packus_epi16, _mm_shuffle_epi8, _mm_storeu_si128,
        };

        #[allow(dead_code)]
        fn print_m256i(var: __m256i) {
            let mut val = [0i16; 16];
            unsafe {
                _mm_storeu_si128(
                    val.as_mut_ptr() as *mut __m128i,
                    _mm256_castsi256_si128(var),
                );
                _mm_storeu_si128(
                    (val.as_mut_ptr() as *mut __m128i).add(1),
                    _mm256_extractf128_si256(var, 1),
                );
            }
            println!(
                "m256i: {:?}",
                val.iter()
                    .map(|&v| format!("{:04X}", v))
                    // .map(|&v| format!("{:016b}", v))
                    .collect::<Vec<_>>()
            );
        }

        // 256/16=16
        const CHUNK_UTF16_USAGE: usize = 16;
        lazy_static! {
            // shuffle mask for swap endian
            // [0,1,2,3,4,5] -> [1,0,3,2,5,4]
            static ref ENDIAN_SWAP_MASK: __m256i = unsafe {_mm256_setr_epi8(
               1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 17, 16, 19, 18, 21, 20, 23,
               22, 25, 24, 27, 26, 29, 28, 31, 30
            )};


             static ref M_FF80:__m256i = unsafe {_mm256_set1_epi16(0xFF80u16 as i16)};
             static ref M_F800:__m256i = unsafe {_mm256_set1_epi16(0xF800u16 as i16)};
             static ref M_D800:__m256i = unsafe {_mm256_set1_epi16(0xD800u16 as i16)};
             static ref M_0000:__m256i = unsafe {_mm256_setzero_si256()};

            static ref DUP_EVEN:__m256i = unsafe {_mm256_setr_epi16(
                0x0000, 0x0202, 0x0404, 0x0606, 0x0808, 0x0a0a, 0x0c0c, 0x0e0e, 0x0000, 0x0202,
                0x0404, 0x0606, 0x0808, 0x0a0a, 0x0c0c, 0x0e0e,
            )};


            // 0b1100_0000_1000_0000
            static ref M_C080:__m256i = unsafe {_mm256_set1_epi16(0xC080u16 as i16)};
            // 0b0001_1111_0000_0000
            static ref M_1F00:__m256i = unsafe {_mm256_set1_epi16(0x1F00)};
            // 0b0000_0000_0011_1111
            static ref M_003F:__m256i = unsafe {_mm256_set1_epi16(0x003F)};
        }

        #[inline]
        unsafe fn one_to_one_two(
            chunk: &__m256i,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            one_byte_bytemask: &__m256i,
            one_byte_bitmask: u32,
        ) {
            // input 16-bit word : [0000|0aaa|aabb|bbbb] x 8
            // expected output   : [110a|aaaa|10bb|bbbb] x 8
            // t0 = [000a|aaaa|bbbb|bb00]
            let t0 = _mm256_slli_epi16::<2>(*chunk);
            // t1 = [000a|aaaa|0000|0000]
            let t1 = _mm256_and_si256(t0, *M_1F00);
            // t2 = [0000|0000|00bb|bbbb]
            let t2 = _mm256_and_si256(*chunk, *M_003F);
            // t3 = [000a|aaaa|00bb|bbbb]
            let t3 = _mm256_or_si256(t1, t2);
            // t4 = [110a|aaaa|10bb|bbbb]
            let t4 = _mm256_or_si256(t3, *M_C080);
            // _mm_blendv_epi8: if mask[i] {b[i]} else {a[i]}
            // if less than 0x80, use chunk(one byte but has extra 0x00), else use t4(two bytes)
            let utf8_unpacked = _mm256_blendv_epi8(t4, *chunk, *one_byte_bytemask);

            // start removing unneeded 0x00
            // one_byte_bitmask = hhggffeeddccbbaa -- the bits are doubled (h - MSB, a - LSB)
            // m0 = 0h0g0f0e0d0c0b0a
            let m0 = one_byte_bitmask & 0x5555_5555;
            // m1 = 00000000h0g0f0e0
            let m1 = m0 >> 7;
            // m2 =         hdgcfbea
            let m2 = (m1 | m0) & 0x00FF_00FF;
            // use table to shuffle
            let row = super::super::PACK_1_2_UTF8_BYTES[m2 as u8 as usize].as_ptr();
            let row_2 = super::super::PACK_1_2_UTF8_BYTES[(m2 >> 16) as u8 as usize].as_ptr();
            let len = *row;
            let len_2 = *row_2;
            let shuffle_mask = _mm_loadu_si128(row.add(1) as *const __m128i);
            let shuffle_mask_2 = _mm_loadu_si128(row_2.add(1) as *const __m128i);
            let utf8_packed = _mm256_shuffle_epi8(
                utf8_unpacked,
                _mm256_setr_m128i(shuffle_mask, shuffle_mask_2),
            );
            _mm_storeu_si128(
                ptr_8.add(*offset_8) as *mut __m128i,
                _mm256_castsi256_si128(utf8_packed),
            );
            *offset_8 += len as usize;
            _mm_storeu_si128(
                ptr_8.add(*offset_8) as *mut __m128i,
                _mm256_extractf128_si256(utf8_packed, 1),
            );
            // set real length
            *offset_8 += len_2 as usize;
            *offset_16 += CHUNK_UTF16_USAGE;
        }

        #[inline]
        unsafe fn one_to_one_two_three(
            chunk: &__m256i,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            one_byte_bitmask: u32,
            one_or_two_bytes_bitmask: u32,
            one_or_two_bytes_bytemask: &__m256i,
        ) {
            // case: code units from register produce either 1, 2 or 3 UTF-8 bytes

            /* In this branch we handle three cases:
            1. [0000|0000|0ccc|cccc] => [0ccc|cccc]                           - single UFT-8 byte
            2. [0000|0bbb|bbcc|cccc] => [110b|bbbb], [10cc|cccc]              - two UTF-8 bytes
            3. [aaaa|bbbb|bbcc|cccc] => [1110|aaaa], [10bb|bbbb], [10cc|cccc] - three UTF-8 bytes

            We expand the input word (16-bit) into two code units (32-bit), thus
            we have room for four bytes. However, we need five distinct bit
            layouts. Note that the last byte in cases #2 and #3 is the same.

            We precompute byte 1 for case #1 and the common byte for cases #2 & #3
            in register t2.

            We precompute byte 1 for case #3 and -- **conditionally** -- precompute
            either byte 1 for case #2 or byte 2 for case #3. Note that they
            differ by exactly one bit.

            Finally from these two code units we build proper UTF-8 sequence, taking
            into account the case (i.e, the number of bytes to write).
            */

            // Given [aaaa|bbbb|bbcc|cccc] our goal is to produce:
            // t2 => [0ccc|cccc] [10cc|cccc]
            // s4 => [1110|aaaa] ([110b|bbbb] OR [10bb|bbbb])
            // [aaaa|bbbb|bbcc|cccc] => [bbcc|cccc|bbcc|cccc]
            let t0 = _mm256_shuffle_epi8(*chunk, *DUP_EVEN);
            // [bbcc|cccc|bbcc|cccc] => [00cc|cccc|0bcc|cccc]
            let t1 = _mm256_and_si256(t0, _mm256_set1_epi16(0b0011111101111111));
            // [00cc|cccc|0bcc|cccc] => [10cc|cccc|0bcc|cccc]
            let t2 = _mm256_or_si256(t1, _mm256_set1_epi16(0b1000000000000000u16 as i16));
            // [aaaa|bbbb|bbcc|cccc] =>  [0000|aaaa|bbbb|bbcc]
            let s0 = _mm256_srli_epi16(*chunk, 4);
            // [0000|aaaa|bbbb|bbcc] => [0000|aaaa|bbbb|bb00]
            let s1 = _mm256_and_si256(s0, _mm256_set1_epi16(0b0000111111111100));
            // [0000|aaaa|bbbb|bb00] => [00bb|bbbb|0000|aaaa]
            let s2 = _mm256_maddubs_epi16(s1, _mm256_set1_epi16(0x0140));
            // [00bb|bbbb|0000|aaaa] => [11bb|bbbb|1110|aaaa]
            let s3 = _mm256_or_si256(s2, _mm256_set1_epi16(0b1100000011100000u16 as i16));
            let m0 = _mm256_andnot_si256(
                *one_or_two_bytes_bytemask,
                _mm256_set1_epi16(0b0100000000000000),
            );
            let s4 = _mm256_xor_si256(s3, m0);

            // 4. expand code units 16-bit => 32-bit
            let out0 = _mm256_unpacklo_epi16(t2, s4);
            let out1 = _mm256_unpackhi_epi16(t2, s4);

            // 5. compress 32-bit code units into 1, 2 or 3 bytes -- 2 x shuffle
            let mask = (one_byte_bitmask & 0x55555555) | (one_or_two_bytes_bitmask & 0xaaaaaaaa);

            let mask0 = mask as u8;
            let row0 = super::super::PACK_1_2_3_UTF8_BYTES[mask0 as usize].as_ptr();
            let len0 = *row0;
            let shuffle0 = _mm_loadu_si128(row0.add(1) as *const __m128i);
            let utf8_0 = _mm_shuffle_epi8(_mm256_castsi256_si128(out0), shuffle0);

            let mask1 = (mask >> 8) as u8;
            let row1 = super::super::PACK_1_2_3_UTF8_BYTES[mask1 as usize].as_ptr();
            let len1 = *row1;
            let shuffle1 = _mm_loadu_si128(row1.add(1) as *const __m128i);
            let utf8_1 = _mm_shuffle_epi8(_mm256_castsi256_si128(out1), shuffle1);

            let mask2 = (mask >> 16) as u8;
            let row2 = super::super::PACK_1_2_3_UTF8_BYTES[mask2 as usize].as_ptr();
            let len2 = *row2;
            let shuffle2 = _mm_loadu_si128(row2.add(1) as *const __m128i);
            let utf8_2 = _mm_shuffle_epi8(_mm256_extractf128_si256(out0, 1), shuffle2);

            let mask3 = (mask >> 24) as u8;
            let row3 = super::super::PACK_1_2_3_UTF8_BYTES[mask3 as usize].as_ptr();
            let len3 = *row3;
            let shuffle3 = _mm_loadu_si128(row3.add(1) as *const __m128i);
            let utf8_3 = _mm_shuffle_epi8(_mm256_extractf128_si256(out1, 1), shuffle3);

            _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_0);
            *offset_8 += len0 as usize;
            _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_1);
            *offset_8 += len1 as usize;
            _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_2);
            *offset_8 += len2 as usize;
            _mm_storeu_si128(ptr_8.add(*offset_8) as *mut __m128i, utf8_3);
            *offset_8 += len3 as usize;
            *offset_16 += CHUNK_UTF16_USAGE;
        }

        #[inline]
        unsafe fn call_fallback(
            ptr_16: *const u16,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            len_16: usize,
            is_little_endian: bool,
        ) -> Option<String> {
            // 3 bytes or 4 bytes case, use fallback algorithm
            // check if one surrogates pair is separated by tow chunk
            let tmp = *ptr_16.add(*offset_16 + CHUNK_UTF16_USAGE - 1);
            let tmp = if is_little_endian != IS_LITTLE_ENDIAN_LOCAL {
                swap_endian(tmp)
            } else {
                tmp
            };
            let mut sub_chunk_len = CHUNK_UTF16_USAGE;
            if (0xD800..=0xDBFF).contains(&tmp) {
                if *offset_16 + CHUNK_UTF16_USAGE < len_16 {
                    sub_chunk_len += 1;
                } else {
                    return Some("Invalid UTF-16 string: missing surrogate pair".to_string());
                }
            }
            let sub_chunk = std::slice::from_raw_parts(ptr_16.add(*offset_16), sub_chunk_len);
            let res = super::super::utf16_to_utf8_fallback(
                sub_chunk,
                ptr_8.add(*offset_8),
                is_little_endian,
            );
            if res.is_err() {
                return Some(res.err().unwrap());
            }
            *offset_8 += res.unwrap();
            *offset_16 += sub_chunk_len;
            None
        }

        /// # Safety
        /// This function is unsafe because it assumes that:
        /// target machine supports avx, avx2 and sse2.
        #[target_feature(enable = "avx", enable = "avx2", enable = "sse2")]
        pub unsafe fn utf16_to_utf8(
            utf16: &[u16],
            is_little_endian: bool,
        ) -> Result<String, String> {
            let mut utf8_bytes: Vec<u8> = Vec::with_capacity(utf16.len() * 3);
            let ptr_8 = utf8_bytes.as_mut_ptr();
            let ptr_16 = utf16.as_ptr();
            let mut offset_8 = 0;
            let mut offset_16 = 0;
            let len_16 = utf16.len();
            while offset_16 + CHUNK_UTF16_USAGE <= len_16 {
                let mut chunk = _mm256_loadu_si256(ptr_16.add(offset_16) as *const __m256i);
                chunk = if is_little_endian == super::super::IS_LITTLE_ENDIAN_LOCAL {
                    chunk
                } else {
                    _mm256_shuffle_epi8(chunk, *ENDIAN_SWAP_MASK)
                };
                // check chunk's all u16 less than 0x80
                if _mm256_testz_si256(chunk, *M_FF80) != 0 {
                    let utf8_packed = _mm_packus_epi16(
                        _mm256_castsi256_si128(chunk),
                        _mm256_extractf128_si256(chunk, 1),
                    );
                    _mm_storeu_si128(ptr_8.add(offset_8) as *mut __m128i, utf8_packed);
                    offset_8 += CHUNK_UTF16_USAGE;
                    offset_16 += CHUNK_UTF16_USAGE;
                    continue;
                }
                // get if each u16 < 0x80
                let one_byte_bytemask =
                    _mm256_cmpeq_epi16(_mm256_and_si256(chunk, *M_FF80), *M_0000);
                let one_byte_bitmask = _mm256_movemask_epi8(one_byte_bytemask) as u32;
                // get if each u16 < 0x800
                let one_or_two_bytes_bytemask =
                    _mm256_cmpeq_epi16(_mm256_and_si256(chunk, *M_F800), *M_0000);
                let one_or_two_bytes_bitmask =
                    _mm256_movemask_epi8(one_or_two_bytes_bytemask) as u32;
                // dealing with all u16 < 0x800, 1 utf16 can convert to 1 or 2 bytes utf8
                if one_or_two_bytes_bitmask == 0xFFFF_FFFF {
                    one_to_one_two(
                        &chunk,
                        ptr_8,
                        &mut offset_16,
                        &mut offset_8,
                        &one_byte_bytemask,
                        one_byte_bitmask,
                    );
                    continue;
                }
                // dealing with no surrogate, 1 utf16 can convert to 1 or 2 or three bytes utf8
                // 1. Check if there are any surrogate word in the input chunk.
                //    We have also deal with situation when there is a surrogate word
                //    at the end of a chunk.
                let surrogates_bytemask =
                    _mm256_cmpeq_epi16(_mm256_and_si256(chunk, *M_F800), *M_D800);
                // bitmask = 0x0000 if there are no surrogates
                //         = 0xc000 if the last word is a surrogate
                let surrogates_bitmask = _mm256_movemask_epi8(surrogates_bytemask);
                // It might seem like checking for surrogates_bitmask == 0xc000 could help. However,
                // it is likely an uncommon occurrence.
                if surrogates_bitmask == 0x00000000 {
                    one_to_one_two_three(
                        &chunk,
                        ptr_8,
                        &mut offset_16,
                        &mut offset_8,
                        one_byte_bitmask,
                        one_or_two_bytes_bitmask,
                        &one_or_two_bytes_bytemask,
                    );
                    continue;
                }
                let res = call_fallback(
                    ptr_16,
                    ptr_8,
                    &mut offset_16,
                    &mut offset_8,
                    len_16,
                    is_little_endian,
                );
                if let Some(err_msg) = res {
                    return Err(err_msg);
                }
            }
            // dealing with remaining u16 not enough to form a chunk.
            if offset_16 < len_16 {
                let suffix_utf16 =
                    std::slice::from_raw_parts(ptr_16.add(offset_16), len_16 - offset_16);
                let res = super::super::utf16_to_utf8_fallback(
                    suffix_utf16,
                    ptr_8.add(offset_8),
                    is_little_endian,
                );
                if res.is_err() {
                    return Err(res.err().unwrap());
                }
                offset_8 += res.unwrap();
            }
            utf8_bytes.set_len(offset_8);
            Ok(String::from_utf8(utf8_bytes).unwrap())
        }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod utf16_to_utf8_simd {
    pub mod neon {
        use super::super::{swap_endian, IS_LITTLE_ENDIAN_LOCAL};
        use lazy_static::lazy_static;
        use std::arch::aarch64::{
            uint16x8_t, vaddvq_u16, vandq_u16, vbicq_u16, vbslq_u16, vceqq_u16, vcleq_u16,
            veorq_u16, vld1q_u16, vld1q_u8, vmaxvq_u16, vmovn_high_u16, vmovn_u16, vmovq_n_u16,
            vorrq_u16, vqtbl1q_u8, vreinterpretq_u16_u8, vreinterpretq_u8_u16, vrev16q_u8,
            vshlq_n_u16, vshrq_n_u16, vst1_u8, vst1q_u8, vzip1q_u16, vzip2q_u16,
        };

        // 128/16=8
        const CHUNK_UTF16_USAGE: usize = 8;
        lazy_static! {
            static ref M_FF80: uint16x8_t = unsafe { vmovq_n_u16(0xFF80) };
            static ref M_F800: uint16x8_t = unsafe { vmovq_n_u16(0xF800) };
            static ref M_D800: uint16x8_t = unsafe { vmovq_n_u16(0xD800) };
            static ref M_1F00: uint16x8_t = unsafe { vmovq_n_u16(0x1F00) };
            static ref M_003F: uint16x8_t = unsafe { vmovq_n_u16(0x003F) };
            static ref M_C080: uint16x8_t = unsafe { vmovq_n_u16(0xC080) };
            static ref M_007F: uint16x8_t = unsafe { vmovq_n_u16(0x007F) };
            static ref DUP_EVEN: uint16x8_t = unsafe {
                vld1q_u16(
                    [
                        0x0000, 0x0202, 0x0404, 0x0606, 0x0808, 0x0a0a, 0x0c0c, 0x0e0e,
                    ]
                    .as_ptr(),
                )
            };
            static ref ONE_MASK: uint16x8_t = unsafe {
                vld1q_u16(
                    [
                        0x0001, 0x0004, 0x0010, 0x0040, 0x0100, 0x0400, 0x1000, 0x4000,
                    ]
                    .as_ptr(),
                )
            };
            static ref TWO_MASK: uint16x8_t = unsafe {
                vld1q_u16(
                    [
                        0x0002, 0x0008, 0x0020, 0x0080, 0x0200, 0x0800, 0x2000, 0x8000,
                    ]
                    .as_ptr(),
                )
            };
        }

        #[inline]
        unsafe fn one_to_one(
            chunk: &uint16x8_t,
            ptr_16: *const u16,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            len_16: usize,
            is_little_endian: bool,
        ) -> Option<uint16x8_t> {
            if *offset_16 + 2 * CHUNK_UTF16_USAGE <= len_16 {
                let next_chunk = vld1q_u16(ptr_16.add(*offset_16 + CHUNK_UTF16_USAGE));
                let next_chunk = if is_little_endian == super::super::IS_LITTLE_ENDIAN_LOCAL {
                    next_chunk
                } else {
                    vreinterpretq_u16_u8(vrev16q_u8(vreinterpretq_u8_u16(next_chunk)))
                };
                // check next_chunk's all u16 less than 0x80
                if vmaxvq_u16(next_chunk) <= 0x7F {
                    // store chunk and next_chunk
                    let utf8_packed = vmovn_high_u16(vmovn_u16(*chunk), next_chunk);
                    vst1q_u8(ptr_8.add(*offset_8), utf8_packed);
                    *offset_8 += CHUNK_UTF16_USAGE * 2;
                    *offset_16 += CHUNK_UTF16_USAGE * 2;
                    None
                } else {
                    // next_chunk has some u16 not less than 0x80
                    // only store chunk
                    let utf8_packed = vmovn_u16(*chunk);
                    vst1_u8(ptr_8.add(*offset_8), utf8_packed);
                    *offset_8 += CHUNK_UTF16_USAGE;
                    *offset_16 += CHUNK_UTF16_USAGE;
                    // keep next_chunk for next step convert
                    Some(next_chunk)
                }
            } else {
                // the last and odd chunk
                let utf8_packed = vmovn_u16(*chunk);
                vst1_u8(ptr_8.add(*offset_8), utf8_packed);
                *offset_8 += CHUNK_UTF16_USAGE;
                *offset_16 += CHUNK_UTF16_USAGE;
                // keep next_chunk for next step convert
                None
            }
        }

        #[inline]
        unsafe fn one_to_one_two(
            chunk: &uint16x8_t,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            one_byte_bytemask: &uint16x8_t,
        ) {
            // input 16-bit word : [0000|0aaa|aabb|bbbb] x 8
            // expected output   : [110a|aaaa|10bb|bbbb] x 8
            // t0 = [000a|aaaa|bbbb|bb00]
            let t0 = vshlq_n_u16::<2>(*chunk);
            // t1 = [000a|aaaa|0000|0000]
            let t1 = vandq_u16(t0, *M_1F00);
            // t2 = [0000|0000|00bb|bbbb]
            let t2 = vandq_u16(*chunk, *M_003F);
            // t3 = [000a|aaaa|00bb|bbbb]
            let t3 = vorrq_u16(t1, t2);
            // t4 = [110a|aaaa|10bb|bbbb]
            let t4 = vorrq_u16(t3, *M_C080);

            let utf8_unpacked = vreinterpretq_u8_u16(vbslq_u16(*one_byte_bytemask, *chunk, t4));

            let mask: [u16; 8] = [
                0x0001, 0x0004, 0x0010, 0x0040, 0x0002, 0x0008, 0x0020, 0x0080,
            ];
            let mask = vld1q_u16(mask.as_ptr());
            let m2 = vaddvq_u16(vandq_u16(*one_byte_bytemask, mask)) as usize;
            // use table to shuffle
            let row = super::super::PACK_1_2_UTF8_BYTES[m2].as_ptr();
            let len = *row;
            let shuffle_mask = vld1q_u8(row.add(1));
            let utf8_packed = vqtbl1q_u8(utf8_unpacked, shuffle_mask);
            vst1q_u8(ptr_8.add(*offset_8), utf8_packed);
            // set real length
            *offset_8 += len as usize;
            *offset_16 += CHUNK_UTF16_USAGE;
        }

        #[inline]
        unsafe fn one_to_one_two_three(
            chunk: &uint16x8_t,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
        ) {
            // case: code units from register produce either 1, 2 or 3 UTF-8 bytes

            /* In this branch we handle three cases:
            1. [0000|0000|0ccc|cccc] => [0ccc|cccc]                           - single UFT-8 byte
            2. [0000|0bbb|bbcc|cccc] => [110b|bbbb], [10cc|cccc]              - two UTF-8 bytes
            3. [aaaa|bbbb|bbcc|cccc] => [1110|aaaa], [10bb|bbbb], [10cc|cccc] - three UTF-8 bytes

            We expand the input word (16-bit) into two code units (32-bit), thus
            we have room for four bytes. However, we need five distinct bit
            layouts. Note that the last byte in cases #2 and #3 is the same.

            We precompute byte 1 for case #1 and the common byte for cases #2 & #3
            in register t2.

            We precompute byte 1 for case #3 and -- **conditionally** -- precompute
            either byte 1 for case #2 or byte 2 for case #3. Note that they
            differ by exactly one bit.

            Finally from these two code units we build proper UTF-8 sequence, taking
            into account the case (i.e, the number of bytes to write).
            */

            // Given [aaaa|bbbb|bbcc|cccc] our goal is to produce:
            // t2 => [0ccc|cccc] [10cc|cccc]
            // s4 => [1110|aaaa] ([110b|bbbb] OR [10bb|bbbb])
            // [aaaa|bbbb|bbcc|cccc] => [bbcc|cccc|bbcc|cccc]
            let t0 = vreinterpretq_u16_u8(vqtbl1q_u8(
                vreinterpretq_u8_u16(*chunk),
                vreinterpretq_u8_u16(*DUP_EVEN),
            ));
            // [bbcc|cccc|bbcc|cccc] => [00cc|cccc|0bcc|cccc]
            let t1 = vandq_u16(t0, vmovq_n_u16(0b0011111101111111));
            // [00cc|cccc|0bcc|cccc] => [10cc|cccc|0bcc|cccc]
            let t2 = vorrq_u16(t1, vmovq_n_u16(0b1000000000000000));

            // s0: [aaaa|bbbb|bbcc|cccc] => [0000|0000|0000|aaaa]
            let s0 = vshrq_n_u16(*chunk, 12);
            // s1: [aaaa|bbbb|bbcc|cccc] => [0000|bbbb|bb00|0000]
            let s1 = vandq_u16(*chunk, vmovq_n_u16(0b0000111111000000));
            // [0000|bbbb|bb00|0000] => [00bb|bbbb|0000|0000]
            let s1s = vshlq_n_u16(s1, 2);
            // [00bb|bbbb|0000|aaaa]
            let s2 = vorrq_u16(s0, s1s);
            // s3: [00bb|bbbb|0000|aaaa] => [11bb|bbbb|1110|aaaa]
            let s3 = vorrq_u16(s2, vmovq_n_u16(0b1100000011100000));
            let v_07ff = vmovq_n_u16(0x07FF_u16);
            let one_or_two_bytes_bytemask = vcleq_u16(*chunk, v_07ff);
            let m0 = vbicq_u16(vmovq_n_u16(0b0100000000000000), one_or_two_bytes_bytemask);
            let s4 = veorq_u16(s3, m0);

            // 4. expand code units 16-bit => 32-bit
            let out0 = vreinterpretq_u8_u16(vzip1q_u16(t2, s4));
            let out1 = vreinterpretq_u8_u16(vzip2q_u16(t2, s4));

            // 5. compress 32-bit code units into 1, 2 or 3 bytes -- 2 x shuffle
            let v_007f = vmovq_n_u16(0x007F);
            let one_byte_bytemask = vcleq_u16(*chunk, v_007f);

            let combined = vorrq_u16(
                vandq_u16(one_byte_bytemask, *ONE_MASK),
                vandq_u16(one_or_two_bytes_bytemask, *TWO_MASK),
            );
            let mask = vaddvq_u16(combined);
            let mask0 = mask as u8;
            let row0 = super::super::PACK_1_2_3_UTF8_BYTES[mask0 as usize].as_ptr();
            let len0 = *row0;
            let shuffle0 = vld1q_u8(row0.add(1));
            let utf8_0 = vqtbl1q_u8(out0, shuffle0);

            let mask1 = (mask >> 8) as u8;
            let row1 = super::super::PACK_1_2_3_UTF8_BYTES[mask1 as usize].as_ptr();
            let len1 = *row1;
            let shuffle1 = vld1q_u8(row1.add(1));
            let utf8_1 = vqtbl1q_u8(out1, shuffle1);

            vst1q_u8(ptr_8.add(*offset_8), utf8_0);
            *offset_8 += len0 as usize;
            vst1q_u8(ptr_8.add(*offset_8), utf8_1);
            *offset_8 += len1 as usize;

            *offset_16 += CHUNK_UTF16_USAGE;
        }

        #[inline]
        unsafe fn call_fallback(
            ptr_16: *const u16,
            ptr_8: *mut u8,
            offset_16: &mut usize,
            offset_8: &mut usize,
            len_16: usize,
            is_little_endian: bool,
        ) -> Option<String> {
            // check if one surrogates pair is separated by tow chunk
            let tmp = *ptr_16.add(*offset_16 + CHUNK_UTF16_USAGE - 1);
            let tmp = if is_little_endian != IS_LITTLE_ENDIAN_LOCAL {
                swap_endian(tmp)
            } else {
                tmp
            };
            let mut sub_chunk_len = CHUNK_UTF16_USAGE;
            if (0xD800..=0xDBFF).contains(&tmp) {
                if *offset_16 + CHUNK_UTF16_USAGE < len_16 {
                    sub_chunk_len += 1;
                } else {
                    return Some("Invalid UTF-16 string: missing surrogate pair".to_string());
                }
            }
            let sub_chunk = std::slice::from_raw_parts(ptr_16.add(*offset_16), sub_chunk_len);
            let res = super::super::utf16_to_utf8_fallback(
                sub_chunk,
                ptr_8.add(*offset_8),
                is_little_endian,
            );
            if res.is_err() {
                return Some(res.err().unwrap());
            }
            *offset_8 += res.unwrap();
            *offset_16 += sub_chunk_len;
            None
        }

        /// # Safety
        /// This function is unsafe because it assumes that:
        /// target machine supports neon.
        #[target_feature(enable = "neon")]
        pub unsafe fn utf16_to_utf8(
            utf16: &[u16],
            is_little_endian: bool,
        ) -> Result<String, String> {
            let mut utf8_bytes: Vec<u8> = Vec::with_capacity(utf16.len() * 3);
            let ptr_8 = utf8_bytes.as_mut_ptr();
            let ptr_16 = utf16.as_ptr();
            let mut offset_8 = 0;
            let mut offset_16 = 0;
            let len_16 = utf16.len();

            while offset_16 + CHUNK_UTF16_USAGE <= len_16 {
                let mut chunk = vld1q_u16(ptr_16.add(offset_16));
                chunk = if is_little_endian == super::super::IS_LITTLE_ENDIAN_LOCAL {
                    chunk
                } else {
                    vreinterpretq_u16_u8(vrev16q_u8(vreinterpretq_u8_u16(chunk)))
                };
                // check chunk's all u16 less than 0x80
                if vmaxvq_u16(chunk) <= 0x7F {
                    let res = one_to_one(
                        &chunk,
                        ptr_16,
                        ptr_8,
                        &mut offset_16,
                        &mut offset_8,
                        len_16,
                        is_little_endian,
                    );
                    if res.is_none() {
                        continue;
                    }
                    chunk = res.unwrap();
                }
                // check chunk's all u16 less than 0x800
                if vmaxvq_u16(chunk) <= 0x7FF {
                    let one_byte_bytemask = vcleq_u16(chunk, *M_007F);
                    one_to_one_two(
                        &chunk,
                        ptr_8,
                        &mut offset_16,
                        &mut offset_8,
                        &one_byte_bytemask,
                    );
                    continue;
                }
                let surrogates_bytemask = vceqq_u16(vandq_u16(chunk, *M_F800), *M_D800);
                // It might seem like checking for surrogates_bitmask == 0xc000 could help. However,
                // it is likely an uncommon occurrence.
                if vmaxvq_u16(surrogates_bytemask) == 0 {
                    one_to_one_two_three(&chunk, ptr_8, &mut offset_16, &mut offset_8);
                    continue;
                }
                // have surrogates
                let res = call_fallback(
                    ptr_16,
                    ptr_8,
                    &mut offset_16,
                    &mut offset_8,
                    len_16,
                    is_little_endian,
                );
                if let Some(err_msg) = res {
                    return Err(err_msg);
                }
            }
            // dealing with remaining u16 not enough to form a chunk.
            if offset_16 < len_16 {
                let suffix_utf16 =
                    std::slice::from_raw_parts(ptr_16.add(offset_16), len_16 - offset_16);
                let res = super::super::utf16_to_utf8_fallback(
                    suffix_utf16,
                    ptr_8.add(offset_8),
                    is_little_endian,
                );
                if res.is_err() {
                    return Err(res.err().unwrap());
                }
                offset_8 += res.unwrap();
            }
            utf8_bytes.set_len(offset_8);
            Ok(String::from_utf8(utf8_bytes).unwrap())
        }
    }
}

// fallback algorithm, convert utf16 to utf8 one by one
pub unsafe fn utf16_to_utf8_fallback(
    utf16: &[u16],
    utf8_ptr: *mut u8,
    is_little_endian: bool,
) -> Result<usize, String> {
    let mut offset = 0;
    let mut iter = utf16.iter();
    while let Some(&wc) = iter.next() {
        let wc = if is_little_endian == IS_LITTLE_ENDIAN_LOCAL {
            wc
        } else {
            swap_endian(wc)
        };
        match wc {
            code_point if code_point & 0xFF80 == 0 => {
                // 1-byte UTF-8
                // [0000|0000|0ccc|cccc] => [0ccc|cccc]
                unsafe {
                    utf8_ptr.add(offset).write(code_point as u8);
                }
                offset += 1;
            }
            code_point if code_point & 0xF800 == 0 => {
                // 2-byte UTF-8
                // [0000|0bbb|bbcc|cccc] => [110|bbbbb], [10|cccccc]
                let bytes = [
                    (code_point >> 6 & 0b1_1111) as u8 | 0b1100_0000,
                    (code_point & 0b11_1111) as u8 | 0b1000_0000,
                ];
                unsafe {
                    ptr::copy_nonoverlapping(bytes.as_ptr(), utf8_ptr.add(offset), 2);
                }
                offset += 2;
            }
            code_point if code_point & 0xF800 != 0xD800 => {
                // 3-byte UTF-8, 1 u16 -> 3 u8
                // [aaaa|bbbb|bbcc|cccc] => [1110|aaaa], [10|bbbbbb], [10|cccccc]
                // Need 16 bit suffix of wc, as same as wc itself
                let bytes = [
                    (wc >> 12 | 0b1110_0000) as u8,
                    (wc >> 6 & 0b11_1111) as u8 | 0b1000_0000,
                    (wc & 0b11_1111) as u8 | 0b1000_0000,
                ];
                unsafe {
                    ptr::copy_nonoverlapping(bytes.as_ptr(), utf8_ptr.add(offset), 3);
                }
                offset += 3;
            }
            wc1 if (0xD800..=0xDBFF).contains(&wc1) => {
                // Surrogate pair (4-byte UTF-8)
                // Need extra u16, 2 u16 -> 4 u8
                if let Some(&wc2) = iter.next() {
                    let wc2 = if is_little_endian == IS_LITTLE_ENDIAN_LOCAL {
                        wc2
                    } else {
                        swap_endian(wc2)
                    };
                    if !(0xDC00..=0xDFFF).contains(&wc2) {
                        return Err("Invalid UTF-16 string: wrong surrogate pair".to_string());
                    }
                    // utf16 to unicode
                    let code_point =
                        ((((wc1 as u32) - 0xD800) << 10) | ((wc2 as u32) - 0xDC00)) + 0x10000;
                    // 11110??? 10?????? 10?????? 10??????
                    // Need 21 bit suffix of code_point
                    let bytes = [
                        (code_point >> 18 & 0b111) as u8 | 0b1111_0000,
                        (code_point >> 12 & 0b11_1111) as u8 | 0b1000_0000,
                        (code_point >> 6 & 0b11_1111) as u8 | 0b1000_0000,
                        (code_point & 0b11_1111) as u8 | 0b1000_0000,
                    ];
                    unsafe {
                        ptr::copy_nonoverlapping(bytes.as_ptr(), utf8_ptr.add(offset), 4);
                    }
                    offset += 4;
                } else {
                    return Err("Invalid UTF-16 string: missing surrogate pair".to_string());
                }
            }
            _ => unreachable!(),
        }
    }
    Ok(offset)
}

// shuffle mask, first byte for final length, other 16 bytes for mask
// used when converting one utf16 to one or two utf8, remove unneeded 0x00 in generating one byte utf8
// example: [utf16_a,utf16_b] -> [0x00,utf8_a1,utf8_b1,utf8_b2](cur_len=4) --shuffle on mask--> [utf8_a1,utf8_b1,utf8_b2,xxx](final_len=3)
const PACK_1_2_UTF8_BYTES: [[u8; 17]; 256] = [
    [16, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14],
    [15, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80],
    [15, 1, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80],
    [
        14, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [15, 1, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80],
    [
        14, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        14, 1, 0, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [15, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80],
    [
        14, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 5, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 5, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [15, 1, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80],
    [
        14, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 4, 7, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 4, 7, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 7, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [15, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80],
    [
        14, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [14, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80],
    [
        13, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 4, 7, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 7, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 7, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 7, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [15, 1, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80],
    [
        14, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 5, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 5, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 5, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 5, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 4, 6, 9, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 6, 8, 11, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 6, 9, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 6, 8, 10, 13, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 5, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 5, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 5, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 5, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 6, 9, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 6, 8, 11, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 3, 2, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 3, 2, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 6, 9, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 1, 0, 2, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 2, 4, 6, 8, 10, 12, 15, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [15, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80],
    [
        14, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80,
    ],
    [
        13, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [14, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80],
    [
        13, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 4, 7, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 7, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 7, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 7, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [14, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80],
    [
        13, 0, 3, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 5, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 5, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 5, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 5, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 7, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 7, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 3, 2, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 3, 2, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 7, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 1, 0, 2, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 2, 4, 7, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        14, 1, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80,
    ],
    [
        13, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 2, 5, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 5, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 5, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 5, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 5, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 4, 6, 9, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 6, 8, 11, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 3, 2, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 3, 2, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 6, 9, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 1, 0, 2, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 2, 4, 6, 8, 10, 13, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        13, 1, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80,
    ],
    [
        12, 0, 3, 2, 5, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 5, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 5, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 2, 5, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 2, 5, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 5, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 5, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 5, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 5, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 3, 2, 5, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 3, 2, 5, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 5, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 5, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 1, 0, 2, 5, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 2, 5, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        12, 1, 0, 3, 2, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 0, 3, 2, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 3, 2, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 3, 2, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 2, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 2, 4, 6, 9, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 1, 0, 2, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 2, 4, 6, 8, 11, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 1, 0, 3, 2, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 3, 2, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 1, 0, 3, 2, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 3, 2, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 1, 0, 2, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 2, 4, 6, 9, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 1, 0, 2, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 0, 2, 4, 6, 8, 10, 12, 14, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
];

// shuffle mask, first byte for final length, other 16 bytes for mask
const PACK_1_2_3_UTF8_BYTES: [[u8; 17]; 256] = [
    [
        12, 2, 3, 1, 6, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 6, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 3, 1, 6, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 0, 6, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 2, 3, 1, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 3, 1, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 7, 5, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 2, 3, 1, 4, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 4, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 3, 1, 4, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 0, 4, 10, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 6, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 6, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 6, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 6, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 7, 5, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 4, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 4, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 4, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 4, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 2, 3, 1, 6, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 6, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 3, 1, 6, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 6, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 2, 3, 1, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 3, 1, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 0, 7, 5, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 4, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 4, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 4, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 4, 11, 9, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 2, 3, 1, 6, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 6, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 3, 1, 6, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 0, 6, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 7, 5, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 4, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 4, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 4, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 4, 8, 14, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 6, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 6, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 6, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 6, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 7, 5, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 4, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 4, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 4, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 4, 10, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 6, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 6, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 6, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 6, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 2, 3, 1, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        0, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80, 0x80,
    ],
    [
        2, 3, 1, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        1, 0, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        5, 2, 3, 1, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 3, 1, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 0, 7, 5, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 2, 3, 1, 4, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        1, 4, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        3, 3, 1, 4, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 0, 4, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 6, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 6, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 6, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 6, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 2, 3, 1, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        4, 3, 1, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 0, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 7, 5, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 4, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 4, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 4, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 4, 11, 9, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 6, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 6, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 6, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 6, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 2, 3, 1, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        1, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        3, 3, 1, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 0, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 7, 5, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 2, 3, 1, 4, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 4, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 3, 1, 4, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 0, 4, 8, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        11, 2, 3, 1, 6, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 6, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 3, 1, 6, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 0, 6, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 2, 3, 1, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 3, 1, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 0, 7, 5, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 4, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 4, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 4, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 4, 10, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 6, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 6, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 6, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 6, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 2, 3, 1, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        4, 3, 1, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 0, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 7, 5, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 4, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 4, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 4, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 4, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 2, 3, 1, 6, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 6, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 3, 1, 6, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 0, 6, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 7, 5, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 4, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 4, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 4, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 4, 11, 9, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 6, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 6, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 6, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 6, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 7, 5, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 4, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 4, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 4, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 4, 8, 15, 13, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        10, 2, 3, 1, 6, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 6, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 3, 1, 6, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 0, 6, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 7, 5, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 4, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 4, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 4, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 4, 10, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 6, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 6, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 6, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 6, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 2, 3, 1, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        1, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        3, 3, 1, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 0, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        6, 2, 3, 1, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 7, 5, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 2, 3, 1, 4, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 4, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        4, 3, 1, 4, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 0, 4, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        9, 2, 3, 1, 6, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 6, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 3, 1, 6, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 0, 6, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 7, 5, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 4, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 4, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 4, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 4, 11, 9, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        8, 2, 3, 1, 6, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 6, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 3, 1, 6, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 0, 6, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 2, 3, 1, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        2, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
        0x80,
    ],
    [
        4, 3, 1, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 0, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        7, 2, 3, 1, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 3, 1, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 0, 7, 5, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        6, 2, 3, 1, 4, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        3, 4, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        5, 3, 1, 4, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
    [
        4, 0, 4, 8, 12, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    ],
];
