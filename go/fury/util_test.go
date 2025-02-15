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

package fury

import (
	"github.com/stretchr/testify/require"
	"testing"
	"time"
)

func TestSnake(t *testing.T) {
	require.Equal(t, "a_bcd_efg_hij", SnakeCase("aBcdEfgHij"))
	require.Equal(t, "a_bcd_efg_hij", SnakeCase("ABcdEfgHij"))
	require.Equal(t, "a_b_c_d_efg_hij", SnakeCase("ABCDEfgHij"))
	require.Equal(t, SnakeCase("ToSnake"), "to_snake")
	require.Equal(t, SnakeCase("toSnake"), "to_snake")
	require.Equal(t, SnakeCase("to_snake"), "to_snake")
	require.Equal(t, SnakeCase("AbcAbcAbc"), "abc_abc_abc")
	require.Equal(t, SnakeCase("ABC"), "a_b_c")
}

func TestTime(t *testing.T) {
	t1 := time.Now()
	ts := GetUnixMicro(t1)
	t2 := CreateTimeFromUnixMicro(ts)
	require.Equal(t, t1.Second(), t2.Second())
	// Micro doesn't preserve Nanosecond precision.
	require.Equal(t, t1.Nanosecond()/1000, t2.Nanosecond()/1000)
	require.WithinDuration(t, t1, t2, 1000)
}

type UTF16TestBean struct {
	UTF16Data      []byte
	expectedValue  string
	isLittleEndian bool
}

func TestUTF16ToString(t *testing.T) {
	data := []UTF16TestBean{
		{
			[]byte{
				0b01101000, 0b00000000,
				0b01100101, 0b00000000,
				0b01101100, 0b00000000,
				0b01101100, 0b00000000,
				0b01101111, 0b00000000,
				0b00010110, 0b01001110,
				0b01001100, 0b01110101,
			},
			"hello世界",
			true,
		},
		{
			[]byte{
				0b00110100, 0b11011000, 0b00011110, 0b11011101,
			},
			// U+1D11E(UTF16 four bytes encode)
			"𝄞",
			true,
		},
		{
			[]byte{
				0b11011000, 0b00110100, 0b11011101, 0b00011110,
			},
			// U+1D11E(UTF16 four bytes encode)
			"𝄞",
			false,
		},
	}

	for _, value := range data {
		check(t, value.UTF16Data, value.expectedValue, value.isLittleEndian)
	}
}

func check(t *testing.T, utf16Data []byte, expectedValue string, isLittleEndian bool) {
	strData, err := UTF16ToString(utf16Data, isLittleEndian)
	require.NoError(t, err)
	require.Equal(t, expectedValue, strData)
}

func TestUTF16ToStringError(t *testing.T) {
	utf16ErrorData := []byte{0b01101000, 0b00000000, 0b01100101}
	_, err := UTF16ToString(utf16ErrorData, true)
	require.Error(t, err)
}

