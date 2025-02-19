load("@rules_cc//cc:defs.bzl", "cc_library")

cc_library(
    name = "libmmh3",
    srcs = ["MurmurHash3.cc"],
    hdrs = ["MurmurHash3.h"],
    strip_include_prefix = "/cpp",
    alwayslink=True,
    linkstatic=True,
    visibility = ["//visibility:public"],
)

cc_library(
    name = "simdutf",
    srcs = ["simdutf.cpp"],
    hdrs = ["simdutf.h"],
    includes = ["."],
    visibility = ["//visibility:public"],
)
