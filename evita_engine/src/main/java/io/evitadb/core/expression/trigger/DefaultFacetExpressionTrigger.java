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

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.core.expression.proxy.ExpressionProxyDescriptor;
import io.evitadb.core.expression.proxy.ExpressionProxyFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Concrete implementation of {@link FacetExpressionTrigger} that extends {@link AbstractExpressionIndexTrigger}
 * with no additional fields. Acts as a pure type marker distinguishing facet triggers from histogram triggers
 * in the {@link io.evitadb.core.catalog.CatalogExpressionTriggerRegistry}.
 *
 * All expression/proxy/FilterBy infrastructure is inherited from the abstract base class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class DefaultFacetExpressionTrigger extends AbstractExpressionIndexTrigger
	implements FacetExpressionTrigger {

	/**
	 * Creates a new trigger for cross-entity evaluation (non-null {@link DependencyType} and
	 * {@link FilterBy}).
	 *
	 * @param ownerEntityType          entity type owning the reference (e.g., "product")
	 * @param referenceName            name of the reference carrying the expression
	 * @param scope                    scope this trigger applies to
	 * @param mutatedEntityType        entity type whose mutations fire this trigger
	 * @param dependencyType           cross-entity dependency classification
	 * @param dependentReferenceName   reference on the target entity whose attributes are read,
	 *                                 or `null` for entity-attribute dependencies
	 * @param dependentAttributes      attribute names on the mutated entity that the expression reads
	 * @param localEntityAttributes    entity-level attribute names the expression reads locally
	 * @param localReferenceAttributes reference-level attribute names the expression reads locally
	 * @param localAssociatedData      associated data names the expression reads locally
	 * @param usesParent               whether the expression reads the entity's parent
	 * @param expression               the parsed expression AST
	 * @param proxyDescriptor          pre-built proxy descriptor from {@link ExpressionProxyFactory}
	 * @param filterByConstraint       pre-translated FilterBy
	 */
	public DefaultFacetExpressionTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nullable String dependentReferenceName,
		@Nonnull Set<String> dependentAttributes,
		@Nonnull Set<String> localEntityAttributes,
		@Nonnull Set<String> localReferenceAttributes,
		@Nonnull Set<String> localAssociatedData,
		boolean usesParent,
		@Nonnull Expression expression,
		@Nonnull ExpressionProxyDescriptor proxyDescriptor,
		@Nonnull FilterBy filterByConstraint
	) {
		super(
			ownerEntityType, referenceName, scope,
			mutatedEntityType, dependencyType, dependentReferenceName,
			dependentAttributes,
			localEntityAttributes, localReferenceAttributes, localAssociatedData,
			usesParent, expression, proxyDescriptor, filterByConstraint
		);
	}

	/**
	 * Creates a new trigger for local-only evaluation (null {@link DependencyType}, no {@link FilterBy}).
	 *
	 * @param ownerEntityType          entity type owning the reference (e.g., "product")
	 * @param referenceName            name of the reference carrying the expression
	 * @param scope                    scope this trigger applies to
	 * @param localEntityAttributes    entity-level attribute names the expression reads locally
	 * @param localReferenceAttributes reference-level attribute names the expression reads locally
	 * @param localAssociatedData      associated data names the expression reads locally
	 * @param usesParent               whether the expression reads the entity's parent
	 * @param expression               the parsed expression AST
	 * @param proxyDescriptor          pre-built proxy descriptor from {@link ExpressionProxyFactory}
	 */
	public DefaultFacetExpressionTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull Set<String> localEntityAttributes,
		@Nonnull Set<String> localReferenceAttributes,
		@Nonnull Set<String> localAssociatedData,
		boolean usesParent,
		@Nonnull Expression expression,
		@Nonnull ExpressionProxyDescriptor proxyDescriptor
	) {
		super(
			ownerEntityType, referenceName, scope,
			localEntityAttributes, localReferenceAttributes, localAssociatedData,
			usesParent, expression, proxyDescriptor
		);
	}

}
