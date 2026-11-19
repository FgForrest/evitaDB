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

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.fulltext.FulltextFieldDefinition;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.RowValues;
import io.evitadb.index.fulltext.SearchableTextExtractor;
import io.evitadb.index.fulltext.SearchableTextExtractor.ExtractionMode;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps the per-locale {@link FulltextIndex fulltext indexes} of {@link GlobalEntityIndex} in step with the searchable
 * attributes and associated data of an entity. The fulltext structures live in the global index only, so every
 * method here works on the global index of the scope being written and never fans out over the reduced indexes.
 *
 * Four kinds of write reach it:
 *
 * - **an entity attribute** (global attributes included) - the field is
 *   {@link FulltextFieldKey#attribute(String) the attribute itself}, and an update is the removal of the old value
 *   followed by the indexing of the new one;
 * - **a reference attribute** - the field is {@link FulltextFieldKey#referenceAttribute(String, String) the attribute
 *   of the reference}, and its value is the **set union** of the attribute over all the entity's references of that
 *   name. The union postings change only when that set changes; the row dictionary also follows target provenance;
 * - **an associated data item** - the field is {@link FulltextFieldKey#associatedData(String) the associated data
 *   itself}, its text the value (a `String`, the elements of a `String[]`) or what the text expression selects from a
 *   complex data object (see {@link SearchableTextExtractor}); an update is a removal followed by an indexing, as for
 *   an attribute;
 * - **a scope change** - the entity leaves the fulltext indexes of its old scope and enters those of the new one with
 *   everything it holds.
 *
 * Every index is updated at the mutation that changes it, so that between two mutations the fulltext indexes answer
 * for every value the entity's storage parts hold that was written while its attribute was searchable - the same
 * invariant the attribute indexes keep, which is what lets a scope change remove exactly what is indexed. A value
 * stored before its attribute became searchable was never indexed, and removing it is a no-op (see
 * {@link FulltextIndex#removeValue}).
 *
 * **Caller ordering.** Both the value an update removes and the union "before" a reference change are read from the
 * storage parts as they stand before the mutation, so every method here must run in the index executor, before the
 * storage executor applies the same local mutation. The reference hook runs before the `isIndexedInScope` gate of the
 * reference fan-out, because the fulltext structures live in the global index whether the reference is indexed or
 * not. The local mutations of an entity arrive ordered as removals, then a scope change, then upserts.
 *
 * **Withdrawal retires lazily.** A write to an attribute or associated data that is no longer searchable in the scope
 * retires its field in the locale's index, if the index still knows it: the field keeps its postings as dead weight
 * until #409 rebuilds the index, and a later re-declaration starts with an empty field - results are only ever
 * incomplete, never phantom. See {@link FulltextIndex#retireField(FulltextFieldKey)}.
 *
 * Only localized `String` and `String[]` attributes can be searchable - the schema refuses any other - so a write of a
 * non-localized attribute or of a delta returns immediately. The same holds for non-localized associated data.
 *
 * **A field keeps the text expression it was built with.** Removing an associated data value re-extracts its text to
 * find the postings to drop, so it must extract exactly as the add did. The field records the expression it was
 * registered with ({@link FulltextIndex#getTextExpression(int)}), and every add and remove extracts with that one, even
 * after the schema declares another. A changed expression applies to a field built anew: after a withdrawal retired
 * it, or once #409 rebuilds it.
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
				executor, globalIndex, locale, fieldKey,
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
	 * Applies an associated data mutation to the fulltext index of its locale in `globalIndex`'s scope: removes the
	 * text of the value the entity holds now and indexes the text of the upserted one - a removal mutation only
	 * removes - or retires the field when the associated data is no longer searchable in the scope. An upsert of the
	 * very value the entity already holds touches nothing. The upserted value is extracted in the
	 * {@link ExtractionMode#STRICT strict} mode, so a value its text expression cannot select text from fails the
	 * write - unless the field still extracts with an expression other than the one the schema declares now, which
	 * the client cannot see and which therefore extracts the upserted value in the
	 * {@link ExtractionMode#LENIENT lenient} mode. The old value is always extracted in the lenient mode.
	 *
	 * The value the entity holds is read from its associated data storage part. The storage executor fetches the same
	 * part right after this method to apply the mutation, and the part is cached between the two, so reading it here
	 * costs no extra I/O; it is read only for associated data searchable in the scope.
	 *
	 * @param executor    the executor of the entity mutation
	 * @param globalIndex the global index of the scope being written
	 * @param mutation    the associated data mutation
	 */
	static void executeAssociatedDataMutation(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull AssociatedDataMutation mutation
	) {
		final AssociatedDataKey associatedDataKey = mutation.getAssociatedDataKey();
		final Locale locale = associatedDataKey.locale();
		if (locale == null) {
			return;
		}
		final String associatedDataName = associatedDataKey.associatedDataName();
		final AssociatedDataSchemaContract associatedDataSchema = executor.getEntitySchema()
			.getAssociatedData(associatedDataName)
			.orElseThrow(() -> new GenericEvitaInternalError(
				"Associated data `" + associatedDataName + "` is not defined in the schema of `" +
					executor.getEntityType() + "`!"
			));
		final FulltextFieldKey fieldKey = FulltextFieldKey.associatedData(associatedDataName);
		if (!associatedDataSchema.isSearchableInScope(globalIndex.getIndexKey().scope())) {
			retireField(globalIndex, locale, fieldKey);
			return;
		}

		final AssociatedDataValue existingValue = executor.getAssociatedDataStoragePart(associatedDataKey).getValue();
		final Serializable existing = existingValue != null && existingValue.exists() ? existingValue.value() : null;
		final Serializable upserted = mutation instanceof UpsertAssociatedDataMutation upsertMutation ?
			upsertMutation.getAssociatedDataValue() : null;
		if (existing != null && Objects.deepEquals(existing, upserted) &&
			isIndexedInCurrentField(
				executor, globalIndex, locale, fieldKey,
				executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING)
			)) {
			// the very value is written again - its text is indexed already
			return;
		}
		final String entityType = executor.getEntityType();
		final Expression textExpression = resolveTextExpression(globalIndex, locale, fieldKey, associatedDataSchema);
		// the value the client writes is extracted strictly - a value that does not fit the expression fails the
		// write - and before anything is removed, so a refused write leaves the index exactly as it was. Only while
		// the field extracts with the expression the schema declares, though: a field still built with an older one
		// (TOBEDONE JNO #409) applies an expression the client can no longer see, so it cannot fail the write
		final ExtractionMode upsertMode = textExpression == associatedDataSchema.getTextExpression() ?
			ExtractionMode.STRICT : ExtractionMode.LENIENT;
		final Set<String> upsertedTexts = upserted == null ?
			null :
			SearchableTextExtractor.extract(upserted, textExpression, entityType, associatedDataName, upsertMode);
		if (existing != null) {
			// the old value is re-extracted leniently, and that removes exactly what its add indexed: a value that
			// passed the strict mode extracts the same texts leniently, and a value added leniently (a scope change)
			// is removed leniently the very same way
			removeValue(
				executor, globalIndex, locale, fieldKey,
				executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING),
				SearchableTextExtractor.extract(
					existing, textExpression, entityType, associatedDataName, ExtractionMode.LENIENT
				)
			);
		}
		if (upsertedTexts != null) {
			addValue(
				executor, globalIndex, locale, fieldKey,
				executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW),
				upsertedTexts,
				toExpressionString(textExpression)
			);
		}
	}

	/**
	 * Applies a reference mutation to the fulltext index: a reference attribute mutation, or the removal of
	 * a reference, may change the union of a searchable reference attribute over the entity's references of that name.
	 * A bare insertion also maintains row counts and exceptions in every existing reference field.
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
		} else if (mutation instanceof InsertReferenceMutation insertMutation) {
			reconcileReferenceRows(executor, globalIndex,
				executor.getReferencesStoragePart().getReferencesAsCollection(),
				insertMutation.getReferenceKey(), 1);
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
		reconcileEntityReferences(executor, globalIndex, entity, false);
		forEachSearchableValue(
			executor.getEntitySchema(), globalIndex, entity,
			(fieldKey, locale, values, textExpression) -> removeValue(executor, globalIndex, locale, fieldKey,
				primaryKey, values)
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
		reconcileEntityReferences(executor, globalIndex, entity, true);
		forEachSearchableValue(
			executor.getEntitySchema(), globalIndex, entity,
			(fieldKey, locale, values, textExpression) -> addValue(
				executor, globalIndex, locale, fieldKey, primaryKey, values, textExpression
			)
		);
	}

	/**
	 * Clears reference-field enrollment at final entity deletion, after its individual reference removals.
	 * @param executor mutation executor with all row deltas already applied
	 * @param globalIndex executor-acquired scope index
	 */
	static void removeEntityEnrollment(
		@Nonnull EntityIndexLocalMutationExecutor executor, @Nonnull GlobalEntityIndex globalIndex
	) {
		for (final UnionKey key : referenceFields(globalIndex, List.of(), null)) {
			if (!isReferenceFieldSearchable(executor, globalIndex, key)) {
				continue;
			}
			reconcileReferenceField(executor, globalIndex, key.locale(),
				FulltextFieldKey.referenceAttribute(key.referenceName(), key.attributeName()),
				List.of(), List.of(), false);
		}
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
		reconcileReferenceField(executor, globalIndex, locale, fieldKey,
			collectRows(references, referenceName, attributeKey, null, null, 0),
			collectRows(references, referenceName, attributeKey, referenceKey, newValue, 0), true);
		replaceUnion(
			executor, globalIndex, locale, fieldKey,
			collectUnion(references, referenceName, attributeKey, null, null),
			collectUnion(references, referenceName, attributeKey, referenceKey, newValue)
		);
	}

	/**
	 * Reconciles row counts and owner membership for every registered reference field and locale, including fields
	 * absent from the removed row. Recomputes each union without that reference and replaces it when changed. The
	 * pre-storage image still includes the removed reference. Fields no longer searchable or declared are retired.
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

		reconcileReferenceRows(executor, globalIndex, references, referenceKey, -1);
	}

	/**
	 * Reconciles bare insertion/removal for all registered fields, including attributes absent from the changed row.
	 * @param executor mutation executor
	 * @param globalIndex current scope index, acquired through the executor
	 * @param references live pre-storage image
	 * @param changedRow exact changed row identity
	 * @param rowChange one for insertion, minus one for removal
	 */
	private static void reconcileReferenceRows(
		@Nonnull EntityIndexLocalMutationExecutor executor, @Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Collection<ReferenceContract> references, @Nonnull ReferenceKey changedRow, int rowChange
	) {
		final String name = changedRow.referenceName();
		for (final UnionKey key : referenceFields(globalIndex, references, name)) {
			if (!isReferenceFieldSearchable(executor, globalIndex, key)) {
				continue;
			}
			final AttributeKey attribute = new AttributeKey(key.attributeName(), key.locale());
			final FulltextFieldKey field = FulltextFieldKey.referenceAttribute(name, key.attributeName());
			final List<RowValues> before = collectRows(references, name, attribute, null, null, 0);
			final List<RowValues> after = collectRows(references, name, attribute, changedRow, null, rowChange);
			reconcileReferenceField(executor, globalIndex, key.locale(), field, before, after, true);
			replaceUnion(executor, globalIndex, key.locale(), field, unionOfRows(before), unionOfRows(after));
		}
	}

	/**
	 * Applies complete row batches on scope entry/leave.
	 * @param executor mutation executor
	 * @param globalIndex scope being entered or left
	 * @param entity image at this local mutation
	 * @param entering true for entry, false for leave
	 */
	private static void reconcileEntityReferences(
		@Nonnull EntityIndexLocalMutationExecutor executor, @Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Entity entity, boolean entering
	) {
		for (final UnionKey key : referenceFields(globalIndex, entity.getReferences(), null)) {
			if (!isReferenceFieldSearchable(executor, globalIndex, key)) {
				continue;
			}
			final List<RowValues> rows = collectRows(entity.getReferences(), key.referenceName(),
				new AttributeKey(key.attributeName(), key.locale()), null, null, 0);
			reconcileReferenceField(executor, globalIndex, key.locale(),
				FulltextFieldKey.referenceAttribute(key.referenceName(), key.attributeName()),
				entering ? List.of() : rows, entering ? rows : List.of(), entering);
		}
	}

	/**
	 * Enumerates registered reference generations plus fields present in the supplied rows, without other owners.
	 * @param globalIndex scope index
	 * @param references supplied owner's rows
	 * @param referenceName restrict to one reference, null for every reference
	 * @return field/locale identities, deduplicated
	 */
	@Nonnull
	private static Set<UnionKey> referenceFields(
		@Nonnull GlobalEntityIndex globalIndex, @Nonnull Collection<ReferenceContract> references,
		@Nullable String referenceName
	) {
		final Set<UnionKey> result = CollectionUtils.createLinkedHashSet(8);
		for (final Locale locale : globalIndex.getFulltextIndexLocales()) {
			final FulltextIndex index = Objects.requireNonNull(globalIndex.getFulltextIndex(locale));
			for (int field = 0; field < index.getFieldCount(); field++) {
				final FulltextFieldKey key = Objects.requireNonNull(index.getFieldKey(field));
				if (!index.isFieldRetired(field) && key.kind() == FulltextFieldKey.FieldKind.REFERENCE_ATTRIBUTE &&
					(referenceName == null || referenceName.equals(key.referenceName()))) {
					result.add(new UnionKey(key.referenceName(), key.name(), locale));
				}
			}
		}
		for (final ReferenceContract reference : references) {
			if (reference.exists() && (referenceName == null || referenceName.equals(reference.getReferenceName()))) {
				for (final AttributeValue value : reference.getAttributeValues()) {
					if (value.exists() && value.key().locale() != null) {
						result.add(new UnionKey(reference.getReferenceName(),
							value.key().attributeName(), value.key().locale()));
					}
				}
			}
		}
		return result;
	}

	/**
	 * Checks searchability before reading raw values, which may have non-text types in unsearchable fields.
	 * @param executor current schema owner
	 * @param globalIndex current scope index
	 * @param key reference field and locale
	 * @return whether the field can be reconciled
	 */
	private static boolean isReferenceFieldSearchable(
		@Nonnull EntityIndexLocalMutationExecutor executor, @Nonnull GlobalEntityIndex globalIndex,
		@Nonnull UnionKey key
	) {
		final boolean searchable = executor.getEntitySchema().getReference(key.referenceName())
			.flatMap(reference -> reference.getAttribute(key.attributeName()))
			.map(attribute -> attribute.isSearchableInScope(globalIndex.getIndexKey().scope())).orElse(false);
		if (!searchable) {
			retireField(globalIndex, key.locale(),
				FulltextFieldKey.referenceAttribute(key.referenceName(), key.attributeName()));
		}
		return searchable;
	}

	/**
	 * Reconciles a field after its caller has checked searchability once.
	 * @param executor current mutation executor
	 * @param globalIndex current scope index
	 * @param locale field locale
	 * @param fieldKey reference attribute identity
	 * @param before complete prior owner rows
	 * @param after complete final owner rows
	 * @param enroll whether to retain enrollment, false when the owner leaves the scope or the entity is removed
	 */
	private static void reconcileReferenceField(
		@Nonnull EntityIndexLocalMutationExecutor executor, @Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale, @Nonnull FulltextFieldKey fieldKey, @Nonnull List<RowValues> before,
		@Nonnull List<RowValues> after, boolean enroll
	) {
		FulltextIndex index = globalIndex.getFulltextIndex(locale);
		if (!enroll && index == null) {
			return;
		}
		final FulltextFieldDefinition definition =
			FulltextFieldDefinition.resolve(executor.getEntitySchema(), fieldKey);
		final CompiledFulltextAnalyzer analyzer = compiledAnalyzerOf(executor, definition, locale);
		if (index == null) {
			index = globalIndex.getOrCreateFulltextIndex(locale);
		}
		int fieldId = index.getFieldId(fieldKey);
		if (!enroll && (fieldId == FulltextIndex.UNKNOWN_FIELD_ID ||
			!index.isFieldCurrent(fieldId, analyzer.fingerprint().index(), definition.lengthPivot()))) {
			index.retireField(fieldKey);
			return;
		}
		fieldId = index.getOrAssignFieldId(fieldKey, definition.analyzer(), analyzer.fingerprint().index(),
			analyzer.fingerprint().wordPhase(), definition.lengthPivot(), null);
		index.reconcileReferenceValues(fieldId,
			executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, enroll ? Target.NEW : Target.EXISTING),
			before, after, enroll, analyzer);
	}

	/**
	 * Overlays exactly one local mutation on the owner's pre-storage reference image.
	 * @param references pre-storage rows
	 * @param referenceName reference being reconciled
	 * @param attribute field attribute and locale
	 * @param changedRow exact row changed, null for the unmodified image
	 * @param replacement attribute replacement, null for removal or a bare row event
	 * @param rowChange zero for attribute mutation, one for row insertion, minus one for row removal
	 * @return complete live rows with per-row distinct values
	 */
	@Nonnull
	private static List<RowValues> collectRows(
		@Nonnull Collection<ReferenceContract> references, @Nonnull String referenceName,
		@Nonnull AttributeKey attribute, @Nullable ReferenceKey changedRow,
		@Nullable Serializable replacement, int rowChange
	) {
		final List<RowValues> result = new ArrayList<>(references.size() + 1);
		for (final ReferenceContract reference : references) {
			if (!reference.exists() || !referenceName.equals(reference.getReferenceName())) {
				continue;
			}
			final boolean changed = changedRow != null &&
				ReferenceKey.FULL_COMPARATOR.compare(reference.getReferenceKey(), changedRow) == 0;
			if (!changed || rowChange != -1) {
				Serializable stored = null;
				for (final AttributeValue value : reference.getAttributeValues()) {
					if (value.exists() && value.key().equals(attribute)) {
						stored = value.value();
						break;
					}
				}
				final Serializable raw = changed && rowChange == 0 ? replacement : stored;
				result.add(new RowValues(reference.getReferencedPrimaryKey(),
					raw == null ? Set.of() : distinctValues(raw)));
			}
		}
		if (rowChange == 1) {
			result.add(new RowValues(Objects.requireNonNull(changedRow).primaryKey(), Set.of()));
		}
		return result;
	}

	/**
	 * Collects the unchanged union representation from a transient row image.
	 * @param rows live owner rows
	 * @return distinct raw values across the reference
	 */
	@Nonnull
	private static Set<String> unionOfRows(@Nonnull List<RowValues> rows) {
		final Set<String> result = CollectionUtils.createLinkedHashSet(4);
		for (final RowValues row : rows) {
			result.addAll(row.values());
		}
		return result;
	}

	/**
	 * Visits every searchable value `entity` holds in the scope of `globalIndex`: each localized entity attribute, the
	 * union of each localized reference attribute over the references of its name, and the text of each localized
	 * associated data item. A field that is not searchable in the scope is retired instead of visited.
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
				consumer.accept(fieldKey, locale, distinctValues(attributeValue.value()), null);
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
				entry.getValue(), null
			);
		}

		// the texts of searchable associated data, extracted with the expression of the field they are indexed in
		final String entityType = entitySchema.getName();
		for (final AssociatedDataValue associatedDataValue : entity.getAssociatedDataValues()) {
			final Locale locale = associatedDataValue.key().locale();
			if (locale == null || !associatedDataValue.exists() || associatedDataValue.value() == null) {
				continue;
			}
			final String associatedDataName = associatedDataValue.key().associatedDataName();
			final FulltextFieldKey fieldKey = FulltextFieldKey.associatedData(associatedDataName);
			// associated data the schema no longer declares counts as not searchable, as its stored values may
			// outlive its schema
			final AssociatedDataSchemaContract associatedDataSchema = entitySchema
				.getAssociatedData(associatedDataName)
				.orElse(null);
			if (associatedDataSchema != null && associatedDataSchema.isSearchableInScope(scope)) {
				final Expression textExpression = resolveTextExpression(
					globalIndex, locale, fieldKey, associatedDataSchema
				);
				consumer.accept(
					fieldKey, locale,
					// no client sent this value - the engine moves it, so nothing it holds may fail the move
					SearchableTextExtractor.extract(
						associatedDataValue.value(), textExpression, entityType, associatedDataName,
						ExtractionMode.LENIENT
					),
					toExpressionString(textExpression)
				);
			} else {
				retireField(globalIndex, locale, fieldKey);
			}
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
		final int existingPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
		if (before.equals(after) &&
			isIndexedInCurrentField(executor, globalIndex, locale, fieldKey, existingPrimaryKey)) {
			return;
		}
		removeValue(executor, globalIndex, locale, fieldKey, existingPrimaryKey, before);
		addValue(
			executor, globalIndex, locale, fieldKey, executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW),
			after
		);
	}

	/**
	 * Checks that the entity's value is in the current generation before treating an identical value as already
	 * indexed. The field being current is not enough: after an analyzer or pivot change, the first entity written
	 * registers a fresh field, and every other entity is absent from it until its own write indexes it.
	 *
	 * @param executor the mutation executor supplying current schemas
	 * @param globalIndex the scope index
	 * @param locale the value locale
	 * @param fieldKey the field being written
	 * @param primaryKey the primary key the entity's existing value is indexed under
	 * @return true when the field is registered with the current chain and pivot and holds a value of the entity
	 */
	private static boolean isIndexedInCurrentField(
		@Nonnull EntityIndexLocalMutationExecutor executor, @Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale, @Nonnull FulltextFieldKey fieldKey, int primaryKey
	) {
		final FulltextFieldDefinition definition = FulltextFieldDefinition.resolve(executor.getEntitySchema(),
			fieldKey);
		final CompiledFulltextAnalyzer analyzer = compiledAnalyzerOf(executor, definition, locale);
		final FulltextIndex index = globalIndex.getFulltextIndex(locale);
		final int fieldId = index == null ? FulltextIndex.UNKNOWN_FIELD_ID : index.getFieldId(fieldKey);
		return fieldId != FulltextIndex.UNKNOWN_FIELD_ID &&
			index.isFieldCurrent(fieldId, analyzer.fingerprint().index(), definition.lengthPivot()) &&
			index.hasIndexedValue(fieldId, primaryKey);
	}

	/**
	 * Returns the compiled analysis a field's values are indexed and removed with: the analyzer its schema declares,
	 * compiled for the locale against the catalog schema current at this write.
	 *
	 * @param executor   the executor of the entity mutation, supplying the registry and the current catalog schema
	 * @param definition the field's current schema analysis
	 * @param locale     the locale of the values
	 * @return the shared compiled analyzer
	 */
	@Nonnull
	private static CompiledFulltextAnalyzer compiledAnalyzerOf(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull FulltextFieldDefinition definition,
		@Nonnull Locale locale
	) {
		return executor.getFulltextAnalyzerRegistry()
			.getCompiledAnalyzer(executor.getCatalogSchema(), definition.analyzer(), locale);
	}

	/**
	 * Indexes the values of an entity's field, creating the fulltext index of the locale when there is none yet.
	 * Nothing is indexed - and no index or field created - for no values.
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
		addValue(executor, globalIndex, locale, fieldKey, primaryKey, values, null);
	}

	/**
	 * Indexes the values of an entity's field, creating the fulltext index of the locale when there is none yet and
	 * registering a new field with the text expression its values were selected with. An existing field keeps its own
	 * expression - see the class documentation. Empty value sets change nothing and create no index or field.
	 *
	 * @param executor       the executor of the entity mutation
	 * @param globalIndex    the global index of the scope being written
	 * @param locale         the locale of the values
	 * @param fieldKey       the field
	 * @param primaryKey     the primary key of the entity
	 * @param values         the distinct values of the field
	 * @param textExpression the expression the values were selected with, `null` when the values are the text
	 */
	private static void addValue(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		int primaryKey,
		@Nonnull Set<String> values,
		@Nullable String textExpression
	) {
		if (values.isEmpty()) {
			return;
		}
		final FulltextFieldDefinition definition = FulltextFieldDefinition.resolve(executor.getEntitySchema(),
			fieldKey);
		final CompiledFulltextAnalyzer analyzer = compiledAnalyzerOf(executor, definition, locale);
		final FulltextIndex fulltextIndex = globalIndex.getOrCreateFulltextIndex(locale);
		fulltextIndex.addValue(fieldKey, primaryKey, analyzer, definition.lengthPivot(), textExpression,
			values.toArray(String[]::new));
	}

	/**
	 * Resolves the text expression the values of an associated data field are extracted with: the one the field in
	 * the locale's index was registered with, or the schema's for a field not registered yet.
	 *
	 * @param globalIndex          the global index of the scope being written
	 * @param locale               the locale of the values
	 * @param fieldKey             the field of the associated data
	 * @param associatedDataSchema the schema of the associated data
	 * @return the expression to extract with, `null` when there is none
	 */
	@Nullable
	private static Expression resolveTextExpression(
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Locale locale,
		@Nonnull FulltextFieldKey fieldKey,
		@Nonnull AssociatedDataSchemaContract associatedDataSchema
	) {
		final Expression schemaExpression = associatedDataSchema.getTextExpression();
		final FulltextIndex fulltextIndex = globalIndex.getFulltextIndex(locale);
		final int fieldId = fulltextIndex == null ? FulltextIndex.UNKNOWN_FIELD_ID : fulltextIndex.getFieldId(fieldKey);
		if (fieldId == FulltextIndex.UNKNOWN_FIELD_ID) {
			return schemaExpression;
		}
		final String recordedExpression = fulltextIndex.getTextExpression(fieldId);
		if (Objects.equals(recordedExpression, toExpressionString(schemaExpression))) {
			return schemaExpression;
		}
		// TOBEDONE JNO #409 - rebuild a field whose text expression differs from the one the schema declares now;
		// until then the field keeps extracting with the expression it was built with, so a removal finds exactly what
		// the add indexed
		return recordedExpression == null ? null : ExpressionFactory.parse(recordedExpression);
	}

	/**
	 * Returns the canonical text of an expression - the form a fulltext field records it in.
	 *
	 * @param expression the expression, or `null`
	 * @return its text, or `null`
	 */
	@Nullable
	private static String toExpressionString(@Nullable Expression expression) {
		return expression == null ? null : expression.toExpressionString();
	}

	/**
	 * Removes the values of an entity's field from the fulltext index of the locale. Without an index for the locale
	 * there is nothing to remove, and none is created for it. A field built with an index chain or a pivot the schema
	 * no longer declares is retired instead - see {@link FulltextIndex#removeValue}.
	 *
	 * @param executor supplies the current catalog and field schemas
	 * @param globalIndex the global index of the scope being written
	 * @param locale      the locale of the values
	 * @param fieldKey    the field
	 * @param primaryKey  the primary key of the entity
	 * @param values      the distinct values the field holds
	 */
	private static void removeValue(
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
		final FulltextIndex fulltextIndex = globalIndex.getFulltextIndex(locale);
		if (fulltextIndex == null) {
			return;
		}
		final FulltextFieldDefinition definition = FulltextFieldDefinition.resolve(executor.getEntitySchema(),
			fieldKey);
		final CompiledFulltextAnalyzer analyzer = compiledAnalyzerOf(executor, definition, locale);
		fulltextIndex.removeValue(fieldKey, primaryKey, analyzer, definition.lengthPivot(),
			values.toArray(String[]::new));
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
		 * @param fieldKey       the field
		 * @param locale         the locale of the value
		 * @param values         the distinct values of the field
		 * @param textExpression the text expression the values were extracted with, `null` when the values are the
		 *                       text themselves
		 */
		void accept(
			@Nonnull FulltextFieldKey fieldKey,
			@Nonnull Locale locale,
			@Nonnull Set<String> values,
			@Nullable String textExpression
		);

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
