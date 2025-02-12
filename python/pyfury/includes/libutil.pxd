# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from libc.stdint cimport *
from libcpp cimport bool as c_bool
from libcpp.memory cimport shared_ptr
from libcpp.string cimport string as c_string

cdef extern from "fury/util/buffer.h" namespace "fury" nogil:
    cdef cppclass CStatus" fury::Status":
        c_string ToString() const

        c_string CodeAsString() const

        c_string message() const

        StatusCode code() const

    cdef enum class StatusCode(char):
        OK = 0,
        OutOfMemory = 1,
        OutOfBound = 2,
        KeyError = 3,
        TypeError = 4,
        Invalid = 5,
        IOError = 6,
        UnknownError = 7

    cdef cppclass CBuffer" fury::Buffer":
        CBuffer(uint8_t* data, uint32_t size, c_bool own_data=True)

        inline uint8_t* data()

        inline uint32_t size()

        inline c_bool own_data()

        inline c_bool Reserve(uint32_t new_size)

        inline void UnsafePutByte(uint32_t offset, c_bool)

        inline void UnsafePutByte(uint32_t offset, uint8_t)

        inline void UnsafePutByte(uint32_t offset, int8_t)

        inline void UnsafePut(uint32_t offset, int16_t)

        inline void UnsafePut(uint32_t offset, int32_t)

        inline void UnsafePut(uint32_t offset, int64_t)

        inline void UnsafePut(uint32_t offset, float)

        inline void UnsafePut(uint32_t offset, double)

        void CopyFrom(uint32_t offset, const uint8_t *src, uint32_t src_offset,
                      uint32_t nbytes)

        inline c_bool GetBool(uint32_t offset)

        inline int8_t GetInt8(uint32_t offset)

        inline int16_t GetInt16(uint32_t offset)

        inline int32_t GetInt32(uint32_t offset)

        inline int64_t GetInt64(uint32_t offset)

        inline float GetFloat(uint32_t offset)

        inline double GetDouble(uint32_t offset)

        inline CStatus GetBytesAsInt64(uint32_t offset, uint32_t length, int64_t* target)

        inline uint32_t PutVarUint32(uint32_t offset, int32_t value)

        inline int32_t GetVarUint32(uint32_t offset, uint32_t *readBytesLength)

        void Copy(uint32_t start, uint32_t nbytes,
                  uint8_t* out, uint32_t offset) const

        c_string Hex()

    CBuffer* AllocateBuffer(uint32_t size)
    c_bool AllocateBuffer(uint32_t size, shared_ptr[CBuffer]* out)


cdef extern from "fury/util/bit_util.h" namespace "fury::util" nogil:
    c_bool GetBit(const uint8_t *bits, uint32_t i)

    void SetBit(uint8_t *bits, int64_t i)

    void ClearBit(uint8_t *bits, int64_t i)

    void SetBitTo(uint8_t *bits, int64_t i, c_bool bit_is_set)

    c_string hex(uint8_t *data, int32_t length)


cdef extern from "fury/util/string_util.h" namespace "fury" nogil:
    c_bool utf16HasSurrogatePairs(uint16_t* data, size_t size)
