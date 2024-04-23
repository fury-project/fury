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

package org.apache.fury.logging;

import org.apache.fury.util.GraalvmSupport;

/**
 * A logger factory which can be used to disable fury logging more easily than configure logging.
 */
public class LoggerFactory {
  private static volatile boolean disableLogging;
  private static volatile boolean useSlf4jLogger;
  private static volatile int logLevel = LogLevel.INFO_LEVEL;

  /** Disable Logger, there will be no log output. */
  public static void disableLogging() {
    disableLogging = true;
  }

  /**
   * Enable Logger. {@link FuryLogger} is used by default. You can configure whether to use {@link
   * Slf4jLogger} through {@link LoggerFactory#createSlf4jLogger(Class)}.
   */
  public static void enableLogging() {
    disableLogging = false;
  }

  public static boolean isLoggingDisabled() {
    return disableLogging;
  }

  /**
   * Set the {@link FuryLogger} log output control level, the default is {@link
   * LogLevel#INFO_LEVEL}.
   *
   * @param level The log control level to be set, see {@link LogLevel}.
   */
  public static void setLogLevel(int level) {
    logLevel = level;
    if (level < LogLevel.ERROR_LEVEL) {
      disableLogging();
    }
  }

  /**
   * Set whether to use Slf4jLogging.
   *
   * @param useSlf4jLogging {@code true} means using {@link Slf4jLogger}, {@code false} means not
   *     using it.
   */
  public static void useSlf4jLogging(boolean useSlf4jLogging) {
    LoggerFactory.useSlf4jLogger = useSlf4jLogging;
  }

  /**
   * Get a Logger for log output.
   *
   * @param clazz Class of output Log.
   * @return If the logger is disabled, {@link NilLogger} will be returned, otherwise {@link
   *     FuryLogger} or {@link Slf4jLogger} will be returned.
   */
  public static Logger getLogger(Class<?> clazz) {
    if (disableLogging) {
      return new NilLogger();
    } else {
      if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE || !useSlf4jLogger) {
        return new FuryLogger(clazz, logLevel);
      } else {
        return createSlf4jLogger(clazz);
      }
    }
  }

  private static Logger createSlf4jLogger(Class<?> clazz) {
    return new Slf4jLogger(clazz);
  }
}
