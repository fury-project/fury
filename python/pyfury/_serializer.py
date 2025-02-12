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

import datetime
import logging
from abc import ABC, abstractmethod
from typing import Dict, Iterable, Any

from pyfury._fury import (
    NOT_NULL_INT64_FLAG,
    NOT_NULL_BOOL_FLAG,
    NOT_NULL_STRING_FLAG,
)
from pyfury.resolver import NOT_NULL_VALUE_FLAG, NULL_FLAG
from pyfury.type import is_primitive_type

try:
    import numpy as np
except ImportError:
    np = None

logger = logging.getLogger(__name__)

MAX_CHUNK_SIZE = 255
# Whether track key ref.
TRACKING_KEY_REF = 0b1
# Whether key has null.
KEY_HAS_NULL = 0b10
# Whether key is not declare type.
KEY_DECL_TYPE = 0b100
# Whether track value ref.
TRACKING_VALUE_REF = 0b1000
# Whether value has null.
VALUE_HAS_NULL = 0b10000
# Whether value is not declare type.
VALUE_DECL_TYPE = 0b100000
# When key or value is null that entry will be serialized as a new chunk with size 1.
# In such cases, chunk size will be skipped writing.
# Both key and value are null.
KV_NULL = KEY_HAS_NULL | VALUE_HAS_NULL
# Key is null, value type is declared type, and ref tracking for value is disabled.
NULL_KEY_VALUE_DECL_TYPE = KEY_HAS_NULL | VALUE_DECL_TYPE
# Key is null, value type is declared type, and ref tracking for value is enabled.
NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF = (
    KEY_HAS_NULL | VALUE_DECL_TYPE | TRACKING_VALUE_REF
)
# Value is null, key type is declared type, and ref tracking for key is disabled.
NULL_VALUE_KEY_DECL_TYPE = VALUE_HAS_NULL | KEY_DECL_TYPE
# Value is null, key type is declared type, and ref tracking for key is enabled.
NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF = (
    VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_VALUE_REF
)


class Serializer(ABC):
    __slots__ = "fury", "type_", "need_to_write_ref"

    def __init__(self, fury, type_: type):
        self.fury = fury
        self.type_: type = type_
        self.need_to_write_ref = not is_primitive_type(type_)

    def write(self, buffer, value):
        raise NotImplementedError

    def read(self, buffer):
        raise NotImplementedError

    @abstractmethod
    def xwrite(self, buffer, value):
        pass

    @abstractmethod
    def xread(self, buffer):
        pass

    @classmethod
    def support_subclass(cls) -> bool:
        return False


class CrossLanguageCompatibleSerializer(Serializer):
    def __init__(self, fury, type_):
        super().__init__(fury, type_)

    def xwrite(self, buffer, value):
        self.write(buffer, value)

    def xread(self, buffer):
        return self.read(buffer)


class BooleanSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_bool(value)

    def read(self, buffer):
        return buffer.read_bool()


class ByteSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_int8(value)

    def read(self, buffer):
        return buffer.read_int8()


class Int16Serializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_int16(value)

    def read(self, buffer):
        return buffer.read_int16()


class Int32Serializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_varint32(value)

    def read(self, buffer):
        return buffer.read_varint32()


class Int64Serializer(Serializer):
    def xwrite(self, buffer, value):
        buffer.write_varint64(value)

    def xread(self, buffer):
        return buffer.read_varint64()

    def write(self, buffer, value):
        buffer.write_varint64(value)

    def read(self, buffer):
        return buffer.read_varint64()


class Float32Serializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_float(value)

    def read(self, buffer):
        return buffer.read_float()


class Float64Serializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value):
        buffer.write_double(value)

    def read(self, buffer):
        return buffer.read_double()


class StringSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value: str):
        buffer.write_string(value)

    def read(self, buffer):
        return buffer.read_string()


_base_date = datetime.date(1970, 1, 1)


class DateSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value: datetime.date):
        if not isinstance(value, datetime.date):
            raise TypeError(
                "{} should be {} instead of {}".format(
                    value, datetime.date, type(value)
                )
            )
        days = (value - _base_date).days
        buffer.write_int32(days)

    def read(self, buffer):
        days = buffer.read_int32()
        return _base_date + datetime.timedelta(days=days)


class TimestampSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value: datetime.datetime):
        if not isinstance(value, datetime.datetime):
            raise TypeError(
                "{} should be {} instead of {}".format(value, datetime, type(value))
            )
        # TimestampType represent micro seconds
        timestamp = int(value.timestamp() * 1000000)
        buffer.write_int64(timestamp)

    def read(self, buffer):
        ts = buffer.read_int64() / 1000000
        # TODO support timezone
        return datetime.datetime.fromtimestamp(ts)


class CollectionSerializer(Serializer):
    __slots__ = "class_resolver", "ref_resolver", "elem_serializer"

    def __init__(self, fury, type_, elem_serializer=None):
        super().__init__(fury, type_)
        self.class_resolver = fury.class_resolver
        self.ref_resolver = fury.ref_resolver
        self.elem_serializer = elem_serializer

    def write(self, buffer, value: Iterable[Any]):
        buffer.write_varuint32(len(value))
        for s in value:
            cls = type(s)
            if cls is str:
                buffer.write_int16(NOT_NULL_STRING_FLAG)
                buffer.write_string(s)
            elif cls is int:
                buffer.write_int16(NOT_NULL_INT64_FLAG)
                buffer.write_varint64(s)
            elif cls is bool:
                buffer.write_int16(NOT_NULL_BOOL_FLAG)
                buffer.write_bool(s)
            else:
                if not self.ref_resolver.write_ref_or_null(buffer, s):
                    classinfo = self.class_resolver.get_classinfo(cls)
                    self.class_resolver.write_typeinfo(buffer, classinfo)
                    classinfo.serializer.write(buffer, s)

    def read(self, buffer):
        len_ = buffer.read_varuint32()
        collection_ = self.new_instance(self.type_)
        for i in range(len_):
            self.handle_read_elem(self.fury.deserialize_ref(buffer), collection_)
        return collection_

    def new_instance(self, type_):
        # TODO support iterable subclass
        instance = []
        self.fury.ref_resolver.reference(instance)
        return instance

    def handle_read_elem(self, elem, collection_):
        collection_.append(elem)

    def xwrite(self, buffer, value):
        try:
            len_ = len(value)
        except AttributeError:
            value = list(value)
            len_ = len(value)
        buffer.write_varuint32(len_)
        for s in value:
            self.fury.xserialize_ref(buffer, s, serializer=self.elem_serializer)
            len_ += 1

    def xread(self, buffer):
        len_ = buffer.read_varuint32()
        collection_ = self.new_instance(self.type_)
        for i in range(len_):
            self.handle_read_elem(
                self.fury.xdeserialize_ref(buffer, serializer=self.elem_serializer),
                collection_,
            )
        return collection_


class ListSerializer(CollectionSerializer):
    def read(self, buffer):
        len_ = buffer.read_varuint32()
        instance = []
        self.fury.ref_resolver.reference(instance)
        for i in range(len_):
            instance.append(self.fury.deserialize_ref(buffer))
        return instance


class TupleSerializer(CollectionSerializer):
    def read(self, buffer):
        len_ = buffer.read_varuint32()
        collection_ = []
        for i in range(len_):
            collection_.append(self.fury.deserialize_ref(buffer))
        return tuple(collection_)


class StringArraySerializer(ListSerializer):
    def __init__(self, fury, type_):
        super().__init__(fury, type_, StringSerializer(fury, str))


class SetSerializer(CollectionSerializer):
    def new_instance(self, type_):
        instance = set()
        self.fury.ref_resolver.reference(instance)
        return instance

    def handle_read_elem(self, elem, set_: set):
        set_.add(elem)


