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
import { describe, expect, test } from '@jest/globals';

describe('set', () => {
    test('should set work', () => {
        
        const fury = new Fury({ refTracking: true });    
        const input = fury.serialize(new Set(["foo1", "bar1", "cc2"]));
        const result = fury.deserialize(
            input
        );
        expect(result).toEqual(new Set(["foo1", "bar1", "cc2"]))
    });
    test('should set in object work', () => {
        const classinfo = Type.struct({
            typeName: "example.foo"
        }, {
            a: Type.set(Type.string())
        });
        
        const fury = new Fury({ refTracking: true });    
        const { serialize, deserialize } = fury.registerSerializer(classinfo);
        const input = serialize({ a: new Set(["foo1", "bar2"]) });
        const result = deserialize(
            input
        );
        expect(result).toEqual({ a: new Set(["foo1", "bar2"]) })
    });
});


