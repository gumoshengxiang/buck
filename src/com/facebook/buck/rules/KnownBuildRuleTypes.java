/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidBinaryBuildRuleFactory;
import com.facebook.buck.android.AndroidInstrumentationApkRuleFactory;
import com.facebook.buck.android.AndroidLibraryBuildRuleFactory;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.ApkGenruleDescription;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.android.PrebuiltNativeLibraryDescription;
import com.facebook.buck.android.RobolectricTestBuildRuleFactory;
import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosResourceDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cpp.CppBinaryDescription;
import com.facebook.buck.cpp.CppLibraryDescription;
import com.facebook.buck.java.JavaBinaryDescription;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavaLibraryBuildRuleFactory;
import com.facebook.buck.java.JavaTestBuildRuleFactory;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.KeystoreDescription;
import com.facebook.buck.java.PrebuiltJarBuildRuleFactory;
import com.facebook.buck.parcelable.GenParcelableDescription;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.ShBinaryBuildRuleFactory;
import com.facebook.buck.shell.ShTestBuildRuleFactory;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.Set;

/**
 * A registry of all the build rules types understood by Buck.
 */
public class KnownBuildRuleTypes {

  private final ImmutableSet<Description<?>> descriptions;
  private final ImmutableMap<BuildRuleType, BuildRuleFactory<?>> factories;
  private final ImmutableMap<String, BuildRuleType> types;
  private static final KnownBuildRuleTypes DEFAULT = createDefaultBuilder().build();


  private KnownBuildRuleTypes(Set<Description<?>> descriptions,
      Map<BuildRuleType, BuildRuleFactory<?>> factories,
      Map<String, BuildRuleType> types) {
    this.descriptions = ImmutableSet.copyOf(descriptions);
    this.factories = ImmutableMap.copyOf(factories);
    this.types = ImmutableMap.copyOf(types);
  }

  public BuildRuleType getBuildRuleType(String named) {
    BuildRuleType type = types.get(named);
    if (type == null) {
      throw new HumanReadableException("Unable to find build rule type: " + named);
    }
    return type;
  }

  public BuildRuleFactory<?> getFactory(BuildRuleType buildRuleType) {
    BuildRuleFactory<?> factory = factories.get(buildRuleType);
    if (factory == null) {
      throw new HumanReadableException(
          "Unable to find factory for build rule type: " + buildRuleType);
    }
    return factory;
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return ImmutableSet.copyOf(descriptions);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static KnownBuildRuleTypes getDefault() {
    return DEFAULT;
  }

  public static Builder createDefaultBuilder() {
    Builder builder = builder();

    builder.register(new AndroidManifestDescription());
    builder.register(new AndroidResourceDescription());
    builder.register(new ApkGenruleDescription());
    builder.register(new ExportFileDescription());
    builder.register(new GenAidlDescription());
    builder.register(new GenruleDescription());
    builder.register(new KeystoreDescription());
    builder.register(new IosBinaryDescription());
    builder.register(new IosLibraryDescription());
    builder.register(new IosTestDescription());
    builder.register(new IosResourceDescription());
    builder.register(new CppBinaryDescription());
    builder.register(new CppLibraryDescription());
    builder.register(new JavaBinaryDescription());
    builder.register(new PrebuiltNativeLibraryDescription());
    builder.register(new PythonLibraryDescription());
    builder.register(new PythonBinaryDescription());
    builder.register(new XcodeProjectConfigDescription());
    builder.register(new XcodeNativeDescription());
    builder.register(new NdkLibraryDescription(Optional.<String>absent()));
    builder.register(new GenParcelableDescription());
    builder.register(new ProjectConfigDescription());

    // TODO(simons): Consider once more whether we actually want to have default rules
    builder.register(BuildRuleType.ANDROID_BINARY, new AndroidBinaryBuildRuleFactory());
    builder.register(BuildRuleType.ANDROID_INSTRUMENTATION_APK,
        new AndroidInstrumentationApkRuleFactory());
    builder.register(BuildRuleType.ANDROID_LIBRARY, new AndroidLibraryBuildRuleFactory());
    builder.register(BuildRuleType.JAVA_LIBRARY, new JavaLibraryBuildRuleFactory());
    builder.register(BuildRuleType.JAVA_TEST, new JavaTestBuildRuleFactory());
    builder.register(BuildRuleType.PREBUILT_JAR, new PrebuiltJarBuildRuleFactory());
    builder.register(BuildRuleType.ROBOLECTRIC_TEST, new RobolectricTestBuildRuleFactory());
    builder.register(BuildRuleType.SH_BINARY, new ShBinaryBuildRuleFactory());
    builder.register(BuildRuleType.SH_TEST, new ShTestBuildRuleFactory());

    return builder;
  }

  public static KnownBuildRuleTypes getConfigured(
      BuckConfig buckConfig,
      AndroidDirectoryResolver androidDirectoryResolver,
      JavaCompilerEnvironment javacEnv) {
    return createConfiguredBuilder(
        buckConfig, androidDirectoryResolver, javacEnv).build();
  }

  public static Builder createConfiguredBuilder(
      BuckConfig buckConfig,
      AndroidDirectoryResolver androidDirectoryResolver,
      JavaCompilerEnvironment javacEnv) {

    Builder builder = createDefaultBuilder();
    builder.register(BuildRuleType.JAVA_LIBRARY,
        new JavaLibraryBuildRuleFactory(
            javacEnv.getJavacPath(),
            javacEnv.getJavacVersion()));
    builder.register(BuildRuleType.ANDROID_LIBRARY,
        new AndroidLibraryBuildRuleFactory(
            javacEnv.getJavacPath(),
            javacEnv.getJavacVersion()));
    builder.register(BuildRuleType.ANDROID_BINARY,
        new AndroidBinaryBuildRuleFactory(
            JavacOptions.builder(JavacOptions.DEFAULTS)
                .setJavaCompilerEnviornment(javacEnv)
                .build()));

    Optional<String> ndkVersion = buckConfig.getNdkVersion();
    // If a NDK version isn't specified, we've got to reach into the runtime environment to find
    // out which one we will end up using.
    if (!ndkVersion.isPresent()) {
      ndkVersion = androidDirectoryResolver.getNdkVersion();
    }
    builder.register(new NdkLibraryDescription(ndkVersion));

    return builder;
  }

  public static class Builder {
    private final Map<BuildRuleType, Description<?>> descriptions;
    private final Map<BuildRuleType, BuildRuleFactory<?>> factories;
    private final Map<String, BuildRuleType> types;

    protected Builder() {
      this.descriptions = Maps.newConcurrentMap();
      this.factories = Maps.newConcurrentMap();
      this.types = Maps.newConcurrentMap();
    }

    public void register(BuildRuleType type, BuildRuleFactory<?> factory) {
      Preconditions.checkNotNull(type);
      Preconditions.checkNotNull(factory);
      types.put(type.getName(), type);
      factories.put(type, factory);
    }

    public void register(Description<?> description) {
      Preconditions.checkNotNull(description);
      BuildRuleType type = description.getBuildRuleType();
      types.put(type.getName(), type);
      factories.put(type, new DescribedRuleFactory<>(description));
      descriptions.put(type, description);
    }

    public KnownBuildRuleTypes build() {
      return new KnownBuildRuleTypes(ImmutableSet.copyOf(descriptions.values()), factories, types);
    }
  }
}
