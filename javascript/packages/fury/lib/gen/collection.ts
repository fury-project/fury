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

import { ClassInfo } from "../classInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator, RefState, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { InternalSerializerType, RefFlags, Serializer } from "../type";
import { Scope } from "./scope";
import Fury from "../fury";

export const CollectionFlags = {
  /** Whether track elements ref. */
  TRACKING_REF: 0b1,

  /** Whether collection has null. */
  HAS_NULL: 0b10,

  /** Whether collection elements type is not declare type. */
  NOT_DECL_ELEMENT_TYPE: 0b100,

  /** Whether collection elements type different. */
  NOT_SAME_TYPE: 0b1000,
};

class CollectionAnySerializer {
  constructor(private fury: Fury) {

  }

  protected writeElementsHeader(arr: any) {
    let flag = 0;
    let isSame = true;
    let serializer: Serializer | null | undefined = null;
    let includeNone = false;

    for (const item of arr) {
      if ((item === undefined || item === null) && !includeNone) {
        includeNone = true;
      }
      if (isSame) {
        const current = this.fury.classResolver.getSerializerByData(item);
        if (serializer !== null && serializer !== undefined && current !== serializer) {
          isSame = false;
        } else {
          serializer = current;
        }
      }
    }

    if (!isSame) {
      flag |= CollectionFlags.NOT_SAME_TYPE;
    }
    if (includeNone) {
      flag |= CollectionFlags.HAS_NULL;
    }
    if (serializer && serializer!.needToWriteRef()) {
      flag |= CollectionFlags.TRACKING_REF;
    }
    this.fury.binaryWriter.uint8(flag);
    if (isSame) {
      this.fury.binaryWriter.int16(serializer ? serializer!.getTypeId() : 0);
    }
    return {
      serializer,
      isSame,
      flag,
      includeNone,
    };
  }

  write(value: any, size: number) {
    const { serializer, isSame, includeNone } = this.writeElementsHeader(value);
    this.fury.binaryWriter.varUInt32(size);
    for (const item of value) {
      if (includeNone) {
        if (item === null || item === undefined) {
          this.fury.binaryWriter.uint8(RefFlags.NullFlag);
          continue;
        } else {
          this.fury.binaryWriter.uint8(RefFlags.NotNullValueFlag);
        }
      }
      let finalSerializer = serializer;
      if (!isSame) {
        finalSerializer = this.fury.classResolver.getSerializerByData(item);
        this.fury.binaryWriter.uint16(finalSerializer!.getTypeId());
      }
      finalSerializer!.write(item);
    }
  }

  read(accessor: (result: any, index: number, v: any) => void, createCollection: (len: number) => any, fromRef: boolean): any {
    const flags = this.fury.binaryReader.uint8();
    const isSame = !(flags & CollectionFlags.NOT_SAME_TYPE);
    const includeNone = flags & CollectionFlags.HAS_NULL;

    let serializer: Serializer;
    if (isSame) {
      serializer = this.fury.classResolver.getSerializerById(this.fury.binaryReader.int16());
    }
    const len = this.fury.binaryReader.varUInt32();
    const result = createCollection(len);
    if (fromRef) {
      this.fury.referenceResolver.reference(result);
    }
    for (let index = 0; index < len; index++) {
      if (includeNone) {
        const refFlag = this.fury.binaryReader.uint8();
        if (RefFlags.NullFlag === refFlag) {
          accessor(result, index, null);
          continue;
        }
      }
      if (!isSame) {
        serializer = this.fury.classResolver.getSerializerById(this.fury.binaryReader.int16());
      }
      accessor(result, index, serializer!.read());
    }
    return result;
  }
}

export abstract class CollectionSerializerGenerator extends BaseSerializerGenerator {
  classInfo: ClassInfo;
  innerGenerator: SerializerGenerator;

  constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classinfo, builder, scope);
    this.classInfo = classinfo;
    const inner = this.genericTypeDescriptin();
    this.innerGenerator = CodegenRegistry.newGeneratorByClassInfo(inner, this.builder, this.scope);
  }

  abstract genericTypeDescriptin(): ClassInfo;

  private isAny() {
    return this.genericTypeDescriptin().type === InternalSerializerType.ANY;
  }

  abstract newCollection(lenAccessor: string): string;

  abstract putAccessor(result: string, item: string, index: string): string;

  abstract sizeProp(): string;

  protected writeElementsHeader(accessor: string, flagAccessor: string) {
    const item = this.scope.uniqueName("item");
    const stmts = [
    ];
    if (this.innerGenerator.needToWriteRef()) {
      stmts.push(`${flagAccessor} |= ${CollectionFlags.TRACKING_REF}`);
    }
    stmts.push(`
        for (const ${item} of ${accessor}) {
            if (${item} === null || ${item} === undefined) {
                ${flagAccessor} |= ${CollectionFlags.HAS_NULL};
                break;
            }
        }
    `);
    stmts.push(`${this.builder.writer.uint8(flagAccessor)}`);
    return stmts.join("\n");
  }

  writeStmtSpecificType(accessor: string): string {
    const item = this.scope.uniqueName("item");
    const flags = this.scope.uniqueName("flags");
    const existsId = this.scope.uniqueName("existsId");

    return `
            let ${flags} = 0;
            ${this.writeElementsHeader(accessor, flags)}
            ${this.builder.writer.int16(this.classInfo.type)}
            ${this.builder.writer.varUInt32(`${accessor}.${this.sizeProp()}`)}
            ${this.builder.writer.reserve(`${this.innerGenerator.getFixedSize()} * ${accessor}.${this.sizeProp()}`)};
            if (${flags} & ${CollectionFlags.TRACKING_REF}) {
                
                for (const ${item} of ${accessor}) {
                    if (${accessor} !== null && ${accessor} !== undefined) {
                        const ${existsId} = ${this.builder.referenceResolver.existsWriteObject(item)};
                        if (typeof ${existsId} === "number") {
                            ${this.builder.writer.int8(RefFlags.RefFlag)}
                            ${this.builder.writer.varUInt32(existsId)}
                        } else {
                            ${this.builder.referenceResolver.writeRef(item)}
                            ${this.builder.writer.int8(RefFlags.RefValueFlag)};
                            ${this.innerGenerator.toWriteEmbed(item, true)}
                        }
                    } else {
                        ${this.builder.writer.int8(RefFlags.NullFlag)};
                    }
                }
            } else {
                if (${flags} & ${CollectionFlags.HAS_NULL}) {
                    for (const ${item} of ${accessor}) {
                        if (${accessor} !== null && ${accessor} !== undefined) {
                            ${this.builder.writer.int8(RefFlags.NotNullValueFlag)};
                            ${this.innerGenerator.toWriteEmbed(item, true)}
                        } else {
                            ${this.builder.writer.int8(RefFlags.NullFlag)};
                        }
                    }
                } else {
                    for (const ${item} of ${accessor}) {
                        ${this.innerGenerator.toWriteEmbed(item, true)}
                    }
                }
            }
        `;
  }

  readStmtSpecificType(accessor: (expr: string) => string, refState: RefState): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");
    const flags = this.scope.uniqueName("flags");
    const idx = this.scope.uniqueName("idx");
    const refFlag = this.scope.uniqueName("refFlag");

    // If track elements ref, use first bit 0b1 of header to flag it.
    // If collection has null, use second bit 0b10 of header to flag it. If ref tracking is enabled for this element type, this flag is invalid.
    // If collection element types is not declared type, use 3rd bit 0b100 of header to flag it.
    // If collection element types different, use 4rd bit 0b1000 of header to flag it.
    return `
            const ${flags} = ${this.builder.reader.uint8()};
            ${this.builder.reader.skip(2)};
            const ${len} = ${this.builder.reader.varUInt32()};
            const ${result} = ${this.newCollection(len)};
            ${this.maybeReference(result, refState)}
            if (${flags} & ${CollectionFlags.TRACKING_REF}) {
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                    const ${refFlag} = ${this.builder.reader.int8()};
                    switch (${refFlag}) {
                        case ${RefFlags.NotNullValueFlag}:
                        case ${RefFlags.RefValueFlag}:
                            ${this.innerGenerator.toReadEmbed(x => `${this.putAccessor(result, x, idx)}`, true, RefState.fromCondition(`${refFlag} === ${RefFlags.RefValueFlag}`))}
                            break;
                        case ${RefFlags.RefFlag}:
                            ${this.putAccessor(result, this.builder.referenceResolver.getReadObject(this.builder.reader.varUInt32()), idx)}
                            break;
                        case ${RefFlags.NullFlag}:
                            ${this.putAccessor(result, "null", idx)}
                            break;
                    }
                }
            } else {
                if (!(${flags} & ${CollectionFlags.HAS_NULL})) {
                    for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                        ${this.innerGenerator.toReadEmbed(x => `${this.putAccessor(result, x, idx)}`, true, RefState.fromFalse())}
                    }
                } else {
                    for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                        if (${this.builder.reader.uint8()} == ${RefFlags.NullFlag}) {
                            ${this.putAccessor(result, "null", idx)}
                        } else {
                            ${this.innerGenerator.toReadEmbed(x => `${this.putAccessor(result, x, idx)}`, true, RefState.fromFalse())}
                        }
                    }
                }
            }
            ${accessor(result)}
        `;
  }

  writeStmt(accessor: string): string {
    if (this.isAny()) {
      return `
                new (${this.builder.getExternal(CollectionAnySerializer.name)})(${this.builder.getFuryName()}).write(${accessor}, ${accessor}.${this.sizeProp()})
            `;
    }
    return this.writeStmtSpecificType(accessor);
  }

  readStmt(accessor: (expr: string) => string, refState: RefState): string {
    if (this.isAny()) {
      return accessor(`new (${this.builder.getExternal(CollectionAnySerializer.name)})(${this.builder.getFuryName()}).read((result, i, v) => {
              ${this.putAccessor("result", "v", "i")};
          }, (len) => ${this.newCollection("len")}, ${refState.toConditionExpr()});
      `);
    }
    return this.readStmtSpecificType(accessor, refState);
  }
}

CodegenRegistry.registerExternal(CollectionSerializerGenerator);
CodegenRegistry.registerExternal(CollectionAnySerializer);
