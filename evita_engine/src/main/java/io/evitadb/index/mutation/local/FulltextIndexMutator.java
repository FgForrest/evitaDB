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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps the per-locale {@link FulltextIndex fulltext indexes} of {@link GlobalEntityIndex} in step with the searchable
 * attributes of an entity. The fulltext structures live in the global index only, so every method here works on the
 * global index of the scope being written and never fans out over the reduced indexes.
 *
 * Three kinds of write reach it:
 *
 * - **an entity attribute** (global attributes included) - the field is
 *   {@link FulltextFieldKey#attribute(String) the attribute itself}, and an update is the removal of the old value
 *   followed by the indexing of the new one;
 * - **a reference attribute** - the field is {@link FulltextFieldKey#referenceAttribute(String, String) the attribute
 *   of the reference}, and its value is the **set union** of the attribute over all the entity's references of that
 *   name. A change that neither adds a member to that set nor removes one touches no index at all;
 * - **a scope change** - the entity leaves the fulltext indexes of its old scope and enters those of the new one with
 *   everything it holds.
 *
 * Every index is updated at the mutation that changes it, so that between two mutations the fulltext indexes answer
 * for every value the entity's storage parts hold that was written while its attribute was searchable - the same
 * invariant the attribute indexes keep, which is what lets a scope change remove exactly what is indexed. A value
 * stored before its attribute became searchable was never indexed, and removing it is a no-op (see
 * {@link FulltextIndex#removeValue(FulltextFieldKey, int, String[])}).
 *
 * **Caller ordering.** Both the value an update removes and the union "before" a reference change are read from the
 * storage parts as they stand before the mutation, so every method here must run in the index executor, before the
 * storage executor applies the same local mutation. The reference hook runs before the `isIndexedInScope` gate of the
 * reference fan-out, because the fulltext structures live in the global index whether the reference is indexed or
 * not. The local mutations of an entity arrive ordered as removals, then a scope change, then upserts.
 *
 * **Withdrawal retires lazily.** A write to an attribute that is no longer searchable in the scope retires its field in
 * the locale's index, if the index still knows it: the field keeps its postings as dead weight until #409 rebuilds the
 * index, and a later re-declaration starts with an empty field - results are only ever incomplete, never phantom. See
 * {@link FulltextIndex#retireField(FulltextFieldKey)}.
 *
 * Only localized `String` and `String[]` attributes can be searchable - the schema refuses any other - so a write of a
 * non-localized attribute or of a delta returns immediately.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface FulltextIndexMutator {

	/**
	 * Applies an entity attribute mutation to the fulltext index of its locale in `globalIndex`'s scope: removes the
	 * value the entity holds now and indexes the upserted one - a removal mutation only removes - or retires the field
	 * when the attribute is no longer searchable in the scope.
	 *
	 * @param executor              the executor of the entity mutation
	 * @param globalIndex           the global index of the scope being written
	 * @param mutation              the attribute mutation
	 * @param existingValueSupplier supplies the value the entity holds before the mutation
	 */
	static void executeAttributeMutation(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull AttributeMutation mutation,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier
	) {
		final AttributeKey attributeKey = mutation.getAttributeKey();
		final Locale locale = attributeKey.locale();
		if (locale == null || mutation instanceof ApplyDeltaAttributeMutation<?>) {
			return;
		}
		final String attributeName = attributeKey.attributeName();
		final AttributeSchemaContract attributeSchema = executor.getEntitySchema()
			.getAttribute(attributeName)
			.orElseThrow(() -> new GenericEvitaInternalError(
				"Attribute `" + attributeName + "` is not defined in the schema of `" + executor.getEntityType() + "`!"
			));
		final FulltextFieldKey fieldKey = FulltextFieldKey.attribute(attributeName);
		if (!attributeSchema.isSearchableInScope(globalIndex.getIndexKey().scope())) {
			retireField(globalIndex, locale, fieldKey);
			return;
		}

		final AttributeValue existingValue = existingValueSupplier.getAttributeValue(attributeKey).orElse(null);
		if (existingValue != null && existingValue.value() != null) {
			removeValue(
				globalIndex, locale, fieldKey,
				executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING),
				distinctValues(existingValue.value())
			);
		}
		if (mutation instanceof UpsertAttributeMutation upsertMutation) {
			addValue(
				executor, globalIndex, locale, fieldKey,
				executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW),
				distinctValues(toSchemaType(upsertMutation.getAttributeValue(), attributeSchema))
			);
		}
	}

	/**
	 * Applies a reference mutation to the fulltext index: a reference attribute mutation, or the removal of
	 * a reference, may change the union of a searchable reference attribute over the entity's references of that name.
	 * Inserting a reference never does - its attributes arrive in mutations of their own.
	 *
	 * @param executor    the executor of the entity mutation
	 * @param globalIndex the global index of the scope being written
	 * @param mutation    the reference mutation
	 */
	static void executeReferenceMutation(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull ReferenceMutation<?> mutation
	) {
		if (mutation instanceof ReferenceAttributeMutation attributeMutation) {
			executeReferenceAttributeMutation(executor, globalIndex, attributeMutation);
		} else if (mutation instanceof RemoveReferenceMutation removeMutation) {
			executeReferenceRemoval(executor, globalIndex, removeMutation.getReferenceKey());
		}
	}

	/**
	 * Removes everything `entity` has indexed in the fulltext indexes of `globalIndex`'s scope - the entity is leaving
	 * the scope. A field that is no longer searchable there is retired instead.
	 *
	 * @param executor    the executor of the entity mutation
	 * @param globalIndex the global index of the scope the entity leaves
	 * @param entity      the entity, as its storage parts hold it
	 */
	static void unindexEntity(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Entity entity
	) {
		final int primaryKey = entity.getPrimaryKeyOrThrowException();
		forEachSearchableValue(
			executor.getEntitySchema(), globalIndex, entity,
			(fieldKey, locale, values) -> removeValue(globalIndex, locale, fieldKey, primaryKey, values)
		);
	}

	/**
	 * Indexes everything searchable `entity` holds into the fulltext indexes of `globalIndex`'s scope - the entity is
	 * entering the scope. A field that is not searchable there is retired if the index still knows it.
	 *
	 * @param executor    the executor of the entity mutation
	 * @param globalIndex the global index of the scope the entity enters
	 * @param entity      the entity, as its storage parts hold it
	 */
	static void indexEntity(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Entity entity
	) {
		final int primaryKey = entity.getPrimaryKeyOrThrowException();
		forEachSearchableValue(
			executor.getEntitySchema(), globalIndex, entity,
			(fieldKey, locale, values) -> addValue(executor, globalIndex, locale, fieldKey, primaryKey, values)
		);
	}

	/**
	 * Applies an attribute mutation of one reference: recomputes the union of the attribute over the entity's
	 * references of that name, before and after the mutation, and replaces the indexed union when the two differ. Both
	 * unions are computed from the references storage part as it stands before the mutation. A field that is not
	 * searchable in the scope - or whose attribute the reference schema no longer declares - is retired instead.
	 *
	 * @param executor    the executor of the entity mutation
	 * @param globalIndex the global index of the scope being written
	 * @param mutation    the reference attribute mutation
	 */
	private static void executeReferenceAttributeMutation(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull ReferenceAttributeMutation mutation
	) {
		final AttributeMutation attributeMutation = mutation.getAttributeMutation();
		final AttributeKey attributeKey = attributeMutation.getAttributeKey();
		final Locale locale = attributeKey.locale();
		if (locale == null || attributeMutation instanceof ApplyDeltaAttributeMutation<?>) {
			return;
		}
		final ReferenceKey referenceKey = mutation.getReferenceKey();
		final String referenceName = referenceKey.referenceName();
		final String attributeName = attributeKey.attributeName();
		// an attribute the reference schema no longer declares counts as not searchable, as its stored values may
		// outlive its schema
		final AttributeSchemaContract attributeSchema = executor.getEntitySchema()
			.getReferenceOrThrowException(referenceName)
			.getAttribute(attributeName)
			.orElse(null);
		final FulltextFieldKey fieldKey = FulltextFieldKey.referenceAttribute(referenceName, attributeName);
		if (attributeSchema == null || !attributeSchema.isSearchableInScope(globalIndex.getIndexKey().scope())) {
			retireField(globalIndex, locale, fieldKey);
			return;
		}

		final Collection<ReferenceContract> references =
			executor.getReferencesStoragePart().getReferencesAsCollection();
		final Serializable newValue = attributeMutation instanceof UpsertAttributeMutation upsertMutation ?
			toSchemaType(upsertMutation.getAttributeValue(), attributeSchema) : null;
		replaceUnion(
			executor, globalIndex, locale, fieldKey,
			collectUnion(references, referenceName, attributeKey, null, null),
			collectUnion(references, referenceName, attributeKey, referenceKey, newValue)
		);
	}

	/**
	 * Applies the removal of one reference: for every localized attribute the removed reference carries, recomputes
	 * the union over the entity's references of that name without it, and replaces the indexed union when the two
	 * differ. The union "before" is computed from the references storage part as it stands before the removal, so it
	 * still includes the removed reference. A field that is not searchable in the scope - or whose attribute the
	 * reference schema no longer declares - is retired instead.
	 *
	 * @param executor     the executor of the entity mutation
	 * @param globalIndex  the global index of the scope being written
	 * @param referenceKey the key of the removed reference
	 */
	private static void executeReferenceRemoval(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull ReferenceKey referenceKey
	) {
		final Collection<ReferenceContract> references =
			executor.getReferencesStoragePart().getReferencesAsCollection();
		ReferenceContract removedReference = null;
		for (final ReferenceContract reference : references) {
			if (
				reference.exists() &&
					ReferenceKey.FULL_COMPARATOR.compare(reference.getReferenceKey(), referenceKey) == 0
			) {
				removedReference = reference;
				break;
			}
		}
		if (removedReference == null) {
			return;
		}

		final String referenceName = referenceKey.referenceName();
		final ReferenceSchemaContract referenceSchema =
			executor.getEntitySchema().getReferenceOrThrowException(referenceName);
		final Scope scope = globalIndex.getIndexKey().scope();
		for (final AttributeValue attributeValue : removedReference.getAttributeValues()) {
			final AttributeKey attributeKey = attributeValue.key();
			final Locale locale = attributeKey.locale();
			if (locale == null || !attributeValue.exists()) {
				continue;
			}
			final String attributeName = attributeKey.attributeName();
			final FulltextFieldKey fieldKey = FulltextFieldKey.referenceAttribute(referenceName, attributeName);
			final boolean searchable = referenceSchema.getAttribute(attributeName)
				.map(it -> it.isSearchableInScope(scope))
				.orElse(false);
			if (searchable) {
				replaceUnion(
					executor, globalIndex, locale, fieldKey,
					collectUnion(references, referenceName, attributeKey, null, null),
					collectUnion(references, referenceName, attributeKey, referenceKey, null)
				);
			} else {
				retireField(globalIndex, locale, fieldKey);
			}
		}
	}

	/**
	 * Visits every searchable value `entity` holds in the scope of `globalIndex`: each localized entity attribute, and
	 * the union of each localized reference attribute over the references of its name. A field that is not searchable
	 * in the scope is retired instead of visited.
	 *
	 * @param entitySchema the schema of the entity
	 * @param globalIndex  the global index of the scope
	 * @param entity       the entity
	 * @param consumer     receives the field, the locale and the distinct values of the field
	 */
	private static void forEachSearchableValue(
		@Nonnull EntitySchema entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Entity entity,
		@Nonnull SearchableValueConsumer consumer
	) {
		final Scope scope = globalIndex.getIndexKey().scope();
		for (final AttributeValue attributeValue : entity.getAttributeValues()) {
			final Locale locale = attributeValue.key().locale();
			if (locale == null || !attributeValue.exists() || attributeValue.value() == null) {
				continue;
			}
			final String attributeName = attributeValue.key().attributeName();
			final FulltextFieldKey fieldKey = FulltextFieldKey.attribute(attributeName);
			final boolean searchable = entitySchema.getAttribute(attributeName)
				.map(it -> it.isSearchableInScope(scope))
				.orElse(false);
			if (searchable) {
				consumer.accept(fieldKey, locale, distinctValues(attributeValue.value()));
			} else {
				retireField(globalIndex, locale, fieldKey);
			}
		}

		// the unions of reference attributes, collected over all references before any is visited
		final Map<UnionKey, Set<String>> unions = CollectionUtils.createLinkedHashMap(8);
		for (final ReferenceContract reference : entity.getReferences()) {
			if (!reference.exists()) {
				continue;
			}
			final String referenceName = reference.getReferenceName();
			final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
			for (final AttributeValue attributeValue : reference.getAttributeValues()) {
				final Locale locale = attributeValue.key().locale();
				if (locale == null || !attributeValue.exists() || attributeValue.value() == null) {
					continue;
				}
				final String attributeName = attributeValue.key().attributeName();
				final boolean searchable = referenceSchema.getAttribute(attributeName)
					.map(it -> it.isSearchableInScope(scope))
					.orElse(false);
				if (searchable) {
					collectValues(
						attributeValue.value(),
						unions.computeIfAbsent(
							new UnionKey(referenceName, attributeName, locale),
							key -> CollectionUtils.createLinkedHashSet(4)
						)
					);
				} else {
					retireField(globalIndex, locale, FulltextFieldKey.referenceAttribute(referenceName, attributeName));
				}
			}
		}
		for (final Map.Entry<UnionKey, Set<String>> entry : unions.entrySet()) {
			final UnionKey key = entry.getKey();
			consumer.accept(
				FulltextFieldKey.referenceAttribute(key.referenceName(), key.attributeName()), key.locale(),
				entry.getValue()
			);
		}
	}

	/**
	 * Collects the union of a reference attribute over the existing references of a name. When `replacedReference` is
	 * passed, the reference of that key contributes `replacement` instead of its own value - nothing at all when
	 * `replacement` is `null` - which is how the union after a mutation is computed from the state before it.
	 *
	 * @param references        all references of the entity, before the mutation
	 * @param referenceName     the name of the references to unite
	 * @param attributeKey      the attribute and locale to unite
	 * @param replacedReference the reference whose value is replaced, or `null` for the union as it is
	 * @param replacement       the value replacing the one of `replacedReference`, or `null` for none
	 * @return the distinct values of the union
	 */
	@Nonnull
	private static Set<String> collectUnion(
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull String referenceName,
		@Nonnull AttributeKey attributeKey,
		@Nullable ReferenceKey replacedReference,
		@Nullable Serializable replacement
	) {
		final Set<String> union = CollectionUtils.createLinkedHashSet(8);
		boolean replaced = false;
		for (final ReferenceContract reference : references) {
			if (!reference.exists() || !referenceName.equals(reference.getReferenceName())) {
				continue;
			}
			// the full comparison tells apart duplicates of one target - `equals` takes a not yet persisted
			// (negative) internal primary key for a match of every duplicate
			if (
				replacedReference != null &&
					ReferenceKey.FULL_COMPARATOR.compare(reference.getReferenceKey(), replacedReference) == 0
			) {
				replaced = true;
				if (replacement != null) {
					collectValues(replacement, union);
				}
			} else {
				for (final AttributeValue attributeValue : reference.getAttributeValues()) {
					if (attributeValue.exists() && attributeValue.value() != null &&
						attributeKey.equals(attributeValue.key())) {
						collectValues(attributeValue.value(), union);
					}
				}
			}
		}
		// defensive: a reference inserted earlier in the same entity mutation is already in the storage part, so this
		// is reached only for a reference the storage part does not hold - whose mutation the storage executor rejects
		if (!replaced && replacement != null) {
			collectValues(replacement, union);
		}
		return union;
	}

	/**
	 * Replaces the indexed union of a reference attribute: does nothing when the union did not change as a set,
	 * otherwise removes the old union and indexes the new one.
	 *
	 * @param executor    the executor of the entity mutation
	 * @param globalIndex the global index of the scope being written
	 * @param locale      the locale of the attribute
	 * @param fieldKey    the field of the reference attribute
	 * @param before      the union before the mutation
	 * @param after       the union after the mutation
	 */
	private static void replaceUnion(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		@Nonnull Set<String> before,
		@Nonnull Set<String> after
	) {
		if (before.equals(after)) {
			return;
		}
		removeValue(
			globalIndex, locale, fieldKey, executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING),
			before
		);
		addValue(
			executor, globalIndex, locale, fieldKey, executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW),
			after
		);
	}

	/**
	 * Indexes the values of an entity's field, creating the fulltext index of the locale when there is none yet.
	 * Nothing is indexed - and no index created - for no values.
	 *
	 * @param executor    the executor of the entity mutation
	 * @param globalIndex the global index of the scope being written
	 * @param locale      the locale of the values
	 * @param fieldKey    the field
	 * @param primaryKey  the primary key of the entity
	 * @param values      the distinct values of the field
	 */
	private static void addValue(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey,
		@Nonnull Set<String> values
	) {
		if (values.isEmpty()) {
			return;
		}
		globalIndex.getOrCreateFulltextIndex(
			locale, executor.getFulltextAnalyzerRegistry().getIndexAnalyzer(executor.getEntityType(), locale)
		).addValue(fieldKey, primaryKey, values.toArray(String[]::new));
	}

	/**
	 * Removes the values of an entity's field from the fulltext index of the locale. Without an index for the locale
	 * there is nothing to remove, and none is created for it.
	 *
	 * @param globalIndex the global index of the scope being written
	 * @param locale      the locale of the values
	 * @param fieldKey    the field
	 * @param primaryKey  the primary key of the entity
	 * @param values      the distinct values the field holds
	 */
	private static void removeValue(
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey,
		@Nonnull Set<String> values
	) {
		final FulltextIndex fulltextIndex = globalIndex.getFulltextIndex(locale);
		if (fulltextIndex != null && !values.isEmpty()) {
			fulltextIndex.removeValue(fieldKey, primaryKey, values.toArray(String[]::new));
		}
	}

	/**
	 * Retires the field in the fulltext index of the locale, if the index has one registered under the key - the lazy
	 * reconciliation of an attribute that stopped being searchable.
	 *
	 * @param globalIndex the global index of the scope being written
	 * @param locale      the locale of the written value
	 * @param fieldKey    the field of the attribute
	 */
	private static void retireField(
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey
	) {
		final FulltextIndex fulltextIndex = globalIndex.getFulltextIndex(locale);
		if (fulltextIndex != null) {
			fulltextIndex.retireField(fieldKey);
		}
	}

	/**
	 * Returns the distinct elements of a searchable value.
	 *
	 * @param value a `String` or a `String[]`
	 * @return a new set of the elements
	 */
	@Nonnull
	private static Set<String> distinctValues(@Nonnull Serializable value) {
		return collectValues(value, CollectionUtils.createLinkedHashSet(4));
	}

	/**
	 * Adds the elements of a searchable value to `target`.
	 *
	 * @param value  a `String` or a `String[]`
	 * @param target the set collecting the values
	 * @return `target`
	 * @throws GenericEvitaInternalError when the value is of any other type, which the schema never lets a searchable
	 *                                   attribute hold
	 */
	@Nonnull
	private static Set<String> collectValues(@Nonnull Serializable value, @Nonnull Set<String> target) {
		if (value instanceof String text) {
			target.add(text);
		} else if (value instanceof String[] texts) {
			for (final String text : texts) {
				if (text != null) {
					target.add(text);
				}
			}
		} else {
			throw new GenericEvitaInternalError(
				"A searchable attribute must hold a `String` or a `String[]`, `" + value.getClass().getName() +
					"` was found!"
			);
		}
		return target;
	}

	/**
	 * Converts an upserted value to the type the attribute schema declares, as the attribute indexes do.
	 *
	 * @param value           the upserted value
	 * @param attributeSchema the schema of the attribute
	 * @return the converted value
	 */
	@Nonnull
	private static Serializable toSchemaType(
		@Nonnull Serializable value,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		return Objects.requireNonNull(
			EvitaDataTypes.toTargetType(value, attributeSchema.getType(), attributeSchema.getIndexedDecimalPlaces())
		);
	}

	/**
	 * Receives one searchable value of an entity.
	 */
	@FunctionalInterface
	interface SearchableValueConsumer {

		/**
		 * Receives the value.
		 *
		 * @param fieldKey the field
		 * @param locale   the locale of the value
		 * @param values   the distinct values of the field
		 */
		void accept(@Nonnull FulltextFieldKey fieldKey, @Nonnull Locale locale, @Nonnull Set<String> values);

	}

	/**
	 * Identifies the union of one reference attribute in one locale.
	 *
	 * @param referenceName the name of the references
	 * @param attributeName the name of the attribute
	 * @param locale        the locale
	 */
	record UnionKey(@Nonnull String referenceName, @Nonnull String attributeName, @Nonnull Locale locale) {
	}

}
