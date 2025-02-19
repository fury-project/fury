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
import { BaseSerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { InternalSerializerType } from "../type";
import { Scope } from "./scope";

class TimestampSerializerGenerator extends BaseSerializerGenerator {
  classInfo: ClassInfo;

  constructor(classInfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classInfo, builder, scope);
    this.classInfo = classInfo;
  }

  writeStmt(accessor: string): string {
    if (/^-?[0-9]+$/.test(accessor)) {
      return this.builder.writer.int64(`BigInt(${accessor})`);
    }
    return this.builder.writer.int64(`BigInt(${accessor}.getTime())`);
  }

  readStmt(accessor: (expr: string) => string): string {
    return accessor(`new Date(Number(${this.builder.reader.int64()}))`);
  }

  getFixedSize(): number {
    return 11;
  }

  needToWriteRef(): boolean {
    return false;
  }
}

class DurationSerializerGenerator extends BaseSerializerGenerator {
  classInfo: ClassInfo;

  constructor(classInfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classInfo, builder, scope);
    this.classInfo = classInfo;
  }

  writeStmt(accessor: string): string {
    const epoch = this.scope.declareByName("epoch", `new Date("1970/01/01 00:00").getTime()`);
    if (/^-?[0-9]+$/.test(accessor)) {
      return `
            ${this.builder.writer.int32(`Math.floor((${accessor} - ${epoch}) / 1000 / (24 * 60 * 60))`)}
        `;
    }
    return `
            ${this.builder.writer.int32(`Math.floor((${accessor}.getTime() - ${epoch}) / 1000 / (24 * 60 * 60))`)}
        `;
  }

  readStmt(accessor: (expr: string) => string): string {
    const epoch = this.scope.declareByName("epoch", `new Date("1970/01/01 00:00").getTime()`);
    return accessor(`
            new Date(${epoch} + (${this.builder.reader.int32()} * (24 * 60 * 60) * 1000))
        `);
  }

  getFixedSize(): number {
    return 7;
  }

  needToWriteRef(): boolean {
    return false;
  }
}

CodegenRegistry.register(InternalSerializerType.DURATION, DurationSerializerGenerator);
CodegenRegistry.register(InternalSerializerType.TIMESTAMP, TimestampSerializerGenerator);
