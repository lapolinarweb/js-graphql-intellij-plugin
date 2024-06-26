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
package com.intellij.lang.jsgraphql.types.analysis;

import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.execution.ValuesResolver;
import com.intellij.lang.jsgraphql.types.language.*;
import com.intellij.lang.jsgraphql.types.schema.GraphQLCompositeType;
import com.intellij.lang.jsgraphql.types.schema.GraphQLObjectType;
import com.intellij.lang.jsgraphql.types.schema.GraphQLSchema;

import java.util.*;

import static com.intellij.lang.jsgraphql.types.Assert.assertNotNull;
import static com.intellij.lang.jsgraphql.types.Assert.assertShouldNeverHappen;
import static java.util.Collections.singletonList;

/**
 * Helps to traverse (or reduce) a Document (or parts of it) and tracks at the same time the corresponding Schema types.
 * <p>
 * This is an important distinction to just traversing the Document without any type information: Each field has a clearly
 * defined type. See {@link QueryVisitorFieldEnvironment}.
 * <p>
 * Furthermore are the built in Directives skip/include automatically evaluated: if parts of the Document should be ignored they will not
 * be visited. But this is not a full evaluation of a Query: every fragment will be visited/followed regardless of the type condition.
 * <p>
 * It also doesn't consider field merging, which means for example {@code { user{firstName} user{firstName}} } will result in four
 * visitField calls.
 */
@PublicApi
public class QueryTraverser {

  private final Collection<? extends Node> roots;
  private final GraphQLSchema schema;
  private final Map<String, FragmentDefinition> fragmentsByName;
  private final Map<String, Object> variables;

  private final GraphQLCompositeType rootParentType;

  private QueryTraverser(GraphQLSchema schema,
                         Document document,
                         String operation,
                         Map<String, Object> variables) {
    assertNotNull(document, () -> "document  can't be null");
    NodeUtil.GetOperationResult getOperationResult = NodeUtil.getOperation(document, operation);
    List<VariableDefinition> variableDefinitions = getOperationResult.operationDefinition.getVariableDefinitions();
    this.schema = assertNotNull(schema, () -> "schema can't be null");
    this.fragmentsByName = getOperationResult.fragmentsByName;
    this.roots = singletonList(getOperationResult.operationDefinition);
    this.rootParentType = getRootTypeFromOperation(getOperationResult.operationDefinition);
    this.variables = coerceVariables(assertNotNull(variables, () -> "variables can't be null"), variableDefinitions);
  }

  private Map<String, Object> coerceVariables(Map<String, Object> rawVariables, List<VariableDefinition> variableDefinitions) {
    return new ValuesResolver().coerceVariableValues(schema, variableDefinitions, rawVariables);
  }

  private QueryTraverser(GraphQLSchema schema,
                         Node root,
                         GraphQLCompositeType rootParentType,
                         Map<String, FragmentDefinition> fragmentsByName,
                         Map<String, Object> variables) {
    this.schema = assertNotNull(schema, () -> "schema can't be null");
    this.variables = assertNotNull(variables, () -> "variables can't be null");
    assertNotNull(root, () -> "root can't be null");
    this.roots = Collections.singleton(root);
    this.rootParentType = assertNotNull(rootParentType, () -> "rootParentType can't be null");
    this.fragmentsByName = assertNotNull(fragmentsByName, () -> "fragmentsByName can't be null");
  }

  public Object visitDepthFirst(QueryVisitor queryVisitor) {
    return visitImpl(queryVisitor, null);
  }

  /**
   * Visits the Document (or parts of it) in post-order.
   *
   * @param visitor the query visitor that will be called back
   */
  public void visitPostOrder(QueryVisitor visitor) {
    visitImpl(visitor, false);
  }

  /**
   * Visits the Document (or parts of it) in pre-order.
   *
   * @param visitor the query visitor that will be called back
   */
  public void visitPreOrder(QueryVisitor visitor) {
    visitImpl(visitor, true);
  }


  /**
   * Reduces the fields of a Document (or parts of it) to a single value. The fields are visited in post-order.
   *
   * @param queryReducer the query reducer
   * @param initialValue the initial value to pass to the reducer
   * @param <T>          the type of reduced value
   * @return the calculated overall value
   */
  @SuppressWarnings("unchecked")
  public <T> T reducePostOrder(QueryReducer<T> queryReducer, T initialValue) {
    // compiler hack to make acc final and mutable :-)
    final Object[] acc = {initialValue};
    visitPostOrder(new QueryVisitorStub() {
      @Override
      public void visitField(QueryVisitorFieldEnvironment env) {
        acc[0] = queryReducer.reduceField(env, (T)acc[0]);
      }
    });
    return (T)acc[0];
  }

