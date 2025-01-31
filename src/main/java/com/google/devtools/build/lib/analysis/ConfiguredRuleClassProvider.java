// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.ABSTRACT;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.TEST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.transitions.ComposingTransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics;
import com.google.devtools.build.lib.analysis.skylark.SkylarkModules;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.graph.Node;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BazelStarlarkContext;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.ThirdPartyLicenseExistencePolicy;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.SymbolGenerator;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skylarkbuildapi.Bootstrap;
import com.google.devtools.build.lib.skylarkinterface.SkylarkInterfaceUtils;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.SkylarkUtils.Phase;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.StarlarkThread.Extension;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionsProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * Knows about every rule Blaze supports and the associated configuration options.
 *
 * <p>This class is initialized on server startup and the set of rules, build info factories
 * and configuration options is guaranteed not to change over the life time of the Blaze server.
 */
public class ConfiguredRuleClassProvider implements RuleClassProvider {

  /**
   * Predicate for determining whether the analysis cache should be cleared, given the new and old
   * value of an option which has changed and the values of the other new options.
   */
  @FunctionalInterface
  public interface OptionsDiffPredicate {
    public static final OptionsDiffPredicate ALWAYS_INVALIDATE =
        (options, option, oldValue, newValue) -> true;

    public boolean apply(
        BuildOptions newOptions, OptionDefinition option, Object oldValue, Object newValue);
  }

  /**
   * Custom dependency validation logic.
   */
  public interface PrerequisiteValidator {
    /**
     * Checks whether the rule in {@code contextBuilder} is allowed to depend on {@code
     * prerequisite} through the attribute {@code attribute}.
     *
     * <p>Can be used for enforcing any organization-specific policies about the layout of the
     * workspace.
     */
    void validate(
        RuleContext.Builder contextBuilder,
        ConfiguredTargetAndData prerequisite,
        Attribute attribute);
  }

  /**
   * A coherent set of options, fragments, aspects and rules; each of these may declare a dependency
   * on other such sets.
   */
  public interface RuleSet {
    /** Add stuff to the configured rule class provider builder. */
    void init(ConfiguredRuleClassProvider.Builder builder);

    /** List of required modules. */
    ImmutableList<RuleSet> requires();
  }

  /** Builder for {@link ConfiguredRuleClassProvider}. */
  public static class Builder implements RuleDefinitionEnvironment {
    private final StringBuilder defaultWorkspaceFilePrefix = new StringBuilder();
    private final StringBuilder defaultWorkspaceFileSuffix = new StringBuilder();
    private Label preludeLabel;
    private String runfilesPrefix;
    private String toolsRepository;
    private final List<ConfigurationFragmentFactory> configurationFragmentFactories =
        new ArrayList<>();
    private final List<BuildInfoFactory> buildInfoFactories = new ArrayList<>();
    private final Set<Class<? extends FragmentOptions>> configurationOptions =
        new LinkedHashSet<>();

    private final Map<String, RuleClass> ruleClassMap = new HashMap<>();
    private final Map<String, RuleDefinition> ruleDefinitionMap = new HashMap<>();
    private final Map<String, NativeAspectClass> nativeAspectClassMap =
        new HashMap<>();
    private final Map<Class<? extends RuleDefinition>, RuleClass> ruleMap = new HashMap<>();
    private final Digraph<Class<? extends RuleDefinition>> dependencyGraph =
        new Digraph<>();
    private List<Class<? extends BuildConfiguration.Fragment>> universalFragments =
        new ArrayList<>();
    @Nullable private TransitionFactory<Rule> trimmingTransitionFactory = null;
    @Nullable private PatchTransition toolchainTaggedTrimmingTransition = null;
    private OptionsDiffPredicate shouldInvalidateCacheForOptionDiff =
        OptionsDiffPredicate.ALWAYS_INVALIDATE;
    private PrerequisiteValidator prerequisiteValidator;
    private ImmutableList.Builder<Bootstrap> skylarkBootstraps =
        ImmutableList.<Bootstrap>builder();
    private ImmutableMap.Builder<String, Object> skylarkAccessibleTopLevels =
        ImmutableMap.builder();
    private Set<String> reservedActionMnemonics = new TreeSet<>();
    private BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider =
        (BuildOptions options) -> ActionEnvironment.EMPTY;
    private ConstraintSemantics constraintSemantics = new ConstraintSemantics();

