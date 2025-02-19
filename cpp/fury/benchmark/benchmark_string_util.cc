/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include <benchmark/benchmark.h>

#include <codecvt>
#include <locale>
#include <random>

#include "fury/util/string_util.h"

#include "simdutf.h"
#include <cstring>
#include <string>

/*
 * TEST
 */

// Generate random bytes (0x00 to 0xFF)
std::string generateRandom(size_t length) {
  std::string result;
  result.reserve(length);

  std::mt19937 generator(std::random_device{}());
  std::uniform_int_distribution<unsigned short> distribution(0x00, 0xFF);

  for (size_t i = 0; i < length; ++i) {
    result.push_back(static_cast<char>(distribution(generator)));
  }
  return result;
}

// Generate ASCII string (0x00 to 0x7F)
std::string generateAscii(size_t length) {
  const char charset[] =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  std::default_random_engine rng(std::random_device{}());
  std::uniform_int_distribution<> dist(0, sizeof(charset) - 2);

  std::string result;
  result.reserve(length);
  for (size_t i = 0; i < length; ++i) {
    result += charset[dist(rng)];
  }
  return result;
}

// Generate Latin-1 string (0x00 to 0xFF) as std::u16string
std::u16string generateLatin1(size_t length) {
  std::u16string result;
  result.reserve(length);

  std::mt19937 generator(std::random_device{}());
  std::uniform_int_distribution<uint16_t> distribution(0x00, 0xFF);

  for (size_t i = 0; i < length; ++i) {
    result.push_back(static_cast<char16_t>(distribution(generator)));
  }
  return result;
}

// Generate UTF-8 string (valid Unicode code points)
std::string generateUtf8(size_t length) {
  std::string result;
  result.reserve(length);

  std::mt19937 generator(std::random_device{}());
  std::uniform_int_distribution<uint32_t> distribution(0, 0x10FFFF);

  while (result.size() < length) {
    uint32_t code_point = distribution(generator);

    // Skip surrogate pairs (0xD800 to 0xDFFF) and invalid Unicode code points
    if ((code_point >= 0xD800 && code_point <= 0xDFFF) ||
        code_point > 0x10FFFF) {
      continue;
    }

    if (code_point <= 0x7F) {
      result.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7FF) {
      result.push_back(0xC0 | (code_point >> 6));
      result.push_back(0x80 | (code_point & 0x3F));
    } else if (code_point <= 0xFFFF) {
      result.push_back(0xE0 | (code_point >> 12));
      result.push_back(0x80 | ((code_point >> 6) & 0x3F));
      result.push_back(0x80 | (code_point & 0x3F));
    } else {
      result.push_back(0xF0 | (code_point >> 18));
      result.push_back(0x80 | ((code_point >> 12) & 0x3F));
      result.push_back(0x80 | ((code_point >> 6) & 0x3F));
      result.push_back(0x80 | (code_point & 0x3F));
    }
  }
  return result;
}

// Generate UTF-16 string (valid Unicode code points)
std::u16string generateUtf16(size_t length) {
  std::u16string result;
  result.reserve(length);

  std::mt19937 generator(std::random_device{}());
  std::uniform_int_distribution<uint32_t> distribution(0, 0x10FFFF);

  while (result.size() < length) {
    uint32_t code_point = distribution(generator);

    // Skip surrogate pairs (0xD800 to 0xDFFF) and invalid Unicode code points
    if ((code_point >= 0xD800 && code_point <= 0xDFFF) ||
        code_point > 0x10FFFF) {
      continue;
    }

    if (code_point <= 0xFFFF) {
      result.push_back(static_cast<char16_t>(code_point));
    } else {
      // Handle code points greater than 0xFFFF (requires surrogate pairs)
      code_point -= 0x10000;
      char16_t high_surrogate = 0xD800 | ((code_point >> 10) & 0x3FF);
      char16_t low_surrogate = 0xDC00 | (code_point & 0x3FF);
      result.push_back(high_surrogate);
      result.push_back(low_surrogate);
    }
  }
  return result;
}

/*
 *  TEST NUM
 */
const size_t num_tests = 1000;
const size_t string_length = 1000;

/*
 *  TEST Strings
 */
// Generate a vector of Ascii strings for testing
std::vector<std::string> generateAsciiString(size_t num_tests,
                                             size_t string_length) {
  std::vector<std::string> test_strings;
  for (size_t i = 0; i < string_length; ++i) {
    test_strings.push_back(generateUtf8(num_tests));
  }
  return test_strings;
}

const std::vector<std::string> test_ascii_strings =
    generateAsciiString(num_tests, string_length);

// Generate a vector of Latin-1 strings for testing
std::vector<std::u16string> generateLatin1String(size_t num_tests,
                                                 size_t string_length) {
  std::vector<std::u16string> test_strings;
  for (size_t i = 0; i < num_tests; ++i) {
    test_strings.push_back(generateLatin1(string_length));
  }
  return test_strings;
}

