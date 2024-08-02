// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

mod buffer;
mod deserializer;
mod error;
mod meta;
mod row;
mod serializer;
mod types;
mod util;

pub use deserializer::from_buffer;
pub use error::Error;
pub use fury_derive::*;
pub use meta::{Encoding, MetaStringDecoder, MetaStringEncoder};
pub use row::{from_row, to_row};
pub use serializer::to_buffer;
pub use util::{utf16_to_utf8, utf16_to_utf8_simd};

pub mod __derive {
    pub use crate::buffer::{Reader, Writer};
    pub use crate::deserializer::{Deserialize, DeserializerState};
    pub use crate::row::{ArrayViewer, ArrayWriter, Row, StructViewer, StructWriter};
    pub use crate::serializer::{Serialize, SerializerState};
    pub use crate::types::{compute_struct_hash, FieldType, FuryMeta, SIZE_OF_REF_AND_TYPE};
    pub use crate::Error;
}
