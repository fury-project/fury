#!/usr/bin/env bash

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

set -e
set -x

ROOT="$(git rev-parse --show-toplevel)"
echo "Root path: $ROOT, home path: $HOME"
cd "$ROOT"


install_python() {
  wget -q https://repo.anaconda.com/miniconda/Miniconda3-py38_23.5.2-0-Linux-x86_64.sh -O Miniconda3.sh
  bash Miniconda3.sh -b -p $HOME/miniconda && rm -f miniconda.*
  which python
  echo "Python version $(python -V), path $(which python)"
}

install_pyfury() {
  echo "Python version $(python -V), path $(which python)"
  "$ROOT"/ci/deploy.sh install_pyarrow
  pip install Cython wheel pytest
  pushd "$ROOT/python"
  pip list
  echo "Install pyfury"
  # Fix strange installed deps not found
  pip install setuptools -U
  bazel build //:cp_fury_so
  pip install -v -e .
  popd
}

install_bazel() {
  if command -v bazel >/dev/null; then
    echo "existing bazel location $(which bazel)"
    echo "existing bazel version $(bazel version)"
  fi
  # GRPC support bazel 6.3.2 https://grpc.github.io/grpc/core/md_doc_bazel_support.html
  UNAME_OUT="$(uname -s)"
  case "${UNAME_OUT}" in
    Linux*)     MACHINE=linux;;
    Darwin*)    MACHINE=darwin;;
    *)          echo "unknown machine: $UNAME_OUT"
  esac
  URL="https://github.com/bazelbuild/bazel/releases/download/6.3.2/bazel-6.3.2-installer-$MACHINE-x86_64.sh"
  wget -q -O install.sh $URL
  chmod +x install.sh
  set +x
  ./install.sh --user
  source ~/.bazel/bin/bazel-complete.bash
  set -x
  export PATH=~/bin:$PATH
  echo "$HOME/bin/bazel version: $(~/bin/bazel version)"
  rm -f install.sh
  VERSION=`bazel version`
  echo "bazel version: $VERSION"
  if [[ "$MACHINE" == linux ]]; then
    MEM=`cat /proc/meminfo | grep MemTotal | awk '{print $2}'`
    JOBS=`expr $MEM / 1024 / 1024 / 3`
    echo "build --jobs="$JOBS >> ~/.bazelrc
    grep "jobs" ~/.bazelrc
  fi
}

install_bazel_windows() {
  choco install bazel --version=6.3.2 --force
  VERSION=`bazel version`
  echo "bazel version: $VERSION"
}

JDKS=(
"zulu21.28.85-ca-jdk21.0.0-linux_x64"
"zulu17.44.17-ca-crac-jdk17.0.8-linux_x64"
"zulu15.46.17-ca-jdk15.0.10-linux_x64"
"zulu13.54.17-ca-jdk13.0.14-linux_x64"
"zulu11.66.15-ca-jdk11.0.20-linux_x64"
"zulu8.72.0.17-ca-jdk8.0.382-linux_x64"
)

install_jdks() {
  cd "$ROOT"
  for jdk in "${JDKS[@]}"; do
    wget -q https://cdn.azul.com/zulu/bin/"$jdk".tar.gz -O "$jdk".tar.gz
    tar zxf "$jdk".tar.gz
  done
}

graalvm_test() {
  cd "$ROOT"/java
  mvn -T10 -B --no-transfer-progress clean install -DskipTests
  echo "Start to build graalvm native image"
  cd "$ROOT"/integration_tests/graalvm_tests
  mvn -DskipTests=true --no-transfer-progress -Pnative package
  echo "Built graalvm native image"
  echo "Start to run graalvm native image"
  ./target/main
  echo "Execute graalvm tests succeed!"
}