class MapSerializer(Serializer):
    def __init__(self, fury, type_, key_serializer=None, value_serializer=None):
        super().__init__(fury, type_)
        self.class_resolver = fury.class_resolver
        self.ref_resolver = fury.ref_resolver
        self.key_serializer = key_serializer
        self.value_serializer = value_serializer

    def write(self, buffer, o):
        obj = o
        length = len(obj)
        buffer.write_varuint32(length)
        if length == 0:
            return
        fury = self.fury
        class_resolver = fury.class_resolver
        ref_resolver = fury.ref_resolver
        key_serializer = self.key_serializer
        value_serializer = self.value_serializer

        items_iter = iter(obj.items())
        key, value = next(items_iter)
        has_next = True
        while has_next:

            while True:
                if key is not None:
                    if value is not None:
                        break
                    if key_serializer is not None:
                        if key_serializer.need_to_write_ref:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
                            if not ref_resolver.write_ref_or_null(buffer, key):
                                key_serializer.write(buffer, key)
                        else:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE)
                            key_serializer.write(buffer, key)
                    else:
                        buffer.write_int8(VALUE_HAS_NULL | TRACKING_KEY_REF)
                        fury.serialize_ref(buffer, key)
                else:
                    if value is not None:
                        if value_serializer is not None:
                            if value_serializer.need_to_write_ref:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
                                if not ref_resolver.write_ref_or_null(buffer, key):
                                    value_serializer.write(buffer, key)
                                if not ref_resolver.write_ref_or_null(buffer, value):
                                    value_serializer.write(buffer, value)
                            else:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE)
                                value_serializer.write(buffer, value)
                        else:
                            buffer.write_int8(KEY_HAS_NULL | TRACKING_VALUE_REF)
                            fury.serialize_ref(buffer, value)
                    else:
                        buffer.write_int8(KV_NULL)
                try:
                    key, value = next(items_iter)
                except StopIteration:
                    has_next = False
                    break

            if not has_next:
                break

            key_cls = type(key)
            value_cls = type(value)
            buffer.write_int16(-1)
            chunk_size_offset = buffer.writer_index - 1
            chunk_header = 0

            if key_serializer is not None:
                chunk_header |= KEY_DECL_TYPE
            else:
                key_classinfo = self.class_resolver.get_classinfo(key_cls)
                class_resolver.write_typeinfo(buffer, key_classinfo)
                key_serializer = key_classinfo.serializer

            if value_serializer is not None:
                chunk_header |= VALUE_DECL_TYPE
            else:
                value_classinfo = self.class_resolver.get_classinfo(value_cls)
                class_resolver.write_typeinfo(buffer, value_classinfo)
                value_serializer = value_classinfo.serializer

            key_write_ref = (
                key_serializer.need_to_write_ref if key_serializer else False
            )
            value_write_ref = (
                value_serializer.need_to_write_ref if value_serializer else False
            )
            if key_write_ref:
                chunk_header |= TRACKING_KEY_REF
            if value_write_ref:
                chunk_header |= TRACKING_VALUE_REF

            buffer.put_uint8(chunk_size_offset - 1, chunk_header)
            chunk_size = 0

            while chunk_size < MAX_CHUNK_SIZE:

                if (
                    key is None
                    or value is None
                    or type(key) is not key_cls
                    or type(value) is not value_cls
                ):
                    break
                if not key_write_ref or not ref_resolver.write_ref_or_null(buffer, key):
                    key_serializer.write(buffer, key)
                if not value_write_ref or not ref_resolver.write_ref_or_null(
                    buffer, value
                ):
                    value_serializer.write(buffer, value)

                chunk_size += 1

                try:
                    key, value = next(items_iter)
                except StopIteration:
                    has_next = False
                    break

            key_serializer = self.key_serializer
            value_serializer = self.value_serializer
            buffer.put_uint8(chunk_size_offset, chunk_size)

    def read(self, buffer):
        fury = self.fury
        ref_resolver = self.ref_resolver
        class_resolver = self.class_resolver
        size = buffer.read_varuint32()
        map_ = {}
        ref_resolver.reference(map_)
        chunk_header = 0
        if size != 0:
            chunk_header = buffer.read_uint8()
        key_serializer, value_serializer = None, None

        while size > 0:
            while True:
                key_has_null = (chunk_header & KEY_HAS_NULL) != 0
                value_has_null = (chunk_header & VALUE_HAS_NULL) != 0
                if not key_has_null:
                    if not value_has_null:
                        break
                    else:
                        track_key_ref = (chunk_header & TRACKING_KEY_REF) != 0
                        if (chunk_header & KEY_DECL_TYPE) != 0:
                            if track_key_ref:
                                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                                if ref_id < NOT_NULL_VALUE_FLAG:
                                    key = ref_resolver.get_read_object()
                                else:
                                    key = key_serializer.read(buffer)
                                    ref_resolver.set_read_object(ref_id, key)
                            else:
                                key = key_serializer.read(buffer)
                        else:
                            key = fury.deserialize_ref(buffer)
                        map_[key] = None
                else:
                    if not value_has_null:
                        track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
                        if (chunk_header & VALUE_DECL_TYPE) != 0:
                            if track_value_ref:
                                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                                if ref_id < NOT_NULL_VALUE_FLAG:
                                    value = ref_resolver.get_read_object()
                                else:
                                    value = value_serializer.read(buffer)
                                    ref_resolver.set_read_object(ref_id, value)
                        else:
                            value = fury.deserialize_ref(buffer)
                        map_[None] = value
                    else:
                        map_[None] = None
                size -= 1
                if size == 0:
                    return map_
                else:
                    chunk_header = buffer.read_uint8()

            track_key_ref = (chunk_header & TRACKING_KEY_REF) != 0
            track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
            key_is_declared_type = (chunk_header & KEY_DECL_TYPE) != 0
            value_is_declared_type = (chunk_header & VALUE_DECL_TYPE) != 0
            chunk_size = buffer.read_uint8()
            if not key_is_declared_type:
                key_serializer = class_resolver.read_typeinfo(buffer).serializer
            if not value_is_declared_type:
                value_serializer = class_resolver.read_typeinfo(buffer).serializer
            key_serializer_type = type(key_serializer)
            value_serializer_type = type(value_serializer)
            for i in range(chunk_size):
                if track_key_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        key = ref_resolver.get_read_object()
                    else:
                        key = key_serializer.read(buffer)
                        ref_resolver.set_read_object(ref_id, key)
                else:
                    if key_serializer_type is StringSerializer:
                        key = buffer.read_string()
                    elif key_serializer_type is Int64Serializer:
                        key = buffer.read_varint64()
                    elif key_serializer_type is Float64Serializer:
                        key = buffer.read_double()
                    elif key_serializer_type is Int32Serializer:
                        key = buffer.read_varint32()
                    elif key_serializer_type is Float32Serializer:
                        key = buffer.read_float()
                    else:
                        key = key_serializer.read(buffer)
                if track_value_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        value = ref_resolver.get_read_object()
                    else:
                        value = value_serializer.read(buffer)
                        ref_resolver.set_read_object(ref_id, value)
                else:
                    if value_serializer_type is StringSerializer:
                        value = buffer.read_string()
                    elif value_serializer_type is Int64Serializer:
                        value = buffer.read_varint64()
                    elif value_serializer_type is Float64Serializer:
                        value = buffer.read_double()
                    elif value_serializer_type is Int32Serializer:
                        value = buffer.read_varint32()
                    elif value_serializer_type is Float32Serializer:
                        value = buffer.read_float()
                    elif value_serializer_type is BooleanSerializer:
                        value = buffer.read_bool()
                    else:
                        value = value_serializer.read(buffer)
                map_[key] = value
                size -= 1
            if size != 0:
                chunk_header = buffer.read_uint8()

        return map_

    def xwrite(self, buffer, value: Dict):
        buffer.write_varuint32(len(value))
        for k, v in value.items():
            self.fury.xserialize_ref(buffer, k, serializer=self.key_serializer)
            self.fury.xserialize_ref(buffer, v, serializer=self.value_serializer)

    def xread(self, buffer):
        len_ = buffer.read_varuint32()
        map_ = {}
        self.fury.ref_resolver.reference(map_)
        for i in range(len_):
            k = self.fury.xdeserialize_ref(buffer, serializer=self.key_serializer)
            v = self.fury.xdeserialize_ref(buffer, serializer=self.value_serializer)
            map_[k] = v
        return map_


SubMapSerializer = MapSerializer


class EnumSerializer(Serializer):
    @classmethod
    def support_subclass(cls) -> bool:
        return True

    def write(self, buffer, value):
        buffer.write_string(value.name)

    def read(self, buffer):
        name = buffer.read_string()
        return getattr(self.type_, name)

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError


class SliceSerializer(Serializer):
    def write(self, buffer, value: slice):
        start, stop, step = value.start, value.stop, value.step
        if type(start) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(start)
        else:
            if start is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fury.serialize_nonref(buffer, start)
        if type(stop) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fury.serialize_nonref(buffer, stop)
        if type(step) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fury.serialize_nonref(buffer, step)

    def read(self, buffer):
        if buffer.read_int8() == NULL_FLAG:
            start = None
        else:
            start = self.fury.deserialize_nonref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            stop = None
        else:
            stop = self.fury.deserialize_nonref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            step = None
        else:
            step = self.fury.deserialize_nonref(buffer)
        return slice(start, stop, step)

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError
