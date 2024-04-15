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

package org.apache.fury.meta;

import org.apache.fury.util.StringUtils;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class MetaStringTest {

  @Test
  public void testEncodeMetaStringLowerSpecial() {
    // special chars not matter for encodeLowerSpecial
    MetaStringEncoder encoder = new MetaStringEncoder('_', '$');
    byte[] encoded = encoder.encodeLowerSpecial("abc_def");
    Assert.assertEquals(encoded.length, 5);
    MetaStringDecoder decoder = new MetaStringDecoder('_', '$');
    String decoded = decoder.decode(encoded, MetaString.Encoding.LOWER_SPECIAL, 7 * 5);
    Assert.assertEquals(decoded, "abc_def");
    for (int i = 0; i < 128; i++) {
      StringBuilder builder = new StringBuilder();
      for (int j = 0; j < i; j++) {
        builder.append((char) ('a' + j % 26));
      }
      String str = builder.toString();
      encoded = encoder.encodeLowerSpecial(str);
      decoded = decoder.decode(encoded, MetaString.Encoding.LOWER_SPECIAL, i * 5);
      Assert.assertEquals(decoded, str);
    }
  }

  @Test
  public void testEncodeMetaStringLowerUpperDigitSpecial() {
    char specialChar1 = '.';
    char specialChar2 = '_';
    MetaStringEncoder encoder = new MetaStringEncoder(specialChar1, specialChar2);
    byte[] encoded = encoder.encodeLowerUpperDigitSpecial("ExampleInput123");
    Assert.assertEquals(encoded.length, 12);
    MetaStringDecoder decoder = new MetaStringDecoder(specialChar1, specialChar2);
    String decoded = decoder.decode(encoded, MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL, 15 * 6);
    Assert.assertEquals(decoded, "ExampleInput123");

    for (int i = 1; i < 128; i++) {
      String str = createString(i, specialChar1, specialChar2);
      encoded = encoder.encodeLowerUpperDigitSpecial(str);
      decoded = decoder.decode(encoded, MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL, i * 6);
      Assert.assertEquals(decoded, str, "Failed at " + i);
    }
  }

  private static String createString(int i, char specialChar1, char specialChar2) {
    StringBuilder builder = new StringBuilder();
    for (int j = 0; j < i; j++) {
      int n = j % 64;
      char c;
      if (n < 26) {
        c = (char) ('a' + n);
      } else if (n < 52) {
        c = (char) ('A' + n - 26);
      } else if (n < 62) {
        c = (char) ('0' + n - 52);
      } else if (n == 62) {
        c = specialChar1;
      } else {
        c = specialChar2;
      }
      builder.append(c);
    }
    StringUtils.shuffle(builder);
    return builder.toString();
  }

  @DataProvider
  public static Object[][] specialChars() {
    return new Object[][] {{'.', '_'}, {'.', '$'}, {'_', '$'}};
  }

  @Test(dataProvider = "specialChars")
  public void testMetaString(char specialChar1, char specialChar2) {
    MetaStringEncoder encoder = new MetaStringEncoder(specialChar1, specialChar2);
    for (int i = 0; i < 128; i++) {
      try {
        String str = createString(i, specialChar1, specialChar2);
        MetaString metaString = encoder.encode(str);
        Assert.assertEquals(metaString.getString(), str);
        Assert.assertEquals(metaString.getSpecialChar1(), specialChar1);
        Assert.assertEquals(metaString.getSpecialChar2(), specialChar2);
        MetaStringDecoder decoder = new MetaStringDecoder(specialChar1, specialChar2);
        String newStr =
            decoder.decode(
                metaString.getBytes(), metaString.getEncoding(), metaString.getNumBits());
        Assert.assertEquals(newStr, str);
      } catch (Throwable e) {
        throw new RuntimeException("Failed at " + i, e);
      }
    }
  }

  @DataProvider(name = "emptyStringProvider")
  public Object[][] emptyStringProvider() {
    return new Object[][] {
      {MetaString.Encoding.LOWER_SPECIAL},
      {MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL},
      {MetaString.Encoding.FIRST_TO_LOWER_SPECIAL},
      {MetaString.Encoding.ALL_TO_LOWER_SPECIAL},
      {MetaString.Encoding.UTF_8}
    };
  }

  @Test(dataProvider = "emptyStringProvider")
  public void testEncodeEmptyString(MetaString.Encoding encoding) {
    MetaStringEncoder encoder = new MetaStringEncoder('_', '$');
    MetaString metaString = encoder.encode("", encoding);
    Assert.assertEquals(metaString.getBytes().length, 0);
    MetaStringDecoder decoder = new MetaStringDecoder('_', '$');
    String decoded =
        decoder.decode(metaString.getBytes(), metaString.getEncoding(), metaString.getNumBits());
    Assert.assertEquals(decoded, "");
  }

  @Test
  public void testEncodeCharactersOutsideOfLowerSpecial() {
    String testString = "abcdefABCDEF1234!@#"; // Contains characters outside LOWER_SPECIAL
    MetaStringEncoder encoder = new MetaStringEncoder('_', '$');
    MetaString encodedMetaString = encoder.encode(testString);

    Assert.assertNotEquals(encodedMetaString.getEncoding(), MetaString.Encoding.LOWER_SPECIAL);
  }

  @Test
  public void testAllToUpperSpecialEncoding() {
    String testString = "ABC_DEF";
    MetaStringEncoder encoder = new MetaStringEncoder('_', '$');
    MetaString encodedMetaString = encoder.encode(testString);
    Assert.assertEquals(
        encodedMetaString.getEncoding(), MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL);

    MetaStringDecoder decoder = new MetaStringDecoder('_', '$');
    String decodedString =
        decoder.decode(
            encodedMetaString.getBytes(),
            encodedMetaString.getEncoding(),
            encodedMetaString.getNumBits());
    Assert.assertEquals(decodedString, testString);
  }

  @Test
  public void testFirstToLowerSpecialEncoding() {
    String testString = "Aabcdef";
    MetaStringEncoder encoder = new MetaStringEncoder('_', '$');
    MetaString encodedMetaString = encoder.encode(testString);
    Assert.assertEquals(
        encodedMetaString.getEncoding(), MetaString.Encoding.FIRST_TO_LOWER_SPECIAL);

    MetaStringDecoder decoder = new MetaStringDecoder('_', '$');
    String decodedString =
        decoder.decode(
            encodedMetaString.getBytes(),
            encodedMetaString.getEncoding(),
            encodedMetaString.getNumBits());
    Assert.assertEquals(decodedString, testString);
  }

  @Test
  public void testUtf8Encoding() {
    String testString = "你好，世界"; // Non-Latin characters
    MetaStringEncoder encoder = new MetaStringEncoder('_', '$');
    MetaString encodedMetaString = encoder.encode(testString);
    Assert.assertEquals(encodedMetaString.getEncoding(), MetaString.Encoding.UTF_8);

    MetaStringDecoder decoder = new MetaStringDecoder('_', '$');
    String decodedString =
        decoder.decode(
            encodedMetaString.getBytes(),
            encodedMetaString.getEncoding(),
            encodedMetaString.getNumBits());
    Assert.assertEquals(decodedString, testString);
  }
}
