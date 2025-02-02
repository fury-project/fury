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

import Fury, { TypeDescription, InternalSerializerType, Type } from '../packages/fury/index';
import { describe, expect, test } from '@jest/globals';

describe('object', () => {
  test('should object work', () => {
    const description = {
      type: InternalSerializerType.OBJECT as const,
      options: {
        props: {
          a: {
            type: InternalSerializerType.OBJECT as const,
            options: {
              tag: "example.bar",
              props: {
                b: {
                  type: InternalSerializerType.STRING as const,
                },
              }
            }
          }
        },
        tag: "example.foo"
      }
    };
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(description);
    const input = serialize({ a: { b: "hel" } });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ a: { b: "hel" } })
  });


  test('should null value work', () => {
    const description = {
      type: InternalSerializerType.OBJECT as const,
      options: {
        props: {
          a: {
            type: InternalSerializerType.OBJECT as const,
            options: {
              tag: "example.bar",
              props: {
                b: {
                  type: InternalSerializerType.STRING as const,
                },
              }
            }
          }
        },
        tag: "example.foo"
      }
    };
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(description);
    const input = serialize({ a: null });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ a: null })
  });

  test('should object in array work', () => {
    const description = Type.object('example.foo', {
      a: Type.array(Type.object('example.bar', {
        b: Type.string(),
        c: Type.bool(),
        d: Type.int32(),
        e: Type.int64(),
        f: Type.binary(),
      }))
    })
    
    const fury = new Fury({ refTracking: true });
    const serializer = fury.registerSerializer(description).serializer;
    const input = fury.serialize({ a: [{ b: "hel", c: true, d: 123, e: 123, f: new Uint8Array([1,2,3]) }] }, serializer);
    const result = fury.deserialize(
      input
    );
    result.a.forEach(x => x.e = Number(x.e))
    expect(result).toEqual({ a: [{ b: "hel", c: true, d: 123, e: 123, f: Buffer.from([1,2,3]) }] })
  });

  test('should write tag and read tag work', () => {
    const description = {
      type: InternalSerializerType.OBJECT,
      options: {
        props: {
          a: {
            type: InternalSerializerType.OBJECT as const,
            options: {
              tag: "example.bar",
              props: {
                b: {
                  type: InternalSerializerType.STRING
                },
              }
            }
          },
          a2: {
            type: InternalSerializerType.OBJECT as const,
            options: {
              tag: "example.bar",
            }
          }
        },
        tag: "example.foo"
      }
    };
    
    const fury = new Fury({ refTracking: true });
    const serializer = fury.registerSerializer(description).serializer;
    const input = fury.serialize({ a: { b: "hel" }, a2: { b: "hel2" } }, serializer);
    const result = fury.deserialize(
      input
    );
    expect(result).toEqual({ a: { b: "hel" }, a2: { b: "hel2" } })
  });

  test('should ciycle ref work', () => {
    const description = {
      type: InternalSerializerType.OBJECT,
      options: {
        props: {
          a: {
            type: InternalSerializerType.OBJECT as const,
            options: {
              tag: "example.bar",
              props: {
                b: {
                  type: InternalSerializerType.STRING
                },
              }
            }
          },
          a2: {
            type: InternalSerializerType.OBJECT as const,
            options: {
              tag: "example.foo",
            }
          }
        },
        tag: "example.foo"
      }
    };
    
    const fury = new Fury({ refTracking: true });
    const serialize = fury.registerSerializer(description).serializer;
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
    const description = Type.object("example.foo", {
      "+a": Type.object("example.bar", {
        "delete": Type.string(),
        c: Type.array(Type.object("example.foo2", {
          d: Type.string(),
        }))
      }),
    })
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(description);
    const input = serialize({ "+a": { "delete": "hel", c: [{ d: "hello" }] } });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ "+a": { "delete": "hel", c: [{ d: "hello" }] } })
  });


  test('should type function tools work', () => {
    const description = Type.object("example.foo", {
      a: Type.object("example\".bar", {
        b: Type.string(),
        c: Type.array(Type.object("example\\\".foo2", {
          d: Type.string(),
        }))
      }),
    })
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(description);
    const input = serialize({ a: { b: "hel", c: [{ d: "hello" }] } });
    const result = deserialize(
      input
    );
    expect(result).toEqual({ a: { b: "hel", c: [{ d: "hello" }] } })
  });

  test("should partial record work", () => {
    const description = Type.object('ws-channel-protocol', {
        kind: Type.string(),
        path: Type.string(),
    });

    const fury = new Fury({});
    const { serialize, deserialize } = fury.registerSerializer(description);
    const bin = serialize({
        kind: "123",
    });
    const obj = deserialize(bin);
    expect({kind: "123", path: null}).toEqual(obj)
})

  test('should handle emojis', () => {
    const description = {
      type: InternalSerializerType.OBJECT as const,
      options: {
        props: {
          a: {
            type: InternalSerializerType.STRING as const,
          },
        },
        tag: "example.emoji"
      }
    };
    
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(description);
    const input = serialize({ a: "Hello, world! 🌍😊" });
    const result = deserialize(input);
    expect(result).toEqual({ a: "Hello, world! 🌍😊" });
  });
});


