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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Stateless utility that builds {@link FacetExpressionTrigger} instances from
 * {@link ReferenceSchemaContract} data. The factory analyzes each expression's accessed data paths
 * via {@link AccessedDataFinder} to classify cross-entity dependencies, builds proxy descriptors
 * via {@link ExpressionProxyFactory}, and translates expressions to {@link FilterBy} constraint
 * trees via {@link ExpressionToQueryTranslator}.
 *
 * The factory builds triggers but does **not** register them in any registry — registration is the
 * caller's responsibility (see WBS-04).
 *
 * ## Trigger classification
 *
 * - **Local-only**: expression references only `$entity.*` and `$reference.attributes['x']` — a single
 * trigger with `getDependencyType() == null` is produced, usable only via `evaluate()`
 * - **Cross-entity**: expression references `$reference.referencedEntity.*`,
 * `$reference.groupEntity.*`, and/or `$entity.parentEntity.*` — one trigger per
 * {@link io.evitadb.index.mutation.DependencyType} is produced, each carrying the full
 * pre-translated {@link FilterBy} and the set of dependent attribute names
 * - **Mixed**: if an expression combines local and cross-entity paths, only cross-entity triggers
 * are produced (the local portion is captured in the full {@link FilterBy} tree)
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FacetExpressionTriggerFactory {

	/**
	 * Builds trigger instances for the given reference schema. Iterates
	 * {@link ReferenceSchemaContract#getFacetedPartiallyInScopes()} and produces one or more
	 * triggers per (scope, expression) pair depending on the expression's dependency profile.
	 *
	 * @param ownerEntityType the entity type that owns the reference (e.g. "product")
	 * @param referenceSchema the reference schema to build triggers from
	 * @return list of triggers (empty if the reference has no `facetedPartially` expressions)
	 */
	@Nonnull
	public static List<FacetExpressionTrigger> buildTriggersForReference(
		@Nonnull String ownerEntityType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final Map<Scope, Expression> expressions = referenceSchema.getFacetedPartiallyInScopes();
		if (expressions.isEmpty()) {
			return List.of();
		}

		final String referenceName = referenceSchema.getName();
		final List<FacetExpressionTrigger> triggers = new ArrayList<>(expressions.size() << 1);

		for (final Entry<Scope, Expression> entry : expressions.entrySet()) {
			final Scope scope = entry.getKey();
			final Expression expression = entry.getValue();
			buildTriggersForExpression(
				ownerEntityType, referenceSchema, referenceName, scope, expression, triggers
			);
		}

		return List.copyOf(triggers);
	}

	/**
	 * Builds triggers for a single (scope, expression) pair and appends them to the collector.
	 *
	 * @param ownerEntityType the entity type owning the reference
	 * @param referenceSchema the reference schema (used to derive the mutated entity type)
	 * @param referenceName   the reference name
	 * @param scope           the scope this expression applies to
	 * @param expression      the parsed expression AST
	 * @param collector       the list to append built triggers to
	 */
	private static void buildTriggersForExpression(
		@Nonnull String ownerEntityType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull Expression expression,
		@Nonnull List<FacetExpressionTrigger> collector
	) {
		final List<List<PathItem>> paths = AccessedDataFinder.findAccessedPaths(expression);
		final ExpressionProxyDescriptor proxyDescriptor =
			ExpressionProxyFactory.buildDescriptor(expression);

		// classify paths into dependency keys and collect dependent attributes per key
		final LinkedHashMap<DependencyKey, Set<String>> dependencyAttributes =
			ExpressionDependencyClassifier.classifyPaths(paths);

		// extract local dependencies from the same paths
		final LocalDependencies localDeps =
			ExpressionDependencyClassifier.extractLocalDependencies(paths);

		if (dependencyAttributes.isEmpty()) {
			// purely local expression — build a local-only trigger (no FilterBy, no DependencyType)
			collector.add(
				new DefaultFacetExpressionTrigger(
					ownerEntityType, referenceName, scope,
					localDeps.entityAttributes(), localDeps.referenceAttributes(),
					localDeps.associatedData(), localDeps.usesParent(),
					expression, proxyDescriptor
				)
			);
		} else {
			// cross-entity expression — translate to FilterBy once, reuse for all dependency keys
			final FilterBy filterBy =
				ExpressionToQueryTranslator.translate(expression, referenceName);
			for (final Entry<DependencyKey, Set<String>> depEntry :
				dependencyAttributes.entrySet()) {
				final DependencyKey key = depEntry.getKey();
				final String mutatedEntityType =
					ExpressionDependencyClassifier.resolveMutatedEntityType(
						ownerEntityType, referenceSchema, key.type()
					);
				collector.add(
					new DefaultFacetExpressionTrigger(
						ownerEntityType, referenceName, scope,
						mutatedEntityType, key.type(), key.referenceName(),
						depEntry.getValue(),
						localDeps.entityAttributes(), localDeps.referenceAttributes(),
						localDeps.associatedData(), localDeps.usesParent(),
						expression, proxyDescriptor, filterBy
					)
				);
			}
		}
	}

}