    private ThirdPartyLicenseExistencePolicy thirdPartyLicenseExistencePolicy =
        ThirdPartyLicenseExistencePolicy.USER_CONTROLLABLE;
    private boolean enableExecutionTransition = false;

    public Builder addWorkspaceFilePrefix(String contents) {
      defaultWorkspaceFilePrefix.append(contents);
      return this;
    }

    public Builder addWorkspaceFileSuffix(String contents) {
      defaultWorkspaceFileSuffix.append(contents);
      return this;
    }

    @VisibleForTesting
    public Builder clearWorkspaceFileSuffixForTesting() {
      defaultWorkspaceFileSuffix.delete(0, defaultWorkspaceFileSuffix.length());
      return this;
    }

    public Builder setPrelude(String preludeLabelString) {
      try {
        this.preludeLabel = Label.parseAbsolute(preludeLabelString, ImmutableMap.of());
      } catch (LabelSyntaxException e) {
        String errorMsg =
            String.format("Prelude label '%s' is invalid: %s", preludeLabelString, e.getMessage());
        throw new IllegalArgumentException(errorMsg);
      }
      return this;
    }

    public Builder setRunfilesPrefix(String runfilesPrefix) {
      this.runfilesPrefix = runfilesPrefix;
      return this;
    }

    public Builder setToolsRepository(String toolsRepository) {
      this.toolsRepository = toolsRepository;
      return this;
    }

    public Builder setPrerequisiteValidator(PrerequisiteValidator prerequisiteValidator) {
      this.prerequisiteValidator = prerequisiteValidator;
      return this;
    }

    public Builder addBuildInfoFactory(BuildInfoFactory factory) {
      buildInfoFactories.add(factory);
      return this;
    }

    public Builder addRuleDefinition(RuleDefinition ruleDefinition) {
      Class<? extends RuleDefinition> ruleDefinitionClass = ruleDefinition.getClass();
      ruleDefinitionMap.put(ruleDefinitionClass.getName(), ruleDefinition);
      dependencyGraph.createNode(ruleDefinitionClass);
      for (Class<? extends RuleDefinition> ancestor : ruleDefinition.getMetadata().ancestors()) {
        dependencyGraph.addEdge(ancestor, ruleDefinitionClass);
      }

      return this;
    }

    public Builder addNativeAspectClass(NativeAspectClass aspectFactoryClass) {
      nativeAspectClassMap.put(aspectFactoryClass.getName(), aspectFactoryClass);
      return this;
    }

    /**
     * Adds a configuration fragment factory and all build options required by its fragment.
     *
     * <p>Note that configuration fragments annotated with a Skylark name must have a unique name;
     * no two different configuration fragments can share the same name.
     */
    public Builder addConfigurationFragment(ConfigurationFragmentFactory factory) {
      this.configurationOptions.addAll(factory.requiredOptions());
      configurationFragmentFactories.add(factory);
      return this;
    }

    /**
     * Adds configuration options that aren't required by configuration fragments.
     *
     * <p>If {@link #addConfigurationFragment(ConfigurationFragmentFactory)} adds a fragment factory
     * that also requires these options, this method is redundant.
     */
    public Builder addConfigurationOptions(Class<? extends FragmentOptions> configurationOptions) {
      this.configurationOptions.add(configurationOptions);
      return this;
    }

