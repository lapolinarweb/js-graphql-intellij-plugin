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
package com.intellij.lang.jsgraphql.types.schema.transform;

import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.schema.*;
import com.intellij.lang.jsgraphql.types.schema.transform.VisibleFieldPredicateEnvironment.VisibleFieldPredicateEnvironmentImpl;
import com.intellij.lang.jsgraphql.types.util.TraversalControl;
import com.intellij.lang.jsgraphql.types.util.TraverserContext;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.lang.jsgraphql.types.schema.SchemaTransformer.transformSchema;
import static com.intellij.lang.jsgraphql.types.util.TreeTransformerUtil.deleteNode;

/**
 * Transforms a schema by applying a visibility predicate to every field.
 */
@PublicApi
public class FieldVisibilitySchemaTransformation {

  private final VisibleFieldPredicate visibleFieldPredicate;
  private final Runnable beforeTransformationHook;
  private final Runnable afterTransformationHook;

  public FieldVisibilitySchemaTransformation(VisibleFieldPredicate visibleFieldPredicate) {
    this(visibleFieldPredicate, () -> {
    }, () -> {
    });
  }

  public FieldVisibilitySchemaTransformation(VisibleFieldPredicate visibleFieldPredicate,
                                             Runnable beforeTransformationHook,
                                             Runnable afterTransformationHook) {
    this.visibleFieldPredicate = visibleFieldPredicate;
    this.beforeTransformationHook = beforeTransformationHook;
    this.afterTransformationHook = afterTransformationHook;
  }

  public final GraphQLSchema apply(GraphQLSchema schema) {
    Set<GraphQLType> observedBeforeTransform = new HashSet<>();
    Set<GraphQLType> observedAfterTransform = new HashSet<>();
    Set<GraphQLType> markedForRemovalTypes = new HashSet<>();

    // query, mutation, and subscription types should not be removed
    final Set<String> protectedTypeNames = getRootTypes(schema).stream()
      .map(GraphQLObjectType::getName)
      .collect(Collectors.toSet());

    beforeTransformationHook.run();

    new SchemaTraverser().depthFirst(new TypeObservingVisitor(observedBeforeTransform, schema), getRootTypes(schema));

    // remove fields
    GraphQLSchema interimSchema = transformSchema(schema,
                                                  new FieldRemovalVisitor(visibleFieldPredicate, markedForRemovalTypes));

    new SchemaTraverser().depthFirst(new TypeObservingVisitor(observedAfterTransform, interimSchema), getRootTypes(interimSchema));

    // remove types that are not used after removing fields - (connected schema only)
    GraphQLSchema connectedSchema = transformSchema(interimSchema,
                                                    new TypeVisibilityVisitor(protectedTypeNames, observedBeforeTransform,
                                                                              observedAfterTransform));

    // ensure markedForRemovalTypes are not referenced by other schema elements, and delete from the schema
    // the ones that aren't.
    GraphQLSchema finalSchema = removeUnreferencedTypes(markedForRemovalTypes, connectedSchema);

    afterTransformationHook.run();

    return finalSchema;
  }

  private GraphQLSchema removeUnreferencedTypes(Set<GraphQLType> markedForRemovalTypes, GraphQLSchema connectedSchema) {
    GraphQLSchema withoutAdditionalTypes = connectedSchema.transform(builder -> {
      Set<GraphQLType> additionalTypes = new HashSet<>(connectedSchema.getAdditionalTypes());
      additionalTypes.removeAll(markedForRemovalTypes);
      builder.clearAdditionalTypes();
      builder.additionalTypes(additionalTypes);
    });

    // remove from markedForRemovalTypes any type that might still be referenced by other schema elements
    transformSchema(withoutAdditionalTypes, new AdditionalTypeVisibilityVisitor(markedForRemovalTypes));

    // finally remove the types on the schema we are certain aren't referenced by any other node.
    return transformSchema(connectedSchema, new GraphQLTypeVisitorStub() {
      @Override
      protected TraversalControl visitGraphQLType(GraphQLSchemaElement node, TraverserContext<GraphQLSchemaElement> context) {
        if (node instanceof GraphQLType && markedForRemovalTypes.contains(node)) {
          return deleteNode(context);
        }
        return super.visitGraphQLType(node, context);
      }
    });
  }

  private static class TypeObservingVisitor extends GraphQLTypeVisitorStub {

    private final Set<GraphQLType> observedTypes;
    private GraphQLSchema graphQLSchema;


