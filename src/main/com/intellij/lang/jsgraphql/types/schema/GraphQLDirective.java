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
package com.intellij.lang.jsgraphql.types.schema;


import com.google.common.collect.ImmutableList;
import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.language.DirectiveDefinition;
import com.intellij.lang.jsgraphql.types.util.TraversalControl;
import com.intellij.lang.jsgraphql.types.util.TraverserContext;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static com.intellij.lang.jsgraphql.types.Assert.assertNotNull;
import static com.intellij.lang.jsgraphql.types.Assert.assertValidName;
import static com.intellij.lang.jsgraphql.types.introspection.Introspection.DirectiveLocation;
import static com.intellij.lang.jsgraphql.types.util.FpKit.getByName;

/**
 * A directive can be used to modify the behavior of a graphql field or type.
 * <p>
 * See http://graphql.org/learn/queries/#directives for more details on the concept.
 */
@PublicApi
public class GraphQLDirective implements GraphQLNamedSchemaElement {

  private final String name;
  private final boolean repeatable;
  private final String description;
  private final EnumSet<DirectiveLocation> locations;
  private final ImmutableList<GraphQLArgument> arguments;
  private final DirectiveDefinition definition;


  public static final String CHILD_ARGUMENTS = "arguments";

  private GraphQLDirective(String name,
                           String description,
                           boolean repeatable,
                           EnumSet<DirectiveLocation> locations,
                           List<GraphQLArgument> arguments,
                           DirectiveDefinition definition) {
    assertValidName(name);
    assertNotNull(arguments, () -> "arguments can't be null");
    this.name = name;
    this.description = description;
    this.repeatable = repeatable;
    this.locations = locations;
    this.arguments = ImmutableList.copyOf(arguments);
    this.definition = definition;
  }

  @Override
  public String getName() {
    return name;
  }

  public boolean isRepeatable() {
    return repeatable;
  }

  public boolean isNonRepeatable() {
    return !repeatable;
  }

  public List<GraphQLArgument> getArguments() {
    return arguments;
  }

  public @Nullable GraphQLArgument getArgument(String name) {
    for (GraphQLArgument argument : arguments) {
      if (argument.getName().equals(name)) {
        return argument;
      }
    }
    return null;
  }

  public EnumSet<DirectiveLocation> validLocations() {
    return EnumSet.copyOf(locations);
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public DirectiveDefinition getDefinition() {
    return definition;
  }

  @Override
  public String toString() {
    return "GraphQLDirective{" +
           "name='" + name + '\'' +
           ", repeatable='" + repeatable + '\'' +
           ", arguments=" + arguments +
           ", locations=" + locations +
           '}';
  }

  /**
   * This helps you transform the current GraphQLDirective into another one by starting a builder with all
   * the current values and allows you to transform it how you want.
   *
   * @param builderConsumer the consumer code that will be given a builder to transform
   * @return a new field based on calling build on that builder
   */
  public GraphQLDirective transform(Consumer<Builder> builderConsumer) {
    Builder builder = newDirective(this);
    builderConsumer.accept(builder);
    return builder.build();
  }

  @Override
  public TraversalControl accept(TraverserContext<GraphQLSchemaElement> context, GraphQLTypeVisitor visitor) {
    return visitor.visitGraphQLDirective(this, context);
  }

  @Override
  public List<GraphQLSchemaElement> getChildren() {
    return ImmutableList.copyOf(arguments);
  }

  @Override
  public SchemaElementChildrenContainer getChildrenWithTypeReferences() {
    return SchemaElementChildrenContainer.newSchemaElementChildrenContainer()
      .children(CHILD_ARGUMENTS, arguments)
      .build();
  }

  @Override
  public GraphQLDirective withNewChildren(SchemaElementChildrenContainer newChildren) {
    return transform(builder ->
                       builder.replaceArguments(newChildren.getChildren(CHILD_ARGUMENTS))
    );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean equals(Object o) {
    return super.equals(o);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int hashCode() {
    return super.hashCode();
  }


  public static Builder newDirective() {
    return new Builder();
  }

  public static Builder newDirective(GraphQLDirective existing) {
    return new Builder(existing);
  }

  public static class Builder extends GraphqlTypeBuilder {

    private EnumSet<DirectiveLocation> locations = EnumSet.noneOf(DirectiveLocation.class);
    private final Map<String, GraphQLArgument> arguments = new LinkedHashMap<>();
    private DirectiveDefinition definition;
    private boolean repeatable = false;

    public Builder() {
    }

    public Builder(GraphQLDirective existing) {
      this.name = existing.getName();
      this.description = existing.getDescription();
      this.repeatable = existing.isRepeatable();
      this.locations = existing.validLocations();
      this.arguments.putAll(getByName(existing.getArguments(), GraphQLArgument::getName));
    }

    @Override
    public Builder name(String name) {
      super.name(name);
      return this;
    }

    @Override
    public Builder description(String description) {
      super.description(description);
      return this;
    }

    public Builder repeatable(boolean repeatable) {
      this.repeatable = repeatable;
      return this;
    }

    @Override
    public Builder comparatorRegistry(GraphqlTypeComparatorRegistry comparatorRegistry) {
      super.comparatorRegistry(comparatorRegistry);
      return this;
    }

    public Builder validLocations(DirectiveLocation... validLocations) {
      Collections.addAll(locations, validLocations);
      return this;
    }

    public Builder validLocation(DirectiveLocation validLocation) {
      locations.add(validLocation);
      return this;
    }

    public Builder clearValidLocations() {
      locations = EnumSet.noneOf(DirectiveLocation.class);
      return this;
    }

    public Builder argument(GraphQLArgument argument) {
      assertNotNull(argument, () -> "argument must not be null");
      arguments.put(argument.getName(), argument);
      return this;
    }

    public Builder replaceArguments(List<GraphQLArgument> arguments) {
      assertNotNull(arguments, () -> "arguments must not be null");
      this.arguments.clear();
      for (GraphQLArgument argument : arguments) {
        this.arguments.put(argument.getName(), argument);
      }
      return this;
    }

    /**
     * Take an argument builder in a function definition and apply. Can be used in a jdk8 lambda
     * e.g.:
     * <pre>
     *     {@code
     *      argument(a -> a.name("argumentName"))
     *     }
     * </pre>
     *
     * @param builderFunction a supplier for the builder impl
     * @return this
     */
    public Builder argument(UnaryOperator<GraphQLArgument.Builder> builderFunction) {
      GraphQLArgument.Builder builder = GraphQLArgument.newArgument();
      builder = builderFunction.apply(builder);
      return argument(builder);
    }

    /**
     * Same effect as the argument(GraphQLArgument). Builder.build() is called
     * from within
     *
     * @param builder an un-built/incomplete GraphQLArgument
     * @return this
     */
    public Builder argument(GraphQLArgument.Builder builder) {
      return argument(builder.build());
    }

    /**
     * This is used to clear all the arguments in the builder so far.
     *
     * @return the builder
     */
    public Builder clearArguments() {
      arguments.clear();
      return this;
    }


    public Builder definition(DirectiveDefinition definition) {
      this.definition = definition;
      return this;
    }

    public GraphQLDirective build() {
      return new GraphQLDirective(
        name,
        description,
        repeatable,
        locations,
        sort(arguments, GraphQLDirective.class, GraphQLArgument.class),
        definition);
    }
  }
}
