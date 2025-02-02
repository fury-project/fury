# Apache Fury™ JavaScript

Javascript implementation for the Fury protocol.

The Cross-Language part of the protocol is not stable, so the output of this library may change in the future. Please be cautious when using it in a production environment.

## Install

```shell
npm install @furyjs/fury
```

## Usage

```Javascript
import Fury, { Type, InternalSerializerType } from '@furyjs/fury';

// Now we describe data structures using JSON, but in the future, we will use more ways.
const description = Type.object('example.foo', {
  foo: Type.string(),
});
const fury = new Fury({});
const { serialize, deserialize } = fury.registerSerializer(description);
const input = serialize({ foo: 'hello fury' });
const result = deserialize(input);
console.log(result);
```

## Packages

### fury

Fury protocol implementation. It generates JavaScript code at runtime to make sure that all the code could be optimized by v8 JIT efficiently.
