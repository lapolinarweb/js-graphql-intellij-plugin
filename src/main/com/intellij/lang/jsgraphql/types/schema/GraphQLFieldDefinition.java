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
import com.intellij.lang.jsgraphql.types.DirectivesUtil;
import com.intellij.lang.jsgraphql.types.Internal;
import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.language.FieldDefinition;
import com.intellij.lang.jsgraphql.types.util.TraversalControl;
import com.intellij.lang.jsgraphql.types.util.TraverserContext;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static com.intellij.lang.jsgraphql.types.Assert.assertNotNull;
import static com.intellij.lang.jsgraphql.types.Assert.assertValidName;
import static com.intellij.lang.jsgraphql.types.util.FpKit.getByName;

@PublicApi
public class GraphQLFieldDefinition implements GraphQLNamedSchemaElement, GraphQLDirectiveContainer {

  private final String name;
  private final String description;
  private final GraphQLOutputType originalType;
  private final String deprecationReason;
  private final ImmutableList<GraphQLArgument> arguments;
  private final DirectivesUtil.DirectivesHolder directives;
  private final FieldDefinition definition;

  private GraphQLOutputType replacedType;

  public static final String CHILD_ARGUMENTS = "arguments";
  public static final String CHILD_DIRECTIVES = "directives";
  public static final String CHILD_TYPE = "type";


  /**
   * @param name              the name
   * @param description       the description
   * @param type              the field type
   * @param dataFetcher       the field data fetcher
   * @param arguments         the field arguments
   * @param deprecationReason the deprecation reason
   * @deprecated use the {@link #newFieldDefinition()} builder pattern instead, as this constructor will be made private in a future version.
   */
  @Internal
  @Deprecated(forRemoval = true)
  public GraphQLFieldDefinition(String name,
                                String description,
                                GraphQLOutputType type,
                                List<GraphQLArgument> arguments,
                                String deprecationReason) {
    this(name, description, type, arguments, deprecationReason, Collections.emptyList(), null);
  }

  /**
   * @param name               the name
   * @param description        the description
   * @param type               the field type
   * @param dataFetcherFactory the field data fetcher factory
   * @param arguments          the field arguments
   * @param deprecationReason  the deprecation reason
   * @param directives         the directives on this type element
   * @param definition         the AST definition
   * @deprecated use the {@link #newFieldDefinition()} builder pattern instead, as this constructor will be made private in a future version.
   */
  @Internal
  @Deprecated(forRemoval = true)
  public GraphQLFieldDefinition(String name,
                                String description,
                                GraphQLOutputType type,
                                List<GraphQLArgument> arguments,
                                String deprecationReason,
                                List<GraphQLDirective> directives,
                                FieldDefinition definition) {
    assertValidName(name);
    assertNotNull(type, () -> "type can't be null");
    assertNotNull(arguments, () -> "arguments can't be null");
    this.name = name;
    this.description = description;
    this.originalType = type;
    this.arguments = ImmutableList.copyOf(arguments);
    this.directives = new DirectivesUtil.DirectivesHolder(directives);
    this.deprecationReason = deprecationReason;
    this.definition = definition;
  }

  void replaceType(GraphQLOutputType type) {
    this.replacedType = type;
  }

  @Override
  public String getName() {
    return name;
  }


  public GraphQLOutputType getType() {
    return replacedType != null ? replacedType : originalType;
  }

  public GraphQLArgument getArgument(String name) {
    for (GraphQLArgument argument : arguments) {
      if (argument.getName().equals(name)) {
        return argument;
      }
    }
    return null;
  }

  @Override
  public List<GraphQLDirective> getDirectives() {
    return directives.getDirectives();
  }

  @Override
  public Map<String, GraphQLDirective> getDirectivesByName() {
    return directives.getDirectivesByName();
  }

  @Override
  public Map<String, List<GraphQLDirective>> getAllDirectivesByName() {
    return directives.getAllDirectivesByName();
  }

  @Override
  public GraphQLDirective getDirective(String directiveName) {
    return directives.getDirective(directiveName);
  }

