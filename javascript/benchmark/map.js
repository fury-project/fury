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

const Fury = require("@furyjs/fury");
const beautify = require("js-beautify");
const hps = require('@furyjs/hps');
const fury = new Fury.default({
  hps, refTracking: false, useSliceString: true, hooks: {
    afterCodeGenerated: (code) => {
      return beautify.js(code, { indent_size: 2, space_in_empty_paren: true, indent_empty_lines: true });
    }
  }
});
const Benchmark = require("benchmark");
const Type = Fury.Type;



const { serialize: serialize1, deserialize: deserialize1, serializeVolatile: serializeVolatile1 } = fury.registerSerializer(Type.struct("any", {
  f1: Type.map(Type.any(), Type.any()),
  f2: Type.map(Type.any(), Type.any())
}));

const { serialize: serialize2, deserialize: deserialize2, serializeVolatile: serializeVolatile2 } = fury.registerSerializer(Type.struct("specific", {
  f1: Type.map(Type.string(), Type.string()),
  f2: Type.map(Type.int32(), Type.string())
}));
const sample = {
  f1: new Map([["foo", "ba1"], ["foo1", "ba1"], ["foo2", "ba1"], ["foo3", "ba1"], ["foo4", "ba1"], ["foo5", "ba1"], ["foo5", "ba1"], ["foo5", "ba1"]]),
  f2: new Map([[123, "ba1"], [234, "ba1"], [345, "ba1"], [456, "ba1"], [567, "ba1"], [678, "ba1"], [789, "ba1"], [890, "ba1"]])
};

const furyAb1 = serialize1(sample);
const furyAb2 = serialize2(sample);

async function start() {

  let result = {
  }

  {
    var suite = new Benchmark.Suite();
    suite
      .add("any serialize", function () {
        serializeVolatile1(sample).dispose()
      })
      .add("any deserialize", function () {
        deserialize1(furyAb1)
      })
      .add("jit serialize", function () {
        serializeVolatile2(sample).dispose()
      })
      .add("jit deserialize", function () {
        deserialize2(furyAb2)
      })
      .on("complete", function (e) {
        e.currentTarget.forEach(({ name, hz }) => {
          result[name] = Math.ceil(hz / 10000);
        });
      })
      .run({ async: false });
  }

  console.table(result);

}
start();
