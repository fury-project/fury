# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from pyfury import lib  # noqa: F401 # pylint: disable=unused-import
from pyfury._fury import (  # noqa: F401 # pylint: disable=unused-import
    Fury,
    Language,
)

try:
    from pyfury._serialization import ENABLE_FURY_CYTHON_SERIALIZATION
except ImportError:
    ENABLE_FURY_CYTHON_SERIALIZATION = False

from pyfury._registry import ClassInfo

if ENABLE_FURY_CYTHON_SERIALIZATION:
    from pyfury._serialization import Fury, ClassInfo  # noqa: F401,F811

from pyfury._struct import (  # noqa: F401,F403,F811 # pylint: disable=unused-import
    ComplexObjectSerializer,
)
from pyfury.serializer import *  # noqa: F401,F403 # pylint: disable=unused-import
from pyfury.type import (  # noqa: F401 # pylint: disable=unused-import
    record_class_factory,
    get_qualified_classname,
    TypeId,
    Int8Type,
    Int16Type,
    Int32Type,
    Int64Type,
    Float32Type,
    Float64Type,
    # Int8ArrayType,
    Int16ArrayType,
    Int32ArrayType,
    Int64ArrayType,
    Float32ArrayType,
    Float64ArrayType,
    dataslots,
)
from pyfury._util import Buffer  # noqa: F401 # pylint: disable=unused-import

try:
    from pyfury.format import *  # noqa: F401,F403 # pylint: disable=unused-import
except (AttributeError, ImportError):
    pass

__version__ = "0.11.0.dev"