    private TypeObservingVisitor(Set<GraphQLType> observedTypes, GraphQLSchema graphQLSchema) {
      this.observedTypes = observedTypes;
      this.graphQLSchema = graphQLSchema;
    }

    @Override
    protected TraversalControl visitGraphQLType(GraphQLSchemaElement node,
                                                TraverserContext<GraphQLSchemaElement> context) {
      if (node instanceof GraphQLType) {
        observedTypes.add((GraphQLType)node);
      }
      if (node instanceof GraphQLInterfaceType) {
        observedTypes.addAll(graphQLSchema.getImplementations((GraphQLInterfaceType)node));
      }

      return TraversalControl.CONTINUE;
    }
  }

  private static class FieldRemovalVisitor extends GraphQLTypeVisitorStub {

    private final VisibleFieldPredicate visibilityPredicate;
    private final Set<GraphQLType> removedTypes;

    private FieldRemovalVisitor(VisibleFieldPredicate visibilityPredicate,
                                Set<GraphQLType> removedTypes) {
      this.visibilityPredicate = visibilityPredicate;
      this.removedTypes = removedTypes;
    }

    @Override
    public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition definition,
                                                        TraverserContext<GraphQLSchemaElement> context) {
      return visitField(definition, context);
    }

    @Override
    public TraversalControl visitGraphQLInputObjectField(GraphQLInputObjectField definition,
                                                         TraverserContext<GraphQLSchemaElement> context) {
      return visitField(definition, context);
    }

    private TraversalControl visitField(GraphQLNamedSchemaElement element,
                                        TraverserContext<GraphQLSchemaElement> context) {

      VisibleFieldPredicateEnvironment environment = new VisibleFieldPredicateEnvironmentImpl(
        element, context.getParentNode());
      if (!visibilityPredicate.isVisible(environment)) {
        deleteNode(context);

        if (element instanceof GraphQLFieldDefinition) {
          removedTypes.add(((GraphQLFieldDefinition)element).getType());
        }
        else if (element instanceof GraphQLInputObjectField) {
          removedTypes.add(((GraphQLInputObjectField)element).getType());
        }
      }

      return TraversalControl.CONTINUE;
    }
  }

  private static class TypeVisibilityVisitor extends GraphQLTypeVisitorStub {

    private final Set<String> protectedTypeNames;
    private final Set<GraphQLType> observedBeforeTransform;
    private final Set<GraphQLType> observedAfterTransform;

    private TypeVisibilityVisitor(Set<String> protectedTypeNames,
                                  Set<GraphQLType> observedTypes,
                                  Set<GraphQLType> observedAfterTransform) {
      this.protectedTypeNames = protectedTypeNames;
      this.observedBeforeTransform = observedTypes;
      this.observedAfterTransform = observedAfterTransform;
    }

    @Override
    public TraversalControl visitGraphQLType(GraphQLSchemaElement node,
                                             TraverserContext<GraphQLSchemaElement> context) {
      if (observedBeforeTransform.contains(node) &&
          !observedAfterTransform.contains(node) &&
          (node instanceof GraphQLObjectType ||
           node instanceof GraphQLEnumType ||
           node instanceof GraphQLInterfaceType ||
           node instanceof GraphQLUnionType)) {

        return deleteNode(context);
      }

      return TraversalControl.CONTINUE;
    }
  }

  private static class AdditionalTypeVisibilityVisitor extends GraphQLTypeVisitorStub {

    private final Set<GraphQLType> markedForRemovalTypes;

    private AdditionalTypeVisibilityVisitor(Set<GraphQLType> markedForRemovalTypes) {
      this.markedForRemovalTypes = markedForRemovalTypes;
    }

    @Override
    public TraversalControl visitGraphQLType(GraphQLSchemaElement node,
                                             TraverserContext<GraphQLSchemaElement> context) {

      if (node instanceof GraphQLNamedType namedType) {
        // we encountered a node referencing one of the marked types, so it should not be removed.
        if (markedForRemovalTypes.contains(node)) {
          markedForRemovalTypes.remove(namedType);
        }
      }

      return TraversalControl.CONTINUE;
    }
  }

  private List<GraphQLObjectType> getRootTypes(GraphQLSchema schema) {
    return Stream.of(
      schema.getQueryType(),
      schema.getSubscriptionType(),
      schema.getMutationType()
    ).filter(Objects::nonNull).collect(Collectors.toList());
  }
}
