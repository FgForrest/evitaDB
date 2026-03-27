/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.expression.trigger;

import io.evitadb.api.query.expression.visitor.AccessedDataFinder;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.core.expression.proxy.ExpressionProxyDescriptor;
import io.evitadb.core.expression.proxy.ExpressionProxyFactory;
import io.evitadb.core.expression.query.ExpressionToQueryTranslator;
import io.evitadb.core.expression.trigger.ExpressionDependencyClassifier.DependencyKey;
import io.evitadb.core.expression.trigger.ExpressionDependencyClassifier.LocalDependencies;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

/**
 * Stateless utility that builds {@link HistogramExpressionTrigger} instances from
 * {@link ReferenceSchemaContract} data. Processes `bucketedPartially` condition expressions
 * and histogram index definitions to produce triggers with combined dependency paths.
 *
 * Follows the same classification pattern as {@link FacetExpressionTriggerFactory} for
 * cross-entity dependency detection, extended with value expression path analysis and
 * {@link HistogramValueDescriptor} metadata.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HistogramExpressionTriggerFactory {

	/**
	 * Builds trigger instances for the given reference schema.
	 *
	 * @param ownerEntityType the entity type that owns the reference (e.g. "product")
	 * @param referenceSchema the reference schema to build triggers from
	 * @param schemaResolver  function resolving entity type name to entity schema
	 * @return list of triggers (empty if no histogram definitions exist)
	 */
	@Nonnull
	public static List<HistogramExpressionTrigger> buildTriggersForReference(
		@Nonnull String ownerEntityType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver
	) {
		final Map<Scope, Map<String, HistogramIndexDefinition>> allDefinitions =
			referenceSchema.getAllHistogramIndexDefinitions();
		if (allDefinitions.isEmpty()) {
			return List.of();
		}

		final String referenceName = referenceSchema.getName();
		final Map<Scope, Expression> conditionExpressions =
			referenceSchema.getBucketedPartiallyInScopes();
		final List<HistogramExpressionTrigger> triggers =
			new ArrayList<>(allDefinitions.size() << 2);

		for (final Entry<Scope, Map<String, HistogramIndexDefinition>> scopeEntry :
			allDefinitions.entrySet()) {
			final Scope scope = scopeEntry.getKey();
			final Map<String, HistogramIndexDefinition> definitions = scopeEntry.getValue();
			final Expression conditionExpression = conditionExpressions.get(scope);

			for (final Entry<String, HistogramIndexDefinition> defEntry : definitions.entrySet()) {
				final String histogramName = defEntry.getKey();
				final HistogramIndexDefinition definition = defEntry.getValue();
				final Expression valueExpression = definition.valueExpression();

				// count histogram without value expression -- no triggers needed
				if (valueExpression == null) {
					continue;
				}

				final HistogramValueDescriptor valueResolution =
					HistogramValueDescriptorFactory.build(
						valueExpression, referenceName, histogramName,
						referenceSchema, schemaResolver
					);

				buildTriggersForHistogram(
					ownerEntityType, referenceSchema, referenceName, scope,
					conditionExpression, valueExpression,
					histogramName, valueResolution, triggers
				);
			}
		}

		return List.copyOf(triggers);
	}

	/**
	 * Builds triggers for a single histogram definition in a specific scope.
	 */
	private static void buildTriggersForHistogram(
		@Nonnull String ownerEntityType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nullable Expression conditionExpression,
		@Nonnull Expression valueExpression,
		@Nonnull String histogramName,
		@Nonnull HistogramValueDescriptor valueResolution,
		@Nonnull List<HistogramExpressionTrigger> collector
	) {
		final List<List<PathItem>> valuePaths =
			AccessedDataFinder.findAccessedPaths(valueExpression);

		final List<List<PathItem>> conditionPaths;
		final ExpressionProxyDescriptor proxyDescriptor;
		final LocalDependencies localDeps;

		if (conditionExpression != null) {
			conditionPaths = AccessedDataFinder.findAccessedPaths(conditionExpression);
			proxyDescriptor = ExpressionProxyFactory.buildDescriptor(conditionExpression);
			localDeps = ExpressionDependencyClassifier.extractLocalDependencies(conditionPaths);
		} else {
			conditionPaths = List.of();
			proxyDescriptor = null;
			localDeps = new LocalDependencies(Set.of(), Set.of(), Set.of(), false);
		}

		final List<List<PathItem>> allPaths =
			new ArrayList<>(conditionPaths.size() + valuePaths.size());
		allPaths.addAll(conditionPaths);
		allPaths.addAll(valuePaths);

		final LinkedHashMap<DependencyKey, Set<String>> dependencyAttributes =
			ExpressionDependencyClassifier.classifyPaths(allPaths);

		if (dependencyAttributes.isEmpty()) {
			if (conditionExpression != null) {
				collector.add(new DefaultHistogramExpressionTrigger(
					ownerEntityType, referenceName, scope,
					localDeps.entityAttributes(), localDeps.referenceAttributes(),
					localDeps.associatedData(), localDeps.usesParent(),
					conditionExpression, proxyDescriptor,
					histogramName, valueResolution
				));
			} else {
				collector.add(new DefaultHistogramExpressionTrigger(
					ownerEntityType, referenceName, scope,
					histogramName, valueResolution
				));
			}
		} else {
			final FilterBy filterBy;
			if (conditionExpression != null) {
				filterBy = ExpressionToQueryTranslator.translate(
					conditionExpression, referenceName
				);
			} else {
				filterBy = null;
			}

			for (final Entry<DependencyKey, Set<String>> depEntry :
				dependencyAttributes.entrySet()) {
				final DependencyKey key = depEntry.getKey();
				final String mutatedEntityType =
					ExpressionDependencyClassifier.resolveMutatedEntityType(
						ownerEntityType, referenceSchema, key.type()
					);

				if (filterBy != null) {
					collector.add(new DefaultHistogramExpressionTrigger(
						ownerEntityType, referenceName, scope,
						mutatedEntityType, key.type(), key.referenceName(),
						depEntry.getValue(),
						localDeps.entityAttributes(), localDeps.referenceAttributes(),
						localDeps.associatedData(), localDeps.usesParent(),
						conditionExpression, proxyDescriptor, filterBy,
						histogramName, valueResolution
					));
				} else {
					collector.add(new DefaultHistogramExpressionTrigger(
						ownerEntityType, referenceName, scope,
						mutatedEntityType, key.type(), key.referenceName(),
						depEntry.getValue(),
						histogramName, valueResolution
					));
				}
			}
		}
	}

}
