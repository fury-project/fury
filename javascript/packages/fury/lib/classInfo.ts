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

import { TypeMeta } from "./meta/TypeMeta";
import { FuryClsInfoSymbol, InternalSerializerType, WithFuryClsInfo, TypeId } from "./type";

const initMeta = (target: new () => any, classInfo: ClassInfo) => {
  if (!target.prototype) {
    target.prototype = {};
  }
  target.prototype[FuryClsInfoSymbol] = {
    structClassInfo: Type.struct({
      ...(
        TypeId.IS_NAMED_TYPE(classInfo.typeId)
          ? {
              namespace: classInfo.namespace,
              typeName: classInfo.typeName,
            }
          : {
              typeId: classInfo.typeId,
            }
      ),
    }, targetFields.get(target) || {}, {
      withConstructor: true,
    }),
  } as WithFuryClsInfo;
};

const targetFields = new WeakMap<new () => any, { [key: string]: ClassInfo }>();

const addField = (target: new () => any, key: string, des: ClassInfo) => {
  if (!targetFields.has(target)) {
    targetFields.set(target, {});
  }
  targetFields.get(target)![key] = des;
};

// eslint-disable-next-line
class ExtensibleFunction extends Function {
  constructor(f: (target: any, key?: string | { name?: string }) => void) {
    super();
    return Object.setPrototypeOf(f, new.target.prototype);
  }
}

/**
 * T is for type matching
 */
// eslint-disable-next-line
export class ClassInfo<T = unknown> extends ExtensibleFunction {
  dynamicTypeId = -1;
  hash = BigInt(0);
  named = "";
  namespace = "";
  typeName = "";
  options?: any;

  private constructor(public type: InternalSerializerType, public typeId: number) {
    super(function (target: any, key?: string | { name?: string }) {
      if (key === undefined) {
        initMeta(target, that as unknown as StructClassInfo);
      } else {
        const keyString = typeof key === "string" ? key : key?.name;
        if (!keyString) {
          throw new Error("Decorators can only be placed on classes and fields");
        }
        addField(target.constructor, keyString, that);
      }
    });
    // eslint-disable-next-line
    const that = this;
  }

  static fromNonParam<T extends InternalSerializerType>(type: T, typeId: number) {
    return new ClassInfo<{
      type: T;
    }>(type, typeId);
  }

  static fromStruct<T = any>(typeInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, props?: Record<string, ClassInfo>, {
    withConstructor = false,
  }: {
    withConstructor?: boolean;
  } = {}) {
    let typeId: number | undefined;
    let namespace: string | undefined;
    let typeName: string | undefined;
    if (typeof typeInfo === "string") {
      typeName = typeInfo;
    } else if (typeof typeInfo === "number") {
      typeId = typeInfo;
    } else {
      namespace = typeInfo.namespace;
      typeName = typeInfo.typeName;
      typeId = typeInfo.typeId;
    }
    if (typeId !== undefined && typeName !== undefined) {
      throw new Error(`type name ${typeName} and id ${typeId} should not be set at the same time`);
    }
    if (!typeId) {
      if (!typeName) {
        throw new Error(`type name and type id should be set at least one`);
      }
    }
    if (!namespace && typeName) {
      const splits = typeName!.split(".");
      if (splits.length > 1) {
        namespace = splits[0];
        typeName = splits.slice(1).join(".");
      }
    }
    const finalTypeId = typeId !== undefined ? ((typeId << 8) | TypeId.STRUCT) : TypeId.NAMED_STRUCT;
    const classInfo = new ClassInfo<T>(InternalSerializerType.STRUCT, finalTypeId).cast<StructClassInfo>();
    classInfo.options = {
      props: props || {},
      withConstructor,
    };
    classInfo.namespace = namespace || "";
    classInfo.typeName = typeId !== undefined ? "" : typeName!;
    classInfo.hash = TypeMeta.fromClassInfo(classInfo).getHash();
    classInfo.named = `${classInfo.namespace}$${classInfo.typeName}`;
    return classInfo as ClassInfo<T>;
  }

