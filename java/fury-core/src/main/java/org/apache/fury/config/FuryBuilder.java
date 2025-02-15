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

package org.apache.fury.config;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.fury.Fury;
import org.apache.fury.ThreadLocalFury;
import org.apache.fury.ThreadSafeFury;
import org.apache.fury.logging.Logger;
import org.apache.fury.logging.LoggerFactory;
import org.apache.fury.memory.Platform;
import org.apache.fury.meta.DeflaterMetaCompressor;
import org.apache.fury.meta.MetaCompressor;
import org.apache.fury.pool.ThreadPoolFury;
import org.apache.fury.reflect.ReflectionUtils;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.serializer.JavaSerializer;
import org.apache.fury.serializer.ObjectStreamSerializer;
import org.apache.fury.serializer.Serializer;
import org.apache.fury.serializer.TimeSerializers;
import org.apache.fury.serializer.collection.GuavaCollectionSerializers;
import org.apache.fury.util.GraalvmSupport;

/** Builder class to config and create {@link Fury}. */
// Method naming style for this builder:
// - withXXX: withCodegen
// - verbXXX: requireClassRegistration
@SuppressWarnings("rawtypes")
public final class FuryBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(FuryBuilder.class);

  private static final boolean ENABLE_CLASS_REGISTRATION_FORCIBLY;

  static {
    String flagValue =
        System.getProperty(
            "fury.enable_fury_security_mode_forcibly",
            System.getenv("ENABLE_CLASS_REGISTRATION_FORCIBLY"));
    ENABLE_CLASS_REGISTRATION_FORCIBLY = "true".equals(flagValue) || "1".equals(flagValue);
  }

  String name;
  boolean checkClassVersion = false;
  Language language = Language.JAVA;
  boolean trackingRef = false;
  boolean copyRef = false;
  boolean basicTypesRefIgnored = true;
  boolean stringRefIgnored = true;
  boolean timeRefIgnored = true;
  ClassLoader classLoader;
  boolean compressInt = true;
  public LongEncoding longEncoding = LongEncoding.SLI;
  boolean compressString = false;
  Boolean writeNumUtf16BytesForUtf8Encoding;
  CompatibleMode compatibleMode = CompatibleMode.SCHEMA_CONSISTENT;
  boolean checkJdkClassSerializable = true;
  Class<? extends Serializer> defaultJDKStreamSerializerType = ObjectStreamSerializer.class;
  boolean requireClassRegistration = true;
  Boolean metaShareEnabled;
  Boolean scopedMetaShareEnabled;
  boolean codeGenEnabled = true;
  Boolean deserializeNonexistentClass;
  boolean deserializeNonexistentClassNotWriteFullClassInfo = false;
  boolean asyncCompilationEnabled = false;
  boolean registerGuavaTypes = true;
  boolean scalaOptimizationEnabled = false;
  boolean suppressClassRegistrationWarnings = true;
  boolean deserializeNonexistentEnumValueAsNull = false;
  boolean serializeEnumByName = false;
  int bufferSizeLimitBytes = 128 * 1024;
  MetaCompressor metaCompressor = new DeflaterMetaCompressor();

  public FuryBuilder() {}

  /**
   * Whether cross-language serialize the object. If you used fury for java only, please set
   * language to {@link Language#JAVA}, which will have much better performance.
   */
  public FuryBuilder withLanguage(Language language) {
    this.language = language;
    return this;
  }

  /** Whether track shared or circular references. */
  public FuryBuilder withRefTracking(boolean trackingRef) {
    this.trackingRef = trackingRef;
    return this;
  }

  /**
   * Whether track {@link Fury#copy(Object)} shared or circular references.
   *
   * <p>If this option is false, shared reference will be copied into different object, and circular
   * reference copy will raise stack overflow exception.
   *
   * <p>If this option is enabled, the copy performance will be slower.
   */
  public FuryBuilder withRefCopy(boolean copyRef) {
    this.copyRef = copyRef;
    return this;
  }

  /** Whether ignore basic types shared reference. */
  public FuryBuilder ignoreBasicTypesRef(boolean ignoreBasicTypesRef) {
    this.basicTypesRefIgnored = ignoreBasicTypesRef;
    return this;
  }

  /** Whether ignore string shared reference. */
  public FuryBuilder ignoreStringRef(boolean ignoreStringRef) {
    this.stringRefIgnored = ignoreStringRef;
    return this;
  }

  /** ignore Enum Deserialize array out of bounds. */
  public FuryBuilder deserializeNonexistentEnumValueAsNull(
      boolean deserializeNonexistentEnumValueAsNull) {
    this.deserializeNonexistentEnumValueAsNull = deserializeNonexistentEnumValueAsNull;
    return this;
  }

  /** deserialize and serialize enum by name. */
  public FuryBuilder serializeEnumByName(boolean serializeEnumByName) {
    this.serializeEnumByName = serializeEnumByName;
    return this;
  }

  /**
   * Whether ignore reference tracking of all time types registered in {@link TimeSerializers} when
   * ref tracking is enabled.
   *
   * @see Config#isTimeRefIgnored
   */
  public FuryBuilder ignoreTimeRef(boolean ignoreTimeRef) {
    this.timeRefIgnored = ignoreTimeRef;
    return this;
  }

  /** Use variable length encoding for int/long. */
  public FuryBuilder withNumberCompressed(boolean numberCompressed) {
    this.compressInt = numberCompressed;
    withLongCompressed(numberCompressed);
    return this;
  }

  /** Use variable length encoding for int. */
  public FuryBuilder withIntCompressed(boolean intCompressed) {
    this.compressInt = intCompressed;
    return this;
  }

  /**
   * Use variable length encoding for long. Enabled by default, use {@link LongEncoding#SLI} (Small
   * long as int) for long encoding.
   */
  public FuryBuilder withLongCompressed(boolean longCompressed) {
    return withLongCompressed(longCompressed ? LongEncoding.SLI : LongEncoding.LE_RAW_BYTES);
  }

  /** Use variable length encoding for long. */
  public FuryBuilder withLongCompressed(LongEncoding longEncoding) {
    this.longEncoding = Objects.requireNonNull(longEncoding);
    return this;
  }

  /** Whether compress string for small size. */
  public FuryBuilder withStringCompressed(boolean stringCompressed) {
    this.compressString = stringCompressed;
    return this;
  }

  /**
   * Whether write num_bytes of utf16 for utf8 encoding. With this option enabled, fury will write
   * the num_bytes of utf16 before write utf8 encoded data, so that the deserialization can create
   * the appropriate utf16 array for store the data, thus save one copy.
   */
  public FuryBuilder withWriteNumUtf16BytesForUtf8Encoding(
      boolean writeNumUtf16BytesForUtf8Encoding) {
    this.writeNumUtf16BytesForUtf8Encoding = writeNumUtf16BytesForUtf8Encoding;
    return this;
  }

  /**
   * Sets the limit for Fury's internal buffer. If the buffer size exceeds this limit, it will be
   * reset to this limit after every serialization and deserialization.
   *
   * <p>The default is 128k.
   */
  public FuryBuilder withBufferSizeLimitBytes(int bufferSizeLimitBytes) {
    this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    return this;
  }

  /**
   * Set classloader for fury to load classes, this classloader can't up updated. Fury will cache
   * the class meta data, if classloader can be updated, there may be class meta collision if
   * different classloaders have classes with same name.
   *
   * <p>If you want to change classloader, please use {@link org.apache.fury.util.LoaderBinding} or
   * {@link ThreadSafeFury} to setup mapping between classloaders and fury instances.
   */
  public FuryBuilder withClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
    return this;
  }

  /**
   * Set class schema compatible mode.
   *
   * @see CompatibleMode
   */
  public FuryBuilder withCompatibleMode(CompatibleMode compatibleMode) {
    this.compatibleMode = compatibleMode;
    return this;
  }

  /**
   * Whether check class schema consistency, will be disabled automatically when {@link
   * CompatibleMode#COMPATIBLE} is enabled. Do not disable this option unless you can ensure the
   * class won't evolve.
   */
  public FuryBuilder withClassVersionCheck(boolean checkClassVersion) {
    this.checkClassVersion = checkClassVersion;
    return this;
  }

  /** Whether check classes under `java.*` implement {@link java.io.Serializable}. */
  public FuryBuilder withJdkClassSerializableCheck(boolean checkJdkClassSerializable) {
    this.checkJdkClassSerializable = checkJdkClassSerializable;
    return this;
  }

  /**
   * Whether pre-register guava types such as `RegularImmutableMap`/`RegularImmutableList`. Those
   * types are not public API, but seems pretty stable.
   *
   * @see GuavaCollectionSerializers
   */
  public FuryBuilder registerGuavaTypes(boolean register) {
    this.registerGuavaTypes = register;
    return this;
  }

  /**
   * Whether to require registering classes for serialization, enabled by default. If disabled,
   * unknown classes can be deserialized, which may be insecure and cause remote code execution
   * attack if the classes `constructor`/`equals`/`hashCode` method contain malicious code. Do not
   * disable class registration if you can't ensure your environment are *indeed secure*. We are not
   * responsible for security risks if you disable this option. If you disable this option, you can
   * configure {@link org.apache.fury.resolver.ClassChecker} by {@link
   * ClassResolver#setClassChecker} to control which classes are allowed being serialized.
   */
  public FuryBuilder requireClassRegistration(boolean requireClassRegistration) {
    this.requireClassRegistration = requireClassRegistration;
    return this;
  }

  /**
   * Whether suppress class registration warnings. The warnings can be used for security audit, but
   * may be annoying, this suppression will be enabled by default.
   *
   * @see Config#suppressClassRegistrationWarnings()
   */
  public FuryBuilder suppressClassRegistrationWarnings(boolean suppress) {
    this.suppressClassRegistrationWarnings = suppress;
    return this;
  }

  /** Whether to enable meta share mode. */
  public FuryBuilder withMetaShare(boolean shareMeta) {
    this.metaShareEnabled = shareMeta;
    if (!shareMeta) {
      scopedMetaShareEnabled = false;
    }
    return this;
  }

  /**
   * Scoped meta share focuses on a single serialization process. Metadata created or identified
   * during this process is exclusive to it and is not shared with by other serializations.
   */
  public FuryBuilder withScopedMetaShare(boolean scoped) {
    scopedMetaShareEnabled = scoped;
    return this;
  }

  /**
   * Set a compressor for meta compression. Note that the passed {@link MetaCompressor} should be
   * thread-safe. By default, a `Deflater` based compressor {@link DeflaterMetaCompressor} will be
   * used. Users can pass other compressor such as `zstd` for better compression rate.
   */
  public FuryBuilder withMetaCompressor(MetaCompressor metaCompressor) {
    this.metaCompressor = MetaCompressor.checkMetaCompressor(metaCompressor);
    return this;
  }

  /**
   * Whether deserialize/skip data of un-existed class.
   *
   * @see Config#deserializeNonexistentClass()
   */
  public FuryBuilder withDeserializeNonexistentClass(boolean deserializeNonexistentClass) {
    this.deserializeNonexistentClass = deserializeNonexistentClass;
    return this;
  }

  /**
   * Whether deserialize/skip data of un-existed class. if write class full info
   *
   * @see Config#deserializeNonexistentClassNotWriteFullClassInfo()
   */
  public FuryBuilder withDeserializeNonexistentClassNotWriteFullClassInfo(
      boolean deserializeNonexistentClassNotWriteFullClassInfo) {
    this.deserializeNonexistentClassNotWriteFullClassInfo =
        deserializeNonexistentClassNotWriteFullClassInfo;
    return this;
  }

  /**
   * Whether enable jit for serialization. When disabled, the first serialization will be faster
   * since no need to generate code, but later will be much slower compared jit mode.
   */
  public FuryBuilder withCodegen(boolean codeGen) {
    this.codeGenEnabled = codeGen;
    return this;
  }

  /**
   * Whether enable async compilation. If enabled, serialization will use interpreter mode
   * serialization first and switch to jit serialization after async serializer jit for a class \ is
   * finished.
   *
   * <p>This option will be disabled automatically for graalvm native image since graalvm native
   * image doesn't support JIT at the image run time.
   *
   * @see Config#isAsyncCompilationEnabled()
   */
  public FuryBuilder withAsyncCompilation(boolean asyncCompilation) {
    this.asyncCompilationEnabled = asyncCompilation;
    return this;
  }

  /** Whether enable scala-specific serialization optimization. */
  public FuryBuilder withScalaOptimizationEnabled(boolean enableScalaOptimization) {
    this.scalaOptimizationEnabled = enableScalaOptimization;
    if (enableScalaOptimization) {
      try {
        Class.forName(
            ReflectionUtils.getPackage(Fury.class) + ".serializer.scala.ScalaSerializers");
      } catch (ClassNotFoundException e) {
        LOG.warn(
            "`fury-scala` library is not in the classpath, please add it to class path and invoke "
                + "`org.apache.fury.serializer.scala.ScalaSerializers.registerSerializers` for peek performance");
      }
    }
    return this;
  }

  /** Set name for Fury serialization. */
  public FuryBuilder withName(String name) {
    this.name = name;
    return this;
  }

  private void finish() {
    if (classLoader == null) {
      classLoader = Thread.currentThread().getContextClassLoader();
      if (classLoader == null) {
        classLoader = Fury.class.getClassLoader();
      }
    }
    if (language != Language.JAVA) {
      stringRefIgnored = false;
    }
    if (ENABLE_CLASS_REGISTRATION_FORCIBLY) {
      if (!requireClassRegistration) {
        LOG.warn("Class registration is enabled forcibly.");
        requireClassRegistration = true;
      }
    }
    if (defaultJDKStreamSerializerType == JavaSerializer.class) {
      LOG.warn(
          "JDK serialization is used for types which customized java serialization by "
              + "implementing methods such as writeObject/readObject. This is not secure, try to "
              + "use {} instead, or implement a custom {}.",
          ObjectStreamSerializer.class,
          Serializer.class);
    }
    if (writeNumUtf16BytesForUtf8Encoding == null) {
      writeNumUtf16BytesForUtf8Encoding = language == Language.JAVA;
    }
    if (compatibleMode == CompatibleMode.COMPATIBLE) {
      checkClassVersion = false;
      if (deserializeNonexistentClass == null) {
        deserializeNonexistentClass = true;
      }
      if (scopedMetaShareEnabled == null) {
        if (metaShareEnabled == null) {
          metaShareEnabled = true;
          scopedMetaShareEnabled = true;
        } else {
          scopedMetaShareEnabled = false;
        }
      } else {
        if (metaShareEnabled == null) {
          metaShareEnabled = scopedMetaShareEnabled;
        }
      }
    } else {
      if (deserializeNonexistentClass == null) {
        deserializeNonexistentClass = false;
      }
      if (scopedMetaShareEnabled != null && scopedMetaShareEnabled) {
        LOG.warn("Scoped meta share is for CompatibleMode only, disable it for {}", compatibleMode);
      }
      scopedMetaShareEnabled = false;
      if (metaShareEnabled == null) {
        metaShareEnabled = false;
      }
    }
    if (!requireClassRegistration) {
      LOG.warn(
          "Class registration isn't forced, unknown classes can be deserialized. "
              + "If the environment isn't secure, please enable class registration by "
              + "`FuryBuilder#requireClassRegistration(true)` or configure ClassChecker by "
              + "`ClassResolver#setClassChecker`");
    }
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && asyncCompilationEnabled) {
      LOG.info("Use sync compilation for graalvm native image since it doesn't support JIT.");
      asyncCompilationEnabled = false;
    }
  }

  /**
   * Create Fury and print exception when failed. Many application will create fury as a static
   * variable, Fury creation exception will be swallowed by {@link NoClassDefFoundError}. We print
   * exception explicitly for better debugging.
   */
  private static Fury newFury(FuryBuilder builder, ClassLoader classLoader) {
    try {
      return new Fury(builder, classLoader);
    } catch (Throwable t) {
      t.printStackTrace();
      LOG.error("Fury creation failed with classloader {}", classLoader);
      Platform.throwException(t);
      throw new RuntimeException(t);
    }
  }

  public Fury build() {
    finish();
    ClassLoader loader = this.classLoader;
    // clear classLoader to avoid `LoaderBinding#furyFactory` lambda capture classLoader by
    // capturing `FuryBuilder`, which make `classLoader` not able to be gc.
    this.classLoader = null;
    return newFury(this, loader);
  }

  /** Build thread safe fury. */
  public ThreadSafeFury buildThreadSafeFury() {
    return buildThreadLocalFury();
  }

  /** Build thread safe fury backed by {@link ThreadLocalFury}. */
  public ThreadLocalFury buildThreadLocalFury() {
    finish();
    ClassLoader loader = this.classLoader;
    // clear classLoader to avoid `LoaderBinding#furyFactory` lambda capture classLoader by
    // capturing `FuryBuilder`,  which make `classLoader` not able to be gc.
    this.classLoader = null;
    ThreadLocalFury threadSafeFury = new ThreadLocalFury(classLoader -> newFury(this, classLoader));
    threadSafeFury.setClassLoader(loader);
    return threadSafeFury;
  }

  /**
   * Build pooled ThreadSafeFury.
   *
   * @param minPoolSize min pool size
   * @param maxPoolSize max pool size
   * @return ThreadSafeFuryPool
   */
  public ThreadSafeFury buildThreadSafeFuryPool(int minPoolSize, int maxPoolSize) {
    return buildThreadSafeFuryPool(minPoolSize, maxPoolSize, 30L, TimeUnit.SECONDS);
  }

  /**
   * Build pooled ThreadSafeFury.
   *
   * @param minPoolSize min pool size
   * @param maxPoolSize max pool size
   * @param expireTime cache expire time, default 5's
   * @param timeUnit TimeUnit, default SECONDS
   * @return ThreadSafeFuryPool
   */
  public ThreadSafeFury buildThreadSafeFuryPool(
      int minPoolSize, int maxPoolSize, long expireTime, TimeUnit timeUnit) {
    if (minPoolSize < 0 || maxPoolSize < 0 || minPoolSize > maxPoolSize) {
      throw new IllegalArgumentException(
          String.format(
              "thread safe fury pool's init pool size error, please check it, min:[%s], max:[%s]",
              minPoolSize, maxPoolSize));
    }
    finish();
    ClassLoader loader = this.classLoader;
    this.classLoader = null;
    ThreadSafeFury threadSafeFury =
        new ThreadPoolFury(
            classLoader -> newFury(this, classLoader),
            minPoolSize,
            maxPoolSize,
            expireTime,
            timeUnit);
    threadSafeFury.setClassLoader(loader);
    return threadSafeFury;
  }
}
