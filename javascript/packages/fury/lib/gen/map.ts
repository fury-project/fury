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

import { MapClassInfo, ClassInfo } from "../classInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator, RefState, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { InternalSerializerType, RefFlags, Serializer } from "../type";
import { Scope } from "./scope";
import Fury from "../fury";

const MapFlags = {
  /** Whether track elements ref. */
  TRACKING_REF: 0b1,

  /** Whether collection has null. */
  HAS_NULL: 0b10,

  /** Whether collection elements type is not declare type. */
  NOT_DECL_ELEMENT_TYPE: 0b100,

  /** Whether collection elements type different. */
  NOT_SAME_TYPE: 0b1000,
};

class MapTypeInfo {
  private static IS_NULL = 0b10;
  private static TRACKING_REF = 0b01;
  static elementInfo(typeId: number, isNull: 0 | 1, trackRef: 0 | 1) {
    return (typeId << 16) | (isNull << 1) | trackRef;
  }

  static isNull(info: number) {
    return info & this.IS_NULL;
  }

  static trackingRef(info: number) {
    return info & this.TRACKING_REF;
  }
}

class MapChunkWriter {
  private preKeyInfo = 0;
  private preValueInfo = 0;

  private chunkSize = 0;
  private chunkOffset = 0;
  private header = 0;

  constructor(private fury: Fury) {

  }

  private getHead(keyInfo: number, valueInfo: number) {
    let flag = 0;
    if (MapTypeInfo.isNull(keyInfo)) {
      flag |= MapFlags.HAS_NULL;
    }
    if (MapTypeInfo.trackingRef(keyInfo)) {
      flag |= MapFlags.TRACKING_REF;
    }
    flag <<= 4;
    if (MapTypeInfo.isNull(valueInfo)) {
      flag |= MapFlags.HAS_NULL;
    }
    if (MapTypeInfo.trackingRef(valueInfo)) {
      flag |= MapFlags.TRACKING_REF;
    }
    return flag;
  }

  private writeHead(keyInfo: number, valueInfo: number) {
    // chunkSize, max 255
    this.chunkOffset = this.fury.binaryWriter.getCursor();
    // KV header
    const header = this.getHead(keyInfo, valueInfo);
    // chunkSize default 0 | KV header
    this.fury.binaryWriter.uint16(header << 8);
    // key TypeId | value TypeId
    this.fury.binaryWriter.uint32((keyInfo >> 16) | (valueInfo & 0xFFFF0000));
    return header;
  }

  next(keyInfo: number, valueInfo: number) {
    // max size of chunk is 255
    if (this.chunkSize == 255
      || this.chunkOffset == 0
      || this.preKeyInfo !== keyInfo
      || this.preValueInfo !== valueInfo
    ) {
      // new chunk
      this.endChunk();
      this.chunkSize++;
      this.preKeyInfo = keyInfo;
      this.preValueInfo = valueInfo;
      return this.header = this.writeHead(keyInfo, valueInfo);
    }
    this.chunkSize++;
    return this.header;
  }

  endChunk() {
    if (this.chunkOffset > 0) {
      this.fury.binaryWriter.setUint8Position(this.chunkOffset, this.chunkSize);
      this.chunkSize = 0;
    }
  }
}

class MapAnySerializer {
  private keySerializer: Serializer | null = null;
  private valueSerializer: Serializer | null = null;

  constructor(private fury: Fury, keySerializerId: null | number, valueSerializerId: null | number) {
    if (keySerializerId !== null) {
      fury.classResolver.getSerializerById(keySerializerId);
    }
    if (valueSerializerId !== null) {
      fury.classResolver.getSerializerById(valueSerializerId);
    }
  }

  private writeHead(header: number, v: any) {
    if (header !== 0) {
      if (header & MapFlags.HAS_NULL) {
        if (v === null || v === undefined) {
          this.fury.binaryWriter.uint8(RefFlags.NullFlag);
        }
      }
      if (header & MapFlags.TRACKING_REF) {
        const keyRef = this.fury.referenceResolver.existsWriteObject(v);
        if (keyRef !== undefined) {
          this.fury.binaryWriter.uint8(RefFlags.RefFlag);
          this.fury.binaryWriter.uint16(keyRef);
        } else {
          this.fury.binaryWriter.uint8(RefFlags.RefValueFlag);
        }
      } else {
        this.fury.binaryWriter.uint8(RefFlags.NotNullValueFlag);
      }
    }
  }

