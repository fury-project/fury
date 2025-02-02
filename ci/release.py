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


import argparse
import logging
import os
import re
import shutil
import subprocess

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

PROJECT_ROOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../")


def prepare(v: str):
    """Create a new release branch"""
    logger.info("Start to prepare release branch for version %s", v)
    _check_release_version(v)
    os.chdir(PROJECT_ROOT_DIR)
    branch = f"releases-{v}"
    try:
        subprocess.check_call(f"git checkout -b {branch}", shell=True)
        bump_version(version=v, l="all")
        subprocess.check_call("git add -u", shell=True)
        subprocess.check_call(f"git commit -m 'prepare release for {v}'", shell=True)
    except BaseException:
        logger.exception("Prepare branch failed")
        subprocess.check_call(f"git checkout - && git branch -D {branch}", shell=True)
        raise


def build(v: str):
    """version format: 0.5.1"""
    logger.info("Start to prepare release artifacts for version %s", v)
    _check_release_version(v)
    os.chdir(PROJECT_ROOT_DIR)
    if os.path.exists("dist"):
        shutil.rmtree("dist")
    os.mkdir("dist")
    subprocess.check_call(f"git checkout releases-{v}", shell=True)
    branch = f"releases-{v}"
    src_tar = f"apache-fury-{v}-incubating-src.tar.gz"
    _check_all_committed()
    _strip_unnecessary_license()
    subprocess.check_call(
        "git add LICENSE && git commit -m 'remove benchmark from license'", shell=True
    )
    subprocess.check_call(
        f"git archive --format=tar.gz "
        f"--output=dist/{src_tar} "
        f"--prefix=apache-fury-{v}-incubating-src/ {branch}",
        shell=True,
    )
    subprocess.check_call("git reset --hard HEAD~", shell=True)
    os.chdir("dist")
    logger.info("Start to generate signature")
    subprocess.check_call(
        f"gpg --armor --output {src_tar}.asc --detach-sig {src_tar}", shell=True
    )
    subprocess.check_call(f"sha512sum {src_tar} >{src_tar}.sha512", shell=True)
    verify(v)


def _check_release_version(v: str):
    assert v
    if "rc" in v:
        raise ValueError(
            "RC should only be contained in tag and svn directory, not in code"
        )


def _check_all_committed():
    proc = subprocess.run("git diff --quiet", capture_output=True, shell=True)
    result = proc.returncode
    if result != 0:
        raise Exception(
            f"There are some uncommitted files: {proc.stdout}, please commit it."
        )


def _strip_unnecessary_license():
    with open("LICENSE", "r") as f:
        lines = f.readlines()
    new_lines = []
    line_number = 0
    while line_number < len(lines):
        line = lines[line_number]
        if "fast-serialization" in line:
            line_number += 4
        elif "benchmark" in line:  # strip license in benchmark
            line_number += 1
        else:
            new_lines.append(line)
            line_number += 1
    text = "".join(new_lines)
    if lines != new_lines:
        with open("LICENSE", "w") as f:
            f.write(text)


def verify(v):
    src_tar = f"apache-fury-{v}-incubating-src.tar.gz"
    subprocess.check_call(f"gpg --verify {src_tar}.asc {src_tar}", shell=True)
    logger.info("Verified signature")
    subprocess.check_call(f"sha512sum --check {src_tar}.sha512", shell=True)
    logger.info("Verified checksum successfully")


def bump_version(**kwargs):
    new_version = kwargs["version"]
    langs = kwargs["l"]
    if langs == "all":
        langs = ["java", "python", "javascript", "scala", "rust"]
    else:
        langs = langs.split(",")
    for lang in langs:
        if lang == "java":
            bump_java_version(new_version)
        elif lang == "scala":
            _bump_version("scala", "build.sbt", new_version, _update_scala_version)
        elif lang == "rust":
            _bump_version("rust", "Cargo.toml", new_version, _update_rust_version)
        elif lang == "python":
            _bump_version(
                "python/pyfury", "__init__.py", new_version, _update_python_version
            )
        elif lang == "javascript":
            _bump_version(
                "javascript/packages/fury",
                "package.json",
                new_version,
                _update_js_version,
            )
        else:
            raise NotImplementedError(f"Unsupported {lang}")


