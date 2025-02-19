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

import fury from "./fury";
import { MetaString, MetaStringDecoder, MetaStringEncoder } from "./meta/MetaString";
import { BinaryReader } from "./reader";
import { BinaryWriter } from "./writer";

class MetaStringBytes {
  dynamicWriteStringId = -1;

  constructor(
    public bytes: MetaString,
  ) {

  }
}

export class MetaStringResolver {
  private disposeMetaStringBytes: MetaStringBytes[] = [];
  private dynamicNameId = 0;
  private names: string[] = [];
  private namespaceEncoder = new MetaStringEncoder(".", "_");
  private namespaceDecoder = new MetaStringDecoder(".", "_");
  private typenameEncoder = new MetaStringEncoder("$", "_");
  private typenameDecoder = new MetaStringDecoder("$", "_");
  constructor(private fury: fury) {

  }

  writeBytes(writer: BinaryWriter, bytes: MetaStringBytes) {
    if (bytes.dynamicWriteStringId !== -1) {
      writer.varUInt32(((this.dynamicNameId + 1) << 1) | 1);
    } else {
      bytes.dynamicWriteStringId = this.dynamicNameId;
      this.dynamicNameId += 1;
      this.disposeMetaStringBytes.push(bytes);
      writer.varUInt32(bytes.bytes.getBytes().byteLength << 1);
      writer.uint8(bytes.bytes.getEncoding());
      writer.buffer(bytes.bytes.getBytes());
    }
  }

  public encodeNamespace(input: string) {
    return new MetaStringBytes(this.namespaceEncoder.encode(input));
  }

  public encodeTypeName(input: string) {
    return new MetaStringBytes(this.typenameEncoder.encode(input));
  }

  public readTypeName(reader: BinaryReader) {
    const idOrLen = reader.varUInt32();
    if (idOrLen & 1) {
      return this.names[idOrLen >> 1];
    } else {
      const len = idOrLen >> 1; // not used
      const encoding = reader.uint8();
      const name = this.typenameDecoder.decode(reader, len, encoding);
      this.names.push(name);
      return name;
    }
  }

  public readNamespace(reader: BinaryReader) {
    const idOrLen = reader.varUInt32();
    if (idOrLen & 1) {
      return this.names[idOrLen >> 1];
    } else {
      const len = idOrLen >> 1; // not used
      const encoding = reader.uint8();
      const name = this.namespaceDecoder.decode(reader, len, encoding);
      this.names.push(name);
      return name;
    }
  }

  reset() {
    this.disposeMetaStringBytes.forEach((x) => {
      x.dynamicWriteStringId = -1;
    });
    this.dynamicNameId = 0;
  }
}
