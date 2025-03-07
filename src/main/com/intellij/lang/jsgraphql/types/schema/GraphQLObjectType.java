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
import com.google.common.collect.ImmutableMap;
import com.intellij.lang.jsgraphql.types.Assert;
import com.intellij.lang.jsgraphql.types.DirectivesUtil;
import com.intellij.lang.jsgraphql.types.Internal;
import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.language.ObjectTypeDefinition;
import com.intellij.lang.jsgraphql.types.language.ObjectTypeExtensionDefinition;
import com.intellij.lang.jsgraphql.types.util.FpKit;
import com.intellij.lang.jsgraphql.types.util.TraversalControl;
import com.intellij.lang.jsgraphql.types.util.TraverserContext;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static com.intellij.lang.jsgraphql.types.Assert.*;
import static com.intellij.lang.jsgraphql.types.schema.GraphqlTypeComparators.asIsOrder;
import static com.intellij.lang.jsgraphql.types.schema.GraphqlTypeComparators.sortTypes;
import static com.intellij.lang.jsgraphql.types.util.FpKit.getByName;
import static com.intellij.lang.jsgraphql.types.util.FpKit.valuesToList;
import static java.util.Collections.emptyList;

/**
 * This is the work horse type and represents an object with one or more field values that can be retrieved
 * by the graphql system.
 * <p>
 * Those fields can themselves by object types and so on until you reach the leaf nodes of the type tree represented
 * by {@link com.intellij.lang.jsgraphql.types.schema.GraphQLScalarType}s.
 * <p>
 * See http://graphql.org/learn/schema/#object-types-and-fields for more details on the concept.
 */
