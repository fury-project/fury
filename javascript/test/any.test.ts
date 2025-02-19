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

describe('bool', () => {
    test('should write null work', () => {
        const fury = new Fury();
        const bin = fury.serialize(null);
        expect(fury.deserialize(bin)).toBe(null)
    });
    test('should write undefined work', () => {
        const fury = new Fury();
        const bin = fury.serialize(undefined);
        expect(fury.deserialize(bin)).toBe(null)
    });

    test('should write number work', () => {
        const fury = new Fury();
        const bin = fury.serialize(123);
        expect(fury.deserialize(bin)).toBe(123)
    });

    test('should write NaN work', () => {
        const fury = new Fury();
        const bin = fury.serialize(NaN);
        expect(fury.deserialize(bin)).toBe(NaN)
    });

    test('should write big number work', () => {
        const fury = new Fury();
        const bin = fury.serialize(3000000000);
        expect(fury.deserialize(bin)).toBe(3000000000);
    });

    test('should write INFINITY work', () => {
        const fury = new Fury();
        const bin = fury.serialize(Number.NEGATIVE_INFINITY);
        expect(fury.deserialize(bin)).toBe(Number.NEGATIVE_INFINITY)
    });

    test('should write float work', () => {
        const fury = new Fury();
        const bin = fury.serialize(123.123);
        expect(fury.deserialize(bin)).toBe(123.123)
    });

    test('should write bigint work', () => {
        const fury = new Fury();
        const bin = fury.serialize(BigInt(123));
        expect(fury.deserialize(bin)).toBe(BigInt(123))
    });

    test('should write true work', () => {
        const fury = new Fury();
        const bin = fury.serialize(true);
        expect(fury.deserialize(bin)).toBe(true)
    });

    test('should write false work', () => {
        const fury = new Fury();
        const bin = fury.serialize(false);
        expect(fury.deserialize(bin)).toBe(false)
    });

    test('should write date work', () => {
        const fury = new Fury();
        const dt = new Date();
        const bin = fury.serialize(dt);
        const ret = fury.deserialize(bin);
        expect(ret instanceof Date).toBe(true)
        expect(ret.toUTCString()).toBe(dt.toUTCString())
    });

    test('should write string work', () => {
        const fury = new Fury();
        const bin = fury.serialize("hello");
        expect(fury.deserialize(bin)).toBe("hello")
    });

    test('should write map work', () => {
        const fury = new Fury();
        const obj = new Map([[1, 2], [3, 4]]);
        const bin = fury.serialize(obj);
        const ret = fury.deserialize(bin);
        expect(ret instanceof Map).toBe(true)
        expect([...ret.values()]).toEqual([...obj.values()])
        expect([...ret.keys()]).toEqual([...obj.keys()])
    });

    test('should write set work', () => {
        const fury = new Fury();
        const obj = new Set([1,2,3]);
        const bin = fury.serialize(obj);
        const ret = fury.deserialize(bin);
        expect(ret instanceof Set).toBe(true)
        expect([...ret.values()]).toEqual([...obj.values()])
    });

    test('should write Array work', () => {
        const fury = new Fury();
        const obj = [1,2,3]
        const bin = fury.serialize(obj);
        const ret = fury.deserialize(bin);
        expect(ret instanceof Array).toBe(true)
        expect(ret).toEqual(obj)
    });

    test('should root any work', () => {
        const fury = new Fury();
        const { serialize, deserialize } = fury.registerSerializer(Type.any());
        const bin = serialize("hello");
        const result = deserialize(bin);
        expect(result).toEqual("hello")
    });
});
