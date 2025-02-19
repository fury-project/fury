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

describe('object', () => {
  test('should descoration work', () => {
    @Type.struct({
      typeName: "example.foo"
    })
    class Foo {
      @Type.int32()
      a: number;
    }
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(Foo);
    const foo = new Foo();
    foo.a = 123;
    const input = serialize(foo);
    const result = deserialize(
      input
    );

    expect(result instanceof Foo);
    
    expect(result).toEqual({ a: 123 })
  });

  test('should descoration work2', () => {
    @Type.struct("example.foo")
    class Foo {
      @Type.int32()
      a: number;
    }
    const fury = new Fury({ refTracking: true });
    fury.registerSerializer(Foo);

    const foo = new Foo();
    foo.a = 123;
    const input = fury.serialize(foo)
    const result = fury.deserialize(input);
    expect(result instanceof Foo);
    expect(result).toEqual({ a: 123 })
  });

  test('should object work', () => {
    const classInfo = Type.struct("example.foo", {
      a: Type.struct("example.bar", {
        b: Type.string()
      })
    })
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(classInfo);
    const input = serialize({ a: { b: "hel" } });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ a: { b: "hel" } })
  });


  test('should null value work', () => {
    const classInfo = Type.struct("example.foo", {
      a: Type.struct("example.bar", {
        b: Type.string()
      })
    })
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(classInfo);
    const input = serialize({ a: null });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ a: null })
  });

  test('should object in array work', () => {
    const classInfo = Type.struct('example.foo', {
      a: Type.array(Type.struct('example.bar', {
        b: Type.string(),
        c: Type.bool(),
        d: Type.int32(),
        e: Type.int64(),
        f: Type.binary(),
      }))
    })
    
    const fury = new Fury({ refTracking: true });
    const serializer = fury.registerSerializer(classInfo).serializer;
    const input = fury.serialize({ a: [{ b: "hel", c: true, d: 123, e: 123, f: new Uint8Array([1,2,3]) }] }, serializer);
    const result = fury.deserialize(
      input
    );
    result.a.forEach(x => x.e = Number(x.e))
    expect(result).toEqual({ a: [{ b: "hel", c: true, d: 123, e: 123, f: Buffer.from([1,2,3]) }] })
  });

  test('should write tag and read tag work', () => {
    const classInfo = Type.struct("example.foo", {
      a: Type.struct("example.bar", {
        b: Type.string()
      }),
      a2: Type.struct("example.bar")
    });
    const fury = new Fury({ refTracking: true });
    const serializer = fury.registerSerializer(classInfo).serializer;
    const input = fury.serialize({ a: { b: "hel" }, a2: { b: "hel2" } }, serializer);
    const result = fury.deserialize(
      input
    );
    expect(result).toEqual({ a: { b: "hel" }, a2: { b: "hel2" } })
  });

  test('should ciycle ref work', () => {
    const classInfo = Type.struct( "example.foo", {
      a: Type.struct("example.bar", {
        b: Type.string(),
      }),
      a2: Type.struct("example.foo")
    })
    
    const fury = new Fury({ refTracking: true });
    const serialize = fury.registerSerializer(classInfo).serializer;
    const param: any = {};
    param.a = { b: "hel" };
    param.a2 = param;
    const input = fury.serialize(param, serialize);
    const result = fury.deserialize(
      input
    );
    expect(result.a).toEqual({ b: "hel" })
    expect(result.a2).toEqual(result)
  });

  test('should dot prop accessor work', () => {
    const classInfo = Type.struct("example.foo", {
      "+a": Type.struct("example.bar", {
        "delete": Type.string(),
        c: Type.array(Type.struct("example.foo2", {
          d: Type.string(),
        }))
      }),
    })
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(classInfo);
    const input = serialize({ "+a": { "delete": "hel", c: [{ d: "hello" }] } });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ "+a": { "delete": "hel", c: [{ d: "hello" }] } })
  });


  test('should type function tools work', () => {
    const classInfo = Type.struct("example.foo", {
      a: Type.struct("example\".bar", {
        b: Type.string(),
        c: Type.array(Type.struct("example\\\".foo2", {
          d: Type.string(),
        }))
      }),
    })
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(classInfo);
    const input = serialize({ a: { b: "hel", c: [{ d: "hello" }] } });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ a: { b: "hel", c: [{ d: "hello" }] } })
  });

  test("should partial record work", () => {
    const hps = undefined;
    const classInfo = Type.struct('ws-channel-protocol', {
        kind: Type.string(),
        path: Type.string(),
    });

    const fury = new Fury({ hps });
    const { serialize, deserialize } = fury.registerSerializer(classInfo);
    const bin = serialize({
        kind: "123",
    });
    const obj = deserialize(bin);
    expect({kind: "123", path: null}).toEqual(obj)
})

  test('should handle emojis', () => {
    const classInfo = Type.struct("example.emoji", {
      a: Type.string()
    });
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(classInfo);
    const input = serialize({ a: "Hello, world! 🌍😊" });
    const result = deserialize(input);
    expect(result).toEqual({ a: "Hello, world! 🌍😊" });
  });
});