const std::vector<std::u16string> test_latin1_strings =
    generateLatin1String(num_tests, string_length);

// Generate random UTF-16 string
std::vector<std::u16string> generateUTF16String(size_t num_tests,
                                                size_t string_length) {
  std::vector<std::u16string> test_strings;
  for (size_t i = 0; i < string_length; ++i) {
    test_strings.push_back(generateUtf16(num_tests));
  }
  return test_strings;
}

const std::vector<std::u16string> test_utf16_strings =
    generateUTF16String(num_tests, string_length);

// Generate random UTF-8 string
std::vector<std::string> generateUTF8String(size_t num_tests,
                                            size_t string_length) {
  std::vector<std::string> test_strings;
  for (size_t i = 0; i < string_length; ++i) {
    test_strings.push_back(generateUtf8(num_tests));
  }
  return test_strings;
}

const std::vector<std::string> test_utf8_strings =
    generateUTF8String(num_tests, string_length);

/*
 *  TEST IsAscii
 */

// Check if a string is ASCII (all characters <= 0x7F)
bool isAscii_BaseLine(const std::string &str) {
  for (char c : str) {
    if (static_cast<uint8_t>(c) > 0x7F) {
      return false;
    }
  }
  return true;
}

bool isAscii_SIMDUTF(const std::string &str) {
  // Call the API directly without validation
  return simdutf::validate_ascii(str.data(), str.size());
}

