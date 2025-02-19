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

import Fury, { ClassInfo, InternalSerializerType, Type } from '../packages/fury/index';
import {describe, expect, test} from '@jest/globals';

const config = {};

describe('string', () => {
  test('should latin1 string work', () => {
    
    const fury = new Fury(config);    
    const input = fury.serialize("123")
    const result = fury.deserialize(
        input
    );
    expect(result).toEqual("123")
  });

  test('should utf8 string work', () => {
    
    const fury = new Fury(config);    
    const input = fury.serialize("我是Fury, 你好！😁א")
    const result = fury.deserialize(
        input
    );
    expect(result).toEqual("我是Fury, 你好！😁א")
  });

  test('should long latin1 string work', () => {
    const str = new Array(100).fill("123").join();
    const fury = new Fury(config);    
    const input = fury.serialize(str)
    const result = fury.deserialize(
        input
    );
    expect(result).toEqual(str)
  });

  test('should long utf8 string work', () => {
    const str = new Array(10).fill("我是Fury, 你好！😁א").join();
    const fury = new Fury(config);    
    const input = fury.serialize(str)
    const result = fury.deserialize(
        input
    );
    expect(result).toEqual(str)
  });
});
