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
package com.intellij.lang.jsgraphql.types.language;


import com.google.common.collect.ImmutableList;
import com.intellij.lang.jsgraphql.types.Internal;
import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.collect.ImmutableKit;
import com.intellij.lang.jsgraphql.types.util.TraversalControl;
import com.intellij.lang.jsgraphql.types.util.TraverserContext;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.intellij.lang.jsgraphql.types.Assert.assertNotNull;
import static com.intellij.lang.jsgraphql.types.collect.ImmutableKit.emptyList;
import static com.intellij.lang.jsgraphql.types.language.NodeChildrenContainer.newNodeChildrenContainer;

@PublicApi
public class ObjectField extends AbstractNode<ObjectField> implements NamedNode<ObjectField> {

  private final String name;
  private final Value value;

  public static final String CHILD_VALUE = "value";

  @Internal
  protected ObjectField(String name,
                        Value value,
                        SourceLocation sourceLocation,
                        List<Comment> comments,
                        IgnoredChars ignoredChars,
                        Map<String, String> additionalData,
                        @Nullable List<? extends Node> sourceNodes) {
    super(sourceLocation, comments, ignoredChars, additionalData, sourceNodes);
    this.name = name;
    this.value = value;
  }

  /**
   * alternative to using a Builder for convenience
   *
   * @param name  of the field
   * @param value of the field
   */
  public ObjectField(String name, Value value) {
    this(name, value, null, emptyList(), IgnoredChars.EMPTY, ImmutableKit.emptyMap(), null);
  }

  @Override
  public String getName() {
    return name;
  }

  public Value getValue() {
    return value;
  }

  @Override
  public List<Node> getChildren() {
    return ImmutableList.of(value);
  }

  @Override
  public NodeChildrenContainer getNamedChildren() {
    return newNodeChildrenContainer()
      .child(CHILD_VALUE, value)
      .build();
  }

  @Override
  public ObjectField withNewChildren(NodeChildrenContainer newChildren) {
    return transform(builder -> builder
      .value(newChildren.getChildOrNull(CHILD_VALUE))
    );
  }

  @Override
  public boolean isEqualTo(Node o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ObjectField that = (ObjectField)o;

    return !(name != null ? !name.equals(that.name) : that.name != null);
  }

  @Override
  public ObjectField deepCopy() {
    return new ObjectField(name, deepCopy(this.value), getSourceLocation(), getComments(), getIgnoredChars(), getAdditionalData(),
                           getSourceNodes());
  }

  @Override
  public String toString() {
    return "ObjectField{" +
           "name='" + name + '\'' +
           ", value=" + value +
           '}';
  }

  @Override
  public TraversalControl accept(TraverserContext<Node> context, NodeVisitor visitor) {
    return visitor.visitObjectField(this, context);
  }

  public static Builder newObjectField() {
    return new Builder();
  }

  public ObjectField transform(Consumer<Builder> builderConsumer) {
    Builder builder = new Builder(this);
    builderConsumer.accept(builder);
    return builder.build();
  }

  public static final class Builder implements NodeBuilder {
    private SourceLocation sourceLocation;
    private String name;
    private ImmutableList<Comment> comments = emptyList();
    private Value value;
    private IgnoredChars ignoredChars = IgnoredChars.EMPTY;
    private Map<String, String> additionalData = new LinkedHashMap<>();
    private @Nullable List<? extends Node> sourceNodes;

    private Builder() {
    }


    private Builder(ObjectField existing) {
      this.sourceLocation = existing.getSourceLocation();
      this.comments = ImmutableList.copyOf(existing.getComments());
      this.name = existing.getName();
      this.value = existing.getValue();
      this.additionalData = new LinkedHashMap<>(existing.getAdditionalData());
      this.sourceNodes = existing.getSourceNodes();
    }


    @Override
    public Builder sourceLocation(SourceLocation sourceLocation) {
      this.sourceLocation = sourceLocation;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    @Override
    public Builder comments(List<Comment> comments) {
      this.comments = ImmutableList.copyOf(comments);
      return this;
    }

    public Builder value(Value value) {
      this.value = value;
      return this;
    }

    @Override
    public Builder ignoredChars(IgnoredChars ignoredChars) {
      this.ignoredChars = ignoredChars;
      return this;
    }

    @Override
    public Builder additionalData(Map<String, String> additionalData) {
      this.additionalData = assertNotNull(additionalData);
      return this;
    }

    @Override
    public Builder additionalData(String key, String value) {
      this.additionalData.put(key, value);
      return this;
    }

    @Override
    public Builder sourceNodes(@Nullable List<? extends Node> sourceNodes) {
      this.sourceNodes = sourceNodes;
      return this;
    }


    public ObjectField build() {
      return new ObjectField(name, value, sourceLocation, comments, ignoredChars, additionalData, sourceNodes);
    }
  }
}
