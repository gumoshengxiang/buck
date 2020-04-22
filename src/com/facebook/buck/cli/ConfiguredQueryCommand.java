/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryParserEnv;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

/** Buck subcommand which facilitates querying information about the configured target graph. */
public class ConfiguredQueryCommand extends AbstractQueryCommand {

  private PerBuildState perBuildState;
  private TargetUniverse targetUniverse;

  @Option(
      name = "--target-universe",
      usage =
          "Comma separated list of targets at which to root the queryable universe. "
              + "This is useful since targets can exist in multiple configurations. "
              + "While this argument isn't required, it's recommended for basically all queries")
  @Nullable
  private String targetUniverseParam = null;

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the configured target nodes graph";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (arguments.isEmpty()) {
      throw new CommandLineException("must specify at least the query expression");
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("CQuery", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            new PerBuildStateFactory(
                    params.getTypeCoercerFactory(),
                    new DefaultConstructorArgMarshaller(),
                    params.getKnownRuleTypesProvider(),
                    new ParserPythonInterpreterProvider(
                        params.getCells().getRootCell().getBuckConfig(),
                        params.getExecutableFinder()),
                    params.getWatchman(),
                    params.getBuckEventBus(),
                    params.getUnconfiguredBuildTargetFactory(),
                    params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE))
                .create(
                    createParsingContext(params.getCells(), pool.getListeningExecutorService())
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED),
                    params.getParser().getPermState())) {
      perBuildState = parserState;
      ParsingContext parsingContext =
          createParsingContext(params.getCells(), pool.getListeningExecutorService());
      targetUniverse =
          PrecomputedTargetUniverse.createFromRootTargets(
              rootTargetsForUniverse(), params, parserState, parsingContext);
      ConfiguredQueryEnvironment env =
          ConfiguredQueryEnvironment.from(params, targetUniverse, parsingContext);
      formatAndRunQuery(params, env);
    } catch (QueryException e) {
      throw new HumanReadableException(e);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  protected void printSingleQueryOutput(
      CommandRunnerParams params,
      ConfiguredQueryEnvironment env,
      Set<QueryTarget> queryResult,
      PrintStream printStream)
      throws QueryException, IOException {
    // For the most part we print in exactly the format the user asks for, BUT we don't support
    // printing attributes in list format so if they asked for both just print in JSON instead.
    OutputFormat trueOutputFormat =
        (outputFormat == OutputFormat.LIST && shouldOutputAttributes())
            ? OutputFormat.JSON
            : outputFormat;

    Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>> attributesByResult =
        collectAttributes(params, env, queryResult);

    switch (trueOutputFormat) {
      case LIST:
        printListOutput(queryResult, attributesByResult, printStream);
        break;

      case JSON:
        printJsonOutput(queryResult, attributesByResult, printStream);
        break;

      case DOT:
        printDotOutput(queryResult, attributesByResult, printStream);
        break;

      case DOT_COMPACT:
        printDotCompactOutput(queryResult, attributesByResult, printStream);
        break;

      case DOT_BFS:
        printDotBfsOutput(queryResult, attributesByResult, printStream);
        break;

      case DOT_BFS_COMPACT:
        printDotBfsCompactOutput(queryResult, attributesByResult, printStream);
        break;

      case THRIFT:
        printThriftOutput(queryResult, attributesByResult, printStream);
        break;
    }
  }

  @Override
  protected void printMultipleQueryOutput(
      CommandRunnerParams params,
      ConfiguredQueryEnvironment env,
      Multimap<String, QueryTarget> queryResultMap,
      PrintStream printStream)
      throws QueryException, IOException {
    if (shouldOutputAttributes()) {
      // NOTE: This is what the old `buck query` did. If you provide a multiquery and ask Buck to
      // output attributes we just combine all the query results together and print it like it was
      // all one query. Does it make sense? Maybe.
      ImmutableSet<QueryTarget> combinedResults = ImmutableSet.copyOf(queryResultMap.values());
      printJsonOutput(
          combinedResults, collectAttributes(params, env, combinedResults), printStream);
    } else if (outputFormat == OutputFormat.LIST) {
      printListOutput(queryResultMap, printStream);
    } else if (outputFormat == OutputFormat.JSON) {
      printJsonOutput(queryResultMap, printStream);
    } else {
      throw new QueryException(
          "Multiqueries (those using `%s`) do not support printing with the given output format: "
              + outputFormat.toString());
    }
  }

  // TODO: This API is too stringly typed. It's easy for users to provide strings here which will
  // cause us to have problems - if not outright crash - later down the line. We should use a type
  // here which better reflects our intent, something like `BuildTargetPattern`.
  private List<String> rootTargetsForUniverse() throws QueryException {
    // Happy path - the user told us what universe they wanted.
    if (targetUniverseParam != null) {
      return Splitter.on(',').splitToList(targetUniverseParam);
    }

    // Less happy path - parse the query and try to infer the roots of the target universe.
    RepeatingTargetEvaluator evaluator = new RepeatingTargetEvaluator();
    QueryExpression<String> expression =
        QueryExpression.parse(
            arguments.get(0),
            QueryParserEnv.of(ConfiguredQueryEnvironment.defaultFunctions(), evaluator));
    // TODO: Right now we use `expression.getTargets`, which gets all target literals referenced in
    // the query. We don't want all literals, for example with `rdeps(//my:binary, //some:library)`
    // we only want to include `//my:binary` in the universe, not both. We should provide a way for
    // functions to specify which of their parameters should be used for the universe calculation.
    return ImmutableList.copyOf(expression.getTargets(evaluator));
  }

  private void printListOutput(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      PrintStream printStream) {
    Preconditions.checkState(
        !attributesByResultOptional.isPresent(), "We should be printing with JSON instead");

    queryResult.stream().map(this::toPresentationForm).forEach(printStream::println);
  }

  private void printListOutput(
      Multimap<String, QueryTarget> queryResultMap, PrintStream printStream) {
    queryResultMap.values().stream().map(this::toPresentationForm).forEach(printStream::println);
  }

  private void printJsonOutput(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {

    Object printableObject;
    if (attributesByResultOptional.isPresent()) {
      ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>> attributesByResult =
          attributesByResultOptional.get();
      printableObject =
          queryResult.stream()
              .collect(
                  ImmutableMap.toImmutableMap(this::toPresentationForm, attributesByResult::get));
    } else {
      printableObject =
          queryResult.stream()
              .peek(Objects::requireNonNull)
              .map(this::toPresentationForm)
              .collect(ImmutableSet.toImmutableSet());
    }

    ObjectMappers.WRITER.writeValue(printStream, printableObject);
  }

  private void printJsonOutput(
      Multimap<String, QueryTarget> queryResultMap, PrintStream printStream) throws IOException {
    Multimap<String, String> targetsAndResultsNames =
        Multimaps.transformValues(
            queryResultMap, input -> toPresentationForm(Objects.requireNonNull(input)));
    ObjectMappers.WRITER.writeValue(printStream, targetsAndResultsNames.asMap());
  }

  private void printDotOutput(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(
        queryResult, attributesByResultOptional, Dot.OutputOrder.SORTED, false, printStream);
  }

  private void printDotCompactOutput(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(
        queryResult, attributesByResultOptional, Dot.OutputOrder.SORTED, true, printStream);
  }

  private void printDotBfsOutput(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(queryResult, attributesByResultOptional, Dot.OutputOrder.BFS, false, printStream);
  }

  private void printDotBfsCompactOutput(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    printDotGraph(queryResult, attributesByResultOptional, Dot.OutputOrder.BFS, true, printStream);
  }

  private void printThriftOutput(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      PrintStream printStream)
      throws IOException {
    ImmutableMap<BuildTarget, QueryTarget> resultByBuildTarget =
        queryResult.stream()
            .filter(t -> t instanceof QueryBuildTarget)
            .collect(
                ImmutableMap.toImmutableMap(t -> ((QueryBuildTarget) t).getBuildTarget(), t -> t));

    ThriftOutput.Builder<TargetNode<?>> thriftBuilder =
        ThriftOutput.builder(targetUniverse.getTargetGraph())
            .filter(n -> resultByBuildTarget.containsKey(n.getBuildTarget()))
            .nodeToNameMappingFunction(n -> n.getBuildTarget().toStringWithConfiguration());

    attributesByResultOptional.ifPresent(
        attrs ->
            thriftBuilder.nodeToAttributesFunction(
                node -> attrs.get(resultByBuildTarget.get(node.getBuildTarget()))));
    thriftBuilder.build().writeOutput(printStream);
  }

  private void printDotGraph(
      Set<QueryTarget> queryResult,
      Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>>
          attributesByResultOptional,
      Dot.OutputOrder outputOrder,
      boolean compactMode,
      PrintStream printStream)
      throws IOException {
    ImmutableMap<BuildTarget, QueryTarget> resultByBuildTarget =
        queryResult.stream()
            .filter(t -> t instanceof QueryBuildTarget)
            .collect(
                ImmutableMap.toImmutableMap(t -> ((QueryBuildTarget) t).getBuildTarget(), t -> t));

    Dot.Builder<TargetNode<?>> dotBuilder =
        Dot.builder(targetUniverse.getTargetGraph(), "result_graph")
            .setNodesToFilter(n -> resultByBuildTarget.containsKey(n.getBuildTarget()))
            .setNodeToName(node -> node.getBuildTarget().toStringWithConfiguration())
            .setNodeToTypeName(node -> node.getRuleType().getName())
            .setOutputOrder(outputOrder)
            .setCompactMode(compactMode);

    attributesByResultOptional.ifPresent(
        attrs ->
            dotBuilder.setNodeToAttributes(
                node -> attrs.get(resultByBuildTarget.get(node.getBuildTarget()))));
    dotBuilder.build().writeOutput(printStream);
  }

  private Optional<ImmutableMap<QueryTarget, ImmutableSortedMap<String, Object>>> collectAttributes(
      CommandRunnerParams params, ConfiguredQueryEnvironment env, Set<QueryTarget> queryResult) {
    if (!shouldOutputAttributes()) {
      return Optional.empty();
    }

    PatternsMatcher matcher = new PatternsMatcher(outputAttributes());
    ImmutableMap.Builder<QueryTarget, ImmutableSortedMap<String, Object>> result =
        ImmutableMap.builder();

    for (QueryTarget target : queryResult) {
      nodeForQueryTarget(env, target)
          .flatMap(node -> getAllAttributes(params, node))
          .map(attrs -> getMatchingAttributes(matcher, attrs))
          .ifPresent(attrs -> result.put(target, ImmutableSortedMap.copyOf(attrs)));
    }

    return Optional.of(result.build());
  }

  private Optional<TargetNode<?>> nodeForQueryTarget(
      ConfiguredQueryEnvironment env, QueryTarget target) {
    if (target instanceof QueryFileTarget) {
      return Optional.empty();
    }
    QueryBuildTarget queryBuildTarget = (QueryBuildTarget) target;
    try {
      TargetNode<?> node = env.getTargetUniverse().getNode(queryBuildTarget.getBuildTarget());
      return Optional.of(node);
    } catch (QueryException e) {
      throw new IllegalStateException("Unable to find TargetNode for query result", e);
    }
  }

  private Optional<ImmutableMap<String, Object>> getAllAttributes(
      CommandRunnerParams params, TargetNode<?> node) {
    SortedMap<String, Object> rawAttributes =
        params
            .getParser()
            .getTargetNodeRawAttributes(
                perBuildState,
                params.getCells().getRootCell(),
                node,
                DependencyStack.top(node.getBuildTarget()));
    if (rawAttributes == null) {
      params
          .getConsole()
          .printErrorText(
              "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
      return Optional.empty();
    }

    return Optional.of(ImmutableMap.copyOf(rawAttributes));
  }

  private String toPresentationForm(QueryTarget target) {
    if (target instanceof QueryFileTarget) {
      QueryFileTarget fileTarget = (QueryFileTarget) target;
      return fileTarget.toString();
    } else if (target instanceof QueryBuildTarget) {
      QueryBuildTarget buildTarget = (QueryBuildTarget) target;
      return toPresentationForm(buildTarget.getBuildTarget());
    } else {
      throw new IllegalStateException(
          String.format("Unknown QueryTarget implementation - %s", target.getClass().toString()));
    }
  }

  private String toPresentationForm(BuildTarget buildTarget) {
    // NOTE: We are explicitly ignoring flavors here because flavored nodes in the graph can really
    // mess with us. Long term we hope to replace flavors with configurations anyway so in the end
    // this shouldn't really matter.
    return buildTarget.withoutFlavors().toStringWithConfiguration();
  }

  /**
   * `QueryEnvironment.TargetEvaluator` implementation that simply repeats back the target string it
   * was provided. Used to parse the query ahead of time and infer the target universe.
   */
  private static class RepeatingTargetEvaluator
      implements QueryEnvironment.TargetEvaluator<String> {
    @Override
    public Type getType() {
      return Type.LAZY;
    }

    @Override
    public Set<String> evaluateTarget(String target) throws QueryException {
      return ImmutableSet.of(target);
    }
  }
}