    public Builder addUniversalConfigurationFragment(
        Class<? extends BuildConfiguration.Fragment> fragment) {
      this.universalFragments.add(fragment);
      return this;
    }

    public Builder addSkylarkBootstrap(Bootstrap bootstrap) {
      this.skylarkBootstraps.add(bootstrap);
      return this;
    }

    public Builder addSkylarkAccessibleTopLevels(String name, Object object) {
      this.skylarkAccessibleTopLevels.put(name, object);
      return this;
    }

    public Builder addReservedActionMnemonic(String mnemonic) {
      this.reservedActionMnemonics.add(mnemonic);
      return this;
    }

    public Builder setActionEnvironmentProvider(
        BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider) {
      this.actionEnvironmentProvider = actionEnvironmentProvider;
      return this;
    }

    /**
     * Sets the logic that lets rules declare which environments they support and validates rules
     * don't depend on rules that aren't compatible with the same environments. Defaults to
     * {@ConstraintSemantics}. See {@ConstraintSemantics} for more details.
     */
    public Builder setConstraintSemantics(ConstraintSemantics constraintSemantics) {
      this.constraintSemantics = constraintSemantics;
      return this;
    }

    /**
     * Sets the policy for checking if third_party rules declare <code>licenses()</code>. See {@link
     * #thirdPartyLicenseExistencePolicy} for the default value.
     */
    public Builder setThirdPartyLicenseExistencePolicy(ThirdPartyLicenseExistencePolicy policy) {
      this.thirdPartyLicenseExistencePolicy = policy;
      return this;
    }

    /**
     * Adds a transition factory that produces a trimming transition to be run over all targets
     * after other transitions.
     *
     * <p>Transitions are run in the order they're added.
     *
     * <p>This is a temporary measure for supporting trimming of test rules and manual trimming of
     * feature flags, and support for this transition factory will likely be removed at some point
     * in the future (whenever automatic trimming is sufficiently workable).
     */
    public Builder addTrimmingTransitionFactory(TransitionFactory<Rule> factory) {
      Preconditions.checkNotNull(factory);
      Preconditions.checkArgument(!factory.isSplit());
      if (trimmingTransitionFactory == null) {
        trimmingTransitionFactory = factory;
      } else {
        trimmingTransitionFactory =
            ComposingTransitionFactory.of(trimmingTransitionFactory, factory);
      }
      return this;
    }

    /** Sets the transition manual feature flag trimming should apply to toolchain deps. */
    public Builder setToolchainTaggedTrimmingTransition(PatchTransition transition) {
      Preconditions.checkNotNull(transition);
      Preconditions.checkState(toolchainTaggedTrimmingTransition == null);
      this.toolchainTaggedTrimmingTransition = transition;
      return this;
    }

    /**
     * Overrides the transition factory run over all targets.
     *
     * @see {@link #addTrimmingTransitionFactory(TransitionFactory<Rule>)}
     */
    @VisibleForTesting(/* for testing trimming transition factories without relying on prod use */ )
    public Builder overrideTrimmingTransitionFactoryForTesting(TransitionFactory<Rule> factory) {
      trimmingTransitionFactory = null;
      return this.addTrimmingTransitionFactory(factory);
    }

    /**
     * Sets the predicate which determines whether the analysis cache should be invalidated for the
     * given options diff.
     */
    public Builder setShouldInvalidateCacheForOptionDiff(
        OptionsDiffPredicate shouldInvalidateCacheForOptionDiff) {
      Preconditions.checkState(
          this.shouldInvalidateCacheForOptionDiff.equals(OptionsDiffPredicate.ALWAYS_INVALIDATE),
          "Cache invalidation function was already set");
      this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff;
      return this;
    }

    @Override
    public boolean enableExecutionTransition() {
      return enableExecutionTransition;
    }

    public Builder enableExecutionTransition(boolean flag) {
      this.enableExecutionTransition = flag;
      return this;
    }

