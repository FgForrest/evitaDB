/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedHistogramIndexDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedBucketedPartiallyDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedFacetedPartiallyDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;
import io.evitadb.externalApi.api.resolver.mutation.PropertyObjectListMapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ancestor abstract implementation for {@link ReferenceSchemaMutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class ReferenceSchemaMutationConverter<M extends ReferenceSchemaMutation>
	extends SchemaMutationConverter<M> {

	protected ReferenceSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	/**
	 * Parses {@link ScopedFacetedPartially} array from the input using the given property descriptor.
	 * Each entry is expected to contain a scope and an optional expression string.
	 */
	@Nullable
	protected ScopedFacetedPartially[] parseFacetedPartially(
		@Nonnull Input input,
		@Nonnull PropertyDescriptor descriptor
	) {
		return input.getOptionalProperty(
			descriptor.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				descriptor,
				ScopedFacetedPartially.class,
				nestedInput -> {
					final String expressionString = nestedInput.getOptionalProperty(
						ScopedFacetedPartiallyDescriptor.EXPRESSION.name()
					);
					final Expression expression = expressionString != null
						? ExpressionFactory.parse(expressionString)
						: null;
					return new ScopedFacetedPartially(
						nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
						expression
					);
				}
			)
		);
	}

	/**
	 * Pre-serializes {@link ScopedFacetedPartially} array into the output, converting
	 * {@link Expression} objects to their string representation. This must be called
	 * before the reflection-based {@code super.convertToOutput()} because {@link Expression}
	 * is not a supported serialization type in {@link Output}.
	 */
	protected static void serializeFacetedPartially(
		@Nullable ScopedFacetedPartially[] partially,
		@Nonnull Output output
	) {
		if (partially != null) {
			final List<Map<String, Object>> serialized = new ArrayList<>(partially.length);
			for (ScopedFacetedPartially entry : partially) {
				final Map<String, Object> entryMap = new LinkedHashMap<>(2);
				entryMap.put("scope", entry.scope());
				final Expression expr = entry.expression();
				entryMap.put("expression", expr != null ? expr.toExpressionString() : null);
				serialized.add(entryMap);
			}
			output.setProperty("facetedPartiallyInScopes", serialized);
		}
	}

	/**
	 * Parses {@link ScopedHistogramIndexDefinition} array from the input using the given property descriptor.
	 * Each entry is expected to contain a scope, a non-null index name, and an optional value expression string.
	 */
	@Nullable
	protected ScopedHistogramIndexDefinition[] parseBucketedHistogram(
		@Nonnull Input input,
		@Nonnull PropertyDescriptor descriptor
	) {
		return input.getOptionalProperty(
			descriptor.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				descriptor,
				ScopedHistogramIndexDefinition.class,
				nestedInput -> {
					final String valueExpressionString = nestedInput.getOptionalProperty(
						ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name()
					);
					final Expression valueExpression = valueExpressionString != null
						? ExpressionFactory.parse(valueExpressionString)
						: null;
					return new ScopedHistogramIndexDefinition(
						nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
						nestedInput.getProperty(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX),
						valueExpression
					);
				}
			)
		);
	}

	/**
	 * Pre-serializes {@link ScopedHistogramIndexDefinition} array into the output, converting
	 * {@link Expression} objects to their string representation. This must be called
	 * before the reflection-based {@code super.convertToOutput()} because {@link Expression}
	 * is not a supported serialization type in {@link Output}.
	 */
	protected static void serializeBucketedHistogram(
		@Nullable ScopedHistogramIndexDefinition[] bucketedInScopes,
		@Nonnull Output output
	) {
		if (bucketedInScopes != null) {
			final List<Map<String, Object>> serialized = new ArrayList<>(bucketedInScopes.length);
			for (ScopedHistogramIndexDefinition entry : bucketedInScopes) {
				final Map<String, Object> entryMap = new LinkedHashMap<>(3);
				entryMap.put("scope", entry.scope());
				entryMap.put("nameOfTheIndex", entry.nameOfTheIndex());
				final Expression expr = entry.valueExpression();
				entryMap.put("valueExpression", expr != null ? expr.toExpressionString() : null);
				serialized.add(entryMap);
			}
			output.setProperty("bucketedInScopes", serialized);
		}
	}

	/**
	 * Parses {@link ScopedBucketedPartially} array from the input using the given property descriptor.
	 * Each entry is expected to contain a scope and an optional expression string.
	 */
	@Nullable
	protected ScopedBucketedPartially[] parseBucketedPartially(
		@Nonnull Input input,
		@Nonnull PropertyDescriptor descriptor
	) {
		return input.getOptionalProperty(
			descriptor.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				descriptor,
				ScopedBucketedPartially.class,
				nestedInput -> {
					final String expressionString = nestedInput.getOptionalProperty(
						ScopedBucketedPartiallyDescriptor.EXPRESSION.name()
					);
					final Expression expression = expressionString != null
						? ExpressionFactory.parse(expressionString)
						: null;
					return new ScopedBucketedPartially(
						nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
						expression
					);
				}
			)
		);
	}

	/**
	 * Pre-serializes {@link ScopedBucketedPartially} array into the output, converting
	 * {@link Expression} objects to their string representation. This must be called
	 * before the reflection-based {@code super.convertToOutput()} because {@link Expression}
	 * is not a supported serialization type in {@link Output}.
	 */
	protected static void serializeBucketedPartially(
		@Nullable ScopedBucketedPartially[] partially,
		@Nonnull Output output
	) {
		if (partially != null) {
			final List<Map<String, Object>> serialized = new ArrayList<>(partially.length);
			for (ScopedBucketedPartially entry : partially) {
				final Map<String, Object> entryMap = new LinkedHashMap<>(2);
				entryMap.put("scope", entry.scope());
				final Expression expr = entry.expression();
				entryMap.put("expression", expr != null ? expr.toExpressionString() : null);
				serialized.add(entryMap);
			}
			output.setProperty("bucketedPartiallyInScopes", serialized);
		}
	}
}
