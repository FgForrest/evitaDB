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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Scope;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Immutable snapshot of pre-mutation histogram attribute values captured **before** a deferred lambda runs.
 * When a reference attribute mutation changes the value source of a histogram trigger, the old attribute
 * values must be read while the storage still holds pre-mutation data. This record captures those values
 * so they can later be used for precise surgical removal from the histogram FilterIndex — avoiding the
 * expensive O(references x buckets) full-reindex fallback.
 *
 * Only triggers whose {@link HistogramValueDescriptor#source()} is {@link HistogramValueSource#REFERENCE_ATTRIBUTE}
 * **and** whose {@link HistogramValueDescriptor#sourceAttributeName()} matches the mutated attribute are captured.
 * For localized attributes a separate entry is stored per locale; for non-localized attributes a single entry
 * is stored with a `null` locale key.
 *
 * Captured values are typed to {@link HistogramValueDescriptor#plainType()}; this is either a `Number` subtype
 * (plain numeric histograms) or a `Range` subtype (range-typed histograms).
 *
 * @param oldValuesByTrigger    map from captured trigger to per-locale old histogram values
 * @param oldConditionByTrigger map from each trigger to its pre-mutation condition result (`true` = the reference
 *                              was contributing to the histogram before the mutation)
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record PreMutationHistogramSnapshot(
	@Nonnull Map<HistogramExpressionTrigger, Map<Locale, Serializable[]>> oldValuesByTrigger,
	@Nonnull Map<HistogramExpressionTrigger, Boolean> oldConditionByTrigger
) {

	/**
	 * Builds the internal map by iterating over all triggers and capturing old attribute values
	 * for those whose value source is a reference attribute matching the changed attribute name.
	 *
	 * @param triggers         histogram triggers to inspect
	 * @param changedAttribute name of the attribute being mutated
	 * @param oldSupplier      supplier reading pre-mutation attribute values
	 * @return map from trigger to per-locale captured old values
	 */
	@Nonnull
	private static Map<HistogramExpressionTrigger, Map<Locale, Serializable[]>> buildOldValuesMap(
		@Nonnull Collection<HistogramExpressionTrigger> triggers,
		@Nonnull String changedAttribute,
		@Nonnull ExistingAttributeValueSupplier oldSupplier
	) {
		final Map<HistogramExpressionTrigger, Map<Locale, Serializable[]>> result = createHashMap(triggers.size());
		Set<Locale> locales = null;
		for (final HistogramExpressionTrigger trigger : triggers) {
			final HistogramValueDescriptor resolution = trigger.getValueDescriptor();
			if (
				resolution.source() == HistogramValueSource.REFERENCE_ATTRIBUTE
					&& resolution.sourceAttributeName().equals(changedAttribute)
			) {
				if (resolution.localized()) {
					if (locales == null) {
						locales = oldSupplier.getEntityExistingAttributeLocales();
					}
					final Map<Locale, Serializable[]> perLocale = createHashMap(locales.size());
					for (final Locale locale : locales) {
						final Serializable rawOldValue = ReferenceIndexMutator.readReferenceAttributeValue(
							oldSupplier, resolution.sourceAttributeName(), locale
						);
						perLocale.put(locale, ReferenceIndexMutator.resolveHistogramValues(rawOldValue, resolution));
					}
					result.put(trigger, perLocale);
				} else {
					final Serializable rawOldValue = ReferenceIndexMutator.readReferenceAttributeValue(
						oldSupplier, resolution.sourceAttributeName(), null
					);
					result.put(
						trigger,
						Collections.singletonMap(
							null, ReferenceIndexMutator.resolveHistogramValues(rawOldValue, resolution))
					);
				}
			}
		}
		return result;
	}

	/**
	 * Evaluates each trigger's condition against the current (pre-mutation) storage state and
	 * returns a map from trigger to its condition result.
	 *
	 * @param triggers        histogram triggers to evaluate
	 * @param ownerPK         the primary key of the owner entity
	 * @param referenceKey    identifies the specific reference
	 * @param storageAccessor accessor for reading pre-mutation storage data
	 * @param schemaResolver  resolver for entity schemas
	 * @param scope           the current scope
	 * @return map from each trigger to its pre-mutation condition result
	 */
	@Nonnull
	private static Map<HistogramExpressionTrigger, Boolean> evaluateOldConditions(
		@Nonnull Collection<HistogramExpressionTrigger> triggers,
		int ownerPK,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ContainerizedLocalMutationExecutor storageAccessor,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver,
		@Nonnull Scope scope
	) {
		final Map<HistogramExpressionTrigger, Boolean> result = createHashMap(triggers.size());
		for (final HistogramExpressionTrigger trigger : triggers) {
			result.put(trigger, trigger.evaluate(ownerPK, referenceKey, storageAccessor, schemaResolver, scope));
		}
		return result;
	}

	/**
	 * Captures pre-mutation histogram values only (no condition evaluation). Used when condition
	 * results are not needed — e.g. in tests or when the caller handles condition evaluation
	 * separately.
	 *
	 * @param triggers         histogram triggers to inspect
	 * @param changedAttribute name of the attribute being mutated
	 * @param oldSupplier      supplier reading pre-mutation attribute values from storage
	 */
	public PreMutationHistogramSnapshot(
		@Nonnull Collection<HistogramExpressionTrigger> triggers,
		@Nonnull String changedAttribute,
		@Nonnull ExistingAttributeValueSupplier oldSupplier
	) {
		this(buildOldValuesMap(triggers, changedAttribute, oldSupplier), Collections.emptyMap());
	}

	/**
	 * Captures pre-mutation histogram values and condition results for all triggers. Must be called
	 * **before** the mutation is written to storage.
	 *
	 * @param triggers         histogram triggers to inspect
	 * @param changedAttribute name of the attribute being mutated
	 * @param oldSupplier      supplier reading pre-mutation attribute values from storage
	 * @param ownerPK          the primary key of the owner entity
	 * @param referenceKey     identifies the specific reference
	 * @param storageAccessor  accessor for reading pre-mutation storage data
	 * @param schemaResolver   resolver for entity schemas
	 * @param scope            the current scope
	 */
	public PreMutationHistogramSnapshot(
		@Nonnull Collection<HistogramExpressionTrigger> triggers,
		@Nonnull String changedAttribute,
		@Nonnull ExistingAttributeValueSupplier oldSupplier,
		int ownerPK,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ContainerizedLocalMutationExecutor storageAccessor,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver,
		@Nonnull Scope scope
	) {
		this(
			buildOldValuesMap(triggers, changedAttribute, oldSupplier),
			evaluateOldConditions(triggers, ownerPK, referenceKey, storageAccessor, schemaResolver, scope)
		);
	}

	/**
	 * Returns whether the given trigger's value source attribute is the one that was mutated,
	 * i.e. whether old values were captured for this trigger.
	 *
	 * @param trigger the histogram trigger to check
	 * @return `true` if old values were captured for the trigger
	 */
	public boolean isValueSourceChanged(@Nonnull HistogramExpressionTrigger trigger) {
		return this.oldValuesByTrigger.containsKey(trigger);
	}

	/**
	 * Returns the per-locale map of old histogram values for a trigger whose value source was mutated.
	 * For non-localized attributes the map contains a single entry with a `null` locale key. The
	 * captured arrays carry either `Number` instances (plain-numeric histograms) or `Range` instances
	 * (range-typed histograms), matching the trigger's {@link HistogramValueDescriptor#plainType()}.
	 *
	 * @param trigger the histogram trigger to retrieve old values for
	 * @return unmodifiable map from locale (or `null`) to old histogram values
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the trigger was not captured
	 */
	@Nonnull
	public Map<Locale, Serializable[]> getOldValuesByLocale(@Nonnull HistogramExpressionTrigger trigger) {
		final Map<Locale, Serializable[]> result = this.oldValuesByTrigger.get(trigger);
		Assert.isPremiseValid(
			result != null,
			"Old values must be captured for value-source trigger: " + trigger.getHistogramIndexName()
		);
		return result;
	}

	/**
	 * Returns whether the trigger's condition was met (evaluated to `true`) in pre-mutation storage
	 * state. If the condition was `false`, the reference never contributed to the histogram and
	 * removal must be skipped to avoid corrupting another reference's cardinality.
	 *
	 * @param trigger the histogram trigger to check
	 * @return `true` if the trigger's condition was met before the mutation
	 */
	public boolean isOldConditionMet(@Nonnull HistogramExpressionTrigger trigger) {
		return Boolean.TRUE.equals(this.oldConditionByTrigger.get(trigger));
	}

}
