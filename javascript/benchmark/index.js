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
const utils = require("@furyjs/fury/dist/lib/util");
const hps = require('@furyjs/hps').default;
const fury = new Fury.default({ hps, refTracking: false, useSliceString: true });
const Benchmark = require("benchmark");
const protobuf = require("protobufjs");
const path = require('path');
const Type = Fury.Type;
const assert = require('assert');
const { spawn } = require("child_process");


export const data2ClassInfo = (
  data,
  typeName,
) => {
  if (data === null || data === undefined) {
    return null;
  }
  if (Array.isArray(data)) {
    const item = data2ClassInfo(data[0], typeName);
    if (!item) {
      throw new Error("empty array can't convert");
    }
    return Type.array(item);
  }
  if (data instanceof Date) {
    return Type.timestamp();
  }
  if (typeof data === "string") {
    return Type.string();
  }
  if (data instanceof Set) {
    return Type.set(data2ClassInfo([...data.values()][0], typeName));
  }
  if (data instanceof Map) {
    return Type.map(
      data2ClassInfo([...data.keys()][0], typeName),
      data2ClassInfo([...data.values()][0], typeName),
    );
  }
  if (typeof data === "boolean") {
    return Type.bool();
  }
  if (typeof data === "number") {
    if (data > Number.MAX_SAFE_INTEGER || data < Number.MIN_SAFE_INTEGER) {
      return Type.int64();
    }
    return Type.int32();
  }

  if (typeof data === "object") {
    if (isUint8Array(data)) {
      return Type.binary();
    }

    return Type.struct(
      {
        typeName
      },
      Object.fromEntries(
        Object.entries(data)
          .map(([key, value]) => {
            return [key, data2ClassInfo(value, `${typeName}.${key}`)];
          })
          .filter(([, v]) => Boolean(v)),
      ),
    );
  }

  throw new Error(`unkonw data type ${typeof data}`);
};


const sample = {
  id: 123456,
  name: "John Doe",
  email: "johndoe@example.com",
  age: 30,
  address: {
    street: "123 Main St",
    city: "Anytown",
    state: "CA",
    zip: "98765",
  },
  phoneNumbers: [
    {
      type: "home",
      number: "555-1234",
    },
    {
      type: "work",
      number: "555-5678",
    },
  ],
  isMarried: true,
  hasChildren: false,
  interests: [
    "reading",
    "hiking",
    "cooking",
    "swimming",
    "painting",
    "traveling",
    "photography",
    "playing music",
    "watching movies",
    "learning new things",
    "spending time with family and friends",
  ],
  education: [
    {
      degree: "Bachelor of Science",
      major: "Computer Science",
      university: "University of California, Los Angeles",
      graduationYear: 2012,
    },
    {
      degree: "Master of Business Administration",
      major: "Marketing",
      university: "Stanford University",
      graduationYear: 2016,
    },
  ],
  workExperience: [
    {
      company: "Google",
      position: "Software Engineer",
      startDate: "2012-06-01",
      endDate: "2014-08-31",
    },
    {
      company: "Apple",
      position: "Product Manager",
      startDate: "2014-09-01",
      endDate: "2018-12-31",
    },
    {
      company: "Amazon",
      position: "Senior Product Manager",
      startDate: "2019-01-01",
      endDate: "2018-12-31",
    },
  ],
  selfIntroduction: `Hi, my name is John Doe and I am a highly motivated and driven individual with a passion for excellence in all areas of my life. I have a diverse background and have gained valuable experience in various fields such as software engineering, product management, and marketing.
  I am a graduate of the University of California, Los Angeles where I received my Bachelor of Science degree in Computer Science. After graduation, I joined Google as a software engineer where I worked on developing innovative products that revolutionized the way people interact with technology.
  With a desire to broaden my skillset, I pursued a Master of Business Administration degree in Marketing from Stanford University. There, I gained a deep understanding of consumer behavior and developed the ability to effectively communicate complex ideas to various stakeholders.
  After completing my MBA, I joined Apple as a product manager where I led the development of several successful products and played a key role in the company's growth. Currently, I am working as a Senior Product Manager at Amazon, where I am responsible for managing a team of product managers and developing cutting-edge products that meet the needs of our customers.
  Aside from my professional life, I am an avid reader, hiker, and cook. I enjoy spending time with my family and friends, learning new things, and traveling to new places. I believe that success is a journey, not a destination, and I am committed to continuously improving myself and achieving excellence in all that I do.
  `,
};


const classinfo = utils.data2ClassInfo(sample, "fury.test.foo");
const { serialize, deserialize, serializeVolatile } = fury.registerSerializer(classinfo);

const furyAb = serialize(sample);
const sampleJson = JSON.stringify(sample);

function loadProto() {
  return new Promise((resolve) => {
    protobuf.load(path.join(__dirname, 'sample.proto'), function (err, root) {
      if (err) throw err;
      const AwesomeMessage = root.lookupType("SomeMessage");
      resolve({
        encode: (payload) => {
          const message = AwesomeMessage.create(payload); // or use .fromObject if conversion is necessary
          return AwesomeMessage.encode(message).finish();
        },
        decode: (buffer) => {
          const message = AwesomeMessage.decode(buffer);

          return AwesomeMessage.toObject(message, {
            longs: String,
            enums: String,
            bytes: String,
          });
        },
      });
    });
  });
}

async function start() {
  const { encode: protobufEncode, decode: protobufDecode } = await loadProto();
  const protobufBf = protobufEncode(sample);

  {
    console.log('sample json size: ', `${(sampleJson.length / 1000).toFixed()}k`);
    assert(JSON.stringify(protobufDecode(protobufBf)) === sampleJson);
    assert.deepEqual(deserialize(furyAb), sample);
  }
  let result = {
    fury: {
      serialize: 0,
      deserialize: 0,
    },
    protobuf: {
      serialize: 0,
      deserialize: 0,
    },
    json: {
      serialize: 0,
      deserialize: 0,
    }
  }

  {
    var suite = new Benchmark.Suite();
    suite
      .add("fury", function () {
        serializeVolatile(sample).dispose();
      })
      .add("json", function () {
        JSON.stringify(sample);
      })
      .add("protobuf", function () {
        protobufEncode(sample);
      })
      .on("complete", function (e) {
        e.currentTarget.forEach(({ name, hz }) => {
          result[name].serialize = Math.ceil(hz / 10000);
        });
      })
      .run({ async: false });
  }


  {
    var suite = new Benchmark.Suite();
    suite
      .add("fury", function () {
        deserialize(furyAb);
      })
      .add("json", function () {
        JSON.parse(sampleJson);
      })
      .add("protobuf", function () {
        protobufDecode(protobufBf);
      })
      .on("complete", function (e) {
        e.currentTarget.forEach(({ name, hz }) => {
          result[name].deserialize = Math.ceil(hz / 10000);
        });
      })
      .run({ async: false });
  }
  console.table(result);

  spawn(
    `python3`,
    ['draw.py', result.json.serialize, result.json.deserialize, result.protobuf.serialize, result.protobuf.deserialize, result.fury.serialize, result.fury.deserialize],
    {
      cwd: __dirname,
    }
  )
}
start();
