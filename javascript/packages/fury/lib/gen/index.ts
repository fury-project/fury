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

import { InternalSerializerType, Serializer } from "../type";
import { ArrayClassInfo, MapClassInfo, StructClassInfo, OneofClassInfo, SetClassInfo, TupleClassInfo, ClassInfo } from "../classInfo";
import { CodegenRegistry } from "./router";
import { CodecBuilder } from "./builder";
import { Scope } from "./scope";
import "./array";
import "./struct";
import "./string";
import "./binary";
import "./bool";
import "./datetime";
import "./map";
import "./number";
import "./set";
import "./struct";
import "./tuple";
import "./typedArray";
import "./enum";
import Fury from "../fury";

export { AnySerializer } from "./any";

export class Gen {
  static external = CodegenRegistry.getExternal();

  constructor(private fury: Fury, private replace = false, private regOptions: { [key: string]: any } = {}) {

  }

  private generate(classInfo: ClassInfo) {
    const InnerGeneratorClass = CodegenRegistry.get(classInfo.type);
    if (!InnerGeneratorClass) {
      throw new Error(`${classInfo.type} generator not exists`);
    }
    const scope = new Scope();
    const generator = new InnerGeneratorClass(classInfo, new CodecBuilder(scope, this.fury), scope);

    const funcString = generator.toSerializer();
    if (this.fury.config && this.fury.config.hooks) {
      const afterCodeGenerated = this.fury.config.hooks.afterCodeGenerated;
      if (typeof afterCodeGenerated === "function") {
        return new Function(afterCodeGenerated(funcString));
      }
    }
    return new Function(funcString);
  }

  private register(classInfo: StructClassInfo, serializer?: Serializer) {
    this.fury.classResolver.registerSerializer(classInfo, serializer);
  }

  private isRegistered(classInfo: ClassInfo) {
    return !!this.fury.classResolver.classInfoExists(classInfo);
  }

  private traversalContainer(classInfo: ClassInfo) {
    if (classInfo.type === InternalSerializerType.STRUCT) {
      if (this.isRegistered(classInfo) && !this.replace) {
        return;
      }
      const options = (<StructClassInfo>classInfo).options;
      if (options.props) {
        this.register(<StructClassInfo>classInfo);
        Object.values(options.props).forEach((x) => {
          this.traversalContainer(x);
        });
        const func = this.generate(classInfo);
        this.register(<StructClassInfo>classInfo, func()(this.fury, Gen.external, classInfo, this.regOptions));
      }
    }
    if (classInfo.type === InternalSerializerType.ARRAY) {
      this.traversalContainer((<ArrayClassInfo>classInfo).options.inner);
    }
    if (classInfo.type === InternalSerializerType.SET) {
      this.traversalContainer((<SetClassInfo>classInfo).options.key);
    }
    if (classInfo.type === InternalSerializerType.MAP) {
      this.traversalContainer((<MapClassInfo>classInfo).options.key);
      this.traversalContainer((<MapClassInfo>classInfo).options.value);
    }
    if (classInfo.type === InternalSerializerType.TUPLE) {
      (<TupleClassInfo>classInfo).options.inner.forEach((x) => {
        this.traversalContainer(x);
      });
    }
    if (classInfo.type === InternalSerializerType.ONEOF) {
      const options = (<OneofClassInfo>classInfo).options;
      if (options.inner) {
        Object.values(options.inner).forEach((x) => {
          this.traversalContainer(x);
        });
      }
    }
  }

  generateSerializer(classInfo: ClassInfo) {
    this.traversalContainer(classInfo);
    const exists = this.isRegistered(classInfo);
    if (exists) {
      return this.fury.classResolver.getSerializerByClassInfo(classInfo);
    }
    const func = this.generate(classInfo);
    return func()(this.fury, Gen.external, classInfo, this.regOptions);
  }
}