    /**
     * Overrides the predicate which determines whether the analysis cache should be invalidated for
     * the given options diff.
     */
    @VisibleForTesting(/* for testing cache invalidation without relying on prod use */ )
    public Builder overrideShouldInvalidateCacheForOptionDiffForTesting(
        OptionsDiffPredicate shouldInvalidateCacheForOptionDiff) {
      this.shouldInvalidateCacheForOptionDiff = OptionsDiffPredicate.ALWAYS_INVALIDATE;
      return this.setShouldInvalidateCacheForOptionDiff(shouldInvalidateCacheForOptionDiff);
    }

    private RuleConfiguredTargetFactory createFactory(
        Class<? extends RuleConfiguredTargetFactory> factoryClass) {
      try {
        Constructor<? extends RuleConfiguredTargetFactory> ctor = factoryClass.getConstructor();
        return ctor.newInstance();
      } catch (NoSuchMethodException | IllegalAccessException | InstantiationException
          | InvocationTargetException e) {
        throw new IllegalStateException(e);
      }
    }

    private RuleClass commitRuleDefinition(Class<? extends RuleDefinition> definitionClass) {
      RuleDefinition instance = checkNotNull(ruleDefinitionMap.get(definitionClass.getName()),
          "addRuleDefinition(new %s()) should be called before build()", definitionClass.getName());

      RuleDefinition.Metadata metadata = instance.getMetadata();
      checkArgument(
          ruleClassMap.get(metadata.name()) == null,
          "The rule " + metadata.name() + " was committed already, use another name");

      List<Class<? extends RuleDefinition>> ancestors = metadata.ancestors();

      checkArgument(
          metadata.type() == ABSTRACT ^ metadata.factoryClass()
              != RuleConfiguredTargetFactory.class);
      checkArgument(
          (metadata.type() != TEST)
          || ancestors.contains(BaseRuleClasses.TestBaseRule.class));

      RuleClass[] ancestorClasses = new RuleClass[ancestors.size()];
      for (int i = 0; i < ancestorClasses.length; i++) {
        ancestorClasses[i] = ruleMap.get(ancestors.get(i));
        if (ancestorClasses[i] == null) {
          // Ancestors should have been initialized by now
          throw new IllegalStateException("Ancestor " + ancestors.get(i) + " of "
              + metadata.name() + " is not initialized");
        }
      }

      RuleConfiguredTargetFactory factory = null;
      if (metadata.type() != ABSTRACT) {
        factory = createFactory(metadata.factoryClass());
      }

      RuleClass.Builder builder = new RuleClass.Builder(
          metadata.name(), metadata.type(), false, ancestorClasses);
      builder.factory(factory);
      builder.setThirdPartyLicenseExistencePolicy(thirdPartyLicenseExistencePolicy);
      RuleClass ruleClass = instance.build(builder, this);
      ruleMap.put(definitionClass, ruleClass);
      ruleClassMap.put(ruleClass.getName(), ruleClass);
      ruleDefinitionMap.put(ruleClass.getName(), instance);

      return ruleClass;
    }

    public ConfiguredRuleClassProvider build() {
      for (Node<Class<? extends RuleDefinition>> ruleDefinition :
          dependencyGraph.getTopologicalOrder()) {
        commitRuleDefinition(ruleDefinition.getLabel());
      }

      return new ConfiguredRuleClassProvider(
          preludeLabel,
          runfilesPrefix,
          toolsRepository,
          ImmutableMap.copyOf(ruleClassMap),
          ImmutableMap.copyOf(ruleDefinitionMap),
          ImmutableMap.copyOf(nativeAspectClassMap),
          defaultWorkspaceFilePrefix.toString(),
          defaultWorkspaceFileSuffix.toString(),
          ImmutableList.copyOf(buildInfoFactories),
          ImmutableList.copyOf(configurationOptions),
          ImmutableList.copyOf(configurationFragmentFactories),
          ImmutableList.copyOf(universalFragments),
          trimmingTransitionFactory,
          toolchainTaggedTrimmingTransition,
          shouldInvalidateCacheForOptionDiff,
          prerequisiteValidator,
          skylarkAccessibleTopLevels.build(),
          skylarkBootstraps.build(),
          ImmutableSet.copyOf(reservedActionMnemonics),
          actionEnvironmentProvider,
          constraintSemantics,
          thirdPartyLicenseExistencePolicy);
    }

