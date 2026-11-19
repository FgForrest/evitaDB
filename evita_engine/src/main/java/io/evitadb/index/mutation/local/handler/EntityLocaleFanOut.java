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

package io.evitadb.index.mutation.local.handler;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.dataType.Scope;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Withdraws, before a mutation that may change the owner entity's locale set is applied, the histogram values of every
 * stored reference whose local histogram reads a localized reference attribute with a `??` default, and schedules
 * their final add for the current run.
 *
 * Such a value is contributed in every locale of the owner entity - associated data included - in which the reference
 * holds no value of its own (`ReferenceIndexMutator#resolveHistogramLocales`), so what the reference holds depends on
 * the entity's locale set, not only on the reference. The locale set (`EntityBodyStoragePart#getLocales`) is written by
 * four places in `ContainerizedLocalMutationExecutor`, and every one of them is shadowed here:
 *
 * - `recomputeLanguageOnAttributeUpdate` adding a locale - a localized entity attribute or reference attribute upserted
 *   (`UpsertAttributeMutationHandler`, `ReferenceAttributeMutationHandler`);
 * - `recomputeLanguageOnAttributeUpdate` removing a locale - a localized entity attribute or reference attribute
 *   removed (`RemoveAttributeMutationHandler`, `ReferenceAttributeMutationHandler`);
 * - `updateAssociatedData` - localized associated data upserted or removed (`UpsertAssociatedDataMutationHandler`,
 *   `RemoveAssociatedDataMutationHandler`);
 * - `removeEntireReference` - a reference holding localized attributes removed (`RemoveReferenceMutationHandler`).
 *
 * The index executor receives each of those mutations before the storage executor applies it, so the withdrawal reads
 * the locale set the reference's last add used, and the final add of the run reads the set the run left. A reference
 * already awaiting its final add is skipped by
 * {@link EntityIndexLocalMutationExecutor#withdrawHistogramValuesAndScheduleFinalAdd(ReferenceKey, Scope)}. The
 * withdrawal is conservative: an upsert in a locale the entity has already is the only change recognized as leaving
 * the set as it is, so a removal that leaves it unchanged costs a withdrawal and a re-add of the same values. The hooks
 * of the two reference mutations run before `ReferenceMutationFanOut#apply`, because it returns early for a reference
 * not indexed in the scope, whose locales still count, and because for a removal it withdraws the removed reference
 * and cancels its final add - a withdrawal after that would ask for values that are gone. The removal of the whole
 * entity withdraws nothing: it schedules no final add, and removes the values present for the owner instead.
 *
 * **A new writer of the entity's locale set must call this class before it is applied.** Otherwise the next
 * withdrawal of a defaulted value reads a locale set the last add did not use and can encounter a missing value.
 *
 * Cost: for an owner type without such a histogram, a localized mutation or a reference removal pays one registry map
 * probe, a non-localized one nothing. For an owner type with one, an upsert in a locale the entity has already adds a
 * set lookup on the cached entity body. A change that may move the locale set walks the N stored references and
 * withdraws the K selected ones; each withdrawal resolves its reference's attribute supplier by a linear scan of the
 * references and evaluates every histogram trigger of the reference, not only the defaulted one, so the work is
 * O(N·K) - quadratic when most references carry such a histogram. No benchmark was run.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class EntityLocaleFanOut {

	/**
	 * Prevents instantiation of the shared locale-set orchestration.
	 */
	private EntityLocaleFanOut() {
		// no instances
	}

	/**
	 * What a change of a localized value can do to the locale set of the entity that owns it.
	 */
	enum LocalizedValueChange {

		/**
		 * The value is upserted; it adds its locale to the entity unless the entity has that locale already.
		 */
		UPSERT,

		/**
		 * The value is removed; its locale may leave the entity, which depends on every other value of the entity, so
		 * it is not examined further.
		 */
		REMOVAL

	}

	/**
	 * Withdraws the references carrying a localized defaulted histogram value before a reference attribute mutation
	 * that may change the entity's locale set is applied. The mutation wraps an upsert, a removal or a delta of the
	 * attribute: an upsert or a removal of a localized attribute may change the locale set, an applied delta changes a
	 * value the entity has already and never its locales.
	 *
	 * @param executor          the active executor
	 * @param attributeMutation the attribute mutation wrapped by the reference attribute mutation about to be applied
	 */
	static void withdrawBeforeReferenceAttributeChange(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull AttributeMutation attributeMutation
	) {
		switch (attributeMutation) {
			case UpsertAttributeMutation upsert -> withdrawBeforeLocalizedValueChange(
				executor, upsert.getAttributeKey().locale(), LocalizedValueChange.UPSERT
			);
			case RemoveAttributeMutation removal -> withdrawBeforeLocalizedValueChange(
				executor, removal.getAttributeKey().locale(), LocalizedValueChange.REMOVAL
			);
			case ApplyDeltaAttributeMutation<?> delta -> {
				// a delta changes a value the entity has already, never its locale set
			}
		}
	}

	/**
	 * Withdraws the references carrying a localized defaulted histogram value before a localized value is upserted or
	 * removed. An upsert in a locale the entity has already cannot change its locale set and withdraws nothing; a
	 * removal is not examined further, because whether the locale survives it depends on every other value of the
	 * entity.
	 *
	 * @param executor the active executor
	 * @param locale   the locale of the value, or `null` for a non-localized value, which withdraws nothing
	 * @param change   whether the value is upserted or removed
	 */
	static void withdrawBeforeLocalizedValueChange(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable Locale locale,
		@Nonnull LocalizedValueChange change
	) {
		if (locale == null) {
			return;
		}
		final Set<String> referenceNames = getReferencesWithLocalizedDefault(executor);
		if (referenceNames.isEmpty()) {
			return;
		}
		// an upsert in a locale the entity has already leaves its locale set unchanged
		if (change == LocalizedValueChange.UPSERT && getEntityLocales(executor).contains(locale)) {
			return;
		}
		OwnerInputHistogramWithdrawal.withdraw(executor, referenceNames, executor.getScope());
	}

	/**
	 * Withdraws the references carrying a localized defaulted histogram value before a reference is removed, when the
	 * removed reference holds a localized attribute - its locales may leave the entity with it. The removed reference
	 * is withdrawn here too when it carries such a value; the removal then finds it awaiting and cancels its final add.
	 * A reference missing from storage withdraws nothing: the withdrawal of the removal itself refuses it.
	 *
	 * @param executor     the active executor
	 * @param referenceKey the key of the reference about to be removed
	 */
	static void withdrawBeforeReferenceRemoval(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey
	) {
		final Set<String> referenceNames = getReferencesWithLocalizedDefault(executor);
		if (referenceNames.isEmpty()) {
			return;
		}
		final ReferenceContract removedReference =
			executor.getReferencesStoragePart().findReference(referenceKey).orElse(null);
		// `findReference` answers existing references only; one without localized attributes gives the entity no locale
		if (removedReference == null || removedReference.getAttributeLocales().isEmpty()) {
			return;
		}
		OwnerInputHistogramWithdrawal.withdraw(executor, referenceNames, executor.getScope());
	}

	/**
	 * Returns the names of the references whose local histogram reads a localized reference attribute with a `??`
	 * default in the current scope - one registry map probe when the owner type has none - or an empty set when the
	 * catalog has no trigger registry or the entity is being removed entirely. Entity removal schedules no final add
	 * and asserts that nothing awaits one, and it reads the locales the entity had before the removal instead.
	 *
	 * @param executor the active executor
	 * @return the reference names, or an empty set when nothing is to be withdrawn
	 */
	@Nonnull
	private static Set<String> getReferencesWithLocalizedDefault(@Nonnull EntityIndexLocalMutationExecutor executor) {
		final CatalogExpressionTriggerRegistry registry = executor.getCatalogExpressionTriggerRegistry();
		if (registry == null) {
			return Collections.emptySet();
		}
		final Set<String> referenceNames = registry.getLocalHistogramReferencesWithLocalizedDefault(
			executor.getEntityType(), executor.getScope()
		);
		if (referenceNames.isEmpty() || executor.getContainerAccessor().isEntityRemovedEntirely()) {
			return Collections.emptySet();
		}
		return referenceNames;
	}

	/**
	 * Returns the locales of the entity as its in-flight body has them before the mutation being dispatched.
	 *
	 * @param executor the active executor
	 * @return the entity's locales, attributes and associated data together
	 */
	@Nonnull
	private static Set<Locale> getEntityLocales(@Nonnull EntityIndexLocalMutationExecutor executor) {
		return executor.getContainerAccessor()
			.getEntityStoragePart(
				executor.getEntityType(),
				executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING),
				EntityExistence.MUST_EXIST
			)
			.getLocales();
	}

}
