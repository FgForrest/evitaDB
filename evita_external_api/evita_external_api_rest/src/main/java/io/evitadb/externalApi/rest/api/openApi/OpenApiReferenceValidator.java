/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.openApi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Validates component schema references throughout an OpenAPI document, including inline schemas and paths.
 * Schema identity, rather than its optional name, prevents cycles without skipping distinct inline objects.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class OpenApiReferenceValidator {
	/**
	 * Schemas already walked, compared by identity: `Schema#hashCode` descends into nested schemas, so an
	 * equality-based set would never terminate on a cycle.
	 */
	private final Set<Schema<?>> visitedSchemas = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Paths already walked, compared by identity for the same reason, so that a callback leading back to the path
	 * that declares it terminates.
	 */
	private final Set<PathItem> visitedPaths = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Names of component schemas that are referenced but not defined, filled by {@link #validateSchemaReferences()}.
	 */
	@Getter private final HashSet<String> missingSchemas = createHashSet(100);
	@Nonnull private final OpenAPI openAPI;
	/**
	 * Component schema registry that every reference is resolved against.
	 */
	@SuppressWarnings("rawtypes") private final Map<String, Schema> schemas;

	/**
	 * Creates a validator for the document and its component schema registry.
	 *
	 * @param openAPI document with components and a component schema registry, neither of which may be `null`
	 */
	public OpenApiReferenceValidator(@Nonnull OpenAPI openAPI) {
		this.openAPI = openAPI;
		this.schemas = openAPI.getComponents().getSchemas();
	}

	/**
	 * Returns the names of missing component schemas reachable from components, paths or webhooks.
	 */
	@Nonnull
	public Set<String> validateSchemaReferences() {
		this.schemas.values().forEach(this::validateSchemaReferences);
		final Components components = this.openAPI.getComponents();
		visitValues(components.getParameters(), this::validateParameter);
		visitValues(components.getRequestBodies(), this::validateRequestBody);
		visitValues(components.getResponses(), this::validateResponse);
		visitValues(components.getHeaders(), this::validateHeader);
		visitValues(components.getCallbacks(), this::validateCallback);
		visitValues(components.getPathItems(), this::validatePath);
		visitValues(this.openAPI.getPaths(), this::validatePath);
		visitValues(this.openAPI.getWebhooks(), this::validatePath);
		return this.missingSchemas;
	}

	/**
	 * Visits every schema-valued keyword exposed by swagger-models, including OpenAPI 3.1 keywords, and resolves
	 * both `$ref` and discriminator mapping values. A reference can have sibling keywords, so finding one must not
	 * stop traversal.
	 */
	private void validateSchemaReferences(@Nullable Schema<?> schema) {
		if (schema == null || !this.visitedSchemas.add(schema)) {
			return;
		}
		if (schema.get$ref() != null) {
			validateReference(schema.get$ref());
		}
		if (schema.getDiscriminator() != null) {
			visitValues(schema.getDiscriminator().getMapping(), this::validateReference);
		}
		validateSchemaReferences(schema.getItems());
		validateSchemaReferences(schema.getNot());
		validateSchemaReferences(schema.getContains());
		validateSchemaReferences(schema.getContentSchema());
		validateSchemaReferences(schema.getPropertyNames());
		validateSchemaReferences(schema.getUnevaluatedProperties());
		validateSchemaReferences(schema.getAdditionalItems());
		validateSchemaReferences(schema.getUnevaluatedItems());
		validateSchemaReferences(schema.getIf());
		validateSchemaReferences(schema.getThen());
		validateSchemaReferences(schema.getElse());
		if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
			validateSchemaReferences(additionalProperties);
		}
		visitValues(schema.getProperties(), this::validateSchemaReferences);
		visitValues(schema.getPatternProperties(), this::validateSchemaReferences);
		visitValues(schema.getDependentSchemas(), this::validateSchemaReferences);
		visitEach(schema.getAllOf(), this::validateSchemaReferences);
		visitEach(schema.getAnyOf(), this::validateSchemaReferences);
		visitEach(schema.getOneOf(), this::validateSchemaReferences);
		visitEach(schema.getPrefixItems(), this::validateSchemaReferences);
	}

	/**
	 * Records the target of a `$ref` or discriminator mapping value when the component registry lacks it.
	 */
	private void validateReference(@Nonnull String reference) {
		final String schemaName = SchemaUtils.getSchemaNameFromReference(reference);
		if (!this.schemas.containsKey(schemaName)) {
			this.missingSchemas.add(schemaName);
		}
	}

	/**
	 * Visits path-level parameters and every operation, following callbacks with cycle protection.
	 */
	private void validatePath(@Nonnull PathItem path) {
		if (!this.visitedPaths.add(path)) {
			return;
		}
		visitEach(path.getParameters(), this::validateParameter);
		for (final Operation operation : path.readOperations()) {
			visitEach(operation.getParameters(), this::validateParameter);
			validateRequestBody(operation.getRequestBody());
			visitValues(operation.getResponses(), this::validateResponse);
			visitValues(operation.getCallbacks(), this::validateCallback);
		}
	}

	/**
	 * Visits every path a callback can call, whether declared on an operation or in the components.
	 */
	private void validateCallback(@Nonnull Callback callback) {
		visitValues(callback, this::validatePath);
	}

	/**
	 * Visits both schema and content forms of an operation or path parameter.
	 */
	private void validateParameter(@Nonnull Parameter parameter) {
		validateSchemaReferences(parameter.getSchema());
		validateContent(parameter.getContent());
	}

	/**
	 * Visits the schemas of a request body when present.
	 */
	private void validateRequestBody(@Nullable RequestBody requestBody) {
		if (requestBody != null) {
			validateContent(requestBody.getContent());
		}
	}

	/**
	 * Visits response payloads and response headers.
	 */
	private void validateResponse(@Nonnull ApiResponse response) {
		validateContent(response.getContent());
		visitValues(response.getHeaders(), this::validateHeader);
	}

	/**
	 * Visits both schema and content forms of a header.
	 */
	private void validateHeader(@Nonnull Header header) {
		validateSchemaReferences(header.getSchema());
		validateContent(header.getContent());
	}

	/**
	 * Visits media type schemas and headers nested inside encoding definitions.
	 */
	private void validateContent(@Nullable Content content) {
		visitValues(content, mediaType -> {
			validateSchemaReferences(mediaType.getSchema());
			visitValues(mediaType.getEncoding(), encoding -> visitValues(encoding.getHeaders(), this::validateHeader));
		});
	}

	/**
	 * Applies the visitor to an optional map of model objects.
	 */
	private static <T> void visitValues(
		@Nullable Map<String, T> values, @Nonnull Consumer<T> visitor
	) {
		if (values != null) {
			values.values().forEach(visitor);
		}
	}

	/**
	 * Applies the visitor to an optional collection of model objects.
	 */
	private static <T> void visitEach(
		@Nullable Iterable<T> values, @Nonnull Consumer<T> visitor
	) {
		if (values != null) {
			values.forEach(visitor);
		}
	}
}
