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

package io.evitadb.api.statistics;

import javax.annotation.Nonnull;

/**
 * Which kind of data one storage-part type holds. This is the classification a composition table groups by, and it
 * exists because {@link StoragePartUsage#storagePartType()} cannot serve as one: that field is the *simple class name*
 * of a registered storage part, an open set that grows whenever the engine gains an index structure, and a client has
 * no way to tell which of those names is an index and which is entity data.
 *
 * **Closed on purpose.** The set of part types is open; this set is not. A new storage-part type is expected to land
 * in an existing group, so a client that knows these fourteen values keeps rendering a correct table across engine
 * versions without being taught anything. Adding a value here is therefore a deliberate, documented event rather than
 * a side effect of adding a part type - and the reason a classification is declared once, at registration, instead of
 * being derived from a name. Two of the engine's index parts carry no `Index` in their class name at all, so any
 * name-based test a client could write is wrong before it is written.
 *
 * **The index groups mirror the data groups on purpose.** {@link #ATTRIBUTE_DATA} against {@link #ATTRIBUTE_INDEX},
 * {@link #PRICE_DATA} against {@link #PRICE_INDEX}, {@link #REFERENCE_DATA} against {@link #REFERENCE_INDEX}: the pair
 * is what a schema owner reads to see what indexing a feature costs against what storing it costs, which is a decision
 * they can act on. A single `INDEX` row cannot be acted on at all.
 *
 * **Root and leaf pages share a group.** A large index persists its tree as individual leaf-page records instead of
 * one inline blob; that is a storage-format choice, not a different kind of data, so both shapes are charged to the
 * same group. Splitting them would leave two numbers neither of which could be read alone.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see StoragePartKind
 * @see StoragePartUsage
 */
public enum StoragePartGroup {

	/**
	 * The entity's own record - primary key, scope, locales, parent and the manifest of which other parts it owns.
	 * `EntityBodyStoragePart`.
	 */
	ENTITY_BODY(StoragePartKind.ENTITY_DATA),

	/**
	 * Attribute values as stored, one part per entity and locale. `AttributesStoragePart`.
	 */
	ATTRIBUTE_DATA(StoragePartKind.ENTITY_DATA),

	/**
	 * Associated data values as stored, one part per entity, name and locale. Usually the largest data group, because
	 * this is where documents and images end up. `AssociatedDataStoragePart`.
	 */
	ASSOCIATED_DATA(StoragePartKind.ENTITY_DATA),

	/**
	 * Prices as stored, one part per entity. `PricesStoragePart`.
	 */
	PRICE_DATA(StoragePartKind.ENTITY_DATA),

	/**
	 * References to other entities as stored, one part per entity - including their own attributes.
	 * `ReferencesStoragePart`.
	 */
	REFERENCE_DATA(StoragePartKind.ENTITY_DATA),

	/**
	 * An index's own record rather than the values it indexes: which sub-indexes exist, and which entities the index
	 * covers. Deliberately small - the attribute, price and facet structures are stored separately precisely so this
	 * one is not rewritten whenever they change, which is why it is a poor proxy for the index footprint and why the
	 * groups below exist. `EntityIndexStoragePart`, `EntityIdsStoragePart`, `CatalogIndexStoragePart`.
	 */
	INDEX_MANIFEST(StoragePartKind.INDEX),

	/**
	 * Everything built because an attribute is `filterable`, `sortable`, `unique` or part of a sortable compound -
	 * the unique, filter, sort and chain indexes, their cardinality tracking, the catalog's globally-unique indexes,
	 * and the leaf pages of each once an index is large enough to be paged.
	 */
	ATTRIBUTE_INDEX(StoragePartKind.INDEX),

	/**
	 * Everything built to answer price-based filtering and ordering - the per-price-list-and-currency super and
	 * reference indexes and their leaf pages.
	 */
	PRICE_INDEX(StoragePartKind.INDEX),

	/**
	 * Everything built to resolve references between entities - the reference-type and group cardinality indexes and
	 * their leaf pages. Faceting is charged separately, to {@link #FACET_INDEX}, because it is a separate schema flag.
	 */
	REFERENCE_INDEX(StoragePartKind.INDEX),

	/**
	 * The facet index - what a facet summary is computed from. Charged apart from {@link #REFERENCE_INDEX} because
	 * `faceted` and `indexed` are separate schema decisions and an operator turning one off wants to see which one
	 * was paying.
	 */
	FACET_INDEX(StoragePartKind.INDEX),

	/**
	 * The hierarchy index - the tree structure a hierarchical collection is queried through.
	 */
	HIERARCHY_INDEX(StoragePartKind.INDEX),

	/**
	 * Everything built to answer histogram requests - the bucketed values, their range trees, the cardinality index
	 * gating bucket boundaries, and the leaf pages of each.
	 */
	HISTOGRAM_INDEX(StoragePartKind.INDEX),

	/**
	 * Schema records - the catalog schema and one entity schema per collection. One record each, so this group is
	 * small in bytes and constant in count.
	 */
	SCHEMA(StoragePartKind.METADATA),

	/**
	 * Header records the data store keeps to describe itself and its collections. Present only in the catalog's own
	 * data store.
	 */
	HEADER(StoragePartKind.METADATA);

	/**
	 * The coarse axis this group folds to.
	 */
	private final StoragePartKind kind;

	StoragePartGroup(@Nonnull StoragePartKind kind) {
		this.kind = kind;
	}

	/**
	 * What this group fundamentally is - entity data, an index derived from it, or the store's own metadata.
	 *
	 * The kind is a property of the group rather than a second classification of the part type, so the two can never
	 * contradict each other. It travels on the wire as its own field only because a generated client enum carries no
	 * behaviour to derive it with.
	 *
	 * @return the coarse kind this group belongs to
	 */
	@Nonnull
	public StoragePartKind kind() {
		return this.kind;
	}

}
