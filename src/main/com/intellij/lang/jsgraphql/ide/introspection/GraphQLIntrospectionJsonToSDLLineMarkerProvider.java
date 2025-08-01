/*
 * Copyright (c) 2018-present, Jim Kynde Meyer
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.intellij.lang.jsgraphql.ide.introspection;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.json.psi.JsonArray;
import com.intellij.json.psi.JsonObject;
import com.intellij.json.psi.JsonProperty;
import com.intellij.lang.jsgraphql.GraphQLBundle;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Line marker which shows an action to turn a GraphQL Introspection JSON result into a GraphQL schema expressed in GraphQL SDL.
 */
public final class GraphQLIntrospectionJsonToSDLLineMarkerProvider implements LineMarkerProvider {
  @Override
  public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
    VirtualFile virtualFile = element.isValid() ? element.getContainingFile().getVirtualFile() : null;
    if (virtualFile != null && !virtualFile.isInLocalFileSystem()) {
      // skip in-memory JSON files such as the query result viewer
      return null;
    }
    if (!(element instanceof JsonProperty jsonProperty)) {
      return null;
    }
    Project project = element.getProject();
    JsonProperty parentProperty = PsiTreeUtil.getParentOfType(element, JsonProperty.class);
    if (parentProperty != null && !"data".equals(parentProperty.getName())) {
      return null;
    }

    // top level property or inside data property
    String propertyName = jsonProperty.getName();
    if (!"__schema".equals(propertyName) || !(jsonProperty.getValue() instanceof JsonObject)) {
      return null;
    }

    for (JsonProperty property : ((JsonObject)jsonProperty.getValue()).getPropertyList()) {
      if ("types".equals(property.getName()) && property.getValue() instanceof JsonArray) {
        SmartPsiElementPointer<PsiElement> pointer = SmartPointerManager.createPointer(element);
        // likely a GraphQL schema with a { __schema: { types: [] } }
        Runnable generateAction = new GraphQLConvertJsonIntrospectionToSdlCommand(project, pointer);

        PsiElement anchor = jsonProperty.getNameElement().getFirstChild();
        if (anchor == null) return null;

        return new LineMarkerInfo<>(
          anchor,
          anchor.getTextRange(),
          AllIcons.RunConfigurations.TestState.Run,
          o -> GraphQLBundle.message("graphql.line.marker.generate.schema.file"),
          (evt, elt) -> generateAction.run(),
          GutterIconRenderer.Alignment.CENTER,
          () -> GraphQLBundle.message("graphql.line.marker.generate.schema.file")
        );
      }
    }
    return null;
  }
}