// Benchmark function for Baseline ASCII check
static void BM_IsAscii_BaseLine(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_ascii_strings) {
      bool result = isAscii_BaseLine(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_IsAscii_BaseLine);

// Benchmark function for SIMDUTF ASCII check
static void BM_IsAscii_SIMDUTF(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_ascii_strings) {
      bool result = isAscii_SIMDUTF(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_IsAscii_SIMDUTF);

// Benchmark function for SIMD ASCII check
static void BM_IsAscii_FURY(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_ascii_strings) {
      bool result = fury::isAscii(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_IsAscii_FURY);

// Baseline implementation to check if a string is Latin-1
bool isLatin1_BaseLine(const std::u16string &str) {
  const std::uint16_t *data =
      reinterpret_cast<const std::uint16_t *>(str.data());
  size_t size = str.size();

  for (size_t i = 0; i < size; ++i) {
    if (data[i] > 0xFF) {
      return false;
    }
  }
  return true;
}

bool isLatin1_SIMDUTF(const std::u16string &str) {
  // Try the conversion directly, and all characters are considered Latin1 if
  // they are successfully converted
  size_t latin1_len = simdutf::latin1_length_from_utf16(str.size());
  if (latin1_len != str.size())
    return false;
  std::string buffer(str.size(), '\0');
  size_t converted =
      simdutf::convert_utf16_to_latin1(str.data(), str.size(), buffer.data());
  return converted == str.size();
}

// Benchmark function for Baseline Latin-1 check
static void BM_IsLatin1_BaseLine(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_latin1_strings) {
      bool result = isLatin1_BaseLine(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_IsLatin1_BaseLine);

// Benchmark function for Optimized Latin-1 check
static void BM_IsLatin1_SIMDUTF(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_latin1_strings) {
      bool result = isLatin1_SIMDUTF(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_IsLatin1_SIMDUTF);

// Benchmark function for Optimized Latin-1 check
static void BM_IsLatin1_FURY(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_latin1_strings) {
      bool result = fury::isLatin1(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_IsLatin1_FURY);

/*
 * TEST Utf16HasSurrogatePairs
 */
// Check if a UTF-16 string contains surrogate pairs
bool utf16HasSurrogatePairs_BaseLine(const std::u16string &str) {
  for (size_t i = 0; i < str.size(); ++i) {
    char16_t c = str[i];
    if (c >= 0xD800 && c <= 0xDFFF) {
      return true;
    }
  }
  return false;
}

// Benchmark function for checking if a UTF-16 string contains surrogate pairs
static void BM_Utf16HasSurrogatePairs_BaseLine(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf16_strings) {
      bool result = utf16HasSurrogatePairs_BaseLine(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_Utf16HasSurrogatePairs_BaseLine);

// Benchmark function for checking if a UTF-16 string contains surrogate pairs
// with SIMD
static void BM_Utf16HasSurrogatePairs_FURY(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf16_strings) {
      bool result = fury::utf16HasSurrogatePairs(str);
      benchmark::DoNotOptimize(result); // Prevent compiler optimization
    }
  }
}

BENCHMARK(BM_Utf16HasSurrogatePairs_FURY);

/*
 * TEST Utf16ToUtf8
 */

// UTF16 to UTF8 using the standard library
std::string utf16ToUtf8StandardLibrary(const std::u16string &utf16) {
  std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> convert;
  return convert.to_bytes(utf16);
}

// UTF16 to UTF8 baseline conversion (without SIMD)
std::string utf16ToUtf8BaseLine(const std::u16string &utf16,
                                bool is_little_endian = true) {
  size_t utf16_length = utf16.length();
  size_t utf8_length = utf16_length * 3;
  std::string utf8_result(utf8_length, '\0');

  size_t i = 0, j = 0;
  while (i < utf16_length) {
    char16_t utf16_char = utf16[i++];
    if (utf16_char < 0x80) {
      utf8_result[j++] = static_cast<char>(utf16_char);
    } else if (utf16_char < 0x800) {
      utf8_result[j++] = static_cast<char>(0xC0 | (utf16_char >> 6));
      utf8_result[j++] = static_cast<char>(0x80 | (utf16_char & 0x3F));
    } else {
      utf8_result[j++] = static_cast<char>(0xE0 | (utf16_char >> 12));
      utf8_result[j++] = static_cast<char>(0x80 | ((utf16_char >> 6) & 0x3F));
      utf8_result[j++] = static_cast<char>(0x80 | (utf16_char & 0x3F));
    }
  }

  utf8_result.resize(j);
  return utf8_result;
}

std::string utf16ToUtf8_SIMDUTF(const std::u16string &utf16,
                                bool is_little_endian) {
  if (utf16.empty())
    return {};
  size_t utf8_len =
      is_little_endian
          ? simdutf::utf8_length_from_utf16le(utf16.data(), utf16.size())
          : simdutf::utf8_length_from_utf16be(utf16.data(), utf16.size());

  std::string utf8_result(utf8_len, '\0');
  size_t converted = is_little_endian
                         ? simdutf::convert_utf16le_to_utf8(
                               utf16.data(), utf16.size(), utf8_result.data())
                         : simdutf::convert_utf16be_to_utf8(
                               utf16.data(), utf16.size(), utf8_result.data());
  utf8_result.resize(converted);
  return utf8_result;
}

// Benchmark function for Standard Library UTF-16 to UTF-8 conversion
static void BM_Utf16ToUtf8_StandardLibrary(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf16_strings) {
      std::string utf8 = utf16ToUtf8StandardLibrary(str);
      benchmark::DoNotOptimize(
          utf8); // Prevents the compiler from optimizing away unused variables
    }
  }
}

BENCHMARK(BM_Utf16ToUtf8_StandardLibrary);

// Benchmark function for Baseline UTF-16 to UTF-8 conversion
static void BM_Utf16ToUtf8_BaseLine(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf16_strings) {
      std::string utf8 = utf16ToUtf8BaseLine(str, true);
      benchmark::DoNotOptimize(
          utf8); // Prevents the compiler from optimizing away unused variables
    }
  }
}

BENCHMARK(BM_Utf16ToUtf8_BaseLine);

// Benchmark function for SIMD-based UTF-16 to UTF-8 conversion
static void BM_Utf16ToUtf8_SIMDUTF(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf16_strings) {
      std::string utf8 = utf16ToUtf8_SIMDUTF(str, true);
      benchmark::DoNotOptimize(
          utf8); // Prevents the compiler from optimizing away unused variables
    }
  }
}

BENCHMARK(BM_Utf16ToUtf8_SIMDUTF);

// Benchmark function for SIMD-based UTF-16 to UTF-8 conversion
static void BM_Utf16ToUtf8_FURY(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf16_strings) {
      std::string utf8 = fury::utf16ToUtf8(str, true);
      benchmark::DoNotOptimize(
          utf8); // Prevents the compiler from optimizing away unused variables
    }
  }
}

BENCHMARK(BM_Utf16ToUtf8_FURY);

/*
 * TEST Utf8ToUtf16
 */

// UTF8 to UTF16 using the standard library
std::u16string utf8ToUtf16StandardLibrary(const std::string &utf8) {
  std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> convert;
  return convert.from_bytes(utf8);
}

