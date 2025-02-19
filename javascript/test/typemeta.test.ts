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

import Fury, { ClassInfo, InternalSerializerType, Type, Mode } from '../packages/fury/index';
import {describe, expect, test} from '@jest/globals';
import * as beautify from 'js-beautify';


describe('typemeta', () => {
  test('should evoluation scheme work', () => {
    
    const fury = new Fury({
        mode: Mode.Compatible
    });    

    @Type.struct("example.foo")
    class Foo {
        @Type.string()
        bar: string;

        @Type.int32()
        bar2: number;

        setBar(bar: string) {
            this.bar = bar;
            return this;
        }

        setBar2(bar2: number) {
            this.bar2 = bar2;
            return this;
        }
    }

    const { serialize } = fury.registerSerializer(Foo);
    const bin = serialize(new Foo().setBar("hello").setBar2(123));


    @Type.struct("example.foo")
    class Foo2 {
        @Type.string()
        bar: string;
    }

    const fury2 = new Fury({
        mode: Mode.Compatible,
        hooks: {
            afterCodeGenerated: (code: string) => {
                return beautify.js(code, { indent_size: 2, space_in_empty_paren: true, indent_empty_lines: true });
              }        
            }
    });    
    const { deserialize  } = fury2.registerSerializer(Foo2);
    const r = deserialize(bin);
    expect(r).toEqual({
        bar: "hello",
        bar2: 123,
    })
  });
});
