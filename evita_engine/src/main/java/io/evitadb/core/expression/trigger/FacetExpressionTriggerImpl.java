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
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.ExpressionProxyDescriptor;
import io.evitadb.core.expression.proxy.ExpressionProxyFactory;
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator;
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator.InstantiationResult;
import io.evitadb.core.expression.proxy.ExpressionVariableContext;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.FacetExpressionTrigger;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Concrete implementation of {@link FacetExpressionTrigger} that holds a parsed {@link Expression} together with
 * pre-built Proxycian proxy infrastructure ({@link ExpressionProxyDescriptor}) and a pre-translated {@link FilterBy}
 * constraint tree. Built once at schema load/change time and reused for all evaluations.
 *
 * Supports two evaluation modes:
 *
 * - **Per-entity evaluation** via {@link #evaluate} — fetches storage parts per the descriptor's recipe, instantiates
 *   pre-built proxy classes, binds them as expression variables, and computes the expression result
 * - **Index-based query evaluation** via {@link #getFilterByConstraint()} — returns the pre-translated
 *   {@link FilterBy} constraint for cross-entity triggers (no per-entity storage access needed)
 *
 * This class is immutable and thread-safe. The {@link #dependentAttributes} set is defensively copied at construction
 * time and exposed as an unmodifiable set.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FacetExpressionTriggerImpl implements FacetExpressionTrigger {

	@Nonnull private final String ownerEntityType;
	@Nonnull private final String referenceName;
	@Nonnull private final Scope scope;
	@Nullable private final DependencyType dependencyType;
	@Nullable private final String dependentReferenceName;
	@Nonnull private final Set<String> dependentAttributes;
	@Nonnull private final Expression expression;
	@Nonnull private final ExpressionProxyDescriptor proxyDescriptor;
	@Nullable private final FilterBy filterByConstraint;

	/**
	 * Creates a new trigger for cross-entity evaluation (non-null {@link DependencyType} and {@link FilterBy}).
	 *
	 * @param ownerEntityType        entity type owning the reference (e.g., "product")
	 * @param referenceName          name of the reference carrying the expression (e.g., "parameter")
	 * @param scope                  scope this trigger applies to
	 * @param dependencyType         cross-entity dependency classification
	 * @param dependentReferenceName name of the reference on the target entity whose attributes are read,
	 *                               or `null` for entity-attribute dependencies
	 * @param dependentAttributes    attribute names on the mutated entity that the expression reads
	 * @param expression             the parsed expression AST
	 * @param proxyDescriptor        pre-built proxy descriptor from {@link ExpressionProxyFactory}
	 * @param filterByConstraint     pre-translated FilterBy from `ExpressionToQueryTranslator`
	 */
	public FacetExpressionTriggerImpl(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull DependencyType dependencyType,
		@Nullable String dependentReferenceName,
		@Nonnull Set<String> dependentAttributes,
		@Nonnull Expression expression,
		@Nonnull ExpressionProxyDescriptor proxyDescriptor,
		@Nonnull FilterBy filterByConstraint
	) {
		this.ownerEntityType = ownerEntityType;
		this.referenceName = referenceName;
		this.scope = scope;
		this.dependencyType = dependencyType;
		this.dependentReferenceName = dependentReferenceName;
		this.dependentAttributes = Set.copyOf(dependentAttributes);
		this.expression = expression;
		this.proxyDescriptor = proxyDescriptor;
		this.filterByConstraint = filterByConstraint;
	}

	/**
	 * Creates a new trigger for local-only evaluation (null {@link DependencyType}, no {@link FilterBy}).
	 *
	 * @param ownerEntityType entity type owning the reference (e.g., "product")
	 * @param referenceName   name of the reference carrying the expression (e.g., "parameter")
	 * @param scope           scope this trigger applies to
	 * @param expression      the parsed expression AST
	 * @param proxyDescriptor pre-built proxy descriptor from {@link ExpressionProxyFactory}
	 */
	public FacetExpressionTriggerImpl(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull Expression expression,
		@Nonnull ExpressionProxyDescriptor proxyDescriptor
	) {
		this.ownerEntityType = ownerEntityType;
		this.referenceName = referenceName;
		this.scope = scope;
		this.dependencyType = null;
		this.dependentReferenceName = null;
		this.dependentAttributes = Set.of();
		this.expression = expression;
		this.proxyDescriptor = proxyDescriptor;
		this.filterByConstraint = null;
	}

	@Nonnull
	@Override
	public String getOwnerEntityType() {
		return this.ownerEntityType;
	}

	@Nonnull
	@Override
	public String getReferenceName() {
		return this.referenceName;
	}

	@Nonnull
	@Override
	public Scope getScope() {
		return this.scope;
	}

	@Nullable
	@Override
	public DependencyType getDependencyType() {
		return this.dependencyType;
	}

	@Nullable
	@Override
	public String getDependentReferenceName() {
		return this.dependentReferenceName;
	}

	@Nonnull
	@Override
	public Set<String> getDependentAttributes() {
		return this.dependentAttributes;
	}

	@Nonnull
	@Override
	public FilterBy getFilterByConstraint() {
		if (this.filterByConstraint == null) {
			throw new UnsupportedOperationException(
				"Local-only trigger for reference `" + this.referenceName +
					"` does not have a FilterBy constraint. " +
					"Use evaluate() for local evaluation instead."
			);
		}
		return this.filterByConstraint;
	}

	@Override
	public boolean evaluate(
		int ownerEntityPK,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull WritableEntityStorageContainerAccessor storageAccessor,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver
	) {
		final EntitySchemaContract entitySchema = schemaResolver.apply(this.ownerEntityType);
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(this.referenceName);

		final InstantiationResult instantiation = ExpressionProxyInstantiator.instantiate(
			this.proxyDescriptor,
			entitySchema,
			ownerEntityPK,
			referenceSchema,
			referenceKey,
			storageAccessor,
			schemaResolver
		);

		// bind variables — names without $ prefix matching VariableOperand lookup
		final Map<String, Object> variables = createHashMap(2);
		variables.put(EntityContractAccessor.ENTITY_VARIABLE_NAME, instantiation.entityProxy());
		if (instantiation.referenceProxy() != null) {
			variables.put(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME, instantiation.referenceProxy());
		}

		final ExpressionVariableContext context = new ExpressionVariableContext(variables);
		final Serializable result = this.expression.compute(context);

		return convertResult(result);
	}

	/**
	 * Converts the expression result to a boolean value.
	 *
	 * - `Boolean` result: used directly
	 * - `null` result: treated as `false` (missing/null data means condition not met)
	 * - Any other type: throws {@link ExpressionEvaluationException}
	 *
	 * @param result the expression evaluation result
	 * @return `true` if the index entry should exist, `false` otherwise
	 * @throws ExpressionEvaluationException if the result is not boolean or null
	 */
	private boolean convertResult(@Nullable Serializable result) {
		if (result == null) {
			return false;
		}
		if (result instanceof Boolean booleanResult) {
			return booleanResult;
		}
		throw new ExpressionEvaluationException(
			"Expression for reference `" + this.referenceName + "` returned " +
				result.getClass().getSimpleName() + " instead of Boolean.",
			"Expression for reference `" + this.referenceName + "` returned " +
				result.getClass().getSimpleName() + " instead of Boolean. " +
				"Only boolean expressions are supported for conditional facet indexing."
		);
	}

}
