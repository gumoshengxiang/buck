/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.starlark.compatible;

import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Mutability;

/** Simple try-with-resources class that creates and cleans up a mutable environment */
public class TestMutableEnv implements AutoCloseable {
  private final Mutability mutability;
  private final Environment env;

  public TestMutableEnv() {
    mutability = Mutability.create("testing");
    env = Environment.builder(mutability).useDefaultSemantics().build();
  }

  public Environment getEnv() {
    return env;
  }

  @Override
  public void close() {
    mutability.close();
  }
}