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

# cython: profile=True
# distutils: language = c++
# cython: embedsignature = True
# cython: language_level = 3
# cython: annotate = True

from libc.stdint cimport *
from libcpp.memory cimport shared_ptr
from libcpp cimport bool as c_bool
from pyfury.includes.libutil cimport CBuffer


cdef class Buffer:
    """This class implements the Python 'buffer protocol', which allows
    us to use it for calls into Python libraries without having to
    copy the data."""
    cdef:
        shared_ptr[CBuffer] c_buffer
        uint8_t* _c_address
        int32_t _c_size
        # hold python buffer reference count
        object data
        Py_ssize_t shape[1]
        Py_ssize_t stride[1]
        public int32_t reader_index, writer_index

    @staticmethod
    cdef Buffer wrap(shared_ptr[CBuffer] c_buffer)

    cpdef inline check_bound(self, int32_t offset, int32_t length)

    cdef getitem(self, int64_t i)

    cpdef inline c_bool own_data(self)

    cpdef inline reserve(self, int32_t new_size)

    cpdef inline int32_t size(self)

    cpdef inline grow(self, int32_t needed_size)

    cpdef inline ensure(self, int32_t length)

    cpdef inline put_bool(self, uint32_t offset, c_bool v)

    cpdef inline put_uint8(self, uint32_t offset, uint8_t v)

    cpdef inline put_int8(self, uint32_t offset, int8_t v)

    cpdef inline put_int16(self, uint32_t offset, int16_t v)

    cpdef inline put_int24(self, uint32_t offset, int32_t v)

    cpdef inline put_int32(self, uint32_t offset, int32_t v)

    cpdef inline put_int64(self, uint32_t offset, int64_t v)

    cpdef inline put_float(self, uint32_t offset, float v)

    cpdef inline put_double(self, uint32_t offset, double v)

    cpdef inline c_bool get_bool(self, uint32_t offset)

    cpdef inline int8_t get_int8(self, uint32_t offset)

    cpdef inline int16_t get_int16(self, uint32_t offset)

    cpdef inline int32_t get_int24(self, uint32_t offset)

    cpdef inline int32_t get_int32(self, uint32_t offset)

    cpdef inline int64_t get_int64(self, uint32_t offset)

    cpdef inline float get_float(self, uint32_t offset)

    cpdef inline double get_double(self, uint32_t offset)

    cpdef inline write_bool(self, c_bool value)

    cpdef inline write_int8(self, int8_t value)

    cpdef inline write_int16(self, int16_t value)

    cpdef inline write_int24(self, int32_t value)

    cpdef inline write_int32(self, int32_t value)

    cpdef inline write_int64(self, int64_t value)

    cpdef inline write_float(self, float value)

    cpdef inline write_double(self, double value)

    cpdef inline skip(self, int32_t length)

    cpdef inline c_bool read_bool(self)

    cpdef inline uint8_t read_uint8(self)

    cpdef inline int8_t read_int8(self)

    cpdef inline int16_t read_int16(self)

    cpdef inline int16_t read_int24(self)

    cpdef inline int32_t read_int32(self)

    cpdef inline int64_t read_int64(self)

    cpdef inline float read_float(self)

    cpdef inline double read_double(self)

    cpdef inline write_varint64(self, int64_t v)

    cpdef inline write_varuint64(self, int64_t v)

    cpdef inline int64_t read_varint64(self)

    cpdef inline int64_t read_varuint64(self)

    cpdef inline write_varuint32(self, int32_t value)

    cpdef inline write_varint32(self, int32_t value)

    cpdef inline int32_t read_varint32(self)

    cpdef inline int32_t read_varuint32(self)

    cpdef put_buffer(self, uint32_t offset, v, int32_t src_index, int32_t length)

    cdef inline write_c_buffer(self, const uint8_t* value, int32_t length)

    cdef inline int32_t read_c_buffer(self, uint8_t** buf)

    cpdef inline write_bytes_and_size(self, bytes value)

    cpdef inline bytes read_bytes_and_size(self)

    cpdef inline write_bytes(self, bytes value)

    cpdef inline bytes read_bytes(self, int32_t length)

    cpdef inline int64_t read_bytes_as_int64(self, int32_t length)

    cpdef inline put_bytes(self, uint32_t offset, bytes value)

    cpdef inline bytes get_bytes(self, uint32_t offset, uint32_t nbytes)

    cpdef inline write_buffer(self, value, src_index=0, length_=None)

    cpdef inline write_string(self, str value)

    cpdef inline str read_string(self)

    cpdef inline write(self, value)

    cpdef inline bytes read(self, int32_t length)

    cpdef inline bytes readline(self, int32_t size=-1)


cdef uint8_t* get_address(v)
