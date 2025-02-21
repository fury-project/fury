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

package org.apache.fury.benchmark;

import static org.apache.commons.lang3.RandomStringUtils.random;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.fury.Fury;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class StringMapSerializationSuite {
  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fury.*StringMapSerializationSuite.* -f 1 -wi 5 -i 5 -t 1 -w 3s -r 3s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }

  @Benchmark
  public Object serialize(MapState state) {
    state.results[0] = state.fury.serialize(state.object1);
    state.results[1] = state.fury.serialize(state.object2);
    state.results[2] = state.fury.serialize(state.object3);
    return state.results;
  }

  @Benchmark
  public Object deserialize(MapState state) {
    state.results[0] = state.fury.deserialize(state.bytes1);
    state.results[1] = state.fury.deserialize(state.bytes2);
    state.results[2] = state.fury.deserialize(state.bytes3);
    return state.results;
  }

  @State(Scope.Thread)
  public static class MapState {
    @Param({"50"})
    public int mapSize;

    @Param({"8"})
    public int stringKeySize;

    @Param({"32"})
    public int stringValueSize;

    private Object object1;
    private Object object2;
    private Object object3;

    private byte[] bytes1;
    private byte[] bytes2;
    private byte[] bytes3;

    private Fury fury;

    private Object[] results = new Object[3];

    @Setup(Level.Trial)
    public void setup() {
      fury = Fury.builder().build();
      Map map1 = new HashMap(mapSize);
      // Let's assume that the benchmark results for 'various types of Maps' are almost identical to
      // those for 'Maps containing various types of key-value pairs'.
      // Mix various types of key-value pairs to interfere with JIT's dynamic optimization of
      // polymorphism.
      for (int i = 0; i < mapSize; i++) {
        map1.put(random(stringKeySize), random(stringValueSize));
      }
      object1 = map1;
      bytes1 = fury.serialize(map1);

      Map map2 = new HashMap(mapSize);
      for (int i = 0; i < mapSize; i++) {
        map2.put(i, i * 2);
      }
      object2 = map2;
      bytes2 = fury.serialize(map2);

      Map map3 = new HashMap(mapSize);
      for (int i = 0; i < mapSize; i++) {
        map3.put((long) i + 99, i * 3L);
      }
      object3 = map3;
      bytes3 = fury.serialize(map3);
    }
  }
}