    @Override
    public Label getToolsLabel(String labelValue) {
      return Label.parseAbsoluteUnchecked(toolsRepository + labelValue);
    }

    @Override
    public String getToolsRepository() {
      return toolsRepository;
    }
  }

  /**
   * Default content that should be added at the beginning of the WORKSPACE file.
   */
  private final String defaultWorkspaceFilePrefix;

  /**
   * Default content that should be added at the end of the WORKSPACE file.
   */
  private final String defaultWorkspaceFileSuffix;


  /**
   * Label for the prelude file.
   */
  private final Label preludeLabel;

  /**
   * The default runfiles prefix.
   */
  private final String runfilesPrefix;

  /**
   * The path to the tools repository.
   */
  private final String toolsRepository;

  /**
   * Maps rule class name to the metaclass instance for that rule.
   */
  private final ImmutableMap<String, RuleClass> ruleClassMap;

  /**
   * Maps rule class name to the rule definition objects.
   */
  private final ImmutableMap<String, RuleDefinition> ruleDefinitionMap;

  /**
   * Maps aspect name to the aspect factory meta class.
   */
  private final ImmutableMap<String, NativeAspectClass> nativeAspectClassMap;

  /**
   * The configuration options that affect the behavior of the rules.
   */
  private final ImmutableList<Class<? extends FragmentOptions>> configurationOptions;

  /** The set of configuration fragment factories. */
  private final ImmutableList<ConfigurationFragmentFactory> configurationFragmentFactories;

  /**
   * Maps build option names to matching config fragments. This is used to determine correct
   * fragment requirements for config_setting rules, which are unique in that their dependencies are
   * triggered by string representations of option names.
   */
  private final Map<String, Class<? extends Fragment>> optionsToFragmentMap;

  /** The transition factory used to produce the transition that will trim targets. */
  @Nullable private final TransitionFactory<Rule> trimmingTransitionFactory;

  /** The transition to apply to toolchain deps for manual trimming. */
  @Nullable private final PatchTransition toolchainTaggedTrimmingTransition;

  /** The predicate used to determine whether a diff requires the cache to be invalidated. */
  private final OptionsDiffPredicate shouldInvalidateCacheForOptionDiff;

  /**
   * Configuration fragments that should be available to all rules even when they don't
   * explicitly require it.
   */
  private final ImmutableList<Class<? extends BuildConfiguration.Fragment>> universalFragments;

  private final ImmutableList<BuildInfoFactory> buildInfoFactories;

  private final PrerequisiteValidator prerequisiteValidator;

  private final Module globals;

  private final ImmutableSet<String> reservedActionMnemonics;

  private final BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider;

  private final ImmutableMap<String, Class<?>> configurationFragmentMap;

  private final ConstraintSemantics constraintSemantics;

  private final ThirdPartyLicenseExistencePolicy thirdPartyLicenseExistencePolicy;