  public List<GraphQLArgument> getArguments() {
    return arguments;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public FieldDefinition getDefinition() {
    return definition;
  }

  public String getDeprecationReason() {
    return deprecationReason;
  }

  public boolean isDeprecated() {
    return deprecationReason != null;
  }

  @Override
  public String toString() {
    return "GraphQLFieldDefinition{" +
           "name='" + name + '\'' +
           ", type=" + getType() +
           ", arguments=" + arguments +
           ", description='" + description + '\'' +
           ", deprecationReason='" + deprecationReason + '\'' +
           ", definition=" + definition +
           '}';
  }

  /**
   * This helps you transform the current GraphQLFieldDefinition into another one by starting a builder with all
   * the current values and allows you to transform it how you want.
   *
   * @param builderConsumer the consumer code that will be given a builder to transform
   * @return a new field based on calling build on that builder
   */
  public GraphQLFieldDefinition transform(Consumer<Builder> builderConsumer) {
    Builder builder = newFieldDefinition(this);
    builderConsumer.accept(builder);
    return builder.build();
  }

  @Override
  public TraversalControl accept(TraverserContext<GraphQLSchemaElement> context, GraphQLTypeVisitor visitor) {
    return visitor.visitGraphQLFieldDefinition(this, context);
  }

  @Override
  public List<GraphQLSchemaElement> getChildren() {
    List<GraphQLSchemaElement> children = new ArrayList<>();
    children.add(getType());
    children.addAll(arguments);
    children.addAll(directives.getDirectives());
    return children;
  }

  @Override
  public SchemaElementChildrenContainer getChildrenWithTypeReferences() {
    return SchemaElementChildrenContainer.newSchemaElementChildrenContainer()
      .child(CHILD_TYPE, originalType)
      .children(CHILD_ARGUMENTS, arguments)
      .children(CHILD_DIRECTIVES, directives.getDirectives())
      .build();
  }

  // Spock mocking fails with the real return type GraphQLFieldDefinition
  @Override
  public GraphQLSchemaElement withNewChildren(SchemaElementChildrenContainer newChildren) {
    return transform(builder ->
                       builder.replaceDirectives(newChildren.getChildren(CHILD_DIRECTIVES))
                         .replaceArguments(newChildren.getChildren(CHILD_ARGUMENTS))
                         .type((GraphQLOutputType)newChildren.getChildOrNull(CHILD_TYPE))
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


  public static Builder newFieldDefinition(GraphQLFieldDefinition existing) {
    return new Builder(existing);
  }

  public static Builder newFieldDefinition() {
    return new Builder();
  }

  @PublicApi
  public static class Builder extends GraphqlTypeBuilder {

    private GraphQLOutputType type;
    private String deprecationReason;
    private FieldDefinition definition;
    private final Map<String, GraphQLArgument> arguments = new LinkedHashMap<>();
    private final List<GraphQLDirective> directives = new ArrayList<>();

    public Builder() {
    }

    public Builder(GraphQLFieldDefinition existing) {
      this.name = existing.getName();
      this.description = existing.getDescription();
      this.type = existing.originalType;
      this.deprecationReason = existing.getDeprecationReason();
      this.definition = existing.getDefinition();
      this.arguments.putAll(getByName(existing.getArguments(), GraphQLArgument::getName));
      DirectivesUtil.enforceAddAll(this.directives, existing.getDirectives());
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

    @Override
    public Builder comparatorRegistry(GraphqlTypeComparatorRegistry comparatorRegistry) {
      super.comparatorRegistry(comparatorRegistry);
      return this;
    }

    public Builder definition(FieldDefinition definition) {
      this.definition = definition;
      return this;
    }

    public Builder type(GraphQLObjectType.Builder builder) {
      return type(builder.build());
    }

    public Builder type(GraphQLInterfaceType.Builder builder) {
      return type(builder.build());
    }

    public Builder type(GraphQLUnionType.Builder builder) {
      return type(builder.build());
    }

    public Builder type(GraphQLOutputType type) {
      this.type = type;
      return this;
    }

    public Builder argument(GraphQLArgument argument) {
      assertNotNull(argument, () -> "argument can't be null");
      this.arguments.put(argument.getName(), argument);
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
      argument(builder.build());
      return this;
    }

    /**
     * This adds the list of arguments to the field.
     *
     * @param arguments the arguments to add
     * @return this
     * @deprecated This is a badly named method and is replaced by {@link #arguments(List)}
     */
    @Deprecated(forRemoval = true)
    public Builder argument(List<GraphQLArgument> arguments) {
      return arguments(arguments);
    }

    /**
     * This adds the list of arguments to the field.
     *
     * @param arguments the arguments to add
     * @return this
     */
    public Builder arguments(List<GraphQLArgument> arguments) {
      assertNotNull(arguments, () -> "arguments can't be null");
      for (GraphQLArgument argument : arguments) {
        argument(argument);
      }
      return this;
    }

    public Builder replaceArguments(List<GraphQLArgument> arguments) {
      assertNotNull(arguments, () -> "arguments can't be null");
      this.arguments.clear();
      for (GraphQLArgument argument : arguments) {
        argument(argument);
      }
      return this;
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


    public Builder deprecate(String deprecationReason) {
      this.deprecationReason = deprecationReason;
      return this;
    }

    public Builder withDirectives(GraphQLDirective... directives) {
      assertNotNull(directives, () -> "directives can't be null");
      this.directives.clear();
      for (GraphQLDirective directive : directives) {
        withDirective(directive);
      }
      return this;
    }

    public Builder withDirective(GraphQLDirective directive) {
      assertNotNull(directive, () -> "directive can't be null");
      DirectivesUtil.enforceAdd(this.directives, directive);
      return this;
    }

    public Builder replaceDirectives(List<GraphQLDirective> directives) {
      assertNotNull(directives, () -> "directive can't be null");
      this.directives.clear();
      DirectivesUtil.enforceAddAll(this.directives, directives);
      return this;
    }

    public Builder withDirective(GraphQLDirective.Builder builder) {
      return withDirective(builder.build());
    }

    /**
     * This is used to clear all the directives in the builder so far.
     *
     * @return the builder
     */
    public Builder clearDirectives() {
      directives.clear();
      return this;
    }

    public GraphQLFieldDefinition build() {
      return new GraphQLFieldDefinition(
        name,
        description,
        type,
        sort(arguments, GraphQLFieldDefinition.class, GraphQLArgument.class),
        deprecationReason,
        sort(directives, GraphQLFieldDefinition.class, GraphQLDirective.class),
        definition);
    }
  }
}
