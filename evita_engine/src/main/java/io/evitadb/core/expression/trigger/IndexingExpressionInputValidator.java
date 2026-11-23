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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.ExpressionPrecedence;
import io.evitadb.api.query.expression.bool.EqualsOperator;
import io.evitadb.api.query.expression.bool.NotEqualsOperator;
import io.evitadb.api.query.expression.coalesce.NullCoalesceOperator;
import io.evitadb.api.query.expression.coalesce.SpreadNullCoalesceOperator;
import io.evitadb.api.query.expression.function.FunctionOperator;
import io.evitadb.api.query.expression.function.processor.FunctionProcessor;
import io.evitadb.api.query.expression.function.processor.FunctionProcessorRegistry;
import io.evitadb.api.query.expression.object.ElementAccessStep;
import io.evitadb.api.query.expression.object.MethodInvocationStep;
import io.evitadb.api.query.expression.object.NullSafeAccessStep;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.object.ObjectOperationStep;
import io.evitadb.api.query.expression.object.PropertyAccessStep;
import io.evitadb.api.query.expression.object.SpreadAccessStep;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.BinaryExpressionNode;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.UnaryExpressionNode;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * Decides, from the shape of an indexing expression alone, whether evitaDB can keep the index built on it current.
 *
 * A histogram is kept current by withdrawing a reference's contribution before a mutation changes anything its
 * expressions read, and adding it again afterwards (see
 * `documentation/adr/2026-11-11-reference-histogram-net-change-per-run.md`). A
 * withdrawal asserts that the contribution it removes is in the index, so an expression reading data that no hook
 * withdraws on - or data evitaDB changes on its own - lets the index drift from the entities: the value computed
 * before the change stays in the index, and a later withdrawal finds the value it computes from the changed data
 * missing, which a session write reports or refuses by the configured policy. The histogram slots
 * (`bucketedPartially`, `assignedWhen` and the value expression) therefore admit only reads that are constant,
 * withdrawn on by an owner-side hook, or dispatched by the cross-entity path; everything else is refused. The facet
 * slot (`facetedPartially`) is checked against a reject list only: facet removals are presence-aware and skip an
 * absent entry, so an untracked facet input never meets the asserted withdrawal the histogram rules protect (facet
 * writes can still fail for other reasons), and only reads that can never be meaningful there - data evitaDB
 * maintains itself, an unstable locale order, `random()`, prices, properties that do not exist, names that are not
 * constants or are applied to a container that is the result of `??` or `*?`, reads that are always `null` and
 * unknown variables - are refused.
 *
 * The check walks the expression tree itself, every operand and every step of every access chain included, never a
 * flattened list of accessed paths, so that a read nested in a predicate, a key, a spread mapping or on the right of
 * `??` is found as well. It reads nothing but a reference schema or a mutation payload - no entity schema, collection
 * or catalog - so it runs on mutation payloads in the schema mutation preflight of `Catalog`
 * (`verifyEntitySchemaMutationsApplicable`), before a batch is applied, and on stored reference schemas as the first
 * step of both trigger factories, which is what makes a stored schema holding a refused read fail the catalog load.
 * The result of `??` may be either operand, so a step on it is judged on both; the fallback of `*?` replaces `null`
 * elements of a collection, so the result is the collection, and a step on an element is judged on the collection's
 * elements and on the fallback. Parentheses count for nothing where the engine's own analysis of the data an
 * expression reads looks through them - around `null`, a key, a receiver or an operand - but a name in parentheses is
 * no name the analysis knows. A data or `references` container stays a container whatever it passes through, so a
 * step on it must be a name written as a string constant wherever it is applied; a name applied to the result of `??`
 * or `*?` is refused even then, because the analysis records it on no path through the container. A refusal is an
 * {@link InvalidSchemaMutationException} naming the expression, its slot, the reference, the entity type, the scope,
 * the refused read and the reason.
 *
 * What the check guarantees is scoped. For a histogram expression it is that every read of an accepted expression is
 * constant, hooked or dispatched under the schema it was accepted with; a later schema change can alter what an
 * accepted read answers without any entity mutation (for instance a reference cardinality change), and that is the
 * schema rebuild's concern, not this check's. For a facet condition the check is a reject list and guarantees only
 * that an accepted condition reads nothing on it: an accepted facet condition may read data no hook tracks.
 *
 * The class is pure and stateless; every walk allocates its own state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class IndexingExpressionInputValidator {

	/**
	 * The function processor of `random()`, the only function whose result does not follow from its arguments.
	 */
	@Nonnull
	private static final FunctionProcessor RANDOM_FUNCTION = FunctionProcessorRegistry.getInstance()
		.getFunctionProcessor("random")
		.orElseThrow(() -> new GenericEvitaInternalError("The expression language must provide `random()`."));

	/**
	 * The alternatives of a sub-expression producing a plain value: the one immutable list `Walked.PLAIN` holds,
	 * returned for the result of a method, for a property of a plain value and once a refusal is found. Other steps
	 * may yield a plain value in a list of their own, as `$entity.primaryKey` does through `derive`.
	 */
	@Nonnull
	private static final List<Alternative> PLAIN_ALTERNATIVES = Walked.PLAIN.alternatives();

	/**
	 * The expression slots of a reference schema, each with the rules it is checked by.
	 */
	public enum IndexingExpressionKind {

		/**
		 * The reference-level histogram condition `bucketedPartially`, checked by the histogram read rules.
		 */
		BUCKETED_PARTIALLY("`bucketedPartially` condition"),
		/**
		 * The per-histogram condition `assignedWhen`, checked by the histogram read rules.
		 */
		ASSIGNED_WHEN("`assignedWhen` condition"),
		/**
		 * The per-histogram value expression, which must be a single attribute read.
		 */
		HISTOGRAM_VALUE("value expression"),
		/**
		 * The facet condition `facetedPartially`, checked against the reject list.
		 */
		FACETED_PARTIALLY("`facetedPartially` condition");

		/**
		 * The name of the slot in a refusal message.
		 */
		@Nonnull private final String label;

		/**
		 * Creates the slot with its label.
		 *
		 * @param label the name of the slot in a refusal message
		 */
		IndexingExpressionKind(@Nonnull String label) {
			this.label = label;
		}
	}

	/**
	 * Why a read is refused; each reason carries the sentence the user reads.
	 */
	private enum RefusalReason {

		/**
		 * Data no hook withdraws a histogram contribution on.
		 */
		UNTRACKED(
			"evitaDB does not track changes of this data for a histogram, so it could not keep the histogram current " +
				"when the data changes"
		),
		/**
		 * A read whose answer depends on the set of the entity's locales.
		 */
		LOCALE_SET(
			"its value depends on which locales the entity has, and evitaDB does not track changes of that set for a " +
				"histogram, so it could not keep the histogram current when the set changes; read a localized value " +
				"for one locale instead, such as `['en']`"
		),
		/**
		 * An entity one hop away read without an attribute read through it in the same expression.
		 */
		HOP_WITHOUT_ATTRIBUTE(
			"evitaDB keeps a histogram current with this entity only through an attribute the same expression reads " +
				"from it, such as `.attributes['code']`; without one it could not keep the histogram current when " +
				"the entity changes, is archived or is removed"
		),
		/**
		 * An `$entity` or `$reference` read nested in a key, a predicate or a spread mapping.
		 */
		NESTED_ROOT(
			"evitaDB does not track `$entity` or `$reference` data read inside a key, a predicate or a spread " +
				"mapping, so it could not keep the histogram current when that data changes"
		),
		/**
		 * A value evitaDB changes on its own.
		 */
		ENGINE_MAINTAINED(
			"evitaDB maintains this value itself rather than taking it from your mutations, so an index built on it " +
				"could not be kept current"
		),
		/**
		 * `random()`.
		 */
		RANDOM(
			"`random()` returns a different value on every evaluation, so the index could not stay consistent " +
				"with the data"
		),
		/**
		 * Prices.
		 */
		PRICES("prices are not available to indexing expressions"),
		/**
		 * A property the object does not have.
		 */
		MISSING("there is no such property"),
		/**
		 * A data name that is not a string constant.
		 */
		DYNAMIC_NAME(
			"the expression must name the attribute, associated data or reference it reads with a string " +
				"constant, so that evitaDB knows at definition which data it reads"
		),
		/**
		 * An order-dependent use of a list whose order follows the order of locales.
		 */
		POSITIONAL(
			"the order of locales is not stable - it can change when the entity is stored and loaded again - so " +
				"only an order-independent use is supported: `size()`, `any(...)`, `all(...)`, `none(...)` or a " +
				"comparison with `null`"
		),
		/**
		 * A read that is never anything but `null` when indexing expressions are evaluated.
		 */
		ALWAYS_NULL(
			"it is always `null` when the expression is evaluated at indexing time, because evitaDB does not load " +
				"an entity that far from the owner"
		),
		/**
		 * A variable the expression has no binding for.
		 */
		UNKNOWN_VARIABLE(
			"only `$entity`, `$reference` and, inside a predicate or a spread mapping, `$` are available"
		),
		/**
		 * A step on the result of `??` or `*?` whose path reaches no variable: with the histogram rules a property or a
		 * constant-key element step at the top level, with both rules a name applied to a data or `references`
		 * container that is such a result.
		 */
		COMPUTED_RECEIVER(
			"evitaDB cannot map a step applied to the result of `??` or `*?` to the data it reads, so it could not " +
				"keep the index current when that data changes; apply the step to each operand of `??` instead"
		),
		/**
		 * A histogram value expression of another form than a single attribute read.
		 */
		VALUE_FORM(
			"a histogram value must be a single attribute read - `$reference.attributes['x']`, " +
				"`$reference.localizedAttributes['x']`, `$reference.referencedEntity.attributes['x']` or " +
				"`$reference.referencedEntity.localizedAttributes['x']` - optionally followed by `?? <number>`"
		);

		/**
		 * The sentence the user reads.
		 */
		@Nonnull private final String description;

		/**
		 * Creates the reason with its sentence.
		 *
		 * @param description the sentence the user reads
		 */
		RefusalReason(@Nonnull String description) {
			this.description = description;
		}
	}

	/**
	 * A refused read: what it is and why it is refused.
	 *
	 * @param read   the text of the refused read, `null` when the whole expression has an unsupported form
	 * @param reason the reason
	 */
	private record Refusal(@Nullable String read, @Nonnull RefusalReason reason) {
	}

	/**
	 * Prevents instantiation of the stateless validator.
	 */
	private IndexingExpressionInputValidator() {
		// no instances
	}

	/**
	 * Checks every indexing expression carried by the payload of an entity schema change: the expressions of
	 * `CreateReferenceSchemaMutation`, `CreateReflectedReferenceSchemaMutation`, `SetReferenceSchemaFacetedMutation`
	 * and `SetReferenceSchemaBucketedMutation`, the only mutations that put an expression into a schema. Reads only the
	 * payload, so it needs neither the collection nor its schema.
	 *
	 * @param mutation the entity schema change
	 * @throws InvalidSchemaMutationException when an expression reads data its slot does not admit
	 */
	public static void validateEntitySchemaMutation(@Nonnull ModifyEntitySchemaMutation mutation) {
		final String entityType = mutation.getName();
		for (final LocalEntitySchemaMutation schemaMutation : mutation.getSchemaMutations()) {
			if (schemaMutation instanceof CreateReferenceSchemaMutation createReference) {
				validateScopedExpressions(
					entityType, createReference.getName(), createReference.getFacetedPartiallyInScopes(),
					createReference.getBucketedPartiallyInScopes(), createReference.getBucketedInScopes()
				);
			} else if (schemaMutation instanceof CreateReflectedReferenceSchemaMutation createReflectedReference) {
				validateScopedExpressions(
					entityType, createReflectedReference.getName(),
					createReflectedReference.getFacetedPartiallyInScopes(),
					createReflectedReference.getBucketedPartiallyInScopes(),
					createReflectedReference.getBucketedInScopes()
				);
			} else if (schemaMutation instanceof SetReferenceSchemaFacetedMutation setFaceted) {
				validateScopedExpressions(
					entityType, setFaceted.getName(), setFaceted.getFacetedPartiallyInScopes(), null, null
				);
			} else if (schemaMutation instanceof SetReferenceSchemaBucketedMutation setBucketed) {
				validateScopedExpressions(
					entityType, setBucketed.getName(), null, setBucketed.getBucketedPartiallyInScopes(),
					setBucketed.getBucketedInScopes()
				);
			}
		}
	}

	/**
	 * Checks the histogram expressions of a reference schema - `bucketedPartially` in every scope, and the
	 * `assignedWhen` condition and the value expression of every histogram - by the histogram rules.
	 *
	 * @param entityType      the entity type owning the reference
	 * @param referenceSchema the reference schema
	 * @throws InvalidSchemaMutationException when an expression reads data its slot does not admit
	 */
	public static void validateHistogramExpressions(
		@Nonnull String entityType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		for (final Entry<Scope, Expression> condition : referenceSchema.getBucketedPartiallyInScopes().entrySet()) {
			validate(
				condition.getValue(), IndexingExpressionKind.BUCKETED_PARTIALLY, entityType, referenceName, null,
				condition.getKey()
			);
		}
		for (final Entry<Scope, Map<String, HistogramIndexDefinition>> scopeDefinitions :
			referenceSchema.getAllHistogramIndexDefinitions().entrySet()) {
			for (final HistogramIndexDefinition definition : scopeDefinitions.getValue().values()) {
				validateHistogramDefinition(
					entityType, referenceName, definition.nameOfTheIndex(), definition.valueExpression(),
					definition.assignedWhen(), scopeDefinitions.getKey()
				);
			}
		}
	}

	/**
	 * Checks the `facetedPartially` conditions of a reference schema in every scope against the reject list.
	 *
	 * @param entityType      the entity type owning the reference
	 * @param referenceSchema the reference schema
	 * @throws InvalidSchemaMutationException when a condition reads data on the reject list
	 */
	public static void validateFacetExpressions(
		@Nonnull String entityType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		for (final Entry<Scope, Expression> condition : referenceSchema.getFacetedPartiallyInScopes().entrySet()) {
			validate(
				condition.getValue(), IndexingExpressionKind.FACETED_PARTIALLY, entityType, referenceName, null,
				condition.getKey()
			);
		}
	}

	/**
	 * Checks one expression in one slot of a reference.
	 *
	 * @param expression    the expression
	 * @param kind          the slot the expression is defined in
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @param histogramName the histogram, for the `assignedWhen` condition and the value expression; otherwise `null`
	 * @param scope         the scope the expression is defined in
	 * @throws InvalidSchemaMutationException when the expression reads data the slot does not admit
	 */
	public static void validate(
		@Nonnull Expression expression,
		@Nonnull IndexingExpressionKind kind,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nullable String histogramName,
		@Nonnull Scope scope
	) {
		final Refusal refusal = switch (kind) {
			case BUCKETED_PARTIALLY, ASSIGNED_WHEN -> checkCondition(expression.getOperand(), true);
			case FACETED_PARTIALLY -> checkCondition(expression.getOperand(), false);
			case HISTOGRAM_VALUE -> checkValueExpression(expression.getOperand());
		};
		if (refusal != null) {
			throw new InvalidSchemaMutationException(
				composeMessage(expression, kind, entityType, referenceName, histogramName, scope, refusal)
			);
		}
	}

	/**
	 * Checks a condition by the histogram rules or the facet reject list. A parenthesized prefix of a read is walked as
	 * the read itself, except in a condition reading an attribute through an entity one hop away: such a condition is
	 * translated into a query, which reads an access chain only from the variable it starts with, so the condition is
	 * walked again as written and that verdict counts.
	 *
	 * The check sees the one condition it is given. The histogram trigger factory combines `bucketedPartially` with
	 * the `assignedWhen` condition of each histogram and translates the combination also when only the other condition
	 * or the value expression reads through a hop, so a parenthesized prefix this check admits in a histogram condition
	 * can still fail that translation when the trigger is built.
	 *
	 * @param root      the root node of the condition
	 * @param histogram `true` for the histogram rules, `false` for the facet reject list
	 * @return the refusal, or `null` when every read is admitted
	 */
	@Nullable
	private static Refusal checkCondition(@Nonnull ExpressionNode root, boolean histogram) {
		final ReadWalker walker = new ReadWalker(histogram, true);
		final Refusal refusal = walker.check(root);
		if (refusal == null && walker.flattened && walker.attributeReadThroughHop) {
			return new ReadWalker(histogram, false).check(root);
		}
		return refusal;
	}

	/**
	 * Checks the expressions of the scoped payload arrays of one reference schema mutation; `null` arrays and
	 * entries clearing an expression are skipped.
	 *
	 * @param entityType       the entity type owning the reference
	 * @param referenceName    the reference
	 * @param facetConditions  the scoped `facetedPartially` conditions, or `null`
	 * @param bucketConditions the scoped `bucketedPartially` conditions, or `null`
	 * @param histograms       the scoped histogram definitions, or `null`
	 */
	private static void validateScopedExpressions(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nullable ScopedFacetedPartially[] facetConditions,
		@Nullable ScopedBucketedPartially[] bucketConditions,
		@Nullable ScopedHistogramIndexDefinition[] histograms
	) {
		if (facetConditions != null) {
			for (final ScopedFacetedPartially condition : facetConditions) {
				validateIfPresent(
					condition.expression(), IndexingExpressionKind.FACETED_PARTIALLY, entityType, referenceName, null,
					condition.scope()
				);
			}
		}
		if (bucketConditions != null) {
			for (final ScopedBucketedPartially condition : bucketConditions) {
				validateIfPresent(
					condition.expression(), IndexingExpressionKind.BUCKETED_PARTIALLY, entityType, referenceName, null,
					condition.scope()
				);
			}
		}
		if (histograms != null) {
			for (final ScopedHistogramIndexDefinition histogram : histograms) {
				validateHistogramDefinition(
					entityType, referenceName, histogram.nameOfTheIndex(), histogram.valueExpression(),
					histogram.assignedWhen(), histogram.scope()
				);
			}
		}
	}

	/**
	 * Checks the value expression and the `assignedWhen` condition of one histogram, each when present.
	 *
	 * @param entityType      the entity type owning the reference
	 * @param referenceName   the reference
	 * @param histogramName   the histogram
	 * @param valueExpression the value expression, or `null` for a counting histogram
	 * @param assignedWhen    the `assignedWhen` condition, or `null`
	 * @param scope           the scope the histogram is defined in
	 */
	private static void validateHistogramDefinition(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable Expression valueExpression,
		@Nullable Expression assignedWhen,
		@Nonnull Scope scope
	) {
		validateIfPresent(
			valueExpression, IndexingExpressionKind.HISTOGRAM_VALUE, entityType, referenceName, histogramName, scope
		);
		validateIfPresent(
			assignedWhen, IndexingExpressionKind.ASSIGNED_WHEN, entityType, referenceName, histogramName, scope
		);
	}

	/**
	 * Checks one expression in one slot of a reference, when there is one.
	 *
	 * @param expression    the expression, or `null` when the slot is empty or the entry clears it
	 * @param kind          the slot the expression is defined in
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @param histogramName the histogram, for the `assignedWhen` condition and the value expression; otherwise `null`
	 * @param scope         the scope the expression is defined in
	 */
	private static void validateIfPresent(
		@Nullable Expression expression,
		@Nonnull IndexingExpressionKind kind,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nullable String histogramName,
		@Nonnull Scope scope
	) {
		if (expression != null) {
			validate(expression, kind, entityType, referenceName, histogramName, scope);
		}
	}

	/**
	 * Checks that a histogram value expression is one of the four attribute sources `HistogramValueDescriptorFactory`
	 * reads, optionally followed by `??` and a numeric literal, and nothing else: an operator, a function, a second
	 * path, an index after the source or a non-literal default - parentheses around the expression or the default
	 * included, which the descriptor does not look through - would otherwise be ignored without a word.
	 *
	 * @param root the root node of the value expression
	 * @return the refusal, or `null` when the form is supported
	 */
	@Nullable
	private static Refusal checkValueExpression(@Nonnull ExpressionNode root) {
		final ExpressionNode source;
		if (root instanceof NullCoalesceOperator coalesce) {
			if (!(coalesce.getRightOperand() instanceof ConstantOperand constant &&
				constant.getValue() instanceof Number)) {
				return new Refusal(null, RefusalReason.VALUE_FORM);
			}
			source = coalesce.getLeftOperand();
		} else {
			source = root;
		}
		return isValueSource(source) ? null : new Refusal(null, RefusalReason.VALUE_FORM);
	}

	/**
	 * Returns `true` when the node is `$reference.attributes['x']`, `$reference.localizedAttributes['x']`,
	 * `$reference.referencedEntity.attributes['x']` or `$reference.referencedEntity.localizedAttributes['x']`, with
	 * null-safe markers anywhere and `x` a string constant.
	 *
	 * @param node the node
	 * @return `true` for one of the four sources
	 */
	private static boolean isValueSource(@Nonnull ExpressionNode node) {
		if (!(node instanceof ObjectAccessOperator access) ||
			!(access.getOperand() instanceof VariableOperand variable) ||
			!ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(variable.getVariableName())) {
			return false;
		}
		ObjectOperationStep step = skipNullSafe(access.getAccessChain());
		if (step instanceof PropertyAccessStep property &&
			ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY.equals(property.getPropertyIdentifier())) {
			step = skipNullSafe(property.getNext());
		}
		if (!(step instanceof PropertyAccessStep attributes) ||
			!EntityContractAccessor.isAttributesProperty(attributes.getPropertyIdentifier())) {
			return false;
		}
		final ObjectOperationStep nameStep = skipNullSafe(attributes.getNext());
		return nameStep instanceof ElementAccessStep element &&
			element.getNext() == null &&
			isStringConstant(element.getElementIdentifierOperand());
	}

	/**
	 * Returns the step itself, or the first step after a run of null-safe markers.
	 *
	 * @param step the step, or `null`
	 * @return the first step that is not a null-safe marker, or `null`
	 */
	@Nullable
	private static ObjectOperationStep skipNullSafe(@Nullable ObjectOperationStep step) {
		ObjectOperationStep current = step;
		while (current instanceof NullSafeAccessStep nullSafe) {
			current = nullSafe.getNext();
		}
		return current;
	}

	/**
	 * Returns the first step after the step that is not a null-safe marker, continuing into the chains that follow
	 * when the step ends its own chain.
	 *
	 * @param step       the step
	 * @param chains     the chains of an access walked as one, from the outermost to the innermost
	 * @param chainIndex the index of the step's chain
	 * @return the next step, or `null` when the step is the last one
	 */
	@Nullable
	private static ObjectOperationStep nextStep(
		@Nonnull ObjectOperationStep step, @Nonnull List<ObjectOperationStep> chains, int chainIndex
	) {
		ObjectOperationStep next = skipNullSafe(step.getNext());
		for (int i = chainIndex - 1; next == null && i >= 0; i--) {
			next = skipNullSafe(chains.get(i));
		}
		return next;
	}

	/**
	 * Returns the node inside any number of parentheses.
	 *
	 * @param node the node
	 * @return the node without the parentheses around it
	 */
	@Nonnull
	private static ExpressionNode unwrapParentheses(@Nonnull ExpressionNode node) {
		ExpressionNode current = node;
		while (current instanceof NestedOperator nestedOperator) {
			current = nestedOperator.getOperand();
		}
		return current;
	}

	/**
	 * Returns the node the engine starts the data path of an access chain from: its operand inside any parentheses
	 * and, for a parenthesized access chain, the node that chain starts from in turn.
	 *
	 * @param access the access chain operator
	 * @return the node the path starts from
	 */
	@Nonnull
	private static ExpressionNode pathRoot(@Nonnull ObjectAccessOperator access) {
		ExpressionNode current = unwrapParentheses(access.getOperand());
		while (current instanceof ObjectAccessOperator inner) {
			current = unwrapParentheses(inner.getOperand());
		}
		return current;
	}

	/**
	 * Returns `true` when the node is `??` or `*?`.
	 *
	 * @param node the node
	 * @return `true` for a coalescing operator
	 */
	private static boolean isCoalesce(@Nonnull ExpressionNode node) {
		return node instanceof NullCoalesceOperator || node instanceof SpreadNullCoalesceOperator;
	}

	/**
	 * Returns `true` for a step that names the data it reads - a property, or an element with a constant key - and
	 * so adds to the path of data the expression is analyzed by. A key in parentheses is not a constant key here:
	 * the analysis (`AccessedDataFinder`) adds an element to the path only for a key written as a constant, and
	 * nothing for any other key.
	 *
	 * @param step the step
	 * @return `true` for a property step or a constant-key element step
	 */
	private static boolean namesData(@Nonnull ObjectOperationStep step) {
		return step instanceof PropertyAccessStep ||
			step instanceof ElementAccessStep element &&
				element.getElementIdentifierOperand() instanceof ConstantOperand;
	}

	/**
	 * Returns `true` when the node is a string constant written as such. A string in parentheses is not one here:
	 * the analysis of the data an expression reads (`AccessedDataFinder`) takes a name only from a key written as a
	 * constant, so a name in parentheses names no data evitaDB knows at definition.
	 *
	 * @param node the node
	 * @return `true` for a string constant
	 */
	private static boolean isStringConstant(@Nonnull ExpressionNode node) {
		return node instanceof ConstantOperand constant && constant.getValue() instanceof String;
	}

	/**
	 * Composes the refusal message.
	 *
	 * @param expression    the expression
	 * @param kind          the slot
	 * @param entityType    the entity type
	 * @param referenceName the reference
	 * @param histogramName the histogram, or `null`
	 * @param scope         the scope
	 * @param refusal       the refused read and its reason
	 * @return the message
	 */
	@Nonnull
	private static String composeMessage(
		@Nonnull Expression expression,
		@Nonnull IndexingExpressionKind kind,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nullable String histogramName,
		@Nonnull Scope scope,
		@Nonnull Refusal refusal
	) {
		final String expressionText = expression.toExpressionString();
		final StringBuilder message = new StringBuilder(256 + expressionText.length());
		message.append("The ").append(kind.label);
		if (histogramName != null) {
			message.append(" of histogram `").append(histogramName).append('`');
		}
		message.append(" `").append(expressionText).append("` of reference `").append(referenceName)
			.append("` of entity type `").append(entityType).append("` in scope ").append(scope.name());
		if (refusal.read() == null) {
			message.append(" has an unsupported form: ");
		} else {
			message.append(" cannot read `").append(refusal.read()).append("`: ");
		}
		return message.append(refusal.reason().description).append('.').toString();
	}

	/**
	 * Renders the text of one step of an access chain, without the steps after it. The steps' own `toString` cannot
	 * serve: a method step renders without the steps after it, every other step with them.
	 *
	 * @param step the step
	 * @return the text
	 */
	@Nonnull
	private static String stepText(@Nonnull ObjectOperationStep step) {
		if (step instanceof PropertyAccessStep property) {
			return "." + property.getPropertyIdentifier();
		} else if (step instanceof ElementAccessStep element) {
			return "[" + element.getElementIdentifierOperand() + "]";
		} else if (step instanceof NullSafeAccessStep) {
			return "?";
		} else if (step instanceof MethodInvocationStep method) {
			final StringBuilder text = new StringBuilder(16 + method.getMethodIdentifier().length());
			text.append('.').append(method.getMethodIdentifier()).append('(');
			for (int i = 0; i < method.getArgumentOperands().size(); i++) {
				if (i > 0) {
					text.append(", ");
				}
				text.append(method.getArgumentOperands().get(i));
			}
			return text.append(')').toString();
		} else if (step instanceof SpreadAccessStep spread) {
			// the spread renders the steps after it too, and the compact marker is visible only in that rendering
			final String withNext = spread.toString();
			final ObjectOperationStep next = spread.getNext();
			return next == null ? withNext : withNext.substring(0, withNext.length() - next.toString().length());
		} else {
			throw new GenericEvitaInternalError("Unsupported access step `" + step.getClass().getName() + "`.");
		}
	}

	/**
	 * Renders the full text of an access chain operator: its operand and every step.
	 *
	 * @param access the access chain operator
	 * @return the text
	 */
	@Nonnull
	private static String accessText(@Nonnull ObjectAccessOperator access) {
		final StringBuilder text = new StringBuilder(64)
			.append(ExpressionPrecedence.objectAccessBaseText(access.getOperand()));
		for (ObjectOperationStep step = access.getAccessChain(); step != null; step = step.getNext()) {
			text.append(stepText(step));
		}
		return text.toString();
	}

	/**
	 * Returns the text of an access chain before its last step, without the null-safe markers in front of the step,
	 * which belong to the step rather than to the read before it.
	 *
	 * @param text the text of the chain up to and including the step
	 * @param step the last step
	 * @return the text without the step
	 */
	@Nonnull
	private static String textBefore(@Nonnull StringBuilder text, @Nonnull ObjectOperationStep step) {
		int end = text.length() - stepText(step).length();
		while (end > 0 && text.charAt(end - 1) == '?') {
			end--;
		}
		return text.substring(0, end);
	}

	/**
	 * Returns `true` when the node is the `null` constant, also inside any number of parentheses, which evaluate to
	 * what they enclose.
	 *
	 * @param node the node
	 * @return `true` for `null`
	 */
	private static boolean isNullConstant(@Nonnull ExpressionNode node) {
		return unwrapParentheses(node) instanceof ConstantOperand constant && constant.getValue() == null;
	}

	/**
	 * Returns `true` when the function is `random()`. `FunctionOperator` exposes its processor only through equality,
	 * which compares the processor name and the arguments, so the node is compared with a `random()` call over its own
	 * arguments.
	 *
	 * @param function the function node
	 * @return `true` for `random()` with any arguments
	 */
	private static boolean isRandom(@Nonnull FunctionOperator function) {
		final ExpressionNode[] arguments = function.getChildren();
		return function.equals(
			new FunctionOperator(RANDOM_FUNCTION, arguments == null ? List.of() : Arrays.asList(arguments))
		);
	}

	/**
	 * The kinds of entity one hop away from the owner or the reference.
	 */
	private enum HopKind {
		/** `$reference.referencedEntity`. */
		REFERENCED_ENTITY,
		/** `$reference.groupEntity`. */
		GROUP_ENTITY,
		/** `$entity.parentEntity`. */
		PARENT_ENTITY
	}

	/**
	 * What a sub-expression produces, as far as the read rules need to know it.
	 */
	private enum Shape {
		/** `$entity`, the owner entity. */
		OWNER,
		/** `$reference`, the reference being indexed. */
		REFERENCE,
		/** An entity one hop away: the referenced entity, the group entity or the owner's parent. */
		HOP,
		/**
		 * The parent of an entity one hop away, read by the facet rules only: its properties meet the reject list,
		 * and its data is read as the owner's is, without the cross-entity dispatch a hop's attributes have.
		 */
		HOP_PARENT,
		/**
		 * The `references` container of the owner - or of the parent of an entity one hop away, whose reads stay
		 * local as the owner's do - before a reference name.
		 */
		OWNER_REFERENCES,
		/** The `references` container of an entity one hop away, before a reference name. */
		HOP_REFERENCES,
		/** The owner's references of one name: a list, or one reference for a cardinality of at most one. */
		OWNER_REFERENCE_SET,
		/** A hop entity's references of one name. */
		HOP_REFERENCE_SET,
		/** One of the owner's references. */
		OWNER_LISTED_REFERENCE,
		/** One of a hop entity's references. */
		HOP_LISTED_REFERENCE,
		/** An `attributes` container, before an attribute name. */
		ATTRIBUTES,
		/** A `localizedAttributes` container, before an attribute name. */
		LOCALIZED_ATTRIBUTES,
		/** An `associatedData` container, before a name. */
		ASSOCIATED_DATA,
		/** A `localizedAssociatedData` container, before a name. */
		LOCALIZED_ASSOCIATED_DATA,
		/** The whole localized map of one attribute or associated data, keyed by locale. */
		LOCALIZED_MAP,
		/** A list whose order follows the order of locales. */
		LOCALE_LIST,
		/**
		 * Any other value: a constant, an attribute value, the result of an operator or a method, or a collection of
		 * values.
		 */
		VALUE;

		/**
		 * Returns `true` for the containers a hop's attribute read passes through, during which the hop stays known.
		 *
		 * @return `true` for an attribute container
		 */
		boolean isAttributesContainer() {
			return this == ATTRIBUTES || this == LOCALIZED_ATTRIBUTES;
		}

		/**
		 * Returns `true` for the data containers and the `references` containers, which read data only through a
		 * name that follows them.
		 *
		 * @return `true` for a container before its name
		 */
		boolean isContainer() {
			return isAttributesContainer() ||
				this == ASSOCIATED_DATA || this == LOCALIZED_ASSOCIATED_DATA ||
				this == OWNER_REFERENCES || this == HOP_REFERENCES;
		}
	}

	/**
	 * One shape a sub-expression may produce. Most sub-expressions produce one; the result of `??` is either operand
	 * and so has the alternatives of both, and an element of a collection `*?` was applied to is an element of the
	 * collection or the fallback that replaced a `null` one.
	 *
	 * @param shape    what the sub-expression produces
	 * @param hop      the hop kind when the shape is an entity one hop away, or one of its attribute containers in the
	 *                 chain that reaches it, otherwise `null`
	 * @param text     the text of the sub-expression, for messages
	 * @param elements the shapes an element of a collection may have besides those of the collection's own elements:
	 *                 the fallback of `*?` and the result of a spread mapping; empty for anything else. They are kept
	 *                 without their text, which is the text of the read that reaches them.
	 */
	private record Alternative(
		@Nonnull Shape shape,
		@Nullable HopKind hop,
		@Nonnull String text,
		@Nonnull List<Alternative> elements
	) {

		/**
		 * Creates an alternative whose elements, if any, have only the shapes of a collection's own elements.
		 *
		 * @param shape what the sub-expression produces
		 * @param hop   the hop kind of an entity one hop away or of one of its attribute containers, otherwise `null`
		 * @param text  the text of the sub-expression
		 * @return the alternative
		 */
		@Nonnull
		static Alternative of(@Nonnull Shape shape, @Nullable HopKind hop, @Nonnull String text) {
			return new Alternative(shape, hop, text, List.of());
		}

		/**
		 * Creates an alternative of a shape alone: no hop kind, no text, and elements, if any, with only the shapes
		 * of a collection's own elements.
		 *
		 * @param shape what the sub-expression produces
		 * @return the alternative
		 */
		@Nonnull
		static Alternative bare(@Nonnull Shape shape) {
			return new Alternative(shape, null, "", List.of());
		}

		/**
		 * Returns the same alternative with another text.
		 *
		 * @param newText the text
		 * @return the alternative
		 */
		@Nonnull
		Alternative withText(@Nonnull String newText) {
			return newText.equals(this.text) ? this : new Alternative(this.shape, this.hop, newText, this.elements);
		}

		/**
		 * Returns `true` when the other alternative has the same shape, hop kind and element shapes, whatever its
		 * text.
		 *
		 * @param other the other alternative
		 * @return `true` for the same alternative
		 */
		boolean isSameAs(@Nonnull Alternative other) {
			return this.shape == other.shape && this.hop == other.hop && this.elements.equals(other.elements);
		}
	}

	/**
	 * The outcome of walking a sub-expression: every shape it may produce.
	 *
	 * @param alternatives the shapes, in the order of the operands they come from; `??` and a step join them without
	 *                     repeating one, but adding the fallback of `*?` to each of them can make two of them equal
	 */
	private record Walked(@Nonnull List<Alternative> alternatives) {

		/**
		 * The outcome of a sub-expression producing a plain value.
		 */
		static final Walked PLAIN = new Walked(List.of(Alternative.bare(Shape.VALUE)));
	}

	/**
	 * Adds the alternative unless the list holds the same one already.
	 *
	 * @param alternatives the alternatives
	 * @param candidate    the alternative to add
	 */
	private static void addDistinct(@Nonnull List<Alternative> alternatives, @Nonnull Alternative candidate) {
		for (final Alternative alternative : alternatives) {
			if (alternative.isSameAs(candidate)) {
				return;
			}
		}
		alternatives.add(candidate);
	}

	/**
	 * Returns the alternatives of both outcomes, those of the first one first.
	 *
	 * @param first  the first outcome
	 * @param second the second outcome
	 * @return the outcome that may be either
	 */
	@Nonnull
	private static Walked union(@Nonnull Walked first, @Nonnull Walked second) {
		final List<Alternative> alternatives =
			new ArrayList<>(first.alternatives().size() + second.alternatives().size());
		for (final Alternative alternative : first.alternatives()) {
			addDistinct(alternatives, alternative);
		}
		for (final Alternative alternative : second.alternatives()) {
			addDistinct(alternatives, alternative);
		}
		return new Walked(alternatives);
	}

	/**
	 * Returns the outcome of `*?`: the collection, with the fallback among the shapes of its elements.
	 *
	 * @param collection the outcome of the left operand
	 * @param fallback   the outcome of the fallback
	 * @return the outcome of the coalescing
	 */
	@Nonnull
	private static Walked withFallbackElements(@Nonnull Walked collection, @Nonnull Walked fallback) {
		final List<Alternative> alternatives = new ArrayList<>(collection.alternatives().size());
		for (final Alternative alternative : collection.alternatives()) {
			final List<Alternative> elements =
				new ArrayList<>(alternative.elements().size() + fallback.alternatives().size());
			elements.addAll(alternative.elements());
			for (final Alternative element : fallback.alternatives()) {
				addDistinct(elements, element.withText(""));
			}
			alternatives.add(new Alternative(alternative.shape(), alternative.hop(), alternative.text(), elements));
		}
		return new Walked(alternatives);
	}

	/**
	 * Returns the shape of the own elements of a collection: a listed reference for a reference set, a value for a
	 * list ordered by locale, a localized map and any other value. Every caller passes a collection: an entity, a
	 * reference, a listed reference or a container has no elements.
	 *
	 * @param collection the shape of the collection
	 * @return the shape of its elements
	 * @throws GenericEvitaInternalError for a shape that has no elements
	 */
	@Nonnull
	private static Shape ownElementShape(@Nonnull Shape collection) {
		return switch (collection) {
			case OWNER_REFERENCE_SET -> Shape.OWNER_LISTED_REFERENCE;
			case HOP_REFERENCE_SET -> Shape.HOP_LISTED_REFERENCE;
			case LOCALE_LIST, LOCALIZED_MAP, VALUE -> Shape.VALUE;
			case OWNER, REFERENCE, HOP, HOP_PARENT, OWNER_REFERENCES, HOP_REFERENCES, OWNER_LISTED_REFERENCE,
				HOP_LISTED_REFERENCE, ATTRIBUTES, LOCALIZED_ATTRIBUTES, ASSOCIATED_DATA, LOCALIZED_ASSOCIATED_DATA ->
				throw new GenericEvitaInternalError("The shape `" + collection + "` has no elements.");
		};
	}

	/**
	 * Returns the shapes an element of a collection may have: its own elements first, then the further shapes the
	 * collection carries.
	 *
	 * @param collection the collection
	 * @return the shapes of its elements
	 */
	@Nonnull
	private static List<Alternative> elementsOf(@Nonnull Alternative collection) {
		final Alternative own = Alternative.bare(ownElementShape(collection.shape()));
		if (collection.elements().isEmpty()) {
			return List.of(own);
		}
		final List<Alternative> elements = new ArrayList<>(1 + collection.elements().size());
		elements.add(own);
		for (final Alternative element : collection.elements()) {
			addDistinct(elements, element);
		}
		return elements;
	}

	/**
	 * How the expression uses one kind of hop, for the rule that a hop is read only together with an attribute read
	 * through it.
	 */
	private static final class HopUse {

		/**
		 * The text of the first read reaching the hop.
		 */
		@Nonnull private final String firstReadText;
		/**
		 * The text of the first use of the hop other than an attribute read or a comparison of the bare hop with
		 * `null`, or `null` when there is none.
		 */
		@Nullable private String firstOtherUseText;
		/**
		 * Whether the expression reads an attribute through the hop.
		 */
		private boolean attributeRead;

		/**
		 * Creates the record of the first read reaching the hop.
		 *
		 * @param firstReadText the text of the read
		 */
		private HopUse(@Nonnull String firstReadText) {
			this.firstReadText = firstReadText;
		}

		/**
		 * Records a use of the hop other than an attribute read or a comparison of the bare hop with `null`.
		 *
		 * @param text the text of the use
		 */
		private void recordOtherUse(@Nonnull String text) {
			if (this.firstOtherUseText == null) {
				this.firstOtherUseText = text;
			}
		}
	}

	/**
	 * Walks one condition and finds its first refused read. With the histogram rules it admits only the reads listed
	 * in the whitelist; with the facet rules it refuses only the reads on the reject list. A walker is used once.
	 */
	private static final class ReadWalker {

		/**
		 * `true` for the histogram rules, `false` for the facet reject list.
		 */
		private final boolean histogram;
		/**
		 * `true` when a parenthesized prefix of a read is walked as the read itself, `false` when every chain is walked
		 * as written.
		 */
		private final boolean flatten;
		/**
		 * Whether the walk has walked a parenthesized prefix as part of the read it starts.
		 */
		private boolean flattened;
		/**
		 * How the expression uses each hop kind; filled by the histogram rules only.
		 */
		@Nonnull private final Map<HopKind, HopUse> hopUses = new EnumMap<>(HopKind.class);
		/**
		 * The text of the first data container ending an access chain without a name; recorded by the facet rules
		 * only.
		 */
		@Nullable private String unnamedContainerText;
		/**
		 * Whether the expression reads an attribute of an entity one hop away or of one of that entity's references,
		 * which makes evitaDB translate it into a query.
		 */
		private boolean attributeReadThroughHop;
		/**
		 * Whether the engine maps the data of the access chain being walked from a variable - `$entity`, `$reference`,
		 * or, through `$`, the chain a predicate, a mapping or a key belongs to - rather than from the result of `??`,
		 * `*?` or another operator, whose path reaches no variable. A hop's attribute read counts only on such a chain.
		 */
		private boolean rootedChain;
		/**
		 * The first refusal found; the walk stops descending once it is set.
		 */
		@Nullable private Refusal refusal;

		/**
		 * Creates a walker for one condition.
		 *
		 * @param histogram `true` for the histogram rules, `false` for the facet reject list
		 * @param flatten   `true` to walk a parenthesized prefix of a read as the read itself
		 */
		private ReadWalker(boolean histogram, boolean flatten) {
			this.histogram = histogram;
			this.flatten = flatten;
		}

		/**
		 * Walks the condition from its root and returns the first refused read.
		 *
		 * @param root the root node of the condition
		 * @return the refusal, or `null` when every read is admitted
		 */
		@Nullable
		private Refusal check(@Nonnull ExpressionNode root) {
			consume(walk(root, null, false));
			if (this.refusal == null && this.histogram) {
				refuseUnusedHops();
			}
			if (this.unnamedContainerText != null && this.attributeReadThroughHop) {
				// a condition reading an attribute through a hop is translated into a query when it is defined, and
				// the query has no form for a container without a name
				refuse(this.unnamedContainerText, RefusalReason.DYNAMIC_NAME);
			}
			return this.refusal;
		}

		/**
		 * Refuses, with the histogram rules, an entity one hop away the expression reads without reading an attribute
		 * through it; the owner's parent compared with `null` alone is the exception.
		 */
		private void refuseUnusedHops() {
			for (final Entry<HopKind, HopUse> hop : this.hopUses.entrySet()) {
				final HopUse use = hop.getValue();
				if (use.attributeRead) {
					continue;
				}
				if (hop.getKey() != HopKind.PARENT_ENTITY) {
					refuse(use.firstReadText, RefusalReason.HOP_WITHOUT_ATTRIBUTE);
				} else if (use.firstOtherUseText != null) {
					// the owner's own parent link is withdrawn on when it changes, so comparing it with `null`
					// needs no attribute read; any other use reads the parent entity, whose visibility is not
					refuse(use.firstOtherUseText, RefusalReason.HOP_WITHOUT_ATTRIBUTE);
				}
			}
		}

		/**
		 * Records the refusal unless one was found already.
		 *
		 * @param read   the text of the refused read
		 * @param reason the reason
		 */
		private void refuse(@Nonnull String read, @Nonnull RefusalReason reason) {
			if (this.refusal == null) {
				this.refusal = new Refusal(read, reason);
			}
		}

		/**
		 * Records the refusal unless one was found already, and returns the shape a refused step yields.
		 *
		 * @param read   the text of the refused read
		 * @param reason the reason
		 * @return a plain value, the walk being refused
		 */
		@Nonnull
		private Shape refuseValue(@Nonnull String read, @Nonnull RefusalReason reason) {
			refuse(read, reason);
			return Shape.VALUE;
		}

		/**
		 * Records the refusal of the chain read so far when the walker applies the histogram rules; the facet rules
		 * admit the read.
		 *
		 * @param text   the text of the chain up to and including the refused step
		 * @param reason the reason
		 */
		private void refuseInHistogram(@Nonnull StringBuilder text, @Nonnull RefusalReason reason) {
			if (this.histogram) {
				refuse(text.toString(), reason);
			}
		}

		/**
		 * Refuses a step on a data container or a `references` container that is not an element access with a string
		 * constant name: such a step names no data evitaDB knows at definition.
		 *
		 * @param step the step
		 * @param text the text of the chain up to and including the step
		 * @return `true` when the step is refused
		 */
		private boolean refusesUnnamedAccess(@Nonnull ObjectOperationStep step, @Nonnull StringBuilder text) {
			if (!(step instanceof ElementAccessStep element)) {
				refuse(textBefore(text, step), RefusalReason.DYNAMIC_NAME);
				return true;
			} else if (!isStringConstant(element.getElementIdentifierOperand())) {
				refuse(text.toString(), RefusalReason.DYNAMIC_NAME);
				return true;
			}
			return false;
		}

		/**
		 * Accepts the outcome of a sub-expression used as a plain value, whichever of its alternatives it produces.
		 *
		 * @param walked the outcome
		 */
		private void consume(@Nonnull Walked walked) {
			for (final Alternative alternative : walked.alternatives()) {
				consume(alternative);
			}
		}

		/**
		 * Accepts one alternative of a sub-expression used as a plain value: a list ordered by locale cannot be used
		 * so, a bare hop used so counts as a use of the hop other than a comparison with `null`, and with the
		 * histogram rules the bare `$reference` - whose presence alone is tracked - may only be compared with `null`.
		 *
		 * @param alternative the alternative
		 */
		private void consume(@Nonnull Alternative alternative) {
			if (alternative.shape() == Shape.LOCALE_LIST) {
				refuse(alternative.text(), RefusalReason.POSITIONAL);
			} else if (this.histogram && alternative.shape() == Shape.REFERENCE) {
				refuse(alternative.text(), RefusalReason.UNTRACKED);
			}
			if (alternative.shape() == Shape.HOP) {
				recordOtherHopUse(Objects.requireNonNull(alternative.hop()), alternative.text());
			}
		}

		/**
		 * Records a use of a hop other than an attribute read or a comparison of the bare hop with `null`.
		 *
		 * @param hop  the hop kind
		 * @param text the text of the use
		 */
		private void recordOtherHopUse(@Nonnull HopKind hop, @Nonnull String text) {
			final HopUse use = this.hopUses.get(hop);
			if (use != null) {
				use.recordOtherUse(text);
			}
		}

		/**
		 * Walks a node.
		 *
		 * @param node      the node
		 * @param boundThis the shapes `$` may stand for, or `null` outside a predicate and a spread mapping
		 * @param nested    `true` inside a key, a predicate or a spread mapping
		 * @return the outcome
		 */
		@Nonnull
		private Walked walk(@Nonnull ExpressionNode node, @Nullable List<Alternative> boundThis, boolean nested) {
			if (this.refusal != null) {
				return Walked.PLAIN;
			}
			if (node instanceof ObjectAccessOperator access) {
				return walkAccess(access, boundThis, nested);
			} else if (node instanceof VariableOperand variable) {
				return walkBareVariable(variable, boundThis, nested);
			} else if (node instanceof ConstantOperand) {
				return Walked.PLAIN;
			} else if (node instanceof FunctionOperator function) {
				return walkFunction(function, boundThis, nested);
			} else if (node instanceof EqualsOperator || node instanceof NotEqualsOperator) {
				return walkEquality((BinaryExpressionNode) node, boundThis, nested);
			} else if (isCoalesce(node)) {
				return walkCoalesce((BinaryExpressionNode) node, boundThis, nested);
			} else if (node instanceof Expression wrapper) {
				return walk(wrapper.getOperand(), boundThis, nested);
			} else if (node instanceof UnaryExpressionNode unary) {
				final Walked operand = walk(unary.getOperand(), boundThis, nested);
				if (node instanceof NestedOperator) {
					// parentheses add nothing: the consumer of the parenthesized expression decides
					return operand;
				}
				consume(operand);
				return Walked.PLAIN;
			} else if (node instanceof BinaryExpressionNode binary) {
				consume(walk(binary.getLeftOperand(), boundThis, nested));
				consume(walk(binary.getRightOperand(), boundThis, nested));
				return Walked.PLAIN;
			}
			throw new GenericEvitaInternalError("Unsupported expression node `" + node.getClass().getName() + "`.");
		}

		/**
		 * Walks a function call: `random()` is refused, any other function reads only its arguments, each used as a
		 * plain value.
		 *
		 * @param function  the function call
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @param nested    `true` inside a key, a predicate or a spread mapping
		 * @return the outcome, a plain value
		 */
		@Nonnull
		private Walked walkFunction(
			@Nonnull FunctionOperator function, @Nullable List<Alternative> boundThis, boolean nested
		) {
			if (isRandom(function)) {
				refuse(function.toString(), RefusalReason.RANDOM);
				return Walked.PLAIN;
			}
			final ExpressionNode[] arguments = function.getChildren();
			if (arguments != null) {
				for (final ExpressionNode argument : arguments) {
					consume(walk(argument, boundThis, nested));
				}
			}
			return Walked.PLAIN;
		}

		/**
		 * Walks `??` or `*?`. The fallback is used as a plain value, and so is the left operand, except a list ordered
		 * by locale, whose order the coalescing keeps for the consumer of the result to decide on. The result of `??`
		 * is either operand, so it has the alternatives of both; the fallback of `*?` replaces the `null` elements of
		 * the collection, so its result is the collection, with the fallback among the shapes of its elements. A
		 * `null` fallback adds nothing, and neither does a `null` left operand of `??`; a `null` left operand of `*?`
		 * is a value whose elements may have the shapes of the fallback.
		 *
		 * @param coalesce  the coalescing operator
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @param nested    `true` inside a key, a predicate or a spread mapping
		 * @return the outcome
		 */
		@Nonnull
		private Walked walkCoalesce(
			@Nonnull BinaryExpressionNode coalesce, @Nullable List<Alternative> boundThis, boolean nested
		) {
			final Walked value = walk(coalesce.getLeftOperand(), boundThis, nested);
			// the fallback is a plain value; a list ordered by locale cannot serve as one
			final Walked fallback = walk(coalesce.getRightOperand(), boundThis, nested);
			consume(fallback);
			for (final Alternative alternative : value.alternatives()) {
				if (alternative.shape() != Shape.LOCALE_LIST) {
					consume(alternative);
				}
			}
			if (isNullConstant(coalesce.getRightOperand())) {
				return value;
			} else if (coalesce instanceof SpreadNullCoalesceOperator) {
				return withFallbackElements(value, fallback);
			} else if (isNullConstant(coalesce.getLeftOperand())) {
				return fallback;
			}
			return union(value, fallback);
		}

		/**
		 * Walks `==` or `!=`: an operand compared with the `null` constant may be a list ordered by locale or a bare
		 * hop, because the comparison depends only on whether it is present.
		 *
		 * @param equality  the comparison
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @param nested    `true` inside a key, a predicate or a spread mapping
		 * @return the outcome, a plain value
		 */
		@Nonnull
		private Walked walkEquality(
			@Nonnull BinaryExpressionNode equality, @Nullable List<Alternative> boundThis, boolean nested
		) {
			final Walked left = walk(equality.getLeftOperand(), boundThis, nested);
			final Walked right = walk(equality.getRightOperand(), boundThis, nested);
			if (!isNullConstant(equality.getRightOperand())) {
				consume(left);
			}
			if (!isNullConstant(equality.getLeftOperand())) {
				consume(right);
			}
			return Walked.PLAIN;
		}

		/**
		 * Walks a variable used on its own, not as the operand of an access chain.
		 *
		 * @param variable  the variable
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @param nested    `true` inside a key, a predicate or a spread mapping
		 * @return the outcome
		 */
		@Nonnull
		private Walked walkBareVariable(
			@Nonnull VariableOperand variable, @Nullable List<Alternative> boundThis, boolean nested
		) {
			final String text = variable.toString();
			final List<Alternative> rooted = rootAlternatives(variable, boundThis, nested, text);
			if (rooted == null) {
				return Walked.PLAIN;
			}
			final List<Alternative> alternatives = new ArrayList<>(rooted.size());
			for (final Alternative alternative : rooted) {
				if (this.histogram && alternative.shape() == Shape.OWNER) {
					// the whole entity is no single datum any hook could withdraw on
					refuse(text, RefusalReason.UNTRACKED);
					return Walked.PLAIN;
				}
				alternatives.add(alternative.withText(text));
			}
			return new Walked(alternatives);
		}

		/**
		 * Resolves what a variable stands for, refusing an unknown variable, an unbound `$`, and - with the histogram
		 * rules - an `$entity` or `$reference` root nested in a key, a predicate or a spread mapping.
		 *
		 * @param variable  the variable
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @param nested    `true` inside a key, a predicate or a spread mapping
		 * @param readText  the text of the read the variable starts, for a nested-root refusal
		 * @return the shapes the variable may stand for, or `null` when the variable was refused
		 */
		@Nullable
		private List<Alternative> rootAlternatives(
			@Nonnull VariableOperand variable,
			@Nullable List<Alternative> boundThis,
			boolean nested,
			@Nonnull String readText
		) {
			if (variable.isThis()) {
				if (boundThis == null) {
					refuse(variable.toString(), RefusalReason.UNKNOWN_VARIABLE);
				}
				return boundThis;
			}
			final String name = variable.getVariableName();
			final Shape shape;
			if (EntityContractAccessor.ENTITY_VARIABLE_NAME.equals(name)) {
				shape = Shape.OWNER;
			} else if (ReferenceContractAccessor.REFERENCE_VARIABLE_NAME.equals(name)) {
				shape = Shape.REFERENCE;
			} else {
				refuse(variable.toString(), RefusalReason.UNKNOWN_VARIABLE);
				return null;
			}
			if (this.histogram && nested) {
				refuse(readText, RefusalReason.NESTED_ROOT);
				return null;
			}
			return List.of(Alternative.bare(shape));
		}

		/**
		 * Walks an access chain step by step from its operand.
		 *
		 * @param access    the access chain operator
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @param nested    `true` inside a key, a predicate or a spread mapping
		 * @return the outcome
		 */
		@Nonnull
		private Walked walkAccess(
			@Nonnull ObjectAccessOperator access, @Nullable List<Alternative> boundThis, boolean nested
		) {
			// parentheses around a variable or an access chain add no read of their own: `(X.a).b` is walked as
			// `X.a.b`, so the chains are collected from the outermost to the innermost and walked in reverse;
			// a walker walking as written (see `checkCondition`) keeps the parenthesized prefix an operand
			final List<ObjectOperationStep> chains = new ArrayList<>(2);
			ObjectAccessOperator innermost = access;
			chains.add(innermost.getAccessChain());
			while (this.flatten && unwrapParentheses(innermost.getOperand()) instanceof ObjectAccessOperator inner) {
				innermost = inner;
				chains.add(innermost.getAccessChain());
			}
			final ExpressionNode operand = innermost.getOperand();
			final ExpressionNode root = this.flatten ? unwrapParentheses(operand) : operand;
			if (innermost != access || (root != operand && root instanceof VariableOperand)) {
				this.flattened = true;
			}
			final StringBuilder text = new StringBuilder(64);
			final List<Alternative> receivers;
			if (root instanceof VariableOperand variable) {
				receivers = rootAlternatives(variable, boundThis, nested, accessText(access));
				if (receivers == null) {
					return Walked.PLAIN;
				}
				text.append(variable);
			} else {
				final Walked base = walk(operand, boundThis, nested);
				if (this.refusal != null) {
					return Walked.PLAIN;
				}
				receivers = base.alternatives();
				text.append(ExpressionPrecedence.objectAccessBaseText(operand));
			}
			final boolean operandIsCoalesce = isCoalesce(unwrapParentheses(operand));
			// the engine maps no read through the result of `??` or `*?` at the top level: its steps reach no
			// variable; inside a key, a predicate or a mapping they continue the path the step is applied to
			final boolean coalescedReceiver = this.histogram && !nested && operandIsCoalesce;
			// a chain starting at `$entity` or `$reference`, parentheses and parenthesized chains looked through, has a
			// path of its own; any other chain continues the path of the chain its key, predicate or mapping belongs
			// to, and starts one without a variable outside them
			final boolean enclosingRooted = this.rootedChain;
			this.rootedChain = (pathRoot(access) instanceof VariableOperand rootVariable && !rootVariable.isThis()) ||
				(nested && enclosingRooted);
			try {
				final List<Alternative> stepped =
					walkSteps(chains, receivers, text, boundThis, coalescedReceiver, operandIsCoalesce);
				if (this.refusal != null) {
					return Walked.PLAIN;
				}
				return finishChain(stepped, text);
			} finally {
				this.rootedChain = enclosingRooted;
			}
		}

		/**
		 * Applies every step of the chains of an access, the innermost chain first, to each alternative its operand
		 * produces, and appends the text of each step to the text of the chain.
		 *
		 * @param chains            the chains of an access walked as one, from the outermost to the innermost
		 * @param operandShapes     the alternatives the operand of the innermost chain produces
		 * @param text              the text of the chain, extended by every step walked
		 * @param boundThis         the shapes `$` may stand for, or `null`
		 * @param coalescedReceiver `true` when the operand is the result of `??` or `*?` at the top level of a
		 *                          condition checked by the histogram rules, where a step naming data is refused
		 * @param operandIsCoalesce `true` when the operand is the result of `??` or `*?`, so that the first step,
		 *                          applied to a data or `references` container that is such a result, is refused
		 *                          even with a constant name
		 * @return the alternatives the last step produces, or a plain value once a refusal is found
		 */
		@Nonnull
		private List<Alternative> walkSteps(
			@Nonnull List<ObjectOperationStep> chains,
			@Nonnull List<Alternative> operandShapes,
			@Nonnull StringBuilder text,
			@Nullable List<Alternative> boundThis,
			boolean coalescedReceiver,
			boolean operandIsCoalesce
		) {
			List<Alternative> receivers = operandShapes;
			// a data or `references` container that is the result of `??` or `*?` ended the chain reaching it, and the
			// engine records a name applied to the result on no path through the container, nested or not; set while
			// the step walked is the first one, which is applied to the result itself
			boolean onCoalesceResult = operandIsCoalesce;
			for (int chainIndex = chains.size() - 1; chainIndex >= 0; chainIndex--) {
				for (ObjectOperationStep step = chains.get(chainIndex); step != null; step = step.getNext()) {
					if (step instanceof NullSafeAccessStep) {
						text.append('?');
						continue;
					}
					text.append(stepText(step));
					if (coalescedReceiver && namesData(step)) {
						refuse(text.toString(), RefusalReason.COMPUTED_RECEIVER);
						return PLAIN_ALTERNATIVES;
					}
					final ObjectOperationStep next = nextStep(step, chains, chainIndex);
					// a step on several alternatives is a step on each, and is refused when it is refused on any
					final List<Alternative> stepped = new ArrayList<>(receivers.size());
					for (final Alternative receiver : receivers) {
						for (final Alternative result : step(receiver, step, next, text, boundThis)) {
							addDistinct(stepped, result);
						}
						if (onCoalesceResult && receiver.shape().isContainer()) {
							// `step` has applied the container's name rule; a name passing it is refused as well
							refuse(text.toString(), RefusalReason.COMPUTED_RECEIVER);
						}
						if (this.refusal != null) {
							return PLAIN_ALTERNATIVES;
						}
					}
					receivers = stepped;
					onCoalesceResult = false;
				}
			}
			return receivers;
		}

		/**
		 * Judges where an access chain ends, for each alternative: at an entity one hop away, kept for the hop rule; at
		 * a data or a `references` container without a name, refused by the histogram rules, and recorded and kept as
		 * the container by the facet rules; or at its shape. Only an entity one hop away keeps its hop kind: the hop
		 * kind of an attribute container counts for a name read in the chain that reaches the container.
		 *
		 * @param receivers the alternatives the last step produced
		 * @param text      the text of the whole chain
		 * @return the outcome
		 */
		@Nonnull
		private Walked finishChain(@Nonnull List<Alternative> receivers, @Nonnull StringBuilder text) {
			final String chainText = text.toString();
			final List<Alternative> finished = new ArrayList<>(receivers.size());
			for (final Alternative receiver : receivers) {
				if (receiver.shape() == Shape.HOP) {
					addDistinct(finished, receiver.withText(chainText));
				} else if (receiver.shape().isContainer()) {
					if (this.histogram) {
						refuse(chainText, RefusalReason.DYNAMIC_NAME);
						return Walked.PLAIN;
					}
					// a container ending the chain evaluates to the container itself, which a facet condition may
					// compare with `null`; it stays a container for whatever uses it, so a step applied to it later -
					// on the result of `??` or `*?`, or on an element a predicate or a mapping reads - meets the name
					// rule, and a translated condition fails on it (see `check`)
					if (this.unnamedContainerText == null) {
						this.unnamedContainerText = chainText;
					}
					addDistinct(finished, Alternative.of(receiver.shape(), null, chainText));
				} else {
					addDistinct(finished, new Alternative(receiver.shape(), null, chainText, receiver.elements()));
				}
			}
			return new Walked(finished);
		}

		/**
		 * Returns the hop kind a property step reaches.
		 *
		 * @param step the step reaching a hop
		 * @return the hop kind
		 */
		@Nonnull
		private static HopKind hopKindOf(@Nonnull ObjectOperationStep step) {
			final String property = ((PropertyAccessStep) step).getPropertyIdentifier();
			return switch (property) {
				case ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY -> HopKind.REFERENCED_ENTITY;
				case ReferenceContractAccessor.GROUP_ENTITY_PROPERTY -> HopKind.GROUP_ENTITY;
				case EntityContractAccessor.PARENT_ENTITY_PROPERTY -> HopKind.PARENT_ENTITY;
				default -> throw new GenericEvitaInternalError("Property `" + property + "` reaches no hop.");
			};
		}

		/**
		 * Applies one step of an access chain to one alternative it is applied to.
		 *
		 * @param receiver  the alternative the step is applied to
		 * @param step      the step
		 * @param next      the next step that is not a null-safe marker, or `null`
		 * @param text      the text of the chain up to and including the step
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @return the alternatives the step produces
		 */
		@Nonnull
		private List<Alternative> step(
			@Nonnull Alternative receiver,
			@Nonnull ObjectOperationStep step,
			@Nullable ObjectOperationStep next,
			@Nonnull StringBuilder text,
			@Nullable List<Alternative> boundThis
		) {
			final Shape shape = receiver.shape();
			return switch (shape) {
				case OWNER -> derive(receiver, ownerStep(step, text, false), step);
				case HOP_PARENT -> derive(receiver, ownerStep(step, text, true), step);
				case REFERENCE, OWNER_LISTED_REFERENCE, HOP_LISTED_REFERENCE ->
					derive(receiver, referenceStep(shape, step, text), step);
				case HOP -> derive(receiver, hopStep(Objects.requireNonNull(receiver.hop()), step, text), step);
				case OWNER_REFERENCES, HOP_REFERENCES -> {
					if (refusesUnnamedAccess(step, text)) {
						yield PLAIN_ALTERNATIVES;
					}
					yield derive(
						receiver,
						shape == Shape.OWNER_REFERENCES ? Shape.OWNER_REFERENCE_SET : Shape.HOP_REFERENCE_SET,
						step
					);
				}
				case OWNER_REFERENCE_SET, HOP_REFERENCE_SET -> referenceSetStep(receiver, step, text, boundThis);
				case ATTRIBUTES, LOCALIZED_ATTRIBUTES, ASSOCIATED_DATA, LOCALIZED_ASSOCIATED_DATA ->
					derive(receiver, containerStep(shape, receiver.hop(), step, next, text), step);
				case LOCALIZED_MAP -> localizedMapStep(receiver, step, text, boundThis);
				case LOCALE_LIST -> localeListStep(receiver, step, text);
				case VALUE -> valueStep(receiver, step, boundThis);
			};
		}

		/**
		 * Returns the one alternative a step producing a single shape yields: an entity one hop away has the hop kind
		 * of the step reaching it, an attribute container the hop kind of the entity it belongs to.
		 *
		 * @param receiver the alternative the step is applied to
		 * @param shape    the shape the step produces
		 * @param step     the step
		 * @return the alternative
		 */
		@Nonnull
		private static List<Alternative> derive(
			@Nonnull Alternative receiver, @Nonnull Shape shape, @Nonnull ObjectOperationStep step
		) {
			final HopKind hop;
			if (shape == Shape.HOP) {
				hop = hopKindOf(step);
			} else {
				hop = shape.isAttributesContainer() ? receiver.hop() : null;
			}
			return List.of(Alternative.of(shape, hop, ""));
		}

		/**
		 * Applies a step to the owner entity or - with the facet rules only - to the parent of an entity one hop away,
		 * whose data is read the way the owner's is.
		 *
		 * @param step      the step
		 * @param text      the text of the chain up to and including the step
		 * @param hopParent `true` when the step is applied to the parent of an entity one hop away
		 * @return the shape the step produces
		 */
		@Nonnull
		private Shape ownerStep(@Nonnull ObjectOperationStep step, @Nonnull StringBuilder text, boolean hopParent) {
			if (!(step instanceof PropertyAccessStep property)) {
				return refuseValue(text.toString(), RefusalReason.MISSING);
			}
			return switch (property.getPropertyIdentifier()) {
				case EntityContractAccessor.PRIMARY_KEY_PROPERTY -> Shape.VALUE;
				case EntityContractAccessor.ATTRIBUTES_PROPERTY -> Shape.ATTRIBUTES;
				case EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY -> Shape.LOCALIZED_ATTRIBUTES;
				case EntityContractAccessor.ASSOCIATED_DATA_PROPERTY -> Shape.ASSOCIATED_DATA;
				case EntityContractAccessor.LOCALIZED_ASSOCIATED_DATA_PROPERTY -> Shape.LOCALIZED_ASSOCIATED_DATA;
				case EntityContractAccessor.PARENT_ENTITY_PROPERTY ->
					hopParent ? Shape.HOP_PARENT : enterHop(HopKind.PARENT_ENTITY, text);
				case EntityContractAccessor.REFERENCES_PROPERTY -> {
					// no hook withdraws a histogram contribution when the owner's other references change
					refuseInHistogram(text, RefusalReason.UNTRACKED);
					yield Shape.OWNER_REFERENCES;
				}
				case EntityContractAccessor.LOCALES_PROPERTY, EntityContractAccessor.ALL_LOCALES_PROPERTY -> {
					refuseInHistogram(text, RefusalReason.LOCALE_SET);
					yield Shape.LOCALE_LIST;
				}
				case EntityContractAccessor.SCOPE_PROPERTY -> {
					refuseInHistogram(text, RefusalReason.UNTRACKED);
					yield Shape.VALUE;
				}
				default -> entityPropertyRefusal(property.getPropertyIdentifier(), text);
			};
		}

		/**
		 * Applies a step to an entity one hop away. The histogram rules admit only its attributes, and its primary key
		 * and scope - recorded as uses of the hop, which the hop rule admits only beside an attribute read through the
		 * same hop. The facet rules admit every property except the reject list, and read the hop's parent the way the
		 * owner is read.
		 *
		 * @param hop  the hop kind
		 * @param step the step
		 * @param text the text of the chain up to and including the step
		 * @return the shape the step produces
		 */
		@Nonnull
		private Shape hopStep(
			@Nonnull HopKind hop,
			@Nonnull ObjectOperationStep step,
			@Nonnull StringBuilder text
		) {
			if (!(step instanceof PropertyAccessStep property)) {
				return refuseValue(text.toString(), RefusalReason.MISSING);
			}
			return switch (property.getPropertyIdentifier()) {
				case EntityContractAccessor.ATTRIBUTES_PROPERTY -> Shape.ATTRIBUTES;
				case EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY -> Shape.LOCALIZED_ATTRIBUTES;
				case EntityContractAccessor.PRIMARY_KEY_PROPERTY, EntityContractAccessor.SCOPE_PROPERTY -> {
					recordOtherHopUse(hop, text.toString());
					yield Shape.VALUE;
				}
				case EntityContractAccessor.LOCALES_PROPERTY, EntityContractAccessor.ALL_LOCALES_PROPERTY -> {
					refuseInHistogram(text, RefusalReason.LOCALE_SET);
					yield Shape.LOCALE_LIST;
				}
				case EntityContractAccessor.ASSOCIATED_DATA_PROPERTY -> {
					refuseInHistogram(text, RefusalReason.UNTRACKED);
					yield Shape.ASSOCIATED_DATA;
				}
				case EntityContractAccessor.LOCALIZED_ASSOCIATED_DATA_PROPERTY -> {
					refuseInHistogram(text, RefusalReason.UNTRACKED);
					yield Shape.LOCALIZED_ASSOCIATED_DATA;
				}
				case EntityContractAccessor.REFERENCES_PROPERTY -> {
					// the insert and the removal of a hop entity's reference are not dispatched to the owners
					refuseInHistogram(text, RefusalReason.UNTRACKED);
					yield Shape.HOP_REFERENCES;
				}
				case EntityContractAccessor.PARENT_ENTITY_PROPERTY -> {
					// the parent link of an entity one hop away is readable, but its changes are not dispatched
					refuseInHistogram(text, RefusalReason.UNTRACKED);
					yield Shape.HOP_PARENT;
				}
				default -> entityPropertyRefusal(property.getPropertyIdentifier(), text);
			};
		}

		/**
		 * Refuses a property of an entity that is neither readable nor supported: data evitaDB maintains itself,
		 * prices, and properties that do not exist.
		 *
		 * @param property the property name
		 * @param text     the text of the chain up to and including the property
		 * @return a plain value, the walk being refused
		 */
		@Nonnull
		private Shape entityPropertyRefusal(@Nonnull String property, @Nonnull StringBuilder text) {
			final RefusalReason reason = switch (property) {
				case EntityContractAccessor.VERSION_PROPERTY, EntityContractAccessor.DROPPED_PROPERTY ->
					RefusalReason.ENGINE_MAINTAINED;
				case EntityContractAccessor.ALL_PRICES_FOR_SALE_PROPERTY,
					EntityContractAccessor.PRICE_FOR_SALE_PROPERTY,
					EntityContractAccessor.PRICES_PROPERTY, EntityContractAccessor.ACCOMPANYING_PRICE_PROPERTY,
					EntityContractAccessor.PRICE_INNER_RECORD_HANDLING_PROPERTY -> RefusalReason.PRICES;
				default -> RefusalReason.MISSING;
			};
			return refuseValue(text.toString(), reason);
		}

		/**
		 * Records the first read reaching a hop and returns the hop shape.
		 *
		 * @param hop  the hop kind
		 * @param text the text of the chain up to and including the step reaching the hop
		 * @return the hop shape
		 */
		@Nonnull
		private Shape enterHop(@Nonnull HopKind hop, @Nonnull StringBuilder text) {
			if (this.histogram) {
				this.hopUses.computeIfAbsent(hop, ignored -> new HopUse(text.toString()));
			}
			return Shape.HOP;
		}

		/**
		 * Applies a step to a reference: the reference being indexed, or one of the owner's or a hop entity's
		 * references.
		 *
		 * @param shape which reference the step is applied to
		 * @param step  the step
		 * @param text  the text of the chain up to and including the step
		 * @return the shape the step produces
		 */
		@Nonnull
		private Shape referenceStep(
			@Nonnull Shape shape, @Nonnull ObjectOperationStep step, @Nonnull StringBuilder text
		) {
			if (!(step instanceof PropertyAccessStep property)) {
				return refuseValue(text.toString(), RefusalReason.MISSING);
			}
			if (shape == Shape.HOP_LISTED_REFERENCE && this.rootedChain &&
				EntityContractAccessor.isAttributesProperty(property.getPropertyIdentifier())) {
				// an attribute of a hop entity's reference is read through the hop as well
				this.attributeReadThroughHop = true;
			}
			return switch (property.getPropertyIdentifier()) {
				case ReferenceContractAccessor.REFERENCED_PRIMARY_KEY_PROPERTY -> Shape.VALUE;
				case ReferenceContractAccessor.ATTRIBUTES_PROPERTY -> Shape.ATTRIBUTES;
				case ReferenceContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY -> {
					// a condition gets no localized attribute of the reference wired; only a value expression reads
					// one, through the value descriptor
					refuseInHistogram(text, RefusalReason.UNTRACKED);
					yield Shape.LOCALIZED_ATTRIBUTES;
				}
				case ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY ->
					hopOfReference(shape, HopKind.REFERENCED_ENTITY, text);
				case ReferenceContractAccessor.GROUP_ENTITY_PROPERTY ->
					hopOfReference(shape, HopKind.GROUP_ENTITY, text);
				default -> refuseValue(text.toString(), RefusalReason.MISSING);
			};
		}

		/**
		 * Returns the entity one hop away a reference reaches through its referenced or group entity. Only the
		 * reference being indexed has it: the entity of a reference read from the owner's or a hop entity's
		 * references is never loaded for indexing expressions, so the read is refused as always `null`.
		 *
		 * @param shape which reference the step is applied to
		 * @param hop   the hop kind the step reaches
		 * @param text  the text of the chain up to and including the step
		 * @return the hop shape, or a plain value when the read is refused
		 */
		@Nonnull
		private Shape hopOfReference(@Nonnull Shape shape, @Nonnull HopKind hop, @Nonnull StringBuilder text) {
			if (shape == Shape.REFERENCE) {
				return enterHop(hop, text);
			}
			return refuseValue(text.toString(), RefusalReason.ALWAYS_NULL);
		}

		/**
		 * Applies a step to the references of one name, which are a list or - for a cardinality of at most one - a
		 * single reference. An element, a method's predicate and a spread mapping read any shape an element may have.
		 *
		 * @param receiver  the references, the owner's or a hop entity's
		 * @param step      the step
		 * @param text      the text of the chain up to and including the step
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @return the alternatives the step produces
		 */
		@Nonnull
		private List<Alternative> referenceSetStep(
			@Nonnull Alternative receiver,
			@Nonnull ObjectOperationStep step,
			@Nonnull StringBuilder text,
			@Nullable List<Alternative> boundThis
		) {
			final List<Alternative> elements = elementsOf(receiver);
			if (step instanceof PropertyAccessStep) {
				// a reference of a cardinality of at most one is answered as the reference itself
				final Alternative reference = elements.get(0);
				return derive(reference, referenceStep(reference.shape(), step, text), step);
			} else if (step instanceof ElementAccessStep elementAccess) {
				walkKey(elementAccess.getElementIdentifierOperand(), boundThis);
				return elements;
			} else if (step instanceof MethodInvocationStep method) {
				walkArguments(method, elements);
				return PLAIN_ALTERNATIVES;
			} else if (step instanceof SpreadAccessStep spread) {
				return spreadResult(receiver, walk(spread.getMappingExpression(), elements, true));
			} else {
				throw new GenericEvitaInternalError("Unsupported access step `" + step.getClass().getName() + "`.");
			}
		}

		/**
		 * Returns what a spread over a collection produces. A spread mapping each reference of a set to itself yields
		 * the references, read as these are. Any other mapping is used as a value, and its alternatives join the shapes
		 * of the elements of the result: a list of values, or - for a list ordered by locale and a localized map - a
		 * collection keeping their order and their keys.
		 *
		 * @param collection the collection the spread is applied to
		 * @param mapped     the outcome of the mapping
		 * @return the alternatives the spread produces
		 */
		@Nonnull
		private List<Alternative> spreadResult(@Nonnull Alternative collection, @Nonnull Walked mapped) {
			final boolean referenceSet = collection.shape() == Shape.OWNER_REFERENCE_SET ||
				collection.shape() == Shape.HOP_REFERENCE_SET;
			if (referenceSet && mapped.alternatives().size() == 1) {
				final Alternative only = mapped.alternatives().get(0);
				if (only.shape() == ownElementShape(collection.shape()) && only.elements().isEmpty()) {
					return List.of(Alternative.bare(collection.shape()));
				}
			}
			consume(mapped);
			final List<Alternative> elements = new ArrayList<>(mapped.alternatives().size());
			for (final Alternative element : mapped.alternatives()) {
				// a plain value is an own element of the result already
				if (element.shape() != Shape.VALUE || !element.elements().isEmpty()) {
					addDistinct(elements, element.withText(""));
				}
			}
			return List.of(new Alternative(referenceSet ? Shape.VALUE : collection.shape(), null, "", elements));
		}

		/**
		 * Walks the mapping of a spread over a collection, in which `$` stands for any shape an element may have, and
		 * returns what the spread produces.
		 *
		 * @param receiver the collection the spread is applied to
		 * @param spread   the spread step
		 * @return the alternatives the spread produces
		 */
		@Nonnull
		private List<Alternative> walkSpread(@Nonnull Alternative receiver, @Nonnull SpreadAccessStep spread) {
			return spreadResult(receiver, walk(spread.getMappingExpression(), elementsOf(receiver), true));
		}

		/**
		 * Applies a step to an attribute or associated data container: only an element access with a string constant
		 * name reads from it. A non-localized name yields the value, a localized one the map of the value by locale.
		 * A name read from an attribute container of an entity one hop away, on a chain mapped from a variable,
		 * records an attribute read through the hop. With the histogram rules a localized name not followed by an
		 * element access is refused, because the whole map follows the locale set of the entity.
		 *
		 * @param shape the container
		 * @param hop   the hop kind when the container belongs to a hop entity, otherwise `null`
		 * @param step  the step
		 * @param next  the next step that is not a null-safe marker, or `null`
		 * @param text  the text of the chain up to and including the step
		 * @return the shape the step produces
		 */
		@Nonnull
		private Shape containerStep(
			@Nonnull Shape shape,
			@Nullable HopKind hop,
			@Nonnull ObjectOperationStep step,
			@Nullable ObjectOperationStep next,
			@Nonnull StringBuilder text
		) {
			if (refusesUnnamedAccess(step, text)) {
				return Shape.VALUE;
			}
			if (hop != null && shape.isAttributesContainer() && this.rootedChain) {
				// only on a chain mapped from a variable does the engine see the hop's attribute
				this.attributeReadThroughHop = true;
				final HopUse use = this.hopUses.get(hop);
				if (use != null) {
					use.attributeRead = true;
				}
			}
			if (shape == Shape.ATTRIBUTES || shape == Shape.ASSOCIATED_DATA) {
				return Shape.VALUE;
			}
			if (this.histogram && !(next instanceof ElementAccessStep)) {
				// the whole map follows the locale set of the entity, and no hook withdraws a histogram
				// contribution when that set changes
				refuse(text.toString(), RefusalReason.LOCALE_SET);
				return Shape.VALUE;
			}
			return Shape.LOCALIZED_MAP;
		}

		/**
		 * Applies a step to the whole localized map of one attribute or associated data: a locale selector, `.entries`,
		 * `size()` and a spread over its values are the steps a map answers. A value at one locale and the value a
		 * spread mapping reads may have any shape a value of the map may have.
		 *
		 * @param receiver  the localized map
		 * @param step      the step
		 * @param text      the text of the chain up to and including the step
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @return the alternatives the step produces
		 */
		@Nonnull
		private List<Alternative> localizedMapStep(
			@Nonnull Alternative receiver,
			@Nonnull ObjectOperationStep step,
			@Nonnull StringBuilder text,
			@Nullable List<Alternative> boundThis
		) {
			if (step instanceof ElementAccessStep element) {
				// the value at one locale does not depend on the locale set: a missing locale reads as a missing value
				walkKey(element.getElementIdentifierOperand(), boundThis);
				return elementsOf(receiver);
			} else if (step instanceof PropertyAccessStep property &&
				"entries".equals(property.getPropertyIdentifier())) {
				return List.of(Alternative.bare(Shape.LOCALE_LIST));
			} else if (step instanceof MethodInvocationStep method && "size".equals(method.getMethodIdentifier())) {
				walkArguments(method, PLAIN_ALTERNATIVES);
				return PLAIN_ALTERNATIVES;
			} else if (step instanceof SpreadAccessStep spread) {
				// a spread over a map maps its values and keeps its keys, so the result is still a map by locale
				return walkSpread(receiver, spread);
			}
			refuse(text.toString(), RefusalReason.MISSING);
			return PLAIN_ALTERNATIVES;
		}

		/**
		 * Applies a step to a list ordered by locale: only `size()`, `any`, `all`, `none` and a spread, whose result
		 * keeps the order, do not depend on the order. A predicate and a spread mapping read any shape an element may
		 * have.
		 *
		 * @param receiver the list
		 * @param step     the step
		 * @param text     the text of the chain up to and including the step
		 * @return the alternatives the step produces
		 */
		@Nonnull
		private List<Alternative> localeListStep(
			@Nonnull Alternative receiver, @Nonnull ObjectOperationStep step, @Nonnull StringBuilder text
		) {
			if (step instanceof MethodInvocationStep method) {
				switch (method.getMethodIdentifier()) {
					case "size", "any", "all", "none" -> walkArguments(method, elementsOf(receiver));
					default -> refuse(text.toString(), RefusalReason.MISSING);
				}
				return PLAIN_ALTERNATIVES;
			} else if (step instanceof SpreadAccessStep spread) {
				return walkSpread(receiver, spread);
			} else if (step instanceof ElementAccessStep) {
				// the text of the list, without the index
				refuse(textBefore(text, step), RefusalReason.POSITIONAL);
				return PLAIN_ALTERNATIVES;
			}
			refuse(text.toString(), RefusalReason.MISSING);
			return PLAIN_ALTERNATIVES;
		}

		/**
		 * Applies a step to a plain value: every step is allowed, its keys, arguments and mappings are walked. An
		 * element, a method's predicate and a spread mapping read any shape an element of the value may have.
		 *
		 * @param receiver  the value
		 * @param step      the step
		 * @param boundThis the shapes `$` may stand for, or `null`
		 * @return the alternatives the step produces
		 */
		@Nonnull
		private List<Alternative> valueStep(
			@Nonnull Alternative receiver, @Nonnull ObjectOperationStep step, @Nullable List<Alternative> boundThis
		) {
			if (step instanceof ElementAccessStep element) {
				walkKey(element.getElementIdentifierOperand(), boundThis);
				return elementsOf(receiver);
			} else if (step instanceof MethodInvocationStep method) {
				walkArguments(method, elementsOf(receiver));
			} else if (step instanceof SpreadAccessStep spread) {
				return walkSpread(receiver, spread);
			}
			return PLAIN_ALTERNATIVES;
		}

		/**
		 * Walks the key of an element access that selects within a value - an index or a locale - rather than names
		 * data: a constant, also in parentheses, passes; any other key is walked as nested, so that with the histogram
		 * rules it may read no `$entity` or `$reference` data.
		 *
		 * @param key       the key
		 * @param boundThis the shapes `$` may stand for, or `null`
		 */
		private void walkKey(@Nonnull ExpressionNode key, @Nullable List<Alternative> boundThis) {
			if (!(unwrapParentheses(key) instanceof ConstantOperand)) {
				consume(walk(key, boundThis, true));
			}
		}

		/**
		 * Walks the arguments of a method as predicates in which `$` stands for an element.
		 *
		 * @param method   the method step
		 * @param elements the shapes `$` may stand for
		 */
		private void walkArguments(@Nonnull MethodInvocationStep method, @Nonnull List<Alternative> elements) {
			for (final ExpressionNode argument : method.getArgumentOperands()) {
				consume(walk(argument, elements, true));
			}
		}
	}

}
