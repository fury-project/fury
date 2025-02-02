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

import { Meta } from "./meta";

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
  OBJECT,

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
  meta: Meta;
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

export enum Mode {
  SchemaConsistent,
  Compatible,
}

export interface Config {
  refTracking?: boolean;
  useSliceString?: boolean;
  hooks?: {
    afterCodeGenerated?: (code: string) => string;
  };
  mode?: Mode;
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
