/*
    The MIT License (MIT)

    Copyright (c) 2015 Andreas Marek and Contributors

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
    (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
    publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do
    so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
    OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
    LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
    CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.intellij.lang.jsgraphql.types.execution.batched;

import com.intellij.lang.jsgraphql.types.*;
import com.intellij.lang.jsgraphql.types.execution.*;
import com.intellij.lang.jsgraphql.types.execution.directives.QueryDirectivesImpl;
import com.intellij.lang.jsgraphql.types.execution.instrumentation.Instrumentation;
import com.intellij.lang.jsgraphql.types.execution.instrumentation.InstrumentationContext;
import com.intellij.lang.jsgraphql.types.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters;
import com.intellij.lang.jsgraphql.types.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import com.intellij.lang.jsgraphql.types.execution.instrumentation.parameters.InstrumentationFieldParameters;
import com.intellij.lang.jsgraphql.types.normalized.NormalizedField;
import com.intellij.lang.jsgraphql.types.schema.*;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.intellij.lang.jsgraphql.types.execution.ExecutionStepInfo.newExecutionStepInfo;
import static com.intellij.lang.jsgraphql.types.execution.FieldCollectorParameters.newParameters;
import static com.intellij.lang.jsgraphql.types.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

/**
 * <blockquote>
 * BatchedExecutionStrategy has been deprecated in favour of {@link graphql.execution.AsyncExecutionStrategy}
 * and {@link graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentation}.
 * <p>
 * BatchedExecutionStrategy does not properly implement the graphql runtime specification.  Specifically it
 * does not correctly handle non null fields and how they are to cascade up their parent fields.  It has proven
 * an intractable problem to make this code handle these cases.
 * <p>
 * See http://facebook.github.io/graphql/October2016/#sec-Errors-and-Non-Nullability
 * <p>
 * We will remove it once we are sure the alternative is as least good as the BatchedExecutionStrategy.
 *
 * </blockquote>
 * <p>
 * Execution Strategy that minimizes calls to the data fetcher when used in conjunction with {@link DataFetcher}s that have
 * {@link DataFetcher#get(DataFetchingEnvironment)} methods annotated with {@link Batched}. See the javadoc comment on
 * {@link Batched} for a more detailed description of batched data fetchers.
 * <p>
 * The strategy runs a BFS over terms of the query and passes a list of all the relevant sources to the batched data fetcher.
 * </p>
 * Normal DataFetchers can be used, however they will not see benefits of batching as they expect a single source object
 * at a time.
 *
 * @deprecated This has been deprecated in favour of using {@link graphql.execution.AsyncExecutionStrategy} and {@link graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentation}
 */
@PublicApi
@Deprecated
public class BatchedExecutionStrategy extends ExecutionStrategy {

    private final BatchedDataFetcherFactory batchingFactory = new BatchedDataFetcherFactory();
    private final ResolveType resolveType = new ResolveType();

    public BatchedExecutionStrategy() {
        this(new SimpleDataFetcherExceptionHandler());
    }

