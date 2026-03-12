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

import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.visitor.AccessedDataFinder;
import io.evitadb.api.query.expression.visitor.ElementPathItem;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.ExpressionProxyDescriptor;
import io.evitadb.core.expression.proxy.ExpressionProxyFactory;
import io.evitadb.core.expression.query.ExpressionToQueryTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.FacetExpressionTrigger;
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

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

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
 * - **Cross-entity**: expression references `$reference.referencedEntity.*` and/or
 * `$reference.groupEntity.*` — one trigger per {@link DependencyType} is produced, each carrying
 * the full pre-translated {@link FilterBy} and the set of dependent attribute names
 * - **Mixed**: if an expression combines local and cross-entity paths, only cross-entity triggers
 * are produced (the local portion is captured in the full {@link FilterBy} tree)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
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
			buildTriggersForExpression(ownerEntityType, referenceName, scope, expression, triggers);
		}

		return List.copyOf(triggers);
	}

	/**
	 * Builds triggers for a single (scope, expression) pair and appends them to the collector.
	 *
	 * @param ownerEntityType the entity type owning the reference
	 * @param referenceName   the reference name
	 * @param scope           the scope this expression applies to
	 * @param expression      the parsed expression AST
	 * @param collector       the list to append built triggers to
	 */
	private static void buildTriggersForExpression(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull Expression expression,
		@Nonnull List<FacetExpressionTrigger> collector
	) {
		final List<List<PathItem>> paths = AccessedDataFinder.findAccessedPaths(expression);
		final ExpressionProxyDescriptor proxyDescriptor = ExpressionProxyFactory.buildDescriptor(expression);

		// classify paths into dependency types and collect dependent attributes per type
		final LinkedHashMap<DependencyType, Set<String>> dependencyAttributes = classifyPaths(paths);

		if (dependencyAttributes.isEmpty()) {
			// purely local expression — build a local-only trigger (no FilterBy, no DependencyType)
			collector.add(new FacetExpressionTriggerImpl(
				ownerEntityType, referenceName, scope, expression, proxyDescriptor
			));
		} else {
			// cross-entity expression — translate to FilterBy once, reuse for all dependency types
			final FilterBy filterBy = ExpressionToQueryTranslator.translate(expression, referenceName);
			for (final Entry<DependencyType, Set<String>> depEntry : dependencyAttributes.entrySet()) {
				collector.add(
					new FacetExpressionTriggerImpl(
						ownerEntityType, referenceName, scope,
						depEntry.getKey(), depEntry.getValue(),
						expression, proxyDescriptor, filterBy
					)
				);
			}
		}
	}

	/**
	 * Classifies accessed data paths into {@link DependencyType} buckets and collects the dependent
	 * attribute names for each. Paths that reference only local data (`$entity.*`,
	 * `$reference.attributes['x']`) are ignored — they do not produce cross-entity dependencies.
	 *
	 * @param paths the accessed data paths from {@link AccessedDataFinder#findAccessedPaths}
	 * @return map from dependency type to the set of dependent attribute names (empty if local-only)
	 */
	@Nonnull
	private static LinkedHashMap<DependencyType, Set<String>> classifyPaths(
		@Nonnull List<List<PathItem>> paths
	) {
		final LinkedHashMap<DependencyType, Set<String>> result = createLinkedHashMap(2);

		for (final List<PathItem> path : paths) {
			final DependencyType depType = detectDependencyType(path);
			if (depType != null) {
				final String attributeName = extractDependentAttribute(path);
				if (attributeName != null) {
					result.computeIfAbsent(
						depType,
						k -> createLinkedHashSet(4)
					).add(attributeName);
				}
			}
		}

		return result;
	}

	/**
	 * Detects the {@link DependencyType} for a single path. Returns `null` for local-only paths.
	 *
	 * A cross-entity path starts with `$reference` (VariablePathItem) followed by either
	 * `referencedEntity` or `groupEntity` (IdentifierPathItem).
	 *
	 * @param path the accessed data path
	 * @return the dependency type, or `null` if the path is local-only
	 */
	@Nullable
	private static DependencyType detectDependencyType(@Nonnull List<PathItem> path) {
		// minimum cross-entity path: [$reference, referencedEntity/groupEntity, attributes, attrName]
		if (path.size() < 2) {
			return null;
		}

		final PathItem first = path.get(0);
		final PathItem second = path.get(1);

		if (
			first instanceof VariablePathItem variable
				&& ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(variable.value())
				&& second instanceof IdentifierPathItem identifier
		) {
			if (ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY.equals(identifier.value())) {
				return DependencyType.REFERENCED_ENTITY_ATTRIBUTE;
			} else if (ReferenceContractAccessor.GROUP_ENTITY_PROPERTY.equals(identifier.value())) {
				return DependencyType.GROUP_ENTITY_ATTRIBUTE;
			}
		}

		return null;
	}

	/**
	 * Extracts the dependent attribute name from a cross-entity path. Searches for an `attributes`
	 * or `localizedAttributes` identifier followed by an {@link ElementPathItem} containing the
	 * attribute name.
	 *
	 * @param path the accessed data path (must be a cross-entity path)
	 * @return the attribute name, or `null` if no attribute access is found in the path
	 */
	@Nullable
	private static String extractDependentAttribute(@Nonnull List<PathItem> path) {
		for (int i = 0; i < path.size() - 1; i++) {
			final PathItem item = path.get(i);
			if (
				item instanceof IdentifierPathItem identifier
					&& isAttributesProperty(identifier.value())
					&& path.get(i + 1) instanceof ElementPathItem element
			) {
				return element.value();
			}
		}
		return null;
	}

	/**
	 * Checks whether the given property name refers to an attributes accessor.
	 *
	 * @param propertyName the property name to check
	 * @return `true` if the name matches `attributes` or `localizedAttributes`
	 */
	private static boolean isAttributesProperty(@Nonnull String propertyName) {
		return EntityContractAccessor.ATTRIBUTES_PROPERTY.equals(propertyName)
			|| EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY.equals(propertyName);
	}

}
