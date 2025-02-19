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

import { EnumClassInfo, ClassInfo } from "../classInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { InternalSerializerType, MaxUInt32 } from "../type";
import { Scope } from "./scope";

class EnumSerializerGenerator extends BaseSerializerGenerator {
  classInfo: EnumClassInfo;

  constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classinfo, builder, scope);
    this.classInfo = <EnumClassInfo>classinfo;
  }

  writeStmt(accessor: string): string {
    if (Object.values(this.classInfo.options.inner).length < 1) {
      throw new Error("An enum must contain at least one field");
    }
    return `
        ${Object.values(this.classInfo.options.inner).map((value, index) => {
            if (typeof value !== "string" && typeof value !== "number") {
                throw new Error("Enum value must be string or number");
            }
            if (typeof value === "number") {
                if (value > MaxUInt32 || value < 0) {
                    throw new Error("Enum value must be a valid uint32");
                }
            }
            const safeValue = typeof value === "string" ? `"${value}"` : value;
            return ` if (${accessor} === ${safeValue}) {
                    ${this.builder.writer.varUInt32(index)}
                }`;
        }).join(" else ")}
        else {
            throw new Error("Enum received an unexpected value: " + ${accessor});
        }
    `;
  }

  readStmt(accessor: (expr: string) => string): string {
    const enumValue = this.scope.uniqueName("enum_v");
    return `
        const ${enumValue} = ${this.builder.reader.varUInt32()};
        switch(${enumValue}) {
            ${Object.values(this.classInfo.options.inner).map((value, index) => {
                if (typeof value !== "string" && typeof value !== "number") {
                    throw new Error("Enum value must be string or number");
                }
                if (typeof value === "number") {
                    if (value > MaxUInt32 || value < 0) {
                        throw new Error("Enum value must be a valid uint32");
                    }
                }
                const safeValue = typeof value === "string" ? `"${value}"` : `${value}`;
                return `
                case ${index}:
                    ${accessor(safeValue)}
                    break;
                `;
            }).join("\n")}
            default:
                throw new Error("Enum received an unexpected value: " + ${enumValue});
        }
    `;
  }

  getFixedSize(): number {
    return 7;
  }

  needToWriteRef(): boolean {
    return false;
  }
}

CodegenRegistry.register(InternalSerializerType.ENUM, EnumSerializerGenerator);
