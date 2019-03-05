/*
 * Copyright (c) 2018-present, Jim Kynde Meyer
 * All rights reserved.
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.intellij.lang.jsgraphql.schema;

import com.intellij.lang.jsgraphql.psi.GraphQLFile;
import com.intellij.openapi.util.Key;

public class GraphQLSchemaKeys {

    /**
     * Set on the Virtual File that contains the JSON result of an introspection query
     */
    public static final Key<Boolean> IS_GRAPHQL_INTROSPECTION_JSON = Key.create("JSGraphQL.IsIntrospectionJSON");

    /**
     * Set on a JSON introspection file (PSI and Virtual) to get the derived GraphQL SDL file
     */
    public static final Key<GraphQLFile> GRAPHQL_INTROSPECTION_JSON_TO_SDL = Key.create("JSGraphQL.IntrospectionJSONToSDL");

    /**
     * Set on the PSI File that is the SDL version of a JSON Introspection file
     */
    public static final Key<Boolean> IS_GRAPHQL_INTROSPECTION_SDL = Key.create("JSGraphQL.IsIntrospectionSDL");
}