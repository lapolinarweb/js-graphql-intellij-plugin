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
package com.intellij.lang.jsgraphql.types.util;

import com.intellij.lang.jsgraphql.types.Internal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;

import static com.intellij.lang.jsgraphql.types.Assert.*;
import static com.intellij.lang.jsgraphql.types.util.NodeZipper.ModificationType.REPLACE;
import static com.intellij.lang.jsgraphql.types.util.TraversalControl.*;

@Internal
public class TreeParallelTransformer<T> {

  private final Map<Class<?>, Object> rootVars = new ConcurrentHashMap<>();

  private final ForkJoinPool forkJoinPool;
  private final NodeAdapter<T> nodeAdapter;


  private Object sharedContextData;


  private TreeParallelTransformer(Object sharedContextData,
                                  ForkJoinPool forkJoinPool,
                                  NodeAdapter<T> nodeAdapter) {
    this.sharedContextData = sharedContextData;
    this.forkJoinPool = forkJoinPool;
    this.nodeAdapter = nodeAdapter;
  }

  public static <T> TreeParallelTransformer<T> parallelTransformer(NodeAdapter<T> nodeAdapter) {
    return parallelTransformer(nodeAdapter, ForkJoinPool.commonPool());
  }

  public static <T> TreeParallelTransformer<T> parallelTransformer(NodeAdapter<T> nodeAdapter, ForkJoinPool forkJoinPool) {
    return new TreeParallelTransformer<>(null, forkJoinPool, nodeAdapter);
  }


  public TreeParallelTransformer<T> rootVars(Map<Class<?>, Object> rootVars) {
    this.rootVars.putAll(assertNotNull(rootVars));
    return this;
  }

  public TreeParallelTransformer<T> rootVar(Class<?> key, Object value) {
    rootVars.put(key, value);
    return this;
  }

  public T transform(T root, TraverserVisitor<? super T> visitor) {
    return transformImpl(root, visitor);
  }


  public DefaultTraverserContext<T> newRootContext(Map<Class<?>, Object> vars) {
    return newContextImpl(null, null, vars, null, true);
  }


  public T transformImpl(T root, TraverserVisitor<? super T> visitor) {
    assertNotNull(root);
    assertNotNull(visitor);

    DefaultTraverserContext<T> rootContext = newRootContext(rootVars);
    DefaultTraverserContext context = newContext(root, rootContext, null);
    EnterAction enterAction = new EnterAction(null, context, visitor);
    T result = (T)forkJoinPool.invoke(enterAction);
    return result;
  }

  private class EnterAction extends CountedCompleter {
    private DefaultTraverserContext currentContext;
    private TraverserVisitor<? super T> visitor;
    private List<DefaultTraverserContext> children;
    private List<NodeZipper<T>> myZippers = new LinkedList<>();
    private T result;

    private EnterAction(CountedCompleter parent, DefaultTraverserContext currentContext, TraverserVisitor<? super T> visitor) {
      super(parent);
      this.currentContext = currentContext;
      this.visitor = visitor;
    }

    @Override
    public void compute() {
      currentContext.setPhase(TraverserContext.Phase.ENTER);
      currentContext.setVar(List.class, myZippers);
      TraversalControl traversalControl = visitor.enter(currentContext);
      assertNotNull(traversalControl, () -> "result of enter must not be null");
      assertTrue(QUIT != traversalControl, () -> "can't return QUIT for parallel traversing");
      if (traversalControl == ABORT) {
        this.children = Collections.emptyList();
        tryComplete();
        return;
      }
      assertTrue(traversalControl == CONTINUE);

      this.children = pushAll(currentContext);
      if (children.isEmpty()) {
        tryComplete();
        return;
      }
      setPendingCount(children.size() - 1);
      for (int i = 1; i < children.size(); i++) {
        new EnterAction(this, children.get(i), visitor).fork();
      }
      new EnterAction(this, children.get(0), visitor).compute();
    }

    @Override
    public void onCompletion(CountedCompleter caller) {
      if (currentContext.isDeleted()) {
        this.result = null;
        return;
      }
      List<NodeZipper<T>> childZippers = new LinkedList<>();
      for (DefaultTraverserContext childContext : this.children) {
        childZippers.addAll((Collection<? extends NodeZipper<T>>)childContext.getVar(List.class));
      }
      if (!childZippers.isEmpty()) {
        NodeZipper<T> newNode = moveUp((T)currentContext.thisNode(), childZippers);
        myZippers.add(newNode);
        this.result = newNode.getCurNode();
      }
      else if (currentContext.isChanged()) {
        NodeZipper<T> newNode = new NodeZipper(currentContext.thisNode(), currentContext.getBreadcrumbs(), nodeAdapter);
        myZippers.add(newNode);
        this.result = (T)currentContext.thisNode();
      }
      else {
        this.result = (T)currentContext.thisNode();
      }
    }