// UTF8 to UTF16 baseline conversion (without SIMD)
std::u16string utf8ToUtf16BaseLine(const std::string &utf8,
                                   bool is_little_endian) {
  std::u16string utf16;   // Resulting UTF-16 string
  size_t i = 0;           // Index for traversing the UTF-8 string
  size_t n = utf8.size(); // Total length of the UTF-8 string

  // Loop through each byte of the UTF-8 string
  while (i < n) {
    uint32_t code_point = 0;   // The Unicode code point
    unsigned char c = utf8[i]; // Current byte of the UTF-8 string

    // Determine the number of bytes for this character based on its first byte
    if ((c & 0x80) == 0) {
      // 1-byte character (ASCII)
      code_point = c;
      ++i;
    } else if ((c & 0xE0) == 0xC0) {
      // 2-byte character
      code_point = c & 0x1F;
      code_point = (code_point << 6) | (utf8[i + 1] & 0x3F);
      i += 2;
    } else if ((c & 0xF0) == 0xE0) {
      // 3-byte character
      code_point = c & 0x0F;
      code_point = (code_point << 6) | (utf8[i + 1] & 0x3F);
      code_point = (code_point << 6) | (utf8[i + 2] & 0x3F);
      i += 3;
    } else if ((c & 0xF8) == 0xF0) {
      // 4-byte character
      code_point = c & 0x07;
      code_point = (code_point << 6) | (utf8[i + 1] & 0x3F);
      code_point = (code_point << 6) | (utf8[i + 2] & 0x3F);
      code_point = (code_point << 6) | (utf8[i + 3] & 0x3F);
      i += 4;
    } else {
      // Invalid UTF-8 byte sequence
      throw std::invalid_argument("Invalid UTF-8 encoding.");
    }

    // If the code point is beyond the BMP range, use surrogate pairs
    if (code_point >= 0x10000) {
      code_point -= 0x10000; // Subtract 0x10000 to get the surrogate pair
      uint16_t high_surrogate = 0xD800 + (code_point >> 10);  // High surrogate
      uint16_t low_surrogate = 0xDC00 + (code_point & 0x3FF); // Low surrogate

      // If not little-endian, swap bytes of the surrogates
      if (!is_little_endian) {
        high_surrogate = (high_surrogate >> 8) | (high_surrogate << 8);
        low_surrogate = (low_surrogate >> 8) | (low_surrogate << 8);
      }

      // Add both high and low surrogates to the UTF-16 string
      utf16.push_back(high_surrogate);
      utf16.push_back(low_surrogate);
    } else {
      // For code points within the BMP range, directly store as a 16-bit value
      uint16_t utf16_char = static_cast<uint16_t>(code_point);

      // If not little-endian, swap the bytes of the 16-bit character
      if (!is_little_endian) {
        utf16_char = (utf16_char >> 8) | (utf16_char << 8);
      }

      // Add the UTF-16 character to the string
      utf16.push_back(utf16_char);
    }
  }

  // Return the resulting UTF-16 string
  return utf16;
}

std::u16string utf8ToUtf16_SIMDUTF(const std::string &utf8,
                                   bool is_little_endian) {
  if (utf8.empty())
    return {};

  size_t utf16_len = simdutf::utf16_length_from_utf8(utf8.data(), utf8.size());

  std::u16string utf16_result(utf16_len, u'\0');

  size_t converted = is_little_endian
                         ? simdutf::convert_utf8_to_utf16le(
                               utf8.data(), utf8.size(), utf16_result.data())
                         : simdutf::convert_utf8_to_utf16be(
                               utf8.data(), utf8.size(), utf16_result.data());

  utf16_result.resize(converted);
  return utf16_result;
}

// Benchmark function for Standard Library UTF-8 to UTF-16 conversion
static void BM_Utf8ToUtf16_StandardLibrary(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf8_strings) {
      std::u16string utf16 = utf8ToUtf16StandardLibrary(str);
      benchmark::DoNotOptimize(
          utf16); // Prevents the compiler from optimizing away unused variables
    }
  }
}
BENCHMARK(BM_Utf8ToUtf16_StandardLibrary);

// Benchmark function for Baseline UTF-8 to UTF-16 conversion
static void BM_Utf8ToUtf16_BaseLine(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf8_strings) {
      std::u16string utf16 = utf8ToUtf16BaseLine(str, true);
      benchmark::DoNotOptimize(
          utf16); // Prevents the compiler from optimizing away unused variables
    }
  }
}

BENCHMARK(BM_Utf8ToUtf16_BaseLine);

// Benchmark function for SIMD-based UTF-8 to UTF-16 conversion
static void BM_Utf8ToUtf16_SIMDUTF(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf8_strings) {
      std::u16string utf16 = utf8ToUtf16_SIMDUTF(str, true);
      benchmark::DoNotOptimize(
          utf16); // Prevents the compiler from optimizing away unused variables
    }
  }
}

BENCHMARK(BM_Utf8ToUtf16_SIMDUTF);

// Benchmark function for SIMD-based UTF-8 to UTF-16 conversion
static void BM_Utf8ToUtf16_FURY(benchmark::State &state) {
  for (auto _ : state) {
    for (const auto &str : test_utf8_strings) {
      std::u16string utf16 = fury::utf8ToUtf16(str, true);
      benchmark::DoNotOptimize(
          utf16); // Prevents the compiler from optimizing away unused variables
    }
  }
}

BENCHMARK(BM_Utf8ToUtf16_FURY);

BENCHMARK_MAIN();
