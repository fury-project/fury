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

use fury::{utf16_to_utf8, utf16_to_utf8_simd};

#[cfg(target_endian = "big")]
const IS_LITTLE_ENDIAN_LOCAL: bool = false;

#[cfg(target_endian = "little")]
const IS_LITTLE_ENDIAN_LOCAL: bool = true;

fn get_bytes_endian_swapped(s: &str) -> Vec<u16> {
    s.encode_utf16()
        .collect::<Vec<u16>>()
        .iter()
        .map(|&byte| byte << 8 | byte >> 8)
        .collect::<Vec<u16>>()
}

#[cfg(test)]
mod test_normal {
    use super::IS_LITTLE_ENDIAN_LOCAL;
    use super::{get_bytes_endian_swapped, utf16_to_utf8};

    #[test]
    fn test() {
        let s = "Hé€lo, 世界!😀";
        let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
        println!("==========init utf16:");
        let utf16_strings: Vec<String> = utf16_bytes
            .iter()
            .map(|&byte| format!("0x{:04x}", byte))
            .collect();
        println!("{}", utf16_strings.join(","));
        let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
        println!("==========utf8:");
        // final UTF-8 string
        println!("final string: {}", final_string);
        assert_eq!(s, final_string);
    }

    #[test]
    fn test_3byte() {
        let s = "é₫l₪₮";
        let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
        let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
        assert_eq!(final_string, s);
        // swap endian
        let utf16_bytes_rev = get_bytes_endian_swapped(s);
        let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
        assert_eq!(final_string, s);
    }

    #[test]
    fn test_endian() {
        let utf16 = &[0x0061, 0x0062];
        let expected = "ab";
        let result = utf16_to_utf8(utf16, IS_LITTLE_ENDIAN_LOCAL).unwrap();
        assert_eq!(result, expected, "Little endian test failed");
        let utf16 = &[0x6100, 0x6200];
        let result = utf16_to_utf8(utf16, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
        assert_eq!(result, expected, "Big endian test failed");
    }

    #[test]
    fn test_surrogate_pair() {
        let s = "𝄞💡😀🎻";
        let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
        let result = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL);
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), s);
        let utf16_bytes_rev = get_bytes_endian_swapped(s);
        let result = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL);
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), s);
    }

    #[test]
    fn test_surrogate_pair_err() {
        let utf16 = &[0xD800]; // Missing second surrogate
        let result = utf16_to_utf8(utf16, IS_LITTLE_ENDIAN_LOCAL);
        assert!(result.is_err());
        assert_eq!(
            result.unwrap_err(),
            "Invalid UTF-16 string: missing surrogate pair"
        );

        let utf16 = &[0xD800, 0xDA00]; // Wrong second surrogate
        let result = utf16_to_utf8(utf16, IS_LITTLE_ENDIAN_LOCAL);
        assert!(result.is_err());
        assert_eq!(
            result.unwrap_err(),
            "Invalid UTF-16 string: wrong surrogate pair"
        );
    }
}

#[cfg(test)]
#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
mod test_sse {
    use super::{
        get_bytes_endian_swapped, utf16_to_utf8_simd::sse::utf16_to_utf8, IS_LITTLE_ENDIAN_LOCAL,
    };
    use std::arch::x86_64::{__m128i, _mm_storeu_si128};

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

    #[test]
    fn test() {
        unsafe {
            let s = "Hé€世界!😀Hé€世界!😀Hé€世界!😀Hé€世界!😀Hé€世界!😀";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_byte1() {
        unsafe {
            let s = "123123441234123123441234123123441234123123441234123123441234";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_byte1_byte2() {
        unsafe {
            let s = "11111111éé41éé4111éé41éé411111éé41éé411111éé41éé4";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_byte2_byte3() {
        unsafe {
            let s = "é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_surrogate_pair() {
        unsafe {
            let s = "😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }
}

#[cfg(test)]
#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
mod test_avx {

    use super::{
        get_bytes_endian_swapped, utf16_to_utf8_simd::avx::utf16_to_utf8, IS_LITTLE_ENDIAN_LOCAL,
    };
    #[test]
    fn test_byte1() {
        unsafe {
            let s = "123123441234123123441234123123441234123123441234123123441234";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_byte1_byte2() {
        unsafe {
            let s = "11111111éé41éé4111éé41éé411111éé41éé411111éé41éé4";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_byte2_byte3() {
        unsafe {
            let s = "é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_surrogate_pair() {
        unsafe {
            let s = "😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }
}

#[cfg(test)]
#[cfg(target_arch = "aarch64")]
mod test_neon {
    use super::{
        get_bytes_endian_swapped, utf16_to_utf8_simd::neon::utf16_to_utf8, IS_LITTLE_ENDIAN_LOCAL,
    };

    #[test]
    fn test_byte1() {
        unsafe {
            let s = "123123441234123123441234123123441234123123441234123123441234";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_byte1_byte2() {
        unsafe {
            let s = "11111111éé41éé4111éé41éé411111éé41éé411111éé41éé4";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_byte2_byte3() {
        unsafe {
            let s = "é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€é€";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }

    #[test]
    fn test_surrogate_pair() {
        unsafe {
            let s = "😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀😀233😀😀";
            let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
            let final_string = utf16_to_utf8(&utf16_bytes, IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
            let utf16_bytes_rev = get_bytes_endian_swapped(s);
            let final_string = utf16_to_utf8(&utf16_bytes_rev, !IS_LITTLE_ENDIAN_LOCAL).unwrap();
            assert_eq!(final_string, s);
        }
    }
}
