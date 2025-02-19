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

import ClassResolver from "./classResolver";
import { BinaryWriter } from "./writer";
import { BinaryReader } from "./reader";
import { ReferenceResolver } from "./referenceResolver";
import { ConfigFlags, Serializer, Config, Language, MAGIC_NUMBER, Mode, FuryClsInfoSymbol, WithFuryClsInfo } from "./type";
import { OwnershipError } from "./error";
import { InputType, ResultType, ClassInfo } from "./classInfo";
import { Gen, AnySerializer } from "./gen";
import { TypeMeta } from "./meta/TypeMeta";
import { PlatformBuffer } from "./platformBuffer";
import { TypeMetaResolver } from "./typeMetaResolver";
import { MetaStringResolver } from "./metaStringResolver";

export default class {
  binaryReader: BinaryReader;
  binaryWriter: BinaryWriter;
  classResolver: ClassResolver;
  typeMetaResolver: TypeMetaResolver;
  metaStringResolver: MetaStringResolver;
  referenceResolver: ReferenceResolver;
  anySerializer: AnySerializer;
  typeMeta = TypeMeta;
  config: Config;

  constructor(config?: Partial<Config>) {
    this.config = this.initConfig(config);
    this.binaryReader = new BinaryReader(this.config);
    this.binaryWriter = new BinaryWriter(this.config);
    this.referenceResolver = new ReferenceResolver(this.binaryReader);
    this.typeMetaResolver = new TypeMetaResolver(this);
    this.classResolver = new ClassResolver(this);
    this.anySerializer = new AnySerializer(this);
    this.metaStringResolver = new MetaStringResolver(this);
    this.classResolver.init();
  }

  private initConfig(config: Partial<Config> | undefined) {
    return {
      refTracking: Boolean(config?.refTracking),
      useSliceString: Boolean(config?.useSliceString),
      hooks: config?.hooks || {},
      mode: config?.mode || Mode.SchemaConsistent,
      constructClass: Boolean(config?.constructClass),
    };
  }

  registerSerializer<T extends new () => any>(constructor: T, replace?: boolean): {
    serializer: Serializer;
    serialize(data: Partial<InstanceType<T>> | null): PlatformBuffer;
    serializeVolatile(data: Partial<InstanceType<T>>): {
      get: () => Uint8Array;
      dispose: () => void;
    };
    deserialize(bytes: Uint8Array): InstanceType<T> | null;
  };
  registerSerializer<T extends ClassInfo>(classInfo: T, replace?: boolean): {
    serializer: Serializer;
    serialize(data: InputType<T> | null): PlatformBuffer;
    serializeVolatile(data: InputType<T>): {
      get: () => Uint8Array;
      dispose: () => void;
    };
    deserialize(bytes: Uint8Array): ResultType<T>;
  };
  registerSerializer(constructor: any, replace = false) {
    let serializer: Serializer;
    if (constructor.prototype?.[FuryClsInfoSymbol]) {
      const classInfo: ClassInfo = (<WithFuryClsInfo>(constructor.prototype[FuryClsInfoSymbol])).structClassInfo;
      serializer = new Gen(this, replace, { constructor }).generateSerializer(classInfo);
      this.classResolver.registerSerializer(classInfo, serializer);
    } else {
      const classInfo = constructor;
      serializer = new Gen(this, replace).generateSerializer(classInfo);
    }
    return {
      serializer,
      serialize: (data: any) => {
        return this.serialize(data, serializer);
      },
      serializeVolatile: (data: any) => {
        return this.serializeVolatile(data, serializer);
      },
      deserialize: (bytes: Uint8Array) => {
        return this.deserialize(bytes, serializer);
      },
    };
  }

  deserialize<T = any>(bytes: Uint8Array, serializer: Serializer = this.anySerializer): T | null {
    this.referenceResolver.reset();
    this.binaryReader.reset(bytes);
    this.typeMetaResolver.reset();
    this.metaStringResolver.reset();
    if (this.binaryReader.int16() !== MAGIC_NUMBER) {
      throw new Error("the fury xlang serialization must start with magic number 0x%x. Please check whether the serialization is based on the xlang protocol and the data didn't corrupt");
    }
    const bitmap = this.binaryReader.uint8();
    if ((bitmap & ConfigFlags.isNullFlag) === ConfigFlags.isNullFlag) {
      return null;
    }
    const isLittleEndian = (bitmap & ConfigFlags.isLittleEndianFlag) === ConfigFlags.isLittleEndianFlag;
    if (!isLittleEndian) {
      throw new Error("big endian is not supported now");
    }
    const isCrossLanguage = (bitmap & ConfigFlags.isCrossLanguageFlag) == ConfigFlags.isCrossLanguageFlag;
    if (!isCrossLanguage) {
      throw new Error("support crosslanguage mode only");
    }
    const isOutOfBandEnabled = (bitmap & ConfigFlags.isOutOfBandFlag) === ConfigFlags.isOutOfBandFlag;
    if (isOutOfBandEnabled) {
      throw new Error("outofband mode is not supported now");
    }

    this.binaryReader.uint8(); // skip language
    this.binaryReader.int32(); // native object offset. should skip.  javascript support cross mode only
    this.binaryReader.int32(); // native object size. should skip.
    return serializer.read();
  }

  private serializeInternal<T = any>(data: T, serializer: Serializer) {
    try {
      this.binaryWriter.reset();
    } catch (e) {
      if (e instanceof OwnershipError) {
        throw new Error("Permission denied. To release the serialization ownership, you must call the dispose function returned by serializeVolatile.");
      }
      throw e;
    }
    this.referenceResolver.reset();
    let bitmap = 0;
    if (data === null) {
      bitmap |= ConfigFlags.isNullFlag;
    }
    bitmap |= ConfigFlags.isLittleEndianFlag;
    bitmap |= ConfigFlags.isCrossLanguageFlag;
    this.binaryWriter.int16(MAGIC_NUMBER);
    this.binaryWriter.uint8(bitmap);
    this.binaryWriter.uint8(Language.JAVASCRIPT);
    const cursor = this.binaryWriter.getCursor();
    this.binaryWriter.skip(4); // preserve 4-byte for nativeObjects start offsets.
    this.binaryWriter.uint32(0); // nativeObjects length.
    // reserve fixed size
    this.binaryWriter.reserve(serializer.fixedSize);
    // start write
    serializer.write(data);
    this.binaryWriter.setUint32Position(cursor, this.binaryWriter.getCursor()); // nativeObjects start offsets;
    return this.binaryWriter;
  }

  serialize<T = any>(data: T, serializer: Serializer = this.anySerializer) {
    return this.serializeInternal(data, serializer).dump();
  }

  serializeVolatile<T = any>(data: T, serializer: Serializer = this.anySerializer) {
    return this.serializeInternal(data, serializer).dumpAndOwn();
  }
}
