package org.hypergraphql.datafetching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Field;
import graphql.language.IntValue;
import graphql.language.Node;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.StringValue;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.Getter;
import org.apache.jena.rdf.model.Model;
import org.hypergraphql.config.schema.HGQLVocabulary;
import org.hypergraphql.datafetching.services.Service;
import org.hypergraphql.datamodel.HGQLSchema;
import org.hypergraphql.exception.HGQLConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hypergraphql.util.HGQLConstants.ALIAS;
import static org.hypergraphql.util.HGQLConstants.ARGS;
import static org.hypergraphql.util.HGQLConstants.FIELDS;
import static org.hypergraphql.util.HGQLConstants.NAME;
import static org.hypergraphql.util.HGQLConstants.NODE_ID;
import static org.hypergraphql.util.HGQLConstants.PARENT_ID;
import static org.hypergraphql.util.HGQLConstants.TARGET_NAME;

@Getter
// TODO - fix cs suppressions
public class ExecutionTreeNode {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionTreeNode.class);

    private Service service; // getService configuration
    private JsonNode query; // GraphQL in a basic Json format
    private final String executionId; // unique identifier of this execution node
    private final Map<String, ExecutionForest> childrenNodes; // succeeding executions
    private final String rootType;
    private final Map<String, String> ldContext;
    private final HGQLSchema hgqlSchema;

    ExecutionTreeNode(final Field field, final String nodeId, final HGQLSchema schema) {

        if (schema.getQueryFields().containsKey(field.getName())) {
            this.service = schema.getQueryFields().get(field.getName()).service();
        } else if (schema.getFields().containsKey(field.getName())) {
            LOGGER.info("here");
        } else {
            throw new HGQLConfigurationException("Field '" + field.getName() + "' not found in schema");
        }
        this.executionId = createId();
        this.childrenNodes = new HashMap<>();
        this.ldContext = new HashMap<>();
        this.ldContext.putAll(HGQLVocabulary.JSONLD);
        this.rootType = "Query";
        this.hgqlSchema = schema;
        this.query = getFieldJson(field, null, nodeId, "Query");
    }

    private ExecutionTreeNode(final Service service,
                              final Collection<Field> fields,
                              final String parentId,
                              final String parentType,
                              final HGQLSchema schema) {

        this.service = service;
        this.executionId = createId();
        this.childrenNodes = new HashMap<>();
        this.ldContext = new HashMap<>();
        this.rootType = parentType;
        this.hgqlSchema = schema;
        this.query = getFieldsJson(fields, parentId, parentType);
        this.ldContext.putAll(HGQLVocabulary.JSONLD);
    }

    public void setService(final Service service) {
        this.service = service;
    }

    public void setQuery(final JsonNode query) {
        this.query = query;
    }

    Map<String, String> getFullLdContext() {

        final Map<String, String> result = new HashMap<>(ldContext);

        Collection<ExecutionForest> children = getChildrenNodes().values();
        children.forEach(child -> result.putAll(child.getFullLdContext()));

        return result;

    }

    public String toString(final int i) {

        final var space = "\t".repeat(Math.max(0, i));

        final var result = new StringBuilder("\n")
            .append(space).append("ExecutionNode ID: ").append(this.executionId).append("\n")
            .append(space).append("Service ID: ").append(this.service.getId()).append("\n")
            .append(space).append("Query: ").append(this.query.toString()).append("\n")
            .append(space).append("Root type: ").append(this.rootType).append("\n")
            .append(space).append("LD context: ").append(this.ldContext.toString()).append("\n");
        final Collection<Map.Entry<String, ExecutionForest>> children = this.childrenNodes.entrySet();
        if (!children.isEmpty()) {
            result.append(space).append("Children nodes: \n");
            for (final Map.Entry<String, ExecutionForest> child : children) {
                result.append(space).append("\tParent marker: ")
                        .append(child.getKey()).append("\n")
                        .append(space).append("\tChildren execution nodes: \n")
                        .append(child.getValue().toString(i + 1)).append("\n");
            }
        }

        return result.append("\n").toString();
    }

    private JsonNode getFieldsJson(final Collection<Field> fields,
                                   final String parentId,
                                   final String parentType) {

        final var mapper = new ObjectMapper();
        final var queryNode = mapper.createArrayNode();

        int i = 0;
        for (final Field field : fields) {
            i++;
            final String nodeId = parentId + "_" + i;
            queryNode.add(getFieldJson(field, parentId, nodeId, parentType));
        }
        return queryNode;
    }

    private JsonNode getFieldJson(final Field field,
                                  final String parentId,
                                  final String nodeId,
                                  final String parentType) {

        final var mapper = new ObjectMapper();
        final var queryNode = mapper.createObjectNode();

        queryNode.put(NAME, field.getName());
        queryNode.put(ALIAS, field.getAlias());
        queryNode.put(PARENT_ID, parentId);
        queryNode.put(NODE_ID, nodeId);
        final List<Argument> args = field.getArguments();

        final var contextLdKey = (field.getAlias() == null) ? field.getName() : field.getAlias();
        final var contextLdValue = getContextLdValue(contextLdKey);

        this.ldContext.put(contextLdKey, contextLdValue);

        if (args.isEmpty()) {
            queryNode.set(ARGS, null);
        } else {
            queryNode.set(ARGS, getArgsJson(args));
        }

        final var fieldConfig = hgqlSchema.getTypes().get(parentType).getField(field.getName());
        final var targetName = fieldConfig.getTargetName();

        queryNode.put(TARGET_NAME, targetName);
        queryNode.set(FIELDS, this.traverse(field, nodeId, parentType));

        return queryNode;
    }

    private String getContextLdValue(final String contextLdKey) {

        if (hgqlSchema.getFields().containsKey(contextLdKey)) {
            return hgqlSchema.getFields().get(contextLdKey).getId();
        } else {
            return HGQLVocabulary.HGQL_QUERY_NAMESPACE + contextLdKey;
        }
    }

    @SuppressWarnings({"checkstyle:NestedIfDepth", "checkstyle:IllegalCatch"})
    private JsonNode traverse(final Field field, final String parentId, final String parentType) {

        final var subFields = field.getSelectionSet();
        if (subFields != null) {

            final var fieldConfig = hgqlSchema.getTypes().get(parentType).getField(field.getName());
            final var targetName = fieldConfig.getTargetName();

            final Map<Service, Collection<Field>> splitFields = getPartitionedFields(targetName, subFields);

            final Collection<Service> serviceCalls = splitFields.keySet();

            for (final Map.Entry<Service, Collection<Field>> entry : splitFields.entrySet()) {
                if (!entry.getKey().equals(this.service)) {
                    final var childNode = new ExecutionTreeNode(
                            entry.getKey(),
                            entry.getValue(),
                            parentId,
                            targetName,
                            hgqlSchema
                    );

                    if (this.childrenNodes.containsKey(parentId)) {
                        try {
                            this.childrenNodes.get(parentId).getForest().add(childNode);
                        } catch (Exception e) {
                            LOGGER.error("Problem adding parent", e);
                        }
                    } else {
                        final var forest = new ExecutionForest();
                        forest.getForest().add(childNode);
                        try {
                            this.childrenNodes.put(parentId, forest);
                        } catch (Exception e) {
                            LOGGER.error("Problem adding child", e);
                        }
                    }
                }
            }

            if (serviceCalls.contains(this.service)) {

                final Collection<Field> subfields = splitFields.get(this.service);
                return getFieldsJson(subfields, parentId, targetName);
            }
        }
        return null;
    }

    private JsonNode getArgsJson(final List<Argument> args) {

        final var mapper = new ObjectMapper();
        final var argNode = mapper.createObjectNode();

        for (final Argument arg : args) {

            final var value = arg.getValue();
            final var type = value.getClass().getSimpleName();

            switch (type) {
                case "IntValue": // TODO - ???
                    long longValue = ((IntValue) value).getValue().longValueExact();
                    argNode.put(arg.getName(), longValue);
                    break;
                case "StringValue":
                    final var stringValue = ((StringValue) value).getValue();
                    argNode.put(arg.getName(), stringValue);
                    break;
                case "BooleanValue":
                    final var booleanValue = ((BooleanValue) value).isValue();
                    argNode.put(arg.getName(), booleanValue);
                    break;
                case "ArrayValue":
                    final List<Node> nodes = value.getChildren();
                    final var arrayNode = mapper.createArrayNode();

                    for (final Node node : nodes)  {
                        final var v = ((StringValue) node).getValue();
                        arrayNode.add(v);
                    }
                    argNode.set(arg.getName(), arrayNode);
                    break;
                default:
                    break; // this might cause problems
            }

        }

        return argNode;
    }

    @SuppressWarnings("checkstyle:NestedIfDepth")
    private Map<Service, Collection<Field>> getPartitionedFields(final String parentType, final SelectionSet selectionSet) {

        final Map<Service, Collection<Field>> result = new HashMap<>();

        final Collection<Selection> selections = selectionSet.getSelections();

        for (final Selection child : selections) {
            if (child.getClass().getSimpleName().equals("Field")) {
                final var field = (Field) child;
                if (hgqlSchema.getFields().containsKey(field.getName())) {
                    final Service serviceConfig;
                    if (hgqlSchema.getTypes().containsKey(parentType)) {
                        if (hgqlSchema.getTypes().get(parentType).getFields().containsKey(field.getName())) {
                            serviceConfig = hgqlSchema.getTypes().get(parentType).getFields().get(field.getName()).getService();
                        } else {
                            throw new HGQLConfigurationException("Schema is missing field '"
                                    + parentType + "::" + field.getName() + "'");
                        }
                    } else {
                        throw new HGQLConfigurationException("Schema is missing type '" + parentType + "'");
                    }

                    if (result.containsKey(serviceConfig)) {
                        result.get(serviceConfig).add(field);
                    } else {
                        final Collection<Field> newFieldSet = new HashSet<>();
                        newFieldSet.add(field);
                        result.put(serviceConfig, newFieldSet);
                    }
                }
            }
        }

        return result;
    }

    private String createId() {
        return "execution-" + UUID.randomUUID();
    }

    Model generateTreeModel(final Collection<String> input) {

        final var executionResult = service.executeQuery(query, input, childrenNodes.keySet(), rootType, hgqlSchema);
        final Map<String, Collection<String>> resultSet = executionResult.getResultSet();
        final var model = executionResult.getModel();
        final Collection<Model> computedModels = new HashSet<>();
        //    StoredModel.getInstance().add(model);
        final Collection<String> vars = resultSet.keySet();
        final var executor = Executors.newFixedThreadPool(50);
        final Collection<Future<Model>> futureModels = new HashSet<>();
        vars.forEach(var -> {
            final var executionChildren = this.childrenNodes.get(var);
            if (executionChildren.getForest().size() > 0) {
                final Collection<String> values = resultSet.get(var);
                executionChildren.getForest().forEach(node -> {
                    final var childExecution = new FetchingExecution(values, node);
                    futureModels.add(executor.submit(childExecution));
                });
            }
        });

        futureModels.forEach(futureModel -> {
            try {
                computedModels.add(futureModel.get());
            } catch (InterruptedException
                    | ExecutionException e) {
                LOGGER.error("Problem adding execution result", e);
            }
        });
        computedModels.forEach(model::add);
        return model;
    }
}
