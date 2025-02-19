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

import Fury from '../packages/fury/index';
import { describe, expect, test } from '@jest/globals';
import { tupleObjectClassInfo, tupleObjectType3ClassInfo } from './fixtures/tuple';

describe('tuple', () => {
  test('should tuple work', () => {
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(tupleObjectClassInfo);
    const tuple1 = [{a: {b:'1'}}, {a: {c: '2'}}] as [{a: {b: string}}, {a: {c: string}}];
    const tuple2 =  [{a: {b:'1'}}, {a: {b:'1'}}, {a: {c: '2'}}] as [{a: {b: string}}, {a: {b: string}}, {a: {c: string}}];
    const raw = {
      tuple1: tuple1,
      tuple1_: tuple1,
      tuple2: tuple2,
      tuple2_: tuple2
    };
 
    const input = serialize(raw);
    const result = deserialize(
      input
    );
    expect(result).toEqual(raw)
  });

  const type3Raw = {
    tuple: [
      "1234",
      false,
      2333,
      [
        Buffer.alloc(10)
      ]
    ] as const
  };

  test('tuple support other types', () => {
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(tupleObjectType3ClassInfo);

    const input = serialize(type3Raw);
    const result = deserialize(
      input
    );
    expect(result).toEqual(type3Raw)
  });
  test('tuple will ignore items which index out of bounds', () => {
    const fury = new Fury({ refTracking: true });
    const { serialize, deserialize } = fury.registerSerializer(tupleObjectType3ClassInfo);
    const raw = {
      tuple: [
        "1234",
        false,
        2333,
        [
          Buffer.alloc(10)
        ] as const,
      ] as const
    };
 
    const input = serialize(raw);
    const result = deserialize(
      input
    );
    expect(result).toEqual(type3Raw)
  });
})
