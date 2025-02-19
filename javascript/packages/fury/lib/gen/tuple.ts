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

import { TupleClassInfo, ClassInfo } from "../classInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator, RefState, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { InternalSerializerType } from "../type";
import { Scope } from "./scope";

class TupleSerializerGenerator extends BaseSerializerGenerator {
  classInfo: TupleClassInfo;
  innerGenerators: SerializerGenerator[];

  constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classinfo, builder, scope);
    this.classInfo = <TupleClassInfo>classinfo;
    const inner = this.classInfo.options.inner;
    this.innerGenerators = inner.map(x => CodegenRegistry.newGeneratorByClassInfo(x, this.builder, this.scope));
  }

  writeStmt(accessor: string): string {
    const fixedSize = this.innerGenerators.reduce((x, y) => x + y.getFixedSize(), 0);

    return `
            ${this.builder.writer.varUInt32(this.innerGenerators.length)}
            ${this.builder.writer.reserve(fixedSize)};
            ${
                this.innerGenerators.map((generator, index) => {
                    return generator.toWriteEmbed(`${accessor}[${index}]`);
                }).join("\n")
            }
        `;
  }

  readStmt(accessor: (expr: string) => string, refState: RefState): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");

    return `
            const ${len} = ${this.builder.reader.varUInt32()};
            const ${result} = new Array(${len});
            ${this.maybeReference(result, refState)}
            ${
                this.innerGenerators.map((generator, index) => {
                    return `
                    if (${len} > ${index}) {
                        ${generator.toReadEmbed(expr => `${result}[${index}] = ${expr}`)}
                    }
                    `;
                }).join("\n")
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
}

CodegenRegistry.register(InternalSerializerType.TUPLE, TupleSerializerGenerator);
