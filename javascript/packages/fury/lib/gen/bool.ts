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

class BoolSerializerGenerator extends BaseSerializerGenerator {
  classInfo: ClassInfo;

  constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classinfo, builder, scope);
    this.classInfo = classinfo;
  }

  writeStmt(accessor: string): string {
    return this.builder.writer.uint8(`${accessor} ? 1 : 0`);
  }

  readStmt(accessor: (expr: string) => string): string {
    return accessor(`${this.builder.reader.uint8()} === 1`);
  }

  getFixedSize(): number {
    return 4;
  }

  needToWriteRef(): boolean {
    return false;
  }
}

CodegenRegistry.register(InternalSerializerType.BOOL, BoolSerializerGenerator);
