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

import { StructClassInfo } from "./classInfo";

export const TypeId = {
  // a boolean value (true or false).
  BOOL: 1,
  // a 8-bit signed integer.
  INT8: 2,
  // a 16-bit signed integer.
  INT16: 3,
  // a 32-bit signed integer.
  INT32: 4,
  // a 32-bit signed integer which uses fury var_int32 encoding.
  VAR_INT32: 5,
  // a 64-bit signed integer.
  INT64: 6,
  // a 64-bit signed integer which uses fury PVL encoding.
  VAR_INT64: 7,
  // a 64-bit signed integer which uses fury SLI encoding.
  SLI_INT64: 8,
  // a 16-bit floating point number.
  FLOAT16: 9,
  // a 32-bit floating point number.
  FLOAT32: 10,
  // a 64-bit floating point number including NaN and Infinity.
  FLOAT64: 11,
  // a text string encoded using Latin1/UTF16/UTF-8 encoding.
  STRING: 12,
  // a data type consisting of a set of named values. Rust enum with non-predefined field values are not supported as
  // an enum.
  ENUM: 13,
  // an enum whose value will be serialized as the registered name.
  NAMED_ENUM: 14,
  // a morphic(final) type serialized by Fury Struct serializer. i.e., it doesn't have subclasses. Suppose we're
  // deserializing `List[SomeClass]`, we can save dynamic serializer dispatch since `SomeClass` is morphic(final).
  STRUCT: 15,
  // a morphic(final) type serialized by Fury compatible Struct serializer.
  COMPATIBLE_STRUCT: 16,
  // a `struct` whose type mapping will be encoded as a name.
  NAMED_STRUCT: 17,
  // a `compatible_struct` whose type mapping will be encoded as a name.
  NAMED_COMPATIBLE_STRUCT: 18,
  // a type which will be serialized by a customized serializer.
  EXT: 19,
  // an `ext` type whose type mapping will be encoded as a name.
  NAMED_EXT: 20,
  // a sequence of objects.
  LIST: 21,
  // an unordered set of unique elements.
  SET: 22,
  // a map of key-value pairs. Mutable types such as `list/map/set/array/tensor/arrow` are not allowed as key of map.
  MAP: 23,
  // an absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
  DURATION: 24,
  // a point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is relative
  // to an epoch at UTC midnight on January 1, 1970.
  TIMESTAMP: 25,
  // a naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1, 1970.
  LOCAL_DATE: 26,
  // exact decimal value represented as an integer value in two's complement.
  DECIMAL: 27,
  // a variable-length array of bytes.
  BINARY: 28,
  // a multidimensional array which every sub-array can have different sizes but all have the same type.
  // only allow numeric components. Other arrays will be taken as List. The implementation should support the
  // interoperability between array and list.
  ARRAY: 29,
  // one dimensional bool array.
  BOOL_ARRAY: 30,
  // one dimensional int8 array.
  INT8_ARRAY: 31,
  // one dimensional int16 array.
  INT16_ARRAY: 32,
  // one dimensional int32 array.
  INT32_ARRAY: 33,
  // one dimensional int64 array.
  INT64_ARRAY: 34,
  // one dimensional half_float_16 array.
  FLOAT16_ARRAY: 35,
  // one dimensional float32 array.
  FLOAT32_ARRAY: 36,
  // one dimensional float64 array.
  FLOAT64_ARRAY: 37,
  // an arrow [record batch](https://arrow.apache.org/docs/cpp/tables.html#record-batches) object.
  ARROW_RECORD_BATCH: 38,
  // an arrow [table](https://arrow.apache.org/docs/cpp/tables.html#tables) object.
  ARROW_TABLE: 39,

  // BOUND id remains at 64
  BOUND: 64,

  IS_NAMED_TYPE(id: number) {
    return [TypeId.NAMED_COMPATIBLE_STRUCT, TypeId.NAMED_ENUM, TypeId.NAMED_EXT, TypeId.NAMED_STRUCT].includes(id);
  },

};

export enum InternalSerializerType {
  // primitive type
  BOOL,
  INT8,
  INT16,
  INT32,
  VAR_INT32,
  INT64,
  VAR_INT64,
  SLI_INT64,
  FLOAT16,
  FLOAT32,
  FLOAT64,
  STRING,
  ENUM,
  LIST,
  SET,
  MAP,
  DURATION,
  TIMESTAMP,
  DECIMAL,
  BINARY,
  ARRAY,
  BOOL_ARRAY,
  INT8_ARRAY,
  INT16_ARRAY,
  INT32_ARRAY,
  INT64_ARRAY,
  FLOAT16_ARRAY,
  FLOAT32_ARRAY,
  FLOAT64_ARRAY,
  STRUCT,

  // alias type, only use by javascript
  ANY,
  ONEOF,
  TUPLE,
}

export enum ConfigFlags {
  isNullFlag = 1 << 0,
  isLittleEndianFlag = 2,
  isCrossLanguageFlag = 4,
  isOutOfBandFlag = 8,
}

// read, write
export type Serializer<T = any, T2 = any> = {
  read: () => T2;
  write: (v: T2) => T;
  readInner: (refValue?: boolean) => T2;
  writeInner: (v: T2) => T;
  fixedSize: number;
  needToWriteRef: () => boolean;
  getTypeId: () => number;
};

export enum RefFlags {
  NullFlag = -3,
  // RefFlag indicates that object is a not-null value.
  // We don't use another byte to indicate REF, so that we can save one byte.
  RefFlag = -2,
  // NotNullValueFlag indicates that the object is a non-null value.
  NotNullValueFlag = -1,
  // RefValueFlag indicates that the object is a referencable and first read.
  RefValueFlag = 0,
}

export const MaxInt32 = 2147483647;
export const MinInt32 = -2147483648;
export const MaxUInt32 = 0xFFFFFFFF;
export const MinUInt32 = 0;
export const HalfMaxInt32 = MaxInt32 / 2;
export const HalfMinInt32 = MinInt32 / 2;

export const LATIN1 = 0;
export const UTF8 = 1;
export const UTF16 = 2;
export interface Hps {
  serializeString: (str: string, dist: Uint8Array, offset: number) => number;
}

export enum Mode {
  SchemaConsistent,
  Compatible,
}

export interface Config {
  hps?: Hps;
  constructClass: boolean;
  refTracking: boolean;
  useSliceString: boolean;
  hooks: {
    afterCodeGenerated?: (code: string) => string;
  };
  mode: Mode;
}

export enum Language {
  XLANG = 0,
  JAVA = 1,
  PYTHON = 2,
  CPP = 3,
  GO = 4,
  JAVASCRIPT = 5,
  RUST = 6,
}

export const MAGIC_NUMBER = 0x62D4;

export interface WithFuryClsInfo {
  structClassInfo: StructClassInfo;
}

export const FuryClsInfoSymbol = Symbol("furyClsInfo");
