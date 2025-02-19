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

function buildNumberSerializer(writeFun: (builder: CodecBuilder, accessor: string) => string, read: (builder: CodecBuilder) => string) {
  return class NumberSerializerGenerator extends BaseSerializerGenerator {
    classInfo: ClassInfo;

    constructor(classinfo: ClassInfo, builder: CodecBuilder, scope: Scope) {
      super(classinfo, builder, scope);
      this.classInfo = classinfo;
    }

    writeStmt(accessor: string): string {
      return writeFun(this.builder, accessor);
    }

    readStmt(accessor: (expr: string) => string): string {
      return accessor(read(this.builder));
    }

    getFixedSize(): number {
      return 11;
    }

    needToWriteRef(): boolean {
      return false;
    }
  };
}

CodegenRegistry.register(InternalSerializerType.INT8,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.int8(accessor),
    builder => builder.reader.int8()
  )
);

CodegenRegistry.register(InternalSerializerType.INT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.int16(accessor),
    builder => builder.reader.int16()
  )
);

CodegenRegistry.register(InternalSerializerType.INT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.int32(accessor),
    builder => builder.reader.int32()
  )
);

CodegenRegistry.register(InternalSerializerType.VAR_INT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.varInt32(accessor),
    builder => builder.reader.varInt32()
  )
);

CodegenRegistry.register(InternalSerializerType.INT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.sliInt64(accessor),
    builder => builder.reader.sliInt64()
  )
);

CodegenRegistry.register(InternalSerializerType.SLI_INT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.sliInt64(accessor),
    builder => builder.reader.sliInt64()
  )
);
CodegenRegistry.register(InternalSerializerType.FLOAT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.float32(accessor),
    builder => builder.reader.float16()
  )
);
CodegenRegistry.register(InternalSerializerType.FLOAT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.float32(accessor),
    builder => builder.reader.float32()
  )
);
CodegenRegistry.register(InternalSerializerType.FLOAT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.float64(accessor),
    builder => builder.reader.float64()
  )
);
