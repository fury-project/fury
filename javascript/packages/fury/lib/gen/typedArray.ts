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

import { Type, ClassInfo } from "../classInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator, RefState, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { InternalSerializerType } from "../type";
import { Scope } from "./scope";

function build(inner: ClassInfo) {
  return class TypedArraySerializerGenerator extends BaseSerializerGenerator {
    classInfo: ClassInfo;
    innerGenerator: SerializerGenerator;

    constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
      super(classinfo, builder, scope);
      this.classInfo = <ClassInfo>classinfo;
      this.innerGenerator = CodegenRegistry.newGeneratorByClassInfo(inner, builder, scope);
    }

    writeStmt(accessor: string): string {
      const item = this.scope.uniqueName("item");
      return `
                ${this.builder.writer.varUInt32(`${accessor}.length`)}
                ${this.builder.writer.reserve(`${this.innerGenerator.getFixedSize()} * ${accessor}.length`)};
                for (const ${item} of ${accessor}) {
                    ${this.innerGenerator.toWriteEmbed(item, true)}
                }
            `;
    }

    readStmt(accessor: (expr: string) => string, refState: RefState): string {
      const result = this.scope.uniqueName("result");
      const len = this.scope.uniqueName("len");
      const idx = this.scope.uniqueName("idx");

      return `
                const ${len} = ${this.builder.reader.varUInt32()};
                const ${result} = new Array(${len});
                ${this.maybeReference(result, refState)}
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                    ${this.innerGenerator.toReadEmbed(x => `${result}[${idx}] = ${x};`, true, RefState.fromFalse())}
                }
                ${accessor(result)}
             `;
    }

    getFixedSize(): number {
      return 7;
    }

    needToWriteRef(): boolean {
      return Boolean(this.builder.fury.config.refTracking);
    }
  };
}

CodegenRegistry.register(InternalSerializerType.BOOL_ARRAY, build(Type.bool()));
CodegenRegistry.register(InternalSerializerType.INT8_ARRAY, build(Type.int8()));
CodegenRegistry.register(InternalSerializerType.INT16_ARRAY, build(Type.int16()));
CodegenRegistry.register(InternalSerializerType.INT32_ARRAY, build(Type.int32()));
CodegenRegistry.register(InternalSerializerType.INT64_ARRAY, build(Type.int64()));
CodegenRegistry.register(InternalSerializerType.FLOAT16_ARRAY, build(Type.float16()));
CodegenRegistry.register(InternalSerializerType.FLOAT32_ARRAY, build(Type.float32()));
CodegenRegistry.register(InternalSerializerType.FLOAT64_ARRAY, build(Type.float64()));
