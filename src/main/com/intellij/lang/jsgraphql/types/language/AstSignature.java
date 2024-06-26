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
import com.intellij.lang.jsgraphql.types.PublicApi;
import com.intellij.lang.jsgraphql.types.util.TraversalControl;
import com.intellij.lang.jsgraphql.types.util.TraverserContext;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.intellij.lang.jsgraphql.types.util.TreeTransformerUtil.changeNode;

/**
 * This will produce signature query documents that can be used say for logging.
 */
@PublicApi
public class AstSignature {

  /**
   * This can produce a "signature" canonical AST that conforms to the algorithm as outlined
   * <a href="https://github.com/apollographql/apollo-server/blob/master/packages/apollo-engine-reporting/src/signature.ts">here</a>
   * which removes excess operations, removes any field aliases, hides literal values and sorts the result into a canonical
   * query
   *
   * @param document      the document to make a signature query from
   * @param operationName the name of the operation to do it for (since only one query can be run at a time)
   * @return the signature query in document form
   */
  public Document signatureQuery(Document document, String operationName) {
    return sortAST(
      removeAliases(
        hideLiterals(
          dropUnusedQueryDefinitions(document, operationName)))
    );
  }

  private Document hideLiterals(Document document) {
    final Map<String, String> variableRemapping = new HashMap<>();
    final AtomicInteger variableCount = new AtomicInteger();

    NodeVisitorStub visitor = new NodeVisitorStub() {
      @Override
      public TraversalControl visitIntValue(IntValue node, TraverserContext<Node> context) {
        return changeNode(context, node.transform(builder -> builder.value(BigInteger.ZERO)));
      }

      @Override
      public TraversalControl visitFloatValue(FloatValue node, TraverserContext<Node> context) {
        return changeNode(context, node.transform(builder -> builder.value(BigDecimal.ZERO)));
      }

      @Override
      public TraversalControl visitStringValue(StringValue node, TraverserContext<Node> context) {
        return changeNode(context, node.transform(builder -> builder.value("")));
      }

      @Override
      public TraversalControl visitBooleanValue(BooleanValue node, TraverserContext<Node> context) {
        return changeNode(context, node.transform(builder -> builder.value(false)));
      }

      @Override
      public TraversalControl visitArrayValue(ArrayValue node, TraverserContext<Node> context) {
        return changeNode(context, node.transform(builder -> builder.values(Collections.emptyList())));
      }

      @Override
      public TraversalControl visitObjectValue(ObjectValue node, TraverserContext<Node> context) {
        return changeNode(context, node.transform(builder -> builder.objectFields(Collections.emptyList())));
      }

      @Override
      public TraversalControl visitVariableReference(VariableReference node, TraverserContext<Node> context) {
        String varName = remapVariable(node.getName(), variableRemapping, variableCount);
        return changeNode(context, node.transform(builder -> builder.name(varName)));
      }

      @Override
      public TraversalControl visitVariableDefinition(VariableDefinition node, TraverserContext<Node> context) {
        String varName = remapVariable(node.getName(), variableRemapping, variableCount);
        return changeNode(context, node.transform(builder -> builder.name(varName)));
      }
    };
    return transformDoc(document, visitor);
  }

  private String remapVariable(String varName, Map<String, String> variableRemapping, AtomicInteger variableCount) {
    String mappedName = variableRemapping.get(varName);
    if (mappedName == null) {
      mappedName = "var" + variableCount.incrementAndGet();
      variableRemapping.put(varName, mappedName);
    }
    return mappedName;
  }

  private Document removeAliases(Document document) {
    NodeVisitorStub visitor = new NodeVisitorStub() {
      @Override
      public TraversalControl visitField(Field node, TraverserContext<Node> context) {
        return changeNode(context, node.transform(builder -> builder.alias(null)));
      }
    };
    return transformDoc(document, visitor);
  }

  private Document sortAST(Document document) {
    return new AstSorter().sort(document);
  }

  private Document dropUnusedQueryDefinitions(Document document, final String operationName) {
    NodeVisitorStub visitor = new NodeVisitorStub() {
      @Override
      public TraversalControl visitDocument(Document node, TraverserContext<Node> context) {
        List<Definition> wantedDefinitions = node.getDefinitions().stream()
          .filter(d -> {
            if (d instanceof OperationDefinition operationDefinition) {
              return isThisOperation(operationDefinition, operationName);
            }
            return d instanceof FragmentDefinition;
            // SDL in a query makes no sense - its gone should it be present
          })
          .collect(ImmutableList.toImmutableList());

        Document changedNode = node.transform(builder -> {
          builder.definitions(wantedDefinitions);
        });
        return changeNode(context, changedNode);
      }
    };
    return transformDoc(document, visitor);
  }

  private boolean isThisOperation(OperationDefinition operationDefinition, String operationName) {
    String name = operationDefinition.getName();
    if (operationName == null) {
      return name == null;
    }
    return operationName.equals(name);
  }

  private Document transformDoc(Document document, NodeVisitorStub visitor) {
    AstTransformer astTransformer = new AstTransformer();
    Node newDoc = astTransformer.transform(document, visitor);
    return (Document)newDoc;
  }
}