  static fromWithOptions<T extends InternalSerializerType, T2>(type: T, typeId: number, options: T2) {
    const classInfo = new ClassInfo<{
      type: T;
      options: T2;
    }>(type, typeId);
    classInfo.options = options;
    return classInfo;
  }

  static fromEnum<T>(typeInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, props: { [key: string]: any }) {
    let typeId: number | undefined;
    let namespace: string | undefined;
    let typeName: string | undefined;
    if (typeof typeInfo === "string") {
      typeName = typeInfo;
    } else if (typeof typeInfo === "number") {
      typeId = typeInfo;
    } else {
      namespace = typeInfo.namespace;
      typeName = typeInfo.typeName;
      typeId = typeInfo.typeId;
    }
    if (typeId !== undefined && typeName !== undefined) {
      throw new Error(`type name ${typeName} and id ${typeId} should not be set at the same time`);
    }
    if (!typeId) {
      if (!typeName) {
        throw new Error(`type name and type id should be set at least one`);
      }
    }
    if (!namespace && typeName) {
      const splits = typeName!.split(".");
      if (splits.length > 1) {
        namespace = splits[0];
        typeName = splits.slice(1).join(".");
      }
    }
    const finalTypeId = typeId !== undefined ? ((typeId << 8) | TypeId.ENUM) : TypeId.NAMED_ENUM;
    const classInfo = new ClassInfo<T>(InternalSerializerType.ENUM, finalTypeId);
    classInfo.cast<EnumClassInfo>().options = {
      inner: props,
    };
    classInfo.namespace = namespace || "";
    classInfo.typeName = typeId !== undefined ? "" : typeName!;
    classInfo.named = `${classInfo.namespace}$${classInfo.typeName}`;
    return classInfo;
  }

  castToStruct() {
    return this as unknown as StructClassInfo;
  }

  cast<T>() {
    return this as unknown as T;
  }
}

export interface StructClassInfo extends ClassInfo {
  options: {
    props?: { [key: string]: ClassInfo };
    withConstructor?: boolean;
  };
}

export interface EnumClassInfo extends ClassInfo {
  options: {
    inner: { [key: string]: any };
  };
}

export interface OneofClassInfo extends ClassInfo {
  options: {
    inner: { [key: string]: ClassInfo };
  };
}

export interface ArrayClassInfo extends ClassInfo {
  options: {
    inner: ClassInfo;
  };
}

export interface TupleClassInfo extends ClassInfo {
  options: {
    inner: ClassInfo[];
  };
}

export interface SetClassInfo extends ClassInfo {
  options: {
    key: ClassInfo;
  };
}

export interface MapClassInfo extends ClassInfo {
  options: {
    key: ClassInfo;
    value: ClassInfo;
  };
}

type Props<T> = T extends {
  options: {
    props?: infer T2 extends { [key: string]: any };
  };
}
  ? {
      [P in keyof T2]?: (InputType<T2[P]> | null);
    }
  : unknown;

type InnerProps<T> = T extends {
  options: {
    inner: infer T2 extends ClassInfo;
  };
}
  ? (InputType<T2> | null)[]
  : unknown;

type MapProps<T> = T extends {
  options: {
    key: infer T2 extends ClassInfo;
    value: infer T3 extends ClassInfo;
  };
}
  ? Map<InputType<T2>, InputType<T3> | null>
  : unknown;

type TupleProps<T> = T extends {
  options: {
    inner: infer T2 extends readonly [...ClassInfo[]];
  };
}
  ? { [K in keyof T2]: InputType<T2[K]> }
  : unknown;

type Value<T> = T extends { [s: string]: infer T2 } ? T2 : unknown;

type EnumProps<T> = T extends {
  options: {
    inner: infer T2;
  };
}
  ? Value<T2>
  : unknown;

type OneofProps<T> = T extends {
  options: {
    inner?: infer T2 extends { [key: string]: any };
  };
}
  ? {
      [P in keyof T2]?: (InputType<T2[P]> | null);
    }
  : unknown;

type OneofResult<T> = T extends {
  options: {
    inner?: infer T2;
  };
}
  ? ResultType<Value<T2>>
  : unknown;