integration_tests() {
  cd "$ROOT"/java
  mvn -T10 -B --no-transfer-progress clean install -DskipTests
  echo "benchmark tests"
  cd "$ROOT"/java/benchmark
  mvn -T10 -B --no-transfer-progress clean test install -Pjmh
  echo "Start latest jdk tests"
  cd "$ROOT"/integration_tests/latest_jdk_tests
  echo "latest_jdk_tests: JDK 21"
  export JAVA_HOME="$ROOT/zulu21.28.85-ca-jdk21.0.0-linux_x64"
  export PATH=$JAVA_HOME/bin:$PATH
  mvn -T10 -B --no-transfer-progress clean test
  echo "Start JPMS tests"
  cd "$ROOT"/integration_tests/jpms_tests
  mvn -T10 -B --no-transfer-progress clean compile
  echo "Start jdk compatibility tests"
  cd "$ROOT"/integration_tests/jdk_compatibility_tests
  mvn -T10 -B --no-transfer-progress clean test
  for jdk in "${JDKS[@]}"; do
     export JAVA_HOME="$ROOT/$jdk"
     export PATH=$JAVA_HOME/bin:$PATH
     echo "First round for generate data: ${jdk}"
     mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fury.integration_tests.JDKCompatibilityTest
  done
  for jdk in "${JDKS[@]}"; do
     export JAVA_HOME="$ROOT/$jdk"
     export PATH=$JAVA_HOME/bin:$PATH
     echo "Second round for compatibility: ${jdk}"
     mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fury.integration_tests.JDKCompatibilityTest
  done
}

jdk17_plus_tests() {
  java -version
  echo "Executing fury java tests"
  cd "$ROOT/java"
  set +e
  mvn -T10 --batch-mode --no-transfer-progress test install -pl '!fury-format,!fury-testsuite'
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    exit $testcode
  fi
  echo "Executing fury java tests succeeds"
  echo "Executing latest_jdk_tests"
  cd "$ROOT"/integration_tests/latest_jdk_tests
  mvn -T10 -B --no-transfer-progress clean test
  echo "Executing latest_jdk_tests succeeds"
}

case $1 in
    java8)
      echo "Executing fury java tests"
      cd "$ROOT/java"
      set +e
      mvn -T16 --batch-mode --no-transfer-progress test
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fury java tests succeeds"
    ;;
    java11)
      java -version
      echo "Executing fury java tests"
      cd "$ROOT/java"
      set +e
      mvn -T16 --batch-mode --no-transfer-progress test
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fury java tests succeeds"
    ;;
    java17)
      jdk17_plus_tests
    ;;
    java21)
      jdk17_plus_tests
    ;;
    integration_tests)
      echo "Install jdk"
      install_jdks
      echo "Executing fury integration tests"
      integration_tests
      echo "Executing fury integration tests succeeds"
     ;;
    javascript)
      set +e
      echo "Executing fury javascript tests"
      cd "$ROOT/javascript"
      npm install
      node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        echo "Executing fury javascript tests failed"
        exit $testcode
      fi
      echo "Executing fury javascript tests succeeds"
    ;;
    rust)
      set -e
      rustup component add clippy-preview
      rustup component add rustfmt
      echo "Executing fury rust tests"
      cd "$ROOT/rust"
      cargo doc --no-deps --document-private-items --all-features --open
      cargo fmt --all -- --check
      cargo fmt --all
      cargo clippy --workspace --all-features --all-targets
      cargo doc
      cargo build --all-features --all-targets
      cargo test
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        echo "Executing fury rust tests failed"
        exit $testcode
      fi
      cargo clean
      echo "Executing fury rust tests succeeds"
    ;;
    cpp)
      echo "Install pyarrow"
      "$ROOT"/ci/deploy.sh install_pyarrow
      export PATH=~/bin:$PATH
      echo "bazel version: $(bazel version)"
      set +e
      echo "Executing fury c++ tests"
      bazel test $(bazel query //...)
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        echo "Executing fury c++ tests failed"
        exit $testcode
      fi
      echo "Executing fury c++ tests succeeds"
    ;;
    python)
      install_pyfury
      pip install pandas
      cd "$ROOT/python"
      echo "Executing fury python tests"
      pytest -v -s --durations=60 pyfury/tests
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fury python tests succeeds"
      ENABLE_FURY_CYTHON_SERIALIZATION=0 pytest -v -s --durations=60 pyfury/tests
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fury python tests succeeds"
    ;;
    go)
      echo "Executing fury go tests for go"
      cd "$ROOT/go/fury"
      go test -v
      echo "Executing fury go tests succeeds"
    ;;
    format)
      echo "Install format tools"
      pip install black==22.1.0 flake8==3.9.1 flake8-quotes flake8-bugbear click==8.0.2
      echo "Executing format check"
      bash ci/format.sh
      cd "$ROOT/java"
      mvn -T10 -B --no-transfer-progress spotless:check
      mvn -T10 -B --no-transfer-progress checkstyle:check
      echo "Executing format check succeeds"
    ;;
    *)
      echo "Execute command $*"
      "$@"
      ;;
esac
