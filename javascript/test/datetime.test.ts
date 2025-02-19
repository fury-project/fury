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

describe('datetime', () => {
  test('should date work', () => {
    
    const fury = new Fury({ refTracking: true });    
    const now = new Date();
    const input = fury.serialize(now);
    const result = fury.deserialize(
        input
    );
    expect(result).toEqual(now)
  });
  test('should datetime work', () => {
    const classinfo = Type.struct("example.foo", {
      a: Type.timestamp(),
      b: Type.duration(),
    })
    const fury = new Fury({ refTracking: true });    
    const serializer = fury.registerSerializer(classinfo).serializer;
    const d = new Date('2021/10/20 09:13');
    const input = fury.serialize({ a:  d, b: d}, serializer);
    const result = fury.deserialize(
      input
    );
    expect(result).toEqual({ a: d, b: new Date('2021/10/20 00:00') })
  });
});


