package com.intellij.lang.jsgraphql.schema.builder;

import com.intellij.lang.jsgraphql.types.language.Directive;
import com.intellij.lang.jsgraphql.types.language.OperationTypeDefinition;
import com.intellij.lang.jsgraphql.types.language.SchemaDefinition;
import com.intellij.lang.jsgraphql.types.language.SchemaExtensionDefinition;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.intellij.lang.jsgraphql.schema.GraphQLTypeDefinitionUtil.*;

public class GraphQLSchemaTypeCompositeDefinition
    extends GraphQLExtendableCompositeDefinition<SchemaDefinition, SchemaExtensionDefinition> {

    @NotNull
    @Override
    protected SchemaDefinition mergeDefinitions() {
        List<Directive> directives = new ArrayList<>();
        Map<String, OperationTypeDefinition> operationTypeDefinitions = new LinkedHashMap<>();

        for (SchemaDefinition definition : myDefinitions) {
            directives.addAll(definition.getDirectives());
            mergeNodes(operationTypeDefinitions, mapNamedNodesByKey(definition.getOperationTypeDefinitions()));
        }

        SchemaDefinition definition = ContainerUtil.getFirstItem(myDefinitions);
        return definition.transform(builder -> builder
            .directives(directives)
            .operationTypeDefinitions(toList(operationTypeDefinitions))
            .sourceNodes(myDefinitions)
        );
    }
}