type SetProps<T> = T extends {
  options: {
    key: infer T2 extends ClassInfo;
  };
}
  ? Set<(InputType<T2> | null)>
  : unknown;

export type InputType<T> = T extends ClassInfo<infer M> ? HintInput<M> : unknown;

export type HintInput<T> = T extends unknown ? any : T extends {
  type: InternalSerializerType.STRUCT;
}
  ? Props<T>
  : T extends {
    type: InternalSerializerType.STRING;
  }
    ? string
    : T extends {
      type: InternalSerializerType.TUPLE;
    }
      ? TupleProps<T>
      : T extends {
        type:
          | InternalSerializerType.INT8
          | InternalSerializerType.INT16
          | InternalSerializerType.INT32
          | InternalSerializerType.VAR_INT32
          | InternalSerializerType.FLOAT16
          | InternalSerializerType.FLOAT32
          | InternalSerializerType.FLOAT64;
      }
        ? number

        : T extends {
          type: InternalSerializerType.VAR_INT64
            | InternalSerializerType.SLI_INT64
            | InternalSerializerType.INT64;
        }
          ? bigint
          : T extends {
            type: InternalSerializerType.MAP;
          }
            ? MapProps<T>
            : T extends {
              type: InternalSerializerType.SET;
            }
              ? SetProps<T>
              : T extends {
                type: InternalSerializerType.ARRAY;
              }
                ? InnerProps<T>
                : T extends {
                  type: InternalSerializerType.BOOL;
                }
                  ? boolean
                  : T extends {
                    type: InternalSerializerType.DURATION;
                  }
                    ? Date
                    : T extends {
                      type: InternalSerializerType.TIMESTAMP;
                    }
                      ? number
                      : T extends {
                        type: InternalSerializerType.ANY;
                      }
                        ? any
                        : T extends { type: InternalSerializerType.BINARY;
                        }
                          ? Uint8Array
                          : T extends {
                            type: InternalSerializerType.ENUM;
                          }
                            ? EnumProps<T> : T extends {
                              type: InternalSerializerType.ONEOF;
                            } ? OneofProps<T> : unknown;

export type ResultType<T> = T extends ClassInfo<infer M> ? HintResult<M> : HintResult<T>;

export type HintResult<T> = T extends never ? any : T extends {
  type: InternalSerializerType.STRUCT;
}
  ? Props<T>
  : T extends {
    type: InternalSerializerType.STRING;
  }
    ? string
    : T extends {
      type: InternalSerializerType.TUPLE;
    }
      ? TupleProps<T>
      : T extends {
        type:
          | InternalSerializerType.INT8
          | InternalSerializerType.INT16
          | InternalSerializerType.INT32
          | InternalSerializerType.VAR_INT32
          | InternalSerializerType.FLOAT16
          | InternalSerializerType.FLOAT32
          | InternalSerializerType.FLOAT64;
      }
        ? number

        : T extends {
          type: InternalSerializerType.SLI_INT64
            | InternalSerializerType.INT64;
        }
          ? bigint
          : T extends {
            type: InternalSerializerType.MAP;
          }
            ? MapProps<T>
            : T extends {
              type: InternalSerializerType.SET;
            }
              ? SetProps<T>
              : T extends {
                type: InternalSerializerType.ARRAY;
              }
                ? InnerProps<T>
                : T extends {
                  type: InternalSerializerType.BOOL;
                }
                  ? boolean
                  : T extends {
                    type: InternalSerializerType.DURATION;
                  }
                    ? Date
                    : T extends {
                      type: InternalSerializerType.TIMESTAMP;
                    }
                      ? number
                      : T extends { type: InternalSerializerType.BINARY;
                      }
                        ? Uint8Array : T extends {
                          type: InternalSerializerType.ANY;
                        }
                          ? any
                          : T extends {
                            type: InternalSerializerType.ENUM;
                          }
                            ? EnumProps<T> : T extends {
                              type: InternalSerializerType.ONEOF;
                            } ? OneofResult<T> : unknown;