    @Override
    public T getRawResult() {
      return result;
    }

    private NodeZipper<T> moveUp(T parent, List<NodeZipper<T>> sameParent) {
      assertNotEmpty(sameParent, () -> "expected at least one zipper");

      Map<String, List<T>> childrenMap = new HashMap<>(nodeAdapter.getNamedChildren(parent));
      Map<String, Integer> indexCorrection = new HashMap<>();

      sameParent.sort((zipper1, zipper2) -> {
        int index1 = zipper1.getBreadcrumbs().get(0).getLocation().getIndex();
        int index2 = zipper2.getBreadcrumbs().get(0).getLocation().getIndex();
        if (index1 != index2) {
          return Integer.compare(index1, index2);
        }
        NodeZipper.ModificationType modificationType1 = zipper1.getModificationType();
        NodeZipper.ModificationType modificationType2 = zipper2.getModificationType();

        // same index can never be deleted and changed at the same time

        if (modificationType1 == modificationType2) {
          return 0;
        }

        // always first replacing the node
        if (modificationType1 == REPLACE) {
          return -1;
        }
        // and then INSERT_BEFORE before INSERT_AFTER
        return modificationType1 == NodeZipper.ModificationType.INSERT_BEFORE ? -1 : 1;
      });

      for (NodeZipper<T> zipper : sameParent) {
        NodeLocation location = zipper.getBreadcrumbs().get(0).getLocation();
        Integer ixDiff = indexCorrection.getOrDefault(location.getName(), 0);
        int ix = location.getIndex() + ixDiff;
        String name = location.getName();
        List<T> childList = new ArrayList<>(childrenMap.get(name));
        switch (zipper.getModificationType()) {
          case REPLACE -> childList.set(ix, zipper.getCurNode());
          case DELETE -> {
            childList.remove(ix);
            indexCorrection.put(name, ixDiff - 1);
          }
          case INSERT_BEFORE -> {
            childList.add(ix, zipper.getCurNode());
            indexCorrection.put(name, ixDiff + 1);
          }
          case INSERT_AFTER -> {
            childList.add(ix + 1, zipper.getCurNode());
            indexCorrection.put(name, ixDiff + 1);
          }
        }
        childrenMap.put(name, childList);
      }

      T newNode = nodeAdapter.withNewChildren(parent, childrenMap);
      List<Breadcrumb<T>> newBreadcrumbs = sameParent.get(0).getBreadcrumbs().subList(1, sameParent.get(0).getBreadcrumbs().size());
      return new NodeZipper<>(newNode, newBreadcrumbs, nodeAdapter);
    }
  }

  private List<DefaultTraverserContext> pushAll(TraverserContext<T> traverserContext) {

    Map<String, List<TraverserContext<T>>> childrenContextMap = new LinkedHashMap<>();

    LinkedList<DefaultTraverserContext> contexts = new LinkedList<>();
    if (!traverserContext.isDeleted()) {

      Map<String, ? extends List<T>> childrenMap = this.nodeAdapter.getNamedChildren(traverserContext.thisNode());
      childrenMap.keySet().forEach(key -> {
        List<T> children = childrenMap.get(key);
        for (int i = children.size() - 1; i >= 0; i--) {
          T child = assertNotNull(children.get(i), () -> String.format("null child for key %s", key));
          NodeLocation nodeLocation = new NodeLocation(key, i);
          DefaultTraverserContext<T> context = newContext(child, traverserContext, nodeLocation);
          contexts.push(context);
          childrenContextMap.computeIfAbsent(key, notUsed -> new ArrayList<>());
          childrenContextMap.get(key).add(0, context);
        }
      });
    }
    return contexts;
  }

  private DefaultTraverserContext<T> newContext(T o, TraverserContext<T> parent, NodeLocation position) {
    return newContextImpl(o, parent, new LinkedHashMap<>(), position, false);
  }

  private DefaultTraverserContext<T> newContextImpl(T curNode,
                                                    TraverserContext<T> parent,
                                                    Map<Class<?>, Object> vars,
                                                    NodeLocation nodeLocation,
                                                    boolean isRootContext) {
    assertNotNull(vars);
    return new DefaultTraverserContext<>(curNode, parent, null, vars, sharedContextData, nodeLocation, isRootContext, true);
  }
}