  write(value: Map<any, any>) {
    const mapChunkWriter = new MapChunkWriter(this.fury);
    this.fury.binaryWriter.varInt32(value.size);
    for (const [k, v] of value.entries()) {
      const keySerializer = this.keySerializer !== null ? this.keySerializer : this.fury.classResolver.getSerializerByData(k);
      const valueSerializer = this.valueSerializer !== null ? this.valueSerializer : this.fury.classResolver.getSerializerByData(v);

      const header = mapChunkWriter.next(
        MapTypeInfo.elementInfo(keySerializer!.getTypeId()!, k == null ? 1 : 0, keySerializer!.needToWriteRef() ? 1 : 0),
        MapTypeInfo.elementInfo(valueSerializer!.getTypeId()!, v == null ? 1 : 0, valueSerializer!.needToWriteRef() ? 1 : 0)
      );

      this.writeHead(header >> 4, k);
      keySerializer!.writeInner(k);
      this.writeHead(header & 0b00001111, v);
      valueSerializer!.writeInner(v);
    }
    mapChunkWriter.endChunk();
  }

  private readElement(header: number, serializer: Serializer | null) {
    if (header === 0) {
      return serializer!.readInner(false);
    }
    const isSame = !(header & MapFlags.NOT_SAME_TYPE);
    const includeNone = header & MapFlags.HAS_NULL;
    const trackingRef = header & MapFlags.TRACKING_REF;

    let flag = 0;
    if (trackingRef || includeNone) {
      flag = this.fury.binaryReader.uint8();
    }
    if (!isSame) {
      serializer = this.fury.classResolver.getSerializerById(this.fury.binaryReader.int16());
    }
    switch (flag) {
      case RefFlags.RefValueFlag:
        return serializer!.readInner(true);
      case RefFlags.RefFlag:
        return this.fury.referenceResolver.getReadObject(this.fury.binaryReader.varUInt32());
      case RefFlags.NullFlag:
        return null;
      case RefFlags.NotNullValueFlag:
        return serializer!.readInner(false);
    }
  }

  read(fromRef: boolean): any {
    let count = this.fury.binaryReader.varInt32();
    const result = new Map();
    if (fromRef) {
      this.fury.referenceResolver.reference(result);
    }
    while (count > 0) {
      const header = this.fury.binaryReader.uint16();
      const keyHeader = header >> 12;
      const valueHeader = (header >> 8) & 0b00001111;
      const chunkSize = header & 0b11111111;

      let keySerializer = null;
      let valueSerializer = null;

      if (!(keyHeader & MapFlags.NOT_SAME_TYPE)) {
        keySerializer = this.fury.classResolver.getSerializerById(this.fury.binaryReader.uint16());
      }
      if (!(valueHeader & MapFlags.NOT_SAME_TYPE)) {
        valueSerializer = this.fury.classResolver.getSerializerById(this.fury.binaryReader.uint16());
      }
      for (let index = 0; index < chunkSize; index++) {
        result.set(
          this.readElement(keyHeader, keySerializer),
          this.readElement(valueHeader, valueSerializer)
        );
        count--;
      }
    }
    return result;
  }
}

export class MapSerializerGenerator extends BaseSerializerGenerator {
  classInfo: MapClassInfo;
  keyGenerator: SerializerGenerator;
  valueGenerator: SerializerGenerator;

  constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classinfo, builder, scope);
    this.classInfo = <MapClassInfo>classinfo;
    this.keyGenerator = CodegenRegistry.newGeneratorByClassInfo(this.classInfo.options.key, this.builder, this.scope);
    this.valueGenerator = CodegenRegistry.newGeneratorByClassInfo(this.classInfo.options.value, this.builder, this.scope);
  }

  private isAny() {
    return this.classInfo.options.key.type === InternalSerializerType.ANY || this.classInfo.options.value.type === InternalSerializerType.ANY;
  }

  private writeStmtSpecificType(accessor: string) {
    const k = this.scope.uniqueName("k");
    const v = this.scope.uniqueName("v");
    const keyHeader = (this.keyGenerator.needToWriteRef() ? MapFlags.TRACKING_REF : 0);
    const valueHeader = (this.valueGenerator.needToWriteRef() ? MapFlags.TRACKING_REF : 0);
    const typeId = (this.keyGenerator.getTypeId()! << 8) | (this.valueGenerator.getTypeId()!);
    const lastKeyIsNull = this.scope.uniqueName("lastKeyIsNull");
    const lastValueIsNull = this.scope.uniqueName("lastValueIsNull");
    const chunkSize = this.scope.uniqueName("chunkSize");
    const chunkSizeOffset = this.scope.uniqueName("chunkSizeOffset");
    const keyRef = this.scope.uniqueName("keyRef");
    const valueRef = this.scope.uniqueName("valueRef");

    return `
      ${this.builder.writer.varInt32(`${accessor}.size`)}
      let ${lastKeyIsNull} = false;
      let ${lastValueIsNull} = false;
      let ${chunkSize} = 0;
      let ${chunkSizeOffset} = 0;

      for (const [${k}, ${v}] of ${accessor}.entries()) {
        let keyIsNull = ${k} === null || ${k} === undefined;
        let valueIsNull = ${v} === null || ${v} === undefined;

        if (${lastKeyIsNull} !== keyIsNull || ${lastValueIsNull} !== valueIsNull || ${chunkSize} === 0 || ${chunkSize} === 255) {
          if (${chunkSize} > 0) {
            ${this.builder.writer.setUint8Position(chunkSizeOffset, chunkSize)};
            ${chunkSize} = 0;
          }
          ${chunkSizeOffset} = ${this.builder.writer.getCursor()}
          ${
            this.builder.writer.uint16(
              `(((${keyHeader} | (keyIsNull ? ${MapFlags.HAS_NULL} : 0)) << 4) | (${valueHeader} | (valueIsNull ? ${MapFlags.HAS_NULL} : 0))) << 8`
            )
          }
          ${this.builder.writer.uint32(typeId)};

          ${lastKeyIsNull} = keyIsNull;
          ${lastValueIsNull} = valueIsNull;
        }
        if (keyIsNull) {
          ${this.builder.writer.uint8(RefFlags.NullFlag)}
        }
        ${this.keyGenerator.needToWriteRef()
? `
            const ${keyRef} = ${this.builder.referenceResolver.existsWriteObject(v)};
            if (${keyRef} !== undefined) {
              ${this.builder.writer.uint8(RefFlags.RefFlag)};
              ${this.builder.writer.uint16(keyRef)};
            } else {
              ${this.builder.writer.uint8(RefFlags.RefValueFlag)};
              ${this.keyGenerator.toWriteEmbed(k, true)}
            }
        `
: this.keyGenerator.toWriteEmbed(k, true)}
       

        if (valueIsNull) {
          ${this.builder.writer.uint8(RefFlags.NullFlag)}
        }
        ${this.valueGenerator.needToWriteRef()
? `
            const ${valueRef} = ${this.builder.referenceResolver.existsWriteObject(v)};
            if (${valueRef} !== undefined) {
              ${this.builder.writer.uint8(RefFlags.RefFlag)};
              ${this.builder.writer.uint16(valueRef)};
            } else {
              ${this.builder.writer.uint8(RefFlags.RefValueFlag)};
              ${this.valueGenerator.toWriteEmbed(v, true)};
            }
        `
: this.valueGenerator.toWriteEmbed(v, true)}
        

        ${chunkSize}++;
      }
      if (${chunkSize} > 0) {
        ${this.builder.writer.setUint8Position(chunkSizeOffset, chunkSize)};
      }
    `;
  }

  writeStmt(accessor: string): string {
    const anySerializer = this.builder.getExternal(MapAnySerializer.name);
    if (!this.isAny()) {
      return this.writeStmtSpecificType(accessor);
    }
    return `new (${anySerializer})(${this.builder.getFuryName()}, ${
      this.classInfo.options.key.type !== InternalSerializerType.ANY ? this.classInfo.options.key.typeId : null
    }, ${
      this.classInfo.options.value.type !== InternalSerializerType.ANY ? this.classInfo.options.value.typeId : null
    }).write(${accessor})`;
  }

  private readStmtSpecificType(accessor: (expr: string) => string, refState: RefState) {
    const count = this.scope.uniqueName("count");
    const result = this.scope.uniqueName("result");

    return `
      let ${count} = ${this.builder.reader.varInt32()};
      const ${result} = new Map();
      if (${refState.toConditionExpr()}) {
        ${this.builder.referenceResolver.reference(result)}
      }
      while (${count} > 0) {
        const header = ${this.builder.reader.uint16()};
        const keyHeader = header >> 12;
        const valueHeader = (header >> 8) & 0b00001111;
        const chunkSize = header & 0b11111111;
        ${this.builder.reader.skip(4)};
        const keyIncludeNone = keyHeader & ${MapFlags.HAS_NULL};
        const keyTrackingRef = keyHeader & ${MapFlags.TRACKING_REF};
        const valueIncludeNone = valueHeader & ${MapFlags.HAS_NULL};
        const valueTrackingRef = valueHeader & ${MapFlags.TRACKING_REF};
    
        for (let index = 0; index < chunkSize; index++) {
          let key;
          let value;
          let flag = 0;
          if (keyTrackingRef || keyIncludeNone) {
            flag = ${this.builder.reader.uint8()};
          }
          switch (flag) {
            case ${RefFlags.RefValueFlag}:
              ${this.keyGenerator.toReadEmbed(x => `key = ${x}`, true, RefState.fromTrue())}
              break;
            case ${RefFlags.RefFlag}:
              key = ${this.builder.referenceResolver.getReadObject(this.builder.reader.varInt32())}
              break;
            case ${RefFlags.NullFlag}:
              key = null;
              break;
            case ${RefFlags.NotNullValueFlag}:
              ${this.keyGenerator.toReadEmbed(x => `key = ${x}`, true, RefState.fromFalse())}
              break;
          }
          flag = 0;
          if (valueTrackingRef || valueIncludeNone) {
            flag = ${this.builder.reader.uint8()};
          }
          switch (flag) {
            case ${RefFlags.RefValueFlag}:
              ${this.valueGenerator.toReadEmbed(x => `value = ${x}`, true, RefState.fromTrue())}
              break;
            case ${RefFlags.RefFlag}:
              value = ${this.builder.referenceResolver.getReadObject(this.builder.reader.varInt32())}
              break;
            case ${RefFlags.NullFlag}:
              value = null;
              break;
            case ${RefFlags.NotNullValueFlag}:
              ${this.valueGenerator.toReadEmbed(x => `value = ${x}`, true, RefState.fromFalse())}
              break;
          }
          ${result}.set(
            key,
            value
          );
          ${count}--;
        }
      }
      ${accessor(result)}
    `;
  }

  readStmt(accessor: (expr: string) => string, refState: RefState): string {
    const anySerializer = this.builder.getExternal(MapAnySerializer.name);
    if (!this.isAny()) {
      return this.readStmtSpecificType(accessor, refState);
    }
    return accessor(`new (${anySerializer})(${this.builder.getFuryName()}, ${
      this.classInfo.options.key.type !== InternalSerializerType.ANY ? (this.classInfo.options.key.typeId) : null
    }, ${
      this.classInfo.options.value.type !== InternalSerializerType.ANY ? (this.classInfo.options.value.typeId) : null
    }).read(${refState.toConditionExpr()})`);
  }

  getFixedSize(): number {
    return 7;
  }

  needToWriteRef(): boolean {
    return Boolean(this.builder.fury.config.refTracking);
  }
}

CodegenRegistry.registerExternal(MapAnySerializer);
CodegenRegistry.register(InternalSerializerType.MAP, MapSerializerGenerator);