  private ConfiguredRuleClassProvider(
      Label preludeLabel,
      String runfilesPrefix,
      String toolsRepository,
      ImmutableMap<String, RuleClass> ruleClassMap,
      ImmutableMap<String, RuleDefinition> ruleDefinitionMap,
      ImmutableMap<String, NativeAspectClass> nativeAspectClassMap,
      String defaultWorkspaceFilePrefix,
      String defaultWorkspaceFileSuffix,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      ImmutableList<Class<? extends FragmentOptions>> configurationOptions,
      ImmutableList<ConfigurationFragmentFactory> configurationFragments,
      ImmutableList<Class<? extends BuildConfiguration.Fragment>> universalFragments,
      @Nullable TransitionFactory<Rule> trimmingTransitionFactory,
      PatchTransition toolchainTaggedTrimmingTransition,
      OptionsDiffPredicate shouldInvalidateCacheForOptionDiff,
      PrerequisiteValidator prerequisiteValidator,
      ImmutableMap<String, Object> skylarkAccessibleJavaClasses,
      ImmutableList<Bootstrap> skylarkBootstraps,
      ImmutableSet<String> reservedActionMnemonics,
      BuildConfiguration.ActionEnvironmentProvider actionEnvironmentProvider,
      ConstraintSemantics constraintSemantics,
      ThirdPartyLicenseExistencePolicy thirdPartyLicenseExistencePolicy) {
    this.preludeLabel = preludeLabel;
    this.runfilesPrefix = runfilesPrefix;
    this.toolsRepository = toolsRepository;
    this.ruleClassMap = ruleClassMap;
    this.ruleDefinitionMap = ruleDefinitionMap;
    this.nativeAspectClassMap = nativeAspectClassMap;
    this.defaultWorkspaceFilePrefix = defaultWorkspaceFilePrefix;
    this.defaultWorkspaceFileSuffix = defaultWorkspaceFileSuffix;
    this.buildInfoFactories = buildInfoFactories;
    this.configurationOptions = configurationOptions;
    this.configurationFragmentFactories = configurationFragments;
    this.optionsToFragmentMap = computeOptionsToFragmentMap(configurationFragments);
    this.universalFragments = universalFragments;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
    this.toolchainTaggedTrimmingTransition = toolchainTaggedTrimmingTransition;
    this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff;
    this.prerequisiteValidator = prerequisiteValidator;
    this.globals = createGlobals(skylarkAccessibleJavaClasses, skylarkBootstraps);
    this.reservedActionMnemonics = reservedActionMnemonics;
    this.actionEnvironmentProvider = actionEnvironmentProvider;
    this.configurationFragmentMap = createFragmentMap(configurationFragmentFactories);
    this.constraintSemantics = constraintSemantics;
    this.thirdPartyLicenseExistencePolicy = thirdPartyLicenseExistencePolicy;
  }

  /**
   * Computes the option name --> config fragments map. Note that this mapping is technically
   * one-to-many: a single option may be required by multiple fragments (e.g. Java options are used
   * by both JavaConfiguration and Jvm). In such cases, we arbitrarily choose one fragment since
   * that's all that's needed to satisfy the config_setting.
   */
  private static Map<String, Class<? extends Fragment>> computeOptionsToFragmentMap(
      Iterable<ConfigurationFragmentFactory> configurationFragments) {
    Map<String, Class<? extends Fragment>> result = new LinkedHashMap<>();
    Map<Class<? extends FragmentOptions>, Integer> visitedOptionsClasses = new HashMap<>();
    for (ConfigurationFragmentFactory factory : configurationFragments) {
      Set<Class<? extends FragmentOptions>> requiredOpts = factory.requiredOptions();
      for (Class<? extends FragmentOptions> optionsClass : requiredOpts) {
        Integer previousBest = visitedOptionsClasses.get(optionsClass);
        if (previousBest != null && previousBest <= requiredOpts.size()) {
          // Multiple config fragments may require the same options class, but we only need one of
          // them to guarantee that class makes it into the configuration. Pick one that depends
          // on as few options classes as possible (not necessarily unique).
          continue;
        }
        visitedOptionsClasses.put(optionsClass, requiredOpts.size());
        for (Field field : optionsClass.getFields()) {
          if (field.isAnnotationPresent(Option.class)) {
            result.put(field.getAnnotation(Option.class).name(), factory.creates());
          }
        }
      }
    }
    return result;
  }