    public BatchedExecutionStrategy(DataFetcherExceptionHandler dataFetcherExceptionHandler) {
        super(dataFetcherExceptionHandler);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
        InstrumentationContext<ExecutionResult> executionStrategyCtx = executionContext.getInstrumentation()
                .beginExecutionStrategy(new InstrumentationExecutionStrategyParameters(executionContext, parameters));

        GraphQLObjectType type = (GraphQLObjectType) parameters.getExecutionStepInfo().getUnwrappedNonNullType();

        ExecutionNode root = new ExecutionNode(type,
                parameters.getExecutionStepInfo(),
                parameters.getFields().getSubFields(),
                singletonList(MapOrList.createMap(new LinkedHashMap<>())),
                Collections.singletonList(parameters.getSource())
        );

        Queue<ExecutionNode> nodes = new ArrayDeque<>();
        CompletableFuture<ExecutionResult> result = new CompletableFuture<>();
        executeImpl(executionContext,
                parameters,
                root,
                root,
                nodes,
                root.getFields().keySet().iterator(),
                result);

        executionStrategyCtx.onDispatched(result);
        result.whenComplete(executionStrategyCtx::onCompleted);
        return result;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void executeImpl(ExecutionContext executionContext,
                             ExecutionStrategyParameters parameters,
                             ExecutionNode root,
                             ExecutionNode curNode,
                             Queue<ExecutionNode> queueOfNodes,
                             Iterator<String> curFieldNames,
                             CompletableFuture<ExecutionResult> overallResult) {

        if (!curFieldNames.hasNext() && queueOfNodes.isEmpty()) {
            overallResult.complete(new ExecutionResultImpl(root.getParentResults().get(0).toObject(), executionContext.getErrors()));
            return;
        }

        if (!curFieldNames.hasNext()) {
            curNode = queueOfNodes.poll();
            curFieldNames = curNode.getFields().keySet().iterator();
        }

        String fieldName = curFieldNames.next();
        MergedField currentField = curNode.getFields().get(fieldName);


        //
        // once an object is resolved from a interface / union to a node with an object type, the
        // parent type info has effectively changed (it has got more specific), even though the path etc...
        // has not changed
        ExecutionStepInfo currentParentExecutionStepInfo = parameters.getExecutionStepInfo();
        ExecutionStepInfo newParentExecutionStepInfo = newExecutionStepInfo()
                .type(curNode.getType())
                .fieldDefinition(currentParentExecutionStepInfo.getFieldDefinition())
                .field(currentParentExecutionStepInfo.getField())
                .path(currentParentExecutionStepInfo.getPath())
                .parentInfo(currentParentExecutionStepInfo.getParent())
                .build();

        ResultPath fieldPath = curNode.getExecutionStepInfo().getPath().segment(mkNameForPath(currentField));
        GraphQLFieldDefinition fieldDefinition = getFieldDef(executionContext.getGraphQLSchema(), curNode.getType(), currentField.getSingleField());

        ExecutionStepInfo executionStepInfo = newExecutionStepInfo()
                .type(fieldDefinition.getType())
                .fieldDefinition(fieldDefinition)
                .field(currentField)
                .path(fieldPath)
                .parentInfo(newParentExecutionStepInfo)
                .build();

        ExecutionStrategyParameters newParameters = parameters
                .transform(builder -> builder
                        .path(fieldPath)
                        .field(currentField)
                        .executionStepInfo(executionStepInfo)
                );

        ExecutionNode finalCurNode = curNode;
        Iterator<String> finalCurFieldNames = curFieldNames;

        resolveField(executionContext, newParameters, fieldName, curNode)
                .whenComplete((childNodes, exception) -> {
                    if (exception != null) {
                        handleNonNullException(executionContext, overallResult, exception);
                        return;
                    }
                    queueOfNodes.addAll(childNodes);
                    executeImpl(executionContext, newParameters, root, finalCurNode, queueOfNodes, finalCurFieldNames, overallResult);
                });
    }


    private CompletableFuture<List<ExecutionNode>> resolveField(ExecutionContext executionContext,
                                                                ExecutionStrategyParameters parameters,
                                                                String fieldName,
                                                                ExecutionNode node) {
        GraphQLObjectType parentType = node.getType();
        MergedField fields = node.getFields().get(fieldName);

        GraphQLFieldDefinition fieldDef = getFieldDef(executionContext.getGraphQLSchema(), parentType, fields.getSingleField());

        Instrumentation instrumentation = executionContext.getInstrumentation();
        ExecutionStepInfo executionStepInfo = parameters.getExecutionStepInfo();
        InstrumentationContext<ExecutionResult> fieldCtx = instrumentation.beginField(
                new InstrumentationFieldParameters(executionContext, () -> executionStepInfo)
        );

        CompletableFuture<FetchedValues> fetchedData = fetchData(executionContext, parameters, fieldName, node, fieldDef);

        CompletableFuture<List<ExecutionNode>> result = fetchedData.thenApply((fetchedValues) -> {

            GraphQLCodeRegistry codeRegistry = executionContext.getGraphQLSchema().getCodeRegistry();
            Map<String, Object> argumentValues = valuesResolver.getArgumentValues(
                    codeRegistry,
                    fieldDef.getArguments(), fields.getSingleField().getArguments(), executionContext.getVariables());

            return completeValues(executionContext, fetchedValues, executionStepInfo, fieldName, fields, argumentValues);
        });
        fieldCtx.onDispatched(null);
        result = result.whenComplete((nodes, throwable) -> fieldCtx.onCompleted(null, throwable));
        return result;

    }

    private CompletableFuture<FetchedValues> fetchData(ExecutionContext executionContext,
                                                       ExecutionStrategyParameters parameters,
                                                       String fieldName,
                                                       ExecutionNode node,
                                                       GraphQLFieldDefinition fieldDef) {
        GraphQLObjectType parentType = node.getType();
        MergedField fields = node.getFields().get(fieldName);
        List<MapOrList> parentResults = node.getParentResults();

        GraphQLCodeRegistry codeRegistry = executionContext.getGraphQLSchema().getCodeRegistry();
        Map<String, Object> argumentValues = valuesResolver.getArgumentValues(
                codeRegistry,
                fieldDef.getArguments(), fields.getSingleField().getArguments(), executionContext.getVariables());

        QueryDirectivesImpl queryDirectives = new QueryDirectivesImpl(fields, executionContext.getGraphQLSchema(), executionContext.getVariables());

        Supplier<NormalizedField> normalizedFieldSupplier = getNormalizedField(executionContext, parameters, parameters::getExecutionStepInfo);

        GraphQLOutputType fieldType = fieldDef.getType();
        DataFetchingFieldSelectionSet fieldCollector = DataFetchingFieldSelectionSetImpl.newCollector(fieldType, normalizedFieldSupplier);

        DataFetchingEnvironment environment = newDataFetchingEnvironment(executionContext)
                .source(node.getSources())
                .arguments(argumentValues)
                .fieldDefinition(fieldDef)
                .mergedField(fields)
                .fieldType(fieldDef.getType())
                .executionStepInfo(parameters.getExecutionStepInfo())
                .parentType(parentType)
                .selectionSet(fieldCollector)
                .queryDirectives(queryDirectives)
                .build();

        DataFetcher<?> supplied = codeRegistry.getDataFetcher(parentType, fieldDef);
        boolean trivialDataFetcher = supplied instanceof TrivialDataFetcher;
        BatchedDataFetcher batchedDataFetcher = batchingFactory.create(supplied);

        Instrumentation instrumentation = executionContext.getInstrumentation();
        InstrumentationFieldFetchParameters instrumentationFieldFetchParameters =
                new InstrumentationFieldFetchParameters(executionContext, fieldDef, environment, parameters, trivialDataFetcher);
        InstrumentationContext<Object> fetchCtx = instrumentation.beginFieldFetch(instrumentationFieldFetchParameters);

        CompletableFuture<Object> fetchedValue;
        try {
            DataFetcher<?> dataFetcher = instrumentation.instrumentDataFetcher(
                    batchedDataFetcher, instrumentationFieldFetchParameters);
            Object fetchedValueRaw = dataFetcher.get(environment);
            fetchedValue = Async.toCompletableFuture(fetchedValueRaw);
        } catch (Exception e) {
            fetchedValue = new CompletableFuture<>();
            fetchedValue.completeExceptionally(e);
        }
        return fetchedValue
                .thenApply((result) -> assertResult(parentResults, result))
                .whenComplete(fetchCtx::onCompleted)
                .handle(handleResult(executionContext, parameters, parentResults, environment));
    }

    private BiFunction<List<Object>, Throwable, FetchedValues> handleResult(ExecutionContext executionContext, ExecutionStrategyParameters parameters, List<MapOrList> parentResults, DataFetchingEnvironment environment) {
        return (result, exception) -> {
            if (exception != null) {
                if (exception instanceof CompletionException) {
                    exception = exception.getCause();
                }

                handleFetchingException(executionContext, environment, exception);

                result = Collections.nCopies(parentResults.size(), null);
            }
            List<Object> values = result;
            List<FetchedValue> retVal = new ArrayList<>();
            for (int i = 0; i < parentResults.size(); i++) {
                Object value = executionContext.getValueUnboxer().unbox(values.get(i));
                retVal.add(new FetchedValue(parentResults.get(i), value));
            }
            return new FetchedValues(retVal, parameters.getExecutionStepInfo(), parameters.getPath());
        };
    }

    private List<Object> assertResult(List<MapOrList> parentResults, Object result) {
        result = convertPossibleArray(result);
        if (result != null && !(result instanceof Iterable)) {
            throw new BatchAssertionFailed(String.format("BatchedDataFetcher provided an invalid result: Iterable expected but got '%s'. Affected fields are set to null.", result.getClass().getName()));
        }
        @SuppressWarnings("unchecked")
        Iterable<Object> iterableResult = (Iterable<Object>) result;
        if (iterableResult == null) {
            throw new BatchAssertionFailed("BatchedDataFetcher provided a null Iterable of result values. Affected fields are set to null.");
        }
        List<Object> resultList = new ArrayList<>();
        iterableResult.forEach(resultList::add);

        long size = resultList.size();
        if (size != parentResults.size()) {
            throw new BatchAssertionFailed(String.format("BatchedDataFetcher provided invalid number of result values, expected %d but got %d. Affected fields are set to null.", parentResults.size(), size));
        }
        return resultList;
    }

    private List<ExecutionNode> completeValues(ExecutionContext executionContext,
                                               FetchedValues fetchedValues, ExecutionStepInfo executionStepInfo,
                                               String fieldName, MergedField fields,
                                               Map<String, Object> argumentValues) {

        handleNonNullType(executionContext, fetchedValues);

        GraphQLType unwrappedFieldType = executionStepInfo.getUnwrappedNonNullType();

        if (isPrimitive(unwrappedFieldType)) {
            handlePrimitives(fetchedValues, fieldName, unwrappedFieldType);
            return Collections.emptyList();
        } else if (isObject(unwrappedFieldType)) {
            return handleObject(executionContext, argumentValues, fetchedValues, fieldName, fields, executionStepInfo);
        } else if (isList(unwrappedFieldType)) {
            return handleList(executionContext, argumentValues, fetchedValues, fieldName, fields, executionStepInfo);
        } else {
            return Assert.assertShouldNeverHappen("can't handle type: %s", unwrappedFieldType);
        }
    }

    private List<ExecutionNode> handleList(ExecutionContext executionContext, Map<String, Object> argumentValues,
                                           FetchedValues fetchedValues, String fieldName, MergedField fields,
                                           ExecutionStepInfo executionStepInfo) {

        GraphQLList listType = (GraphQLList) executionStepInfo.getUnwrappedNonNullType();
        List<FetchedValue> flattenedValues = new ArrayList<>();

        for (FetchedValue value : fetchedValues.getValues()) {
            MapOrList mapOrList = value.getParentResult();

            if (value.getValue() == null) {
                mapOrList.putOrAdd(fieldName, null);
                continue;
            }

            MapOrList listResult = mapOrList.createAndPutList(fieldName);
            for (Object rawValue : toIterable(value.getValue())) {
                rawValue = executionContext.getValueUnboxer().unbox(rawValue);
                flattenedValues.add(new FetchedValue(listResult, rawValue));
            }
        }
        GraphQLOutputType innerSubType = (GraphQLOutputType) listType.getWrappedType();
        ExecutionStepInfo newExecutionStepInfo = executionStepInfo.changeTypeWithPreservedNonNull((GraphQLOutputType) GraphQLTypeUtil.unwrapNonNull(innerSubType));
        FetchedValues flattenedFetchedValues = new FetchedValues(flattenedValues, newExecutionStepInfo, fetchedValues.getPath());

        return completeValues(executionContext, flattenedFetchedValues, newExecutionStepInfo, fieldName, fields, argumentValues);
    }

    private List<ExecutionNode> handleObject(ExecutionContext executionContext, Map<String, Object> argumentValues,
                                             FetchedValues fetchedValues, String fieldName, MergedField fields,
                                             ExecutionStepInfo executionStepInfo) {

        // collect list of values by actual type (needed because of interfaces and unions)
        Map<GraphQLObjectType, List<MapOrList>> resultsByType = new LinkedHashMap<>();
        Map<GraphQLObjectType, List<Object>> sourceByType = new LinkedHashMap<>();

        for (FetchedValue value : fetchedValues.getValues()) {
            MapOrList mapOrList = value.getParentResult();
            if (value.getValue() == null) {
                mapOrList.putOrAdd(fieldName, null);
                continue;
            }
            MapOrList childResult = mapOrList.createAndPutMap(fieldName);

            GraphQLObjectType resolvedType = getGraphQLObjectType(executionContext, fields, executionStepInfo.getUnwrappedNonNullType(), value.getValue(), argumentValues);
            resultsByType.putIfAbsent(resolvedType, new ArrayList<>());
            resultsByType.get(resolvedType).add(childResult);

            sourceByType.putIfAbsent(resolvedType, new ArrayList<>());
            sourceByType.get(resolvedType).add(value.getValue());
        }

        List<ExecutionNode> childNodes = new ArrayList<>();
        for (GraphQLObjectType resolvedType : resultsByType.keySet()) {
            List<MapOrList> results = resultsByType.get(resolvedType);
            List<Object> sources = sourceByType.get(resolvedType);
            MergedSelectionSet childFields = getChildFields(executionContext, resolvedType, fields);

            ExecutionStepInfo newExecutionStepInfo = executionStepInfo.changeTypeWithPreservedNonNull(resolvedType);

            childNodes.add(new ExecutionNode(resolvedType, newExecutionStepInfo, childFields.getSubFields(), results, sources));
        }
        return childNodes;
    }


    private void handleNonNullType(ExecutionContext executionContext, FetchedValues fetchedValues) {

        ExecutionStepInfo executionStepInfo = fetchedValues.getExecutionStepInfo();
        NonNullableFieldValidator nonNullableFieldValidator = new NonNullableFieldValidator(executionContext, executionStepInfo);
        ResultPath path = fetchedValues.getPath();
        for (FetchedValue value : fetchedValues.getValues()) {
            nonNullableFieldValidator.validate(path, value.getValue());
        }
    }

    private MergedSelectionSet getChildFields(ExecutionContext executionContext, GraphQLObjectType resolvedType,
                                              MergedField fields) {

        FieldCollectorParameters collectorParameters = newParameters()
                .schema(executionContext.getGraphQLSchema())
                .objectType(resolvedType)
                .fragments(executionContext.getFragmentsByName())
                .variables(executionContext.getVariables())
                .build();

        return fieldCollector.collectFields(collectorParameters, fields);
    }

    private GraphQLObjectType getGraphQLObjectType(ExecutionContext executionContext, MergedField field, GraphQLType fieldType, Object value, Map<String, Object> argumentValues) {
        return resolveType.resolveType(executionContext, field, value, argumentValues, fieldType);
    }

    private void handlePrimitives(FetchedValues fetchedValues, String fieldName, GraphQLType fieldType) {
        for (FetchedValue value : fetchedValues.getValues()) {
            Object coercedValue = coerce(fieldType, value.getValue());
            //6.6.1 http://facebook.github.io/graphql/#sec-Field-entries
            if (coercedValue instanceof Double && ((Double) coercedValue).isNaN()) {
                coercedValue = null;
            }
            value.getParentResult().putOrAdd(fieldName, coercedValue);
        }
    }

    private Object coerce(GraphQLType type, Object value) {
        if (value == null) {
            return null;
        }
        if (type instanceof GraphQLEnumType) {
            return ((GraphQLEnumType) type).serialize(value);
        } else {
            return ((GraphQLScalarType) type).getCoercing().serialize(value);
        }
    }

    private boolean isList(GraphQLType type) {
        return type instanceof GraphQLList;
    }

    private boolean isPrimitive(GraphQLType type) {
        return type instanceof GraphQLScalarType || type instanceof GraphQLEnumType;
    }

    private boolean isObject(GraphQLType type) {
        return type instanceof GraphQLObjectType ||
                type instanceof GraphQLInterfaceType ||
                type instanceof GraphQLUnionType;
    }


    private Object convertPossibleArray(Object result) {
        if (result != null && result.getClass().isArray()) {
            return IntStream.range(0, Array.getLength(result))
                    .mapToObj(i -> Array.get(result, i))
                    .collect(toList());
        }
        return result;
    }

}
