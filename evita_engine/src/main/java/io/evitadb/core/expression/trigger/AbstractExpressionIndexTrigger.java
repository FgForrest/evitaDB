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
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator;
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator.InstantiationResult;
import io.evitadb.core.expression.proxy.ExpressionVariableContext;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Abstract base class for expression-based index trigger implementations. Encapsulates the common infrastructure
 * shared by both facet and histogram triggers: all fields, constructor variants, all
 * {@link ExpressionIndexTrigger} method implementations, the {@link #evaluate} method, and
 * result conversion.
 *
 * Supports three evaluation modes:
 *
 * - **Cross-entity with condition** -- expression is non-null, FilterBy is pre-translated
 * - **Local-only with condition** -- expression is non-null, no FilterBy
 * - **Unconditional** -- expression is null, `evaluate()` returns `true` unconditionally
 *
 * This class is immutable and thread-safe.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class AbstractExpressionIndexTrigger implements ExpressionIndexTrigger {

	/**
	 * Entity type owning the reference with the conditional expression (e.g., `"Product"`).
	 */
	@Nonnull private final String ownerEntityType;
	/**
	 * Name of the reference carrying the conditional expression (e.g., `"parameterValues"`).
	 */
	@Nonnull private final String referenceName;
	/**
	 * Scope in which this trigger applies. A reference with expressions in multiple scopes produces
	 * one trigger per scope.
	 */
	@Nonnull private final Scope scope;
	/**
	 * Entity type whose mutations trigger re-evaluation of the expression. For cross-entity triggers
	 * this is the referenced or group entity type (e.g., `"ParameterGroup"`); `null` for local-only
	 * triggers that depend solely on the owner entity's own data.
	 */
	@Nullable private final String mutatedEntityType;
	/**
	 * Classifies the cross-entity relationship between the mutated entity and the owner entity
	 * (e.g., {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE} or
	 * {@link DependencyType#GROUP_ENTITY_ATTRIBUTE}). `null` for local-only triggers.
	 */
	@Nullable private final DependencyType dependencyType;
	/**
	 * Name of the reference on the mutated entity whose attributes the expression reads. Non-null only
	 * for {@link DependencyType#REFERENCED_ENTITY_REFERENCE_ATTRIBUTE} and
	 * {@link DependencyType#GROUP_ENTITY_REFERENCE_ATTRIBUTE} dependencies; `null` otherwise.
	 */
	@Nullable private final String dependentReferenceName;
	/**
	 * Attribute names on the mutated (remote) entity that this expression reads. Used by the detection
	 * step to skip triggers whose dependent attributes were not changed by the current mutation. Empty
	 * for local-only triggers.
	 */
	@Nonnull private final Set<String> dependentAttributes;
	/**
	 * Entity-level attribute names read from the owner entity (e.g., `$entity.attributes['code']`).
	 * Used by local re-evaluation dispatch to determine whether an `UpsertAttributeMutation` should
	 * trigger expression re-evaluation. Empty if the expression does not reference any entity attributes.
	 */
	@Nonnull private final Set<String> localEntityAttributes;
	/**
	 * Reference-level attribute names read from the owner's reference
	 * (e.g., `$reference.attributes['priority']`). Used by local re-evaluation dispatch to determine
	 * whether an `UpsertReferenceAttributeMutation` should trigger expression re-evaluation. Empty if
	 * the expression does not reference any reference attributes.
	 */
	@Nonnull private final Set<String> localReferenceAttributes;
	/**
	 * Associated data names read from the owner entity (e.g., `$entity.associatedData['description']`).
	 * Used by local re-evaluation dispatch to determine whether an associated data mutation should
	 * trigger expression re-evaluation. Empty if the expression does not reference any associated data.
	 */
	@Nonnull private final Set<String> localAssociatedData;
	/**
	 * `true` if the expression reads the owner entity's parent (e.g., `$entity.parentEntity`). Used by
	 * local re-evaluation dispatch to determine whether a `SetParentMutation` or `RemoveParentMutation`
	 * should trigger expression re-evaluation.
	 */
	private final boolean usesParent;
	/**
	 * Compiled expression to evaluate at trigger time. `null` for unconditional triggers where
	 * the index entry always exists (the trigger only tracks cross-entity value dependencies).
	 */
	@Nullable private final Expression expression;
	/**
	 * Pre-built descriptor for instantiating Proxycian proxy classes backed by StoragePart data.
	 * Contains method classifications, storage part recipes, and proxy class references needed to
	 * create `$entity` and `$reference` variable bindings for local evaluation. `null` when
	 * {@link #expression} is `null`.
	 */
	@Nullable private final ExpressionProxyDescriptor proxyDescriptor;
	/**
	 * Pre-translated {@link FilterBy} constraint tree built at schema load time by
	 * `ExpressionToQueryTranslator`. Used for index-based cross-entity evaluation — the executor
	 * parameterizes it by injecting a PK-scoping clause for the specific mutated entity. `null` for
	 * local-only and unconditional triggers.
	 */
	@Nullable private final FilterBy filterByConstraint;

	/**
	 * Cross-entity constructor (non-null {@link DependencyType} and {@link FilterBy}).
	 */
	protected AbstractExpressionIndexTrigger(
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
		@Nullable Expression expression,
		@Nullable ExpressionProxyDescriptor proxyDescriptor,
		@Nonnull FilterBy filterByConstraint
	) {
		this.ownerEntityType = ownerEntityType;
		this.referenceName = referenceName;
		this.scope = scope;
		this.mutatedEntityType = mutatedEntityType;
		this.dependencyType = dependencyType;
		this.dependentReferenceName = dependentReferenceName;
		this.dependentAttributes = Set.copyOf(dependentAttributes);
		this.localEntityAttributes = Set.copyOf(localEntityAttributes);
		this.localReferenceAttributes = Set.copyOf(localReferenceAttributes);
		this.localAssociatedData = Set.copyOf(localAssociatedData);
		this.usesParent = usesParent;
		this.expression = expression;
		this.proxyDescriptor = proxyDescriptor;
		this.filterByConstraint = filterByConstraint;
	}

	/**
	 * Local-only constructor (null {@link DependencyType}, no {@link FilterBy}).
	 */
	protected AbstractExpressionIndexTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull Set<String> localEntityAttributes,
		@Nonnull Set<String> localReferenceAttributes,
		@Nonnull Set<String> localAssociatedData,
		boolean usesParent,
		@Nullable Expression expression,
		@Nullable ExpressionProxyDescriptor proxyDescriptor
	) {
		this.ownerEntityType = ownerEntityType;
		this.referenceName = referenceName;
		this.scope = scope;
		this.mutatedEntityType = null;
		this.dependencyType = null;
		this.dependentReferenceName = null;
		this.dependentAttributes = Set.of();
		this.localEntityAttributes = Set.copyOf(localEntityAttributes);
		this.localReferenceAttributes = Set.copyOf(localReferenceAttributes);
		this.localAssociatedData = Set.copyOf(localAssociatedData);
		this.usesParent = usesParent;
		this.expression = expression;
		this.proxyDescriptor = proxyDescriptor;
		this.filterByConstraint = null;
	}

	/**
	 * Unconditional cross-entity constructor. Used when the condition expression is null but
	 * cross-entity value dependencies still need to be tracked in the registry.
	 */
	protected AbstractExpressionIndexTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nullable String dependentReferenceName,
		@Nonnull Set<String> dependentAttributes
	) {
		this.ownerEntityType = ownerEntityType;
		this.referenceName = referenceName;
		this.scope = scope;
		this.mutatedEntityType = mutatedEntityType;
		this.dependencyType = dependencyType;
		this.dependentReferenceName = dependentReferenceName;
		this.dependentAttributes = Set.copyOf(dependentAttributes);
		this.localEntityAttributes = Set.of();
		this.localReferenceAttributes = Set.of();
		this.localAssociatedData = Set.of();
		this.usesParent = false;
		this.expression = null;
		this.proxyDescriptor = null;
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
	public String getMutatedEntityType() {
		return this.mutatedEntityType;
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
	public Set<String> getLocalEntityAttributes() {
		return this.localEntityAttributes;
	}

	@Nonnull
	@Override
	public Set<String> getLocalReferenceAttributes() {
		return this.localReferenceAttributes;
	}

	@Nonnull
	@Override
	public Set<String> getLocalAssociatedData() {
		return this.localAssociatedData;
	}

	@Override
	public boolean usesParent() {
		return this.usesParent;
	}

	@Override
	public boolean hasFilterByConstraint() {
		return this.filterByConstraint != null;
	}

	@Nonnull
	@Override
	public FilterBy getFilterByConstraint() {
		if (this.filterByConstraint == null) {
			throw new UnsupportedOperationException(
				"Local-only or unconditional trigger for reference `" + this.referenceName +
					"` on entity `" + this.ownerEntityType +
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
		@Nonnull Function<String, EntitySchemaContract> schemaResolver,
		@Nonnull Scope scope
	) {
		// unconditional trigger -- no expression to evaluate
		if (this.expression == null) {
			return true;
		}
		// expression and proxyDescriptor are always paired -- if expression is non-null,
		// proxyDescriptor must be non-null too (enforced by all constructors)
		Assert.isPremiseValid(
			this.proxyDescriptor != null,
			() -> "Non-null expression requires non-null proxyDescriptor for reference `" +
				this.referenceName + "` on entity `" + this.ownerEntityType + "`"
		);

		final EntitySchemaContract entitySchema = schemaResolver.apply(this.ownerEntityType);
		final ReferenceSchemaContract referenceSchema =
			entitySchema.getReferenceOrThrowException(this.referenceName);

		final InstantiationResult instantiation = ExpressionProxyInstantiator.instantiate(
			this.proxyDescriptor,
			entitySchema,
			ownerEntityPK,
			referenceSchema,
			referenceKey,
			storageAccessor,
			schemaResolver,
			scope
		);

		// bind variables -- names without $ prefix matching VariableOperand lookup
		final Map<String, Object> variables = createHashMap(2);
		variables.put(EntityContractAccessor.ENTITY_VARIABLE_NAME, instantiation.entityProxy());
		if (instantiation.referenceProxy() != null) {
			variables.put(
				ReferenceContractAccessor.REFERENCE_VARIABLE_NAME, instantiation.referenceProxy()
			);
		} else if (this.proxyDescriptor.referencePartials() != null) {
			return false;
		}

		final ExpressionVariableContext context = new ExpressionVariableContext(variables);
		final Serializable result = this.expression.compute(context);

		return convertResult(result);
	}

	/**
	 * Converts the expression result to a boolean value.
	 *
	 * @param result the expression evaluation result
	 * @return `true` if the index entry should exist, `false` otherwise
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
				"Only boolean expressions are supported for conditional indexing."
		);
	}

}