  public PrerequisiteValidator getPrerequisiteValidator() {
    return prerequisiteValidator;
  }

  @Override
  public Label getPreludeLabel() {
    return preludeLabel;
  }

  @Override
  public String getRunfilesPrefix() {
    return runfilesPrefix;
  }

  @Override
  public String getToolsRepository() {
    return toolsRepository;
  }

  @Override
  public Map<String, RuleClass> getRuleClassMap() {
    return ruleClassMap;
  }

  @Override
  public Map<String, NativeAspectClass> getNativeAspectClassMap() {
    return nativeAspectClassMap;
  }

  @Override
  public NativeAspectClass getNativeAspectClass(String key) {
    return nativeAspectClassMap.get(key);
  }

  public Map<BuildInfoKey, BuildInfoFactory> getBuildInfoFactoriesAsMap() {
    ImmutableMap.Builder<BuildInfoKey, BuildInfoFactory> factoryMapBuilder = ImmutableMap.builder();
    for (BuildInfoFactory factory : buildInfoFactories) {
      factoryMapBuilder.put(factory.getKey(), factory);
    }
    return factoryMapBuilder.build();
  }

  /**
   * Returns the set of configuration fragments provided by this module.
   */
  public ImmutableList<ConfigurationFragmentFactory> getConfigurationFragments() {
    return configurationFragmentFactories;
  }

  @Nullable
  public Class<? extends Fragment> getConfigurationFragmentForOption(String requiredOption) {
    return optionsToFragmentMap.get(requiredOption);
  }

  /**
   * Returns the transition factory used to produce the transition to trim targets.
   *
   * <p>This is a temporary measure for supporting manual trimming of feature flags, and support for
   * this transition factory will likely be removed at some point in the future (whenever automatic
   * trimming is sufficiently workable
   */
  @Nullable
  public TransitionFactory<Rule> getTrimmingTransitionFactory() {
    return trimmingTransitionFactory;
  }

  /**
   * Returns the transition manual feature flag trimming should apply to toolchain deps.
   *
   * <p>See extra notes on {@link #getTrimmingTransitionFactory()}.
   */
  @Nullable
  public PatchTransition getToolchainTaggedTrimmingTransition() {
    return toolchainTaggedTrimmingTransition;
  }

  /** Returns whether the analysis cache should be invalidated for the given option diff. */
  public boolean shouldInvalidateCacheForOptionDiff(
      BuildOptions newOptions, OptionDefinition changedOption, Object oldValue, Object newValue) {
    return shouldInvalidateCacheForOptionDiff.apply(newOptions, changedOption, oldValue, newValue);
  }

  /**
   * Returns the set of configuration options that are supported in this module.
   */
  public ImmutableList<Class<? extends FragmentOptions>> getConfigurationOptions() {
    return configurationOptions;
  }

  /**
   * Returns the definition of the rule class definition with the specified name.
   */
  public RuleDefinition getRuleClassDefinition(String ruleClassName) {
    return ruleDefinitionMap.get(ruleClassName);
  }

  /**
   * Returns the configuration fragment that should be available to all rules even when they
   * don't explicitly require it.
   */
  public ImmutableList<Class<? extends BuildConfiguration.Fragment>> getUniversalFragments() {
    return universalFragments;
  }

  /**
   * Creates a BuildOptions class for the given options taken from an optionsProvider.
   */
  public BuildOptions createBuildOptions(OptionsProvider optionsProvider) {
    return BuildOptions.of(configurationOptions, optionsProvider);
  }

  private Module createGlobals(
      ImmutableMap<String, Object> skylarkAccessibleTopLevels,
      ImmutableList<Bootstrap> bootstraps) {
    ImmutableMap.Builder<String, Object> envBuilder = ImmutableMap.builder();

    SkylarkModules.addSkylarkGlobalsToBuilder(envBuilder);
    envBuilder.putAll(skylarkAccessibleTopLevels.entrySet());
    for (Bootstrap bootstrap : bootstraps) {
      bootstrap.addBindingsToBuilder(envBuilder);
    }

    return Module.createForBuiltins(envBuilder.build());
  }