export const Type = {
  any() {
    return ClassInfo.fromNonParam(InternalSerializerType.ANY, TypeId.STRUCT);
  },
  oneof<T extends { [key: string]: ClassInfo }>(inner?: T) {
    return ClassInfo.fromWithOptions(InternalSerializerType.ONEOF as const, TypeId.STRUCT, {
      inner,
    });
  },
  array<T extends ClassInfo>(inner: T) {
    return ClassInfo.fromWithOptions(InternalSerializerType.ARRAY as const, TypeId.ARRAY, {
      inner,
    });
  },
  tuple<T1 extends readonly [...readonly ClassInfo[]]>(t1: T1) {
    return ClassInfo.fromWithOptions(InternalSerializerType.TUPLE as const, TypeId.LIST, {
      inner: t1,
    });
  },
  map<T1 extends ClassInfo, T2 extends ClassInfo>(
    key: T1,
    value: T2
  ) {
    return ClassInfo.fromWithOptions(InternalSerializerType.MAP as const, TypeId.MAP, {
      key,
      value,
    });
  },
  set<T extends ClassInfo>(key: T) {
    return ClassInfo.fromWithOptions(InternalSerializerType.SET as const, TypeId.SET, {
      key,
    });
  },
  enum<T1 extends { [key: string]: any }>(typeInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, t1: T1) {
    return ClassInfo.fromEnum<{
      type: InternalSerializerType.ENUM;
      options: {
        inner: T1;
      };
    }>(typeInfo, t1);
  },
  struct<T extends { [key: string]: ClassInfo }>(typeInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, props?: T, {
    withConstructor = false,
  }: {
    withConstructor?: boolean;
  } = {}) {
    return ClassInfo.fromStruct<{
      type: InternalSerializerType.STRUCT;
      options: {
        props: T;
      };
    }>(typeInfo, props, {
      withConstructor,
    });
  },
  string() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.STRING as const,
      (TypeId.STRING),
    );
  },
  bool() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.BOOL as const,
      (TypeId.BOOL),
    );
  },
  int8() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT8 as const,
      (TypeId.INT8),
    );
  },
  int16() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT16 as const,
      (TypeId.INT16),

    );
  },
  int32() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT32 as const,
      (TypeId.INT32),

    );
  },
  varInt32() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.VAR_INT32 as const,
      (TypeId.VAR_INT32),

    );
  },
  int64() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT64 as const,
      (TypeId.INT64),

    );
  },
  sliInt64() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.SLI_INT64 as const,
      (TypeId.SLI_INT64),

    );
  },
  float16() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.FLOAT16 as const,
      (TypeId.FLOAT16),

    );
  },
  float32() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.FLOAT32 as const,
      (TypeId.FLOAT32),

    );
  },
  float64() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.FLOAT64 as const,
      (TypeId.FLOAT64),

    );
  },
  binary() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.BINARY as const,
      (TypeId.BINARY),

    );
  },
  duration() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.DURATION as const,
      (TypeId.DURATION),

    );
  },
  timestamp() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.TIMESTAMP as const,
      (TypeId.TIMESTAMP),

    );
  },
  boolArray() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.BOOL_ARRAY as const,
      (TypeId.BOOL_ARRAY),

    );
  },
  int8Array() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT8_ARRAY as const,
      (TypeId.INT8_ARRAY),

    );
  },
  int16Array() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT16_ARRAY as const,
      (TypeId.INT16_ARRAY),

    );
  },
  int32Array() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT32_ARRAY as const,
      (TypeId.INT32_ARRAY),

    );
  },
  int64Array() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.INT64_ARRAY as const,
      (TypeId.INT64_ARRAY),

    );
  },
  float16Array() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.FLOAT16_ARRAY as const,
      (TypeId.FLOAT16_ARRAY),

    );
  },
  float32Array() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.FLOAT32_ARRAY as const,
      (TypeId.FLOAT32_ARRAY),

    );
  },
  float64Array() {
    return ClassInfo.fromNonParam(
      InternalSerializerType.FLOAT64_ARRAY as const,
      (TypeId.FLOAT64_ARRAY)
    );
  },
};
