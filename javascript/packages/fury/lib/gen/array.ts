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

import { ArrayClassInfo, ClassInfo } from "../classInfo";
import { CodecBuilder } from "./builder";
import { CodegenRegistry } from "./router";
import { InternalSerializerType } from "../type";
import { Scope } from "./scope";
import { CollectionSerializerGenerator } from "./collection";

class ArraySerializerGenerator extends CollectionSerializerGenerator {
  classInfo: ArrayClassInfo;

  constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
    super(classinfo, builder, scope);
    this.classInfo = <ArrayClassInfo>classinfo;
  }

  genericTypeDescriptin(): ClassInfo {
    return this.classInfo.options.inner;
  }

  sizeProp() {
    return "length";
  }

  newCollection(lenAccessor: string): string {
    return `new Array(${lenAccessor})`;
  }

  putAccessor(result: string, item: string, index: string): string {
    return `${result}[${index}] = ${item}`;
  }

  getFixedSize(): number {
    return 7;
  }

  needToWriteRef(): boolean {
    return Boolean(this.builder.fury.config.refTracking);
  }
}

CodegenRegistry.register(InternalSerializerType.ARRAY, ArraySerializerGenerator);