def _bump_version(path, file, new_version, func):
    os.chdir(os.path.join(PROJECT_ROOT_DIR, path))
    with open(file, "r") as f:
        lines = f.readlines()
    lines = func(lines, new_version) or lines
    text = "".join(lines)
    with open(file, "w") as f:
        f.write(text)


def bump_java_version(new_version):
    for p in [
        "integration_tests/graalvm_tests",
        "integration_tests/jdk_compatibility_tests",
        "integration_tests/jpms_tests",
        "integration_tests/latest_jdk_tests",
        "integration_tests/latest_jdk_tests",
        "java/benchmark",
    ]:
        _bump_version(p, "pom.xml", new_version, _update_pom_parent_version)
    os.chdir(os.path.join(PROJECT_ROOT_DIR, "java"))
    subprocess.check_output(
        f"mvn versions:set -DnewVersion={new_version}",
        shell=True,
        universal_newlines=True,
    )


def _update_pom_parent_version(lines, new_version):
    start_index, end_index = -1, -1
    for i, line in enumerate(lines):
        if "<parent>" in line:
            start_index = i
        if "</parent>" in line:
            end_index = i
            break
    assert start_index != -1
    assert end_index != -1
    for line_number in range(start_index, end_index):
        line = lines[line_number]
        if "version" in line:
            line = re.sub(
                r"(<version>)[^<>]+(</version>)", r"\g<1>" + new_version + r"\2", line
            )
            lines[line_number] = line


def _update_scala_version(lines, v):
    for index, line in enumerate(lines):
        if "furyVersion = " in line:
            lines[index] = f'val furyVersion = "{v}"\n'
            break
    return lines


def _update_rust_version(lines, v):
    for index, line in enumerate(lines):
        if "version = " in line:
            lines[index] = f'version = "{v}"\n'
            break
    return lines


def _update_python_version(lines, v: str):
    for index, line in enumerate(lines):
        if "__version__ = " in line:
            v = v.replace("-alpha", "a")
            v = v.replace("-beta", "b")
            v = v.replace("-rc", "rc")
            lines[index] = f'__version__ = "{v}"\n'
            break


def _update_js_version(lines, v: str):
    for index, line in enumerate(lines):
        if "version" in line:
            # "version": "0.5.9-beta"
            for x in ["-alpha", "-beta", "-rc"]:
                if x in v and v.split(x)[-1].isdigit():
                    v = v.replace(x, x + ".")
            lines[index] = f'  "version": "{v}",\n'
            break


def _parse_args():
    parser = argparse.ArgumentParser(
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    parser.set_defaults(func=parser.print_help)
    subparsers = parser.add_subparsers()
    bump_version_parser = subparsers.add_parser(
        "bump_version",
        description="Bump version",
    )
    bump_version_parser.add_argument("-version", type=str, help="new version")
    bump_version_parser.add_argument("-l", type=str, help="language")
    bump_version_parser.set_defaults(func=bump_version)

    prepare_parser = subparsers.add_parser(
        "prepare",
        description="Prepare release branch",
    )
    prepare_parser.add_argument("-v", type=str, help="new version")
    prepare_parser.set_defaults(func=prepare)

    release_parser = subparsers.add_parser(
        "build",
        description="Build release artifacts",
    )
    release_parser.add_argument("-v", type=str, help="new version")
    release_parser.set_defaults(func=build)

    verify_parser = subparsers.add_parser(
        "verify",
        description="Verify release artifacts",
    )
    verify_parser.add_argument("-v", type=str, help="new version")
    verify_parser.set_defaults(func=verify)

    args = parser.parse_args()
    arg_dict = dict(vars(args))
    del arg_dict["func"]
    print(arg_dict)
    args.func(**arg_dict)


if __name__ == "__main__":
    _parse_args()