@PublicApi
public class GraphQLObjectType
  implements GraphQLNamedOutputType, GraphQLCompositeType, GraphQLUnmodifiedType, GraphQLNullableType, GraphQLDirectiveContainer,
             GraphQLImplementingType {


  private final String name;
  private final String description;
  private final Comparator<? super GraphQLSchemaElement> interfaceComparator;
  private final ImmutableMap<String, GraphQLFieldDefinition> fieldDefinitionsByName;
  private final ImmutableList<GraphQLNamedOutputType> originalInterfaces;
  private final DirectivesUtil.DirectivesHolder directives;
  private final ObjectTypeDefinition definition;
  private final ImmutableList<ObjectTypeExtensionDefinition> extensionDefinitions;

  private ImmutableList<GraphQLNamedOutputType> replacedInterfaces;

  public static final String CHILD_INTERFACES = "interfaces";
  public static final String CHILD_DIRECTIVES = "directives";
  public static final String CHILD_FIELD_DEFINITIONS = "fieldDefinitions";


  /**
   * @param name             the name
   * @param description      the description
   * @param fieldDefinitions the fields
   * @param interfaces       the possible interfaces
   * @deprecated use the {@link #newObject()} builder pattern instead, as this constructor will be made private in a future version.
   */
  @Internal
  @Deprecated(forRemoval = true)
  public GraphQLObjectType(String name, String description, List<GraphQLFieldDefinition> fieldDefinitions,
                           List<GraphQLNamedOutputType> interfaces) {
    this(name, description, fieldDefinitions, interfaces, emptyList(), null);
  }

  /**
   * @param name             the name
   * @param description      the description
   * @param fieldDefinitions the fields
   * @param interfaces       the possible interfaces
   * @param directives       the directives on this type element
   * @param definition       the AST definition
   * @deprecated use the {@link #newObject()} builder pattern instead, as this constructor will be made private in a future version.
   */
  @Internal
  @Deprecated(forRemoval = true)
  public GraphQLObjectType(String name, String description, List<GraphQLFieldDefinition> fieldDefinitions,
                           List<GraphQLNamedOutputType> interfaces, List<GraphQLDirective> directives, ObjectTypeDefinition definition) {
    this(name, description, fieldDefinitions, interfaces, directives, definition, emptyList(), asIsOrder());
  }


  private GraphQLObjectType(String name,
                            String description,
                            List<GraphQLFieldDefinition> fieldDefinitions,
                            List<GraphQLNamedOutputType> interfaces,
                            List<GraphQLDirective> directives,
                            ObjectTypeDefinition definition,
                            List<ObjectTypeExtensionDefinition> extensionDefinitions,
                            Comparator<? super GraphQLSchemaElement> interfaceComparator) {
    assertValidName(name);
    assertNotNull(fieldDefinitions, () -> "fieldDefinitions can't be null");
    assertNotNull(interfaces, () -> "interfaces can't be null");
    this.name = name;
    this.description = description;
    this.interfaceComparator = interfaceComparator;
    this.originalInterfaces = ImmutableList.copyOf(sortTypes(interfaceComparator, interfaces));
    this.definition = definition;
    this.extensionDefinitions = ImmutableList.copyOf(extensionDefinitions);
    this.directives = new DirectivesUtil.DirectivesHolder(assertNotNull(directives));
    this.fieldDefinitionsByName = buildDefinitionMap(fieldDefinitions);
  }

  void replaceInterfaces(List<GraphQLNamedOutputType> interfaces) {
    this.replacedInterfaces = ImmutableList.copyOf(sortTypes(interfaceComparator, interfaces));
  }

  private ImmutableMap<String, GraphQLFieldDefinition> buildDefinitionMap(List<GraphQLFieldDefinition> fieldDefinitions) {
    return ImmutableMap.copyOf(FpKit.getByName(fieldDefinitions, GraphQLFieldDefinition::getName,
                                               (fld1, fld2) -> assertShouldNeverHappen("Duplicated definition for field '%s' in type '%s'",
                                                                                       fld1.getName(), this.name)));
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

  @Override
  public GraphQLFieldDefinition getFieldDefinition(String name) {
    return fieldDefinitionsByName.get(name);
  }

  @Override
  public List<GraphQLFieldDefinition> getFieldDefinitions() {
    return ImmutableList.copyOf(fieldDefinitionsByName.values());
  }


  @Override
  public List<GraphQLNamedOutputType> getInterfaces() {
    if (replacedInterfaces != null) {
      return replacedInterfaces;
    }
    return originalInterfaces;
  }

  @Override
  public String getDescription() {
    return description;
  }


  @Override
  public String getName() {
    return name;
  }

  @Override
  public ObjectTypeDefinition getDefinition() {
    return definition;
  }

  public List<ObjectTypeExtensionDefinition> getExtensionDefinitions() {
    return extensionDefinitions;
  }

  @Override
  public String toString() {
    return "GraphQLObjectType{" +
           "name='" + name + '\'' +
           ", description='" + description + '\'' +
           ", fieldDefinitionsByName=" + fieldDefinitionsByName.keySet() +
           ", interfaces=" + getInterfaces() +
           '}';
  }

  /**
   * This helps you transform the current GraphQLObjectType into another one by starting a builder with all
   * the current values and allows you to transform it how you want.
   *
   * @param builderConsumer the consumer code that will be given a builder to transform
   * @return a new object based on calling build on that builder
   */
  public GraphQLObjectType transform(Consumer<Builder> builderConsumer) {
    Builder builder = newObject(this);
    builderConsumer.accept(builder);
    return builder.build();
  }

  @Override
  public TraversalControl accept(TraverserContext<GraphQLSchemaElement> context, GraphQLTypeVisitor visitor) {
    return visitor.visitGraphQLObjectType(this, context);
  }

  @Override
  public List<GraphQLSchemaElement> getChildren() {
    List<GraphQLSchemaElement> children = new ArrayList<>(fieldDefinitionsByName.values());
    children.addAll(getInterfaces());
    children.addAll(directives.getDirectives());
    return children;
  }

  @Override
  public SchemaElementChildrenContainer getChildrenWithTypeReferences() {
    return SchemaElementChildrenContainer.newSchemaElementChildrenContainer()
      .children(CHILD_FIELD_DEFINITIONS, fieldDefinitionsByName.values())
      .children(CHILD_DIRECTIVES, directives.getDirectives())
      .children(CHILD_INTERFACES, originalInterfaces)
      .build();
  }

  // Spock mocking fails with the real return type GraphQLObjectType
  @Override
  public GraphQLSchemaElement withNewChildren(SchemaElementChildrenContainer newChildren) {
    return transform(builder ->
                       builder.replaceDirectives(newChildren.getChildren(CHILD_DIRECTIVES))
                         .replaceFields(newChildren.getChildren(CHILD_FIELD_DEFINITIONS))
                         .replaceInterfaces(newChildren.getChildren(CHILD_INTERFACES))
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


  public static Builder newObject() {
    return new Builder();
  }

  public static Builder newObject(GraphQLObjectType existing) {
    return new Builder(existing);
  }

  @PublicApi
  public static class Builder extends GraphqlTypeBuilder {
    private ObjectTypeDefinition definition;
    private List<ObjectTypeExtensionDefinition> extensionDefinitions = emptyList();
    private final Map<String, GraphQLFieldDefinition> fields = new LinkedHashMap<>();
    private final Map<String, GraphQLNamedOutputType> interfaces = new LinkedHashMap<>();
    private final List<GraphQLDirective> directives = new ArrayList<>();

    public Builder() {
    }

    public Builder(GraphQLObjectType existing) {
      name = existing.getName();
      description = existing.getDescription();
      definition = existing.getDefinition();
      extensionDefinitions = existing.getExtensionDefinitions();
      fields.putAll(getByName(existing.getFieldDefinitions(), GraphQLFieldDefinition::getName));
      interfaces.putAll(getByName(existing.originalInterfaces, GraphQLNamedType::getName));
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

    public Builder definition(ObjectTypeDefinition definition) {
      this.definition = definition;
      return this;
    }

    public Builder extensionDefinitions(List<ObjectTypeExtensionDefinition> extensionDefinitions) {
      this.extensionDefinitions = extensionDefinitions;
      return this;
    }

    public Builder field(GraphQLFieldDefinition fieldDefinition) {
      assertNotNull(fieldDefinition, () -> "fieldDefinition can't be null");
      this.fields.put(fieldDefinition.getName(), fieldDefinition);
      return this;
    }

    /**
     * Take a field builder in a function definition and apply. Can be used in a jdk8 lambda
     * e.g.:
     * <pre>
     *     {@code
     *      field(f -> f.name("fieldName"))
     *     }
     * </pre>
     *
     * @param builderFunction a supplier for the builder impl
     * @return this
     */
    public Builder field(UnaryOperator<GraphQLFieldDefinition.Builder> builderFunction) {
      assertNotNull(builderFunction, () -> "builderFunction can't be null");
      GraphQLFieldDefinition.Builder builder = GraphQLFieldDefinition.newFieldDefinition();
      builder = builderFunction.apply(builder);
      return field(builder.build());
    }

    /**
     * Same effect as the field(GraphQLFieldDefinition). Builder.build() is called
     * from within
     *
     * @param builder an un-built/incomplete GraphQLFieldDefinition
     * @return this
     */
    public Builder field(GraphQLFieldDefinition.Builder builder) {
      return field(builder.build());
    }

    public Builder fields(List<GraphQLFieldDefinition> fieldDefinitions) {
      assertNotNull(fieldDefinitions, () -> "fieldDefinitions can't be null");
      fieldDefinitions.forEach(this::field);
      return this;
    }

    public Builder replaceFields(List<GraphQLFieldDefinition> fieldDefinitions) {
      assertNotNull(fieldDefinitions, () -> "fieldDefinitions can't be null");
      this.fields.clear();
      fieldDefinitions.forEach(this::field);
      return this;
    }

    /**
     * This is used to clear all the fields in the builder so far.
     *
     * @return the builder
     */
    public Builder clearFields() {
      fields.clear();
      return this;
    }

    public boolean hasField(String fieldName) {
      return fields.containsKey(fieldName);
    }


    public Builder withInterface(GraphQLInterfaceType interfaceType) {
      assertNotNull(interfaceType, () -> "interfaceType can't be null");
      this.interfaces.put(interfaceType.getName(), interfaceType);
      return this;
    }

    public Builder replaceInterfaces(List<? extends GraphQLNamedOutputType> interfaces) {
      assertNotNull(interfaces, () -> "interfaces can't be null");
      this.interfaces.clear();
      for (GraphQLNamedOutputType schemaElement : interfaces) {
        if (schemaElement instanceof GraphQLInterfaceType || schemaElement instanceof GraphQLTypeReference) {
          this.interfaces.put(schemaElement.getName(), schemaElement);
        }
        else {
          Assert.assertShouldNeverHappen("Unexpected type " + (schemaElement != null ? schemaElement.getClass() : "null"));
        }
      }
      return this;
    }

    public Builder withInterface(GraphQLTypeReference reference) {
      assertNotNull(reference, () -> "reference can't be null");
      this.interfaces.put(reference.getName(), reference);
      return this;
    }

    public Builder withInterfaces(GraphQLInterfaceType... interfaceType) {
      for (GraphQLInterfaceType type : interfaceType) {
        withInterface(type);
      }
      return this;
    }

    public Builder withInterfaces(GraphQLTypeReference... references) {
      for (GraphQLTypeReference reference : references) {
        withInterface(reference);
      }
      return this;
    }

    /**
     * This is used to clear all the interfaces in the builder so far.
     *
     * @return the builder
     */
    public Builder clearInterfaces() {
      interfaces.clear();
      return this;
    }

    public Builder replaceDirectives(List<GraphQLDirective> directives) {
      assertNotNull(directives, () -> "directive can't be null");
      this.directives.clear();
      DirectivesUtil.enforceAddAll(this.directives, directives);
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

    public GraphQLObjectType build() {
      return new GraphQLObjectType(
        name,
        description,
        sort(fields, GraphQLObjectType.class, GraphQLFieldDefinition.class),
        valuesToList(interfaces),
        sort(directives, GraphQLObjectType.class, GraphQLDirective.class),
        definition,
        extensionDefinitions,
        getComparator(GraphQLObjectType.class, GraphQLInterfaceType.class)
      );
    }
  }
}
