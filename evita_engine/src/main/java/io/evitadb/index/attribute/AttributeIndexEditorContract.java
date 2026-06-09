/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;

/**
 * AttributeIndexEditorContract is the write surface of the attribute index. It extends the read-only
 * {@link AttributeIndexContract} with coarse, schema-driven mutators — mirroring the
 * {@link io.evitadb.api.requestResponse.data.AttributesContract} / {@link io.evitadb.api.requestResponse.data.AttributesEditor}
 * read/editor split.
 *
 * Unlike the granular per-structure primitives (which {@link AttributeIndex} owns and {@link io.evitadb.index.EntityIndex}
 * forwards to it as overridable methods), each method here takes the whole {@link AttributeSchemaContract} configuration
 * into account and decides — in a single operation — which sub-indexes a value participates in and in which order they
 * must be written. The mutation layer ({@link io.evitadb.index.mutation.local.AttributeIndexMutator}) programs
 * exclusively against this contract: it never reasons about uniqueness, filtering and sorting independently.
 *
 * The implementation ({@link io.evitadb.index.EntityIndex} and its subclasses) orchestrates the per-structure
 * primitives, applying the sort-before-filter ordering that keeps a shared value tree consistent for view-mode sort
 * indexes, and letting subclass overrides of those primitives (cardinality gating in the referenced-type / group
 * indexes) compose automatically.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface AttributeIndexEditorContract extends AttributeIndexContract {

	/**
	 * Inserts the attribute `value` for `recordId` into every index structure the `attributeSchema` enables in
	 * `scope` — the unique, filter and sort sub-indexes — in one schema-driven operation. This is the insert half
	 * of an attribute upsert: callers pair it with {@link #removeAttribute} for the previous value (the two halves
	 * may target different index instances during a reference group reassignment).
	 *
	 * A unique attribute that is not separately filterable still shadows its value into the filter index, because
	 * the shared value tree that backs unique reads is the filter structure.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being inserted
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope of the target index, deciding which structures are maintained
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to insert (a `Serializable[]` for array attributes)
	 * @param recordId        the primary key the value is attributed to
	 */
	void upsertAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Removes the attribute `value` for `recordId` from every index structure the `attributeSchema` enables in
	 * `scope` — the unique, filter and sort sub-indexes — in one schema-driven operation. This is both the remove
	 * half of an attribute upsert and the operation used for outright attribute removal.
	 *
	 * The catalog-level global-unique index is intentionally NOT touched here — it is a separate index object
	 * maintained directly by the mutation executor.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being removed
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope of the target index, deciding which structures are maintained
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to remove (a `Serializable[]` for array attributes)
	 * @param recordId        the primary key the value was attributed to
	 */
	void removeAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Transitions the attribute for `recordId` from `oldValue` to `newValue` across every index structure the
	 * `attributeSchema` enables in `scope`. Unlike an upsert, a delta mutation never reassigns the record between
	 * index instances, so the old-value removal and new-value insertion always target the same index and primary
	 * key and can be applied as one operation.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being modified
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope of the target index, deciding which structures are maintained
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param oldValue        the current attribute value to remove
	 * @param newValue        the new attribute value to insert
	 * @param recordId        the primary key the value is attributed to
	 */
	void applyAttributeDelta(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable oldValue,
		@Nonnull Serializable newValue,
		int recordId
	);

}