  /**
   * Reduces the fields of a Document (or parts of it) to a single value. The fields are visited in pre-order.
   *
   * @param queryReducer the query reducer
   * @param initialValue the initial value to pass to the reducer
   * @param <T>          the type of reduced value
   * @return the calucalated overall value
   */
  @SuppressWarnings("unchecked")
  public <T> T reducePreOrder(QueryReducer<T> queryReducer, T initialValue) {
    // compiler hack to make acc final and mutable :-)
    final Object[] acc = {initialValue};
    visitPreOrder(new QueryVisitorStub() {
      @Override
      public void visitField(QueryVisitorFieldEnvironment env) {
        acc[0] = queryReducer.reduceField(env, (T)acc[0]);
      }
    });
    return (T)acc[0];
  }

  private GraphQLObjectType getRootTypeFromOperation(OperationDefinition operationDefinition) {
    return switch (operationDefinition.getOperation()) {
      case MUTATION -> assertNotNull(schema.getMutationType());
      case QUERY -> assertNotNull(schema.getQueryType());
      case SUBSCRIPTION -> assertNotNull(schema.getSubscriptionType());
      default -> assertShouldNeverHappen();
    };
  }

  private List<Node> childrenOf(Node<?> node) {
    if (!(node instanceof FragmentSpread fragmentSpread)) {
      return node.getChildren();
    }
    return singletonList(fragmentsByName.get(fragmentSpread.getName()));
  }

  private Object visitImpl(QueryVisitor visitFieldCallback, Boolean preOrder) {
    Map<Class<?>, Object> rootVars = new LinkedHashMap<>();
    rootVars.put(QueryTraversalContext.class, new QueryTraversalContext(rootParentType, null, null));

    QueryVisitor preOrderCallback;
    QueryVisitor postOrderCallback;
    if (preOrder == null) {
      preOrderCallback = visitFieldCallback;
      postOrderCallback = visitFieldCallback;
    }
    else {
      QueryVisitor noOp = new QueryVisitorStub();
      preOrderCallback = preOrder ? visitFieldCallback : noOp;
      postOrderCallback = !preOrder ? visitFieldCallback : noOp;
    }

    NodeTraverser nodeTraverser = new NodeTraverser(rootVars, this::childrenOf);
    NodeVisitorWithTypeTracking nodeVisitorWithTypeTracking =
      new NodeVisitorWithTypeTracking(preOrderCallback, postOrderCallback, variables, schema, fragmentsByName);
    return nodeTraverser.depthFirst(nodeVisitorWithTypeTracking, roots);
  }

  public static Builder newQueryTraverser() {
    return new Builder();
  }

  @PublicApi
  public static class Builder {
    private GraphQLSchema schema;
    private Document document;
    private String operation;
    private Map<String, Object> variables;

    private Node root;
    private GraphQLCompositeType rootParentType;
    private Map<String, FragmentDefinition> fragmentsByName;


    /**
     * The schema used to identify the types of the query.
     *
     * @param schema the schema to use
     * @return this builder
     */
    public Builder schema(GraphQLSchema schema) {
      this.schema = schema;
      return this;
    }

    /**
     * specify the operation if a document is traversed and there
     * are more than one operation.
     *
     * @param operationName the operation name to use
     * @return this builder
     */
    public Builder operationName(String operationName) {
      this.operation = operationName;
      return this;
    }

    /**
     * document to be used to traverse the whole query.
     * If set a {@link Builder#operationName(String)} might be required.
     *
     * @param document the document to use
     * @return this builder
     */
    public Builder document(Document document) {
      this.document = document;
      return this;
    }

    /**
     * Variables used in the query.
     *
     * @param variables the variables to use
     * @return this builder
     */
    public Builder variables(Map<String, Object> variables) {
      this.variables = variables;
      return this;
    }

    /**
     * Specify the root node for the traversal. Needs to be provided if there is
     * no {@link Builder#document(Document)}.
     *
     * @param root the root node to use
     * @return this builder
     */
    public Builder root(Node root) {
      this.root = root;
      return this;
    }

    /**
     * The type of the parent of the root node. (See {@link Builder#root(Node)}
     *
     * @param rootParentType the root parent type
     * @return this builder
     */
    public Builder rootParentType(GraphQLCompositeType rootParentType) {
      this.rootParentType = rootParentType;
      return this;
    }

    /**
     * Fragment by name map. Needs to be provided together with a {@link Builder#root(Node)} and {@link Builder#rootParentType(GraphQLCompositeType)}
     *
     * @param fragmentsByName the map of fragments
     * @return this builder
     */
    public Builder fragmentsByName(Map<String, FragmentDefinition> fragmentsByName) {
      this.fragmentsByName = fragmentsByName;
      return this;
    }

    /**
     * @return a built {@link QueryTraverser} object
     */
    public QueryTraverser build() {
      checkState();
      if (document != null) {
        return new QueryTraverser(schema, document, operation, variables);
      }
      else {
        return new QueryTraverser(schema, root, rootParentType, fragmentsByName, variables);
      }
    }

    private void checkState() {
      if (document != null || operation != null) {
        if (root != null || rootParentType != null || fragmentsByName != null) {
          throw new IllegalStateException("ambiguous builder");
        }
      }
    }
  }
}