  private static ImmutableMap<String, Class<?>> createFragmentMap(
      Iterable<ConfigurationFragmentFactory> configurationFragmentFactories) {
    ImmutableMap.Builder<String, Class<?>> mapBuilder = ImmutableMap.builder();
    for (ConfigurationFragmentFactory fragmentFactory : configurationFragmentFactories) {
      Class<? extends Fragment> fragmentClass = fragmentFactory.creates();
      SkylarkModule fragmentModule = SkylarkInterfaceUtils.getSkylarkModule((fragmentClass));
      if (fragmentModule != null) {
        mapBuilder.put(fragmentModule.name(), fragmentClass);
      }
    }
    return mapBuilder.build();
  }

  @Override
  public StarlarkThread createRuleClassStarlarkThread(
      Label fileLabel,
      Mutability mutability,
      StarlarkSemantics starlarkSemantics,
      EventHandler eventHandler,
      String astFileContentHashCode,
      Map<String, Extension> importMap,
      ImmutableMap<RepositoryName, RepositoryName> repoMapping) {
    StarlarkThread thread =
        StarlarkThread.builder(mutability)
            .setGlobals(globals.withLabel(fileLabel))
            .setSemantics(starlarkSemantics)
            .setEventHandler(eventHandler)
            .setFileContentHashCode(astFileContentHashCode)
            .setImportedExtensions(importMap)
            .build();

    new BazelStarlarkContext(
            toolsRepository,
            configurationFragmentMap,
            repoMapping,
            new SymbolGenerator<>(fileLabel),
            /* analysisRuleLabel= */ null)
        .storeInThread(thread);

    SkylarkUtils.setPhase(thread, Phase.LOADING);
    return thread;
  }

  @Override
  public String getDefaultWorkspacePrefix() {
    return defaultWorkspaceFilePrefix;
  }

  @Override
  public String getDefaultWorkspaceSuffix() {
    return defaultWorkspaceFileSuffix;
  }

  @Override
  public Map<String, Class<?>> getConfigurationFragmentMap() {
    return configurationFragmentMap;
  }

  public ConstraintSemantics getConstraintSemantics() {
    return constraintSemantics;
  }

  @Override
  public ThirdPartyLicenseExistencePolicy getThirdPartyLicenseExistencePolicy() {
    return thirdPartyLicenseExistencePolicy;
  }

  /** Returns all skylark objects in global scope for this RuleClassProvider. */
  public Map<String, Object> getTransitiveGlobalBindings() {
    return globals.getTransitiveBindings();
  }

  public Object getGlobalsForConstantRegistration() {
    return globals;
  }

  /** Returns all registered {@link BuildConfiguration.Fragment} classes. */
  public ImmutableSortedSet<Class<? extends BuildConfiguration.Fragment>> getAllFragments() {
    ImmutableSortedSet.Builder<Class<? extends BuildConfiguration.Fragment>> fragmentsBuilder =
        ImmutableSortedSet.orderedBy(BuildConfiguration.lexicalFragmentSorter);
    for (ConfigurationFragmentFactory factory : getConfigurationFragments()) {
      fragmentsBuilder.add(factory.creates());
    }
    fragmentsBuilder.addAll(getUniversalFragments());
    return fragmentsBuilder.build();
  }

  /** Returns a reserved set of action mnemonics. These cannot be used from a Skylark action. */
  public ImmutableSet<String> getReservedActionMnemonics() {
    return reservedActionMnemonics;
  }

  public BuildConfiguration.ActionEnvironmentProvider getActionEnvironmentProvider() {
    return actionEnvironmentProvider;
  }
}
