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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.attributeLessThanEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.attributeSetExact;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRoot;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.pickFirstByEntityProperty;
import static io.evitadb.api.query.QueryConstraints.randomWithSeed;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reduces a catalog to sorted `path -> value` lines that two catalogs holding the same data must share, whatever
 * history, event or conversion produced them - so a catalog can be compared with itself across a reload, a crash
 * recovery, a copy or a storage migration, and with another catalog built by the same history.
 *
 * # What is described
 *
 * - **query level** (see {@link #describeQueriesOf(Evita, String)}) - every entity body in both scopes, the facet
 *   summary, the attribute histograms of numeric filterable attributes, the reference histograms, and per reference a
 *   line of every query route of the virtual reading its schema admits (see {@link #describeQueries}):
 *   `referenceHaving` of targets, negations, groups, locales and attribute bounds, `referenceContent` filtered and
 *   chained, the hierarchy filters and statistics, the `pickFirst` orderings through both routes and the
 *   block-by-block orderings;
 * - **index level** (see {@link #describeIndexesOf(EntityCollection)}) - the global index's members and attribute
 *   indexes, every referenced-type index's advertised partitions per referenced entity and its filter values with
 *   their complete value-to-record mappings, every advertised partition's key, primary key, owners, languages and
 *   attribute indexes - or, for a partition registered as a row of the registry of single-owner partitions, the row
 *   with its owner, referenced entity, representative attribute values, sort values and chain predecessors - and the
 *   reduced-index membership's covered and residual partitions and owners.
 *
 * Every attribute index is described in full:
 *
 * - a filter index by its value-to-record mapping;
 * - a sort index position by position (see {@link SortIndexWalk}) - the records, the value of each and the blocks of
 *   records sharing a value, so two indexes ordering the same records over the same values but splitting them into
 *   blocks differently differ;
 * - a unique index by its records and value count - a unique index folded onto a filter index is described through
 *   that filter index's mapping;
 * - a chain index by its materialized order and by the predecessor every element declares, so a predecessor that
 *   changes without moving the element is found.
 *
 * Values are described by their {@link CanonicalValue} encoding, never by their rendering, which is lossy for exactly
 * the values an index normalizes.
 *
 * # Shapes left out
 *
 * A few query shapes fail when they read index objects as well, so a line built on them would compare two failures.
 * They are left out, and nothing virtual is lost by it - each is a pre-existing defect of the object reading:
 *
 * - the block-by-block ordering by the referenced primary key over a reference allowing duplicates (a stale read of a
 *   scratch buffer in the merge walk of `SortedRecordsSupplierFactory`);
 * - `attributeSetExact` in a block-by-block ordering over several partitions, or over a partition holding no value of
 *   the attribute - the line narrows the owners to those of one target whose row carries a value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CatalogDescriber {
	/**
	 * Upper bound of the entities one query fetches. The catalogs described are far smaller.
	 */
	private static final int PAGE_SIZE = 10_000;
	/**
	 * The seed of the random target order of the `pickFirst` lines of the prefetch route.
	 */
	private static final long SEED = 42L;

	private CatalogDescriber() {
	}

	/**
	 * Reduces the indexes of one collection of a catalog writing its single-owner partitions virtually to the
	 * index-level lines of the description - in every scope, registry included (see {@link #describeIndexes}) - without
	 * running a query. Read inside a session, the lines describe the state of the session's transaction.
	 *
	 * @param collection the collection
	 * @return the lines, sorted by path
	 */
	@Nonnull
	static SortedMap<String, String> describeIndexesOf(@Nonnull EntityCollection collection) {
		final Lines lines = new Lines();
		for (final Scope scope : Scope.values()) {
			describeIndexes(lines, collection, collection.getSchema(), scope);
		}
		return lines.asSortedMap();
	}

	/**
	 * Reduces the committed state of any catalog of an engine to the query-level lines of the description (see
	 * {@link #describeQueries}), every collection in every scope. Each line reads the partitions as the catalog itself
	 * represents them: a catalog representing its single-owner partitions virtually reads their rows, any other one reads
	 * its index objects. Two catalogs holding the same data must have equal lines, whatever their representations.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @return the lines, sorted by path
	 */
	@Nonnull
	public static SortedMap<String, String> describeQueriesOf(@Nonnull Evita evita, @Nonnull String catalogName) {
		return evita.queryCatalog(
			catalogName,
			(Function<EvitaSessionContract, SortedMap<String, String>>) session -> {
				final Lines lines = new Lines();
				for (final String entityType : new TreeSet<>(session.getAllEntityTypes())) {
					final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(entityType);
					for (final Scope scope : Scope.values()) {
						describeQueries(lines, session, schema, scope);
					}
				}
				return lines.asSortedMap();
			}
		);
	}

	/**
	 * Adds the query-level lines of one collection and scope.
	 *
	 * Besides the entity bodies, the facet summary and the histograms, every reference indexed in the scope gets the
	 * lines of every query route of the virtual reading its schema admits:
	 *
	 * - `referenceHaving` of every target, of the negation of every other target, of every other group
	 *   (`groupHaving`), of every owner locale (`entityLocaleEquals`) and of an upper bound of every comparable
	 *   filterable attribute - the last repeated on a line of its own when the reference has a counterpart the
	 *   bidirectional rewrite could answer it from;
	 * - `referenceContent(filterBy)` of every other target and `referenceContent(orderBy)` of every chained attribute;
	 * - `hierarchyWithinRoot`, `hierarchyWithin` of every target and the `hierarchyOfReference` statistics of a
	 *   reference to a hierarchical entity;
	 * - the `pickFirst` orderings of a reference to a non-hierarchical entity - by every sortable attribute and by the
	 *   referenced primary key, through the index route and through the prefetch route;
	 * - the block-by-block orderings - `traverseByEntityProperty` by every sortable and every chained attribute, by
	 *   `attributeSetExact` of a filterable attribute and by the referenced primary key.
	 *
	 * Two block-by-block shapes are left out for a reference allowing duplicates, because pre-existing defects make
	 * them fail when they read index objects as well (see the class documentation): the referenced primary key and
	 * `attributeSetExact`.
	 *
	 * @param lines   the lines to add to
	 * @param session the session to query in
	 * @param schema  the schema of the collection
	 * @param scope   the scope to query
	 */
	private static void describeQueries(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Scope scope
	) {
		final String entityType = schema.getName();
		final String prefix = "query/" + entityType + "/" + scope + "/";
		final EvitaResponse<SealedEntity> bodies = session.query(
			query(collection(entityType), filterBy(scope(scope)), require(page(1, PAGE_SIZE), entityFetchAll())),
			SealedEntity.class
		);
		final ReferenceSample sample = new ReferenceSample();
		for (final SealedEntity entity : bodies.getRecordData()) {
			lines.put(prefix + "entity/" + entity.getPrimaryKeyOrThrowException(), entity.toString());
			sample.addOwner(entity);
		}

		boolean faceted = false;
		final List<String> histogramNames = new ArrayList<>(4);
		for (final ReferenceSchemaContract referenceSchema : schema.getReferences().values()) {
			faceted |= referenceSchema.isFacetedInScope(scope);
			histogramNames.addAll(new TreeSet<>(referenceSchema.getHistogramIndexDefinitions(scope).keySet()));
			if (!referenceSchema.isIndexedInScope(scope)) {
				continue;
			}
			final String referenceName = referenceSchema.getName();
			final Set<Integer> targets = sample.getTargets(referenceName);
			for (final Integer target : targets) {
				final EvitaResponse<EntityReference> matching = queryOwners(
					session, entityType, scope, referenceHaving(referenceName, entityPrimaryKeyInSet(target))
				);
				lines.put(
					prefix + "referenceHaving/" + referenceName + "/" + target,
					String.valueOf(primaryKeysOf(matching.getRecordData()))
				);
			}
			describeNegatedReferenceHaving(lines, session, entityType, scope, referenceName, targets, prefix);
			describeFilteredReferenceContent(lines, session, entityType, scope, referenceName, targets, prefix);
			describeGroupHaving(lines, session, entityType, scope, referenceSchema, sample, prefix);
			describeLocaleHaving(lines, session, entityType, scope, referenceName, sample, prefix);
			describeAttributeHaving(lines, session, schema, scope, referenceSchema, sample, prefix);
			final boolean hierarchical = isReferenceToHierarchy(session, referenceSchema, null);
			final boolean hierarchyIndexed = isReferenceToHierarchy(session, referenceSchema, scope);
			if (hierarchyIndexed) {
				describeHierarchy(lines, session, entityType, scope, referenceName, targets, prefix);
			}
			describeOrderings(
				lines, session, entityType, scope, referenceSchema, hierarchical, hierarchyIndexed, sample, prefix
			);
		}
		if (faceted) {
			describeExtraResults(
				lines, prefix + "facetSummary",
				session.query(
					query(collection(entityType), filterBy(scope(scope)), require(page(1, 1), facetSummary())),
					EntityReference.class
				)
			);
		}
		if (!histogramNames.isEmpty()) {
			describeExtraResults(
				lines, prefix + "referenceHistograms",
				session.query(
					query(
						collection(entityType),
						filterBy(scope(scope)),
						require(
							page(1, 1),
							referenceSummaryWithHistograms(
								null, null, null,
								histogramStatistics(10, histogramNames.toArray(String[]::new))
							)
						)
					),
					EntityReference.class
				)
			);
		}
		final String[] numericAttributes = schema.getAttributes().values().stream()
			.filter(it -> it.isFilterableInScope(scope) && !it.isLocalized())
			.filter(it -> Number.class.isAssignableFrom(it.getPlainType()))
			.map(AttributeSchemaContract::getName)
			.sorted()
			.toArray(String[]::new);
		if (numericAttributes.length > 0) {
			describeExtraResults(
				lines, prefix + "attributeHistograms",
				session.query(
					query(
						collection(entityType),
						filterBy(scope(scope)),
						require(page(1, 1), attributeHistogram(10, numericAttributes))
					),
					EntityReference.class
				)
			);
		}
	}

	/**
	 * Adds one line with the owners `referenceHaving` matches when its body negates every other target of the
	 * reference - a negation settled per row, which a row read virtually has to answer exactly: an owner holding a
	 * negated row and another one matches, an owner holding negated rows only does not.
	 *
	 * @param lines         the lines to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param targets       the targets the entities of the collection reference, ascending
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeNegatedReferenceHaving(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull Set<Integer> targets,
		@Nonnull String prefix
	) {
		if (targets.isEmpty()) {
			return;
		}
		final Integer[] everyOther = everyOther(targets);
		final EvitaResponse<EntityReference> matching = queryOwners(
			session, entityType, scope, referenceHaving(referenceName, not(entityPrimaryKeyInSet(everyOther)))
		);
		lines.put(
			prefix + "referenceHaving/" + referenceName + "/not",
			String.valueOf(primaryKeysOf(matching.getRecordData()))
		);
	}

	/**
	 * Adds one line per entity of the collection in the scope with the references `referenceContent(filterBy)` keeps
	 * when it asks for every other target of the reference - so the answer holds kept and dropped rows of the same
	 * owners, and a row read virtually has to be united with the other rows of its key exactly as an index object's
	 * owners are.
	 *
	 * @param lines         the lines to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param targets       the targets the entities of the collection reference, ascending
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeFilteredReferenceContent(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull Set<Integer> targets,
		@Nonnull String prefix
	) {
		if (targets.isEmpty()) {
			return;
		}
		final Integer[] everyOther = everyOther(targets);
		final EvitaResponse<SealedEntity> filtered = session.query(
			query(
				collection(entityType),
				filterBy(scope(scope)),
				require(
					page(1, PAGE_SIZE),
					entityFetch(
						referenceContentWithAttributes(referenceName, filterBy(entityPrimaryKeyInSet(everyOther)))
					)
				)
			),
			SealedEntity.class
		);
		putReferences(lines, prefix + "referenceContent/" + referenceName + "/", referenceName, filtered, false);
	}

	/**
	 * Adds one line with the owners `referenceHaving` matches when its body asks for every other group the rows of
	 * the reference belong to - a group leaf, which a row read virtually answers from the group of its own row only.
	 * Only a group entity managed by evitaDB can be asked for.
	 *
	 * @param lines           the lines to add to
	 * @param session         the session to query in
	 * @param entityType      the collection
	 * @param scope           the scope to query
	 * @param referenceSchema the reference
	 * @param sample          what the entity bodies of the collection hold
	 * @param prefix          the path prefix of the lines
	 */
	private static void describeGroupHaving(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSample sample,
		@Nonnull String prefix
	) {
		final String referenceName = referenceSchema.getName();
		final Set<Integer> groups = sample.getGroups(referenceName);
		if (
			referenceSchema.getReferencedGroupType() == null || !referenceSchema.isReferencedGroupTypeManaged() ||
				groups.isEmpty()
		) {
			return;
		}
		final Integer[] everyOther = everyOther(groups);
		final EvitaResponse<EntityReference> matching = queryOwners(
			session, entityType, scope,
			referenceHaving(referenceName, groupHaving(entityPrimaryKeyInSet(everyOther)))
		);
		lines.put(
			prefix + "referenceHaving/" + referenceName + "/groupHaving",
			String.valueOf(primaryKeysOf(matching.getRecordData()))
		);
	}

	/**
	 * Adds one line per locale the owners of the collection hold with the owners `referenceHaving` matches when its
	 * body asks for that locale - a locale leaf, which a row read virtually answers from the locales of its owner.
	 *
	 * @param lines         the lines to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param sample        what the entity bodies of the collection hold
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeLocaleHaving(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull ReferenceSample sample,
		@Nonnull String prefix
	) {
		if (sample.getTargets(referenceName).isEmpty()) {
			return;
		}
		for (final Locale locale : sample.getLocales()) {
			final EvitaResponse<EntityReference> matching = queryOwners(
				session, entityType, scope, referenceHaving(referenceName, entityLocaleEquals(locale))
			);
			lines.put(
				prefix + "referenceHaving/" + referenceName + "/locale/" + locale.toLanguageTag(),
				String.valueOf(primaryKeysOf(matching.getRecordData()))
			);
		}
	}

	/**
	 * Adds, for every filterable non-localized numeric or string attribute of the reference the rows of the
	 * collection carry, one line with the owners `referenceHaving` matches when its body bounds the attribute by the
	 * median of its values - a row-exact body - and, when the reference has a counterpart the bidirectional rewrite
	 * could answer it from, one more line answering the very same query under the path of the rewrite.
	 *
	 * @param lines           the lines to add to
	 * @param session         the session to query in
	 * @param schema          the schema of the collection
	 * @param scope           the scope to query
	 * @param referenceSchema the reference
	 * @param sample          what the entity bodies of the collection hold
	 * @param prefix          the path prefix of the lines
	 */
	private static void describeAttributeHaving(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSample sample,
		@Nonnull String prefix
	) {
		final String entityType = schema.getName();
		final String referenceName = referenceSchema.getName();
		final boolean counterpart = hasCounterpart(session, schema, referenceSchema);
		for (final AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
			final String attributeName = attributeSchema.getName();
			final List<Serializable> values = sample.getAttributeValues(referenceName, attributeName);
			if (
				!attributeSchema.isFilterableInScope(scope) || attributeSchema.isLocalized() ||
					!isComparablePlainType(attributeSchema.getPlainType()) || values.isEmpty()
			) {
				continue;
			}
			final Serializable median = values.get(values.size() / 2);
			final FilterConstraint body = referenceHaving(
				referenceName, attributeLessThanEquals(attributeName, median)
			);
			final EvitaResponse<EntityReference> matching = queryOwners(session, entityType, scope, body);
			lines.put(
				prefix + "referenceHaving/" + referenceName + "/attribute/" + attributeName,
				String.valueOf(primaryKeysOf(matching.getRecordData()))
			);
			if (counterpart) {
				final EvitaResponse<EntityReference> rewritten = queryOwners(session, entityType, scope, body);
				lines.put(
					prefix + "bidirectional/" + referenceName + "/" + attributeName,
					String.valueOf(primaryKeysOf(rewritten.getRecordData()))
				);
			}
		}
	}

	/**
	 * Adds the hierarchy lines of a reference to a hierarchical entity: the owners `hierarchyWithinRoot` matches, the
	 * owners `hierarchyWithin` matches for every target, and the `hierarchyOfReference` statistics from the root.
	 *
	 * @param lines         the lines to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param targets       the targets the entities of the collection reference, ascending
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeHierarchy(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull Set<Integer> targets,
		@Nonnull String prefix
	) {
		final EvitaResponse<EntityReference> withinRoot = queryOwners(
			session, entityType, scope, hierarchyWithinRoot(referenceName)
		);
		lines.put(
			prefix + "hierarchyWithinRoot/" + referenceName, String.valueOf(primaryKeysOf(withinRoot.getRecordData()))
		);
		for (final Integer target : targets) {
			final EvitaResponse<EntityReference> within = queryOwners(
				session, entityType, scope, hierarchyWithin(referenceName, entityPrimaryKeyInSet(target))
			);
			lines.put(
				prefix + "hierarchyWithin/" + referenceName + "/" + target,
				String.valueOf(primaryKeysOf(within.getRecordData()))
			);
		}
		describeExtraResults(
			lines, prefix + "hierarchyStatistics/" + referenceName,
			session.query(
				query(
					collection(entityType),
					filterBy(scope(scope)),
					require(
						page(1, 1),
						hierarchyOfReference(
							referenceName, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
							fromRoot(
								"tree",
								statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
							)
						)
					)
				),
				EntityReference.class
			)
		);
	}

	/**
	 * Adds the ordering lines of a reference - each the owners of the collection in the order the ordering sorts them:
	 *
	 * - for a reference to a non-hierarchical entity, the `pickFirst` orderings by every sortable non-chained attribute
	 *   and by the referenced primary key, each through the index route over every owner with the targets in
	 *   descending primary key order, and through the prefetch route over every other owner with the targets in a
	 *   seeded random order - the prefetch route answers the plain primary key order without any partition, and it
	 *   runs for a `Comparable` attribute type only, because it fails for the others reading index objects as well;
	 * - the block-by-block orderings by every sortable attribute, chained ones included, and - for a reference to an
	 *   entity whose hierarchy is indexed in the scope - by every sortable non-chained attribute narrowed by
	 *   `hierarchyWithinRoot`, whose planning set the hierarchy option of index selection supplies, and - for a
	 *   reference not allowing duplicates - by the referenced primary key and by `attributeSetExact` of every sortable
	 *   numeric or string attribute, the latter over the owners of one target whose row carries a value, referenced
	 *   by a single owner where there is one: it sorts by the sort index of the only partition the ordering plans over
	 *   and fails over several partitions and over a partition holding no value, reading index objects as well;
	 * - `referenceContent(orderBy)` of every sortable chained attribute, one line per owner.
	 *
	 * @param lines            the lines to add to
	 * @param session          the session to query in
	 * @param entityType       the collection
	 * @param scope            the scope to query
	 * @param referenceSchema  the reference
	 * @param hierarchical     TRUE when the reference targets a hierarchical entity, which admits no `pickFirst`
	 * @param hierarchyIndexed TRUE when the hierarchy of the referenced entity is indexed in the scope, which admits
	 *                         `hierarchyWithinRoot`
	 * @param sample           what the entity bodies of the collection hold
	 * @param prefix           the path prefix of the lines
	 */
	private static void describeOrderings(
		@Nonnull Lines lines,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean hierarchical,
		boolean hierarchyIndexed,
		@Nonnull ReferenceSample sample,
		@Nonnull String prefix
	) {
		final String referenceName = referenceSchema.getName();
		final List<Integer> owners = sample.getOwners();
		if (sample.getTargets(referenceName).isEmpty()) {
			return;
		}
		final FilterConstraint everyOtherOwner = entityPrimaryKeyInSet(everyOther(owners));
		final boolean duplicates = referenceSchema.getCardinality().allowsDuplicates();
		final String orderPrefix = prefix + "order/" + referenceName + "/";
		for (final AttributeSchemaContract attributeSchema : new TreeMap<>(referenceSchema.getAttributes()).values()) {
			if (!attributeSchema.isSortableInScope(scope) || attributeSchema.isLocalized()) {
				continue;
			}
			final String attributeName = attributeSchema.getName();
			final boolean chained = Predecessor.class.equals(attributeSchema.getPlainType()) ||
				ReferencedEntityPredecessor.class.equals(attributeSchema.getPlainType());
			// the prefetch route compares the values of a type that is not `Comparable` with the comparator of the
			// entities and fails reading index objects as well, so it runs for comparable types only
			final boolean prefetchable = Comparable.class.isAssignableFrom(attributeSchema.getPlainType());
			if (!chained && !hierarchical) {
				final OrderConstraint pickFirst = referenceProperty(
					referenceName,
					pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC)),
					attributeNatural(attributeName, OrderDirection.ASC)
				);
				putOrder(
					lines, orderPrefix + "pickFirst/" + attributeName + "/index",
					queryOrder(session, entityType, scope, null, pickFirst, DebugMode.PREFER_INDEX_SCAN)
				);
			}
			if (!chained && !hierarchical && prefetchable) {
				// the prefetch route ranks the targets through the partitions only for an order other than the plain
				// primary key one, which it answers without touching any partition
				putOrder(
					lines, orderPrefix + "pickFirst/" + attributeName + "/prefetch",
					queryOrder(
						session, entityType, scope, everyOtherOwner,
						referenceProperty(
							referenceName,
							pickFirstByEntityProperty(randomWithSeed(SEED)),
							attributeNatural(attributeName, OrderDirection.ASC)
						),
						DebugMode.PREFER_PREFETCHING
					)
				);
			}
			putOrder(
				lines, orderPrefix + "traverse/" + attributeName,
				queryOrder(
					session, entityType, scope, null,
					referenceProperty(
						referenceName,
						traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeNatural(attributeName, OrderDirection.ASC)
					),
					DebugMode.PREFER_INDEX_SCAN
				)
			);
			if (!chained && hierarchyIndexed) {
				// the hierarchy option of index selection supplies the planning set of the ordering - the partitions
				// of the nodes, read virtually as handles
				putOrder(
					lines, orderPrefix + "hierarchyWithinRoot/traverse/" + attributeName,
					queryOrder(
						session, entityType, scope, hierarchyWithinRoot(referenceName),
						referenceProperty(
							referenceName,
							traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
							attributeNatural(attributeName, OrderDirection.ASC)
						),
						DebugMode.PREFER_INDEX_SCAN
					)
				);
			}
			if (chained) {
				final EvitaResponse<SealedEntity> chainedContent = session.query(
					query(
						collection(entityType),
						filterBy(scope(scope)),
						require(
							page(1, PAGE_SIZE),
							entityFetch(
								referenceContentWithAttributes(
									referenceName, orderBy(attributeNatural(attributeName, OrderDirection.ASC))
								)
							)
						)
					),
					SealedEntity.class
				);
				putReferences(
					lines, prefix + "referenceContent/" + referenceName + "/chain/" + attributeName + "/",
					referenceName, chainedContent, true
				);
			}
		}
		if (!hierarchical) {
			final OrderConstraint byReferencedKey = referenceProperty(
				referenceName, entityPrimaryKeyNatural(OrderDirection.ASC)
			);
			putOrder(
				lines, orderPrefix + "pickFirst/referencedPrimaryKey/index",
				queryOrder(session, entityType, scope, null, byReferencedKey, DebugMode.PREFER_INDEX_SCAN)
			);
			putOrder(
				lines, orderPrefix + "pickFirst/referencedPrimaryKey/prefetch",
				queryOrder(
					session, entityType, scope, everyOtherOwner,
					referenceProperty(
						referenceName,
						pickFirstByEntityProperty(randomWithSeed(SEED)),
						entityPrimaryKeyNatural(OrderDirection.ASC)
					),
					DebugMode.PREFER_PREFETCHING
				)
			);
		}
		if (duplicates) {
			return;
		}
		putOrder(
			lines, orderPrefix + "traverse/referencedPrimaryKey",
			queryOrder(
				session, entityType, scope, null,
				referenceProperty(
					referenceName,
					traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					entityPrimaryKeyNatural(OrderDirection.DESC)
				),
				DebugMode.PREFER_INDEX_SCAN
			)
		);
		for (final AttributeSchemaContract attributeSchema : new TreeMap<>(referenceSchema.getAttributes()).values()) {
			final String attributeName = attributeSchema.getName();
			final List<Serializable> values = sample.getAttributeValues(referenceName, attributeName);
			// `attributeSetExact` sorts by the sort index of the only partition the ordering plans over, and fails
			// reading index objects as well over several partitions and over a partition holding no value of the
			// attribute - so the line narrows the owners to those of one target whose row carries a value
			final Integer exactTarget = sample.getTargetWithValue(referenceName, attributeName);
			if (
				!attributeSchema.isSortableInScope(scope) || attributeSchema.isLocalized() ||
					!isComparablePlainType(attributeSchema.getPlainType()) || values.isEmpty() || exactTarget == null
			) {
				continue;
			}
			final FilterConstraint ownersOfExactTarget = referenceHaving(
				referenceName, entityPrimaryKeyInSet(exactTarget)
			);
			// the last value first, so the exact order differs from the natural one
			final Serializable[] exactOrder = values.size() == 1 ?
				new Serializable[]{values.get(0)} :
				new Serializable[]{values.get(values.size() - 1), values.get(0)};
			putOrder(
				lines, orderPrefix + "traverse/attributeSetExact/" + attributeName,
				queryOrder(
					session, entityType, scope, ownersOfExactTarget,
					referenceProperty(
						referenceName,
						traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeSetExact(attributeName, exactOrder)
					),
					DebugMode.PREFER_INDEX_SCAN
				)
			);
		}
	}

	/**
	 * Adds the line of one ordering with the owners it returned, in its order.
	 *
	 * @param lines   the lines to add to
	 * @param path    the path of the line
	 * @param ordered the response of the ordering query
	 */
	private static void putOrder(
		@Nonnull Lines lines,
		@Nonnull String path,
		@Nonnull EvitaResponse<EntityReference> ordered
	) {
		final List<Integer> primaryKeys = new ArrayList<>(ordered.getRecordData().size());
		for (final EntityReference reference : ordered.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		lines.put(path, primaryKeys.toString());
	}

	/**
	 * Adds one line per fetched entity with its references of the reference - sorted, or in the order the response
	 * holds them when that order is what the line compares.
	 *
	 * @param lines         the lines to add to
	 * @param pathPrefix    the path prefix of the lines, completed by the entity primary key
	 * @param referenceName the reference
	 * @param response      the response holding the entities
	 * @param keepOrder     TRUE to keep the order of the response, FALSE to sort the references
	 */
	private static void putReferences(
		@Nonnull Lines lines,
		@Nonnull String pathPrefix,
		@Nonnull String referenceName,
		@Nonnull EvitaResponse<SealedEntity> response,
		boolean keepOrder
	) {
		for (final SealedEntity entity : response.getRecordData()) {
			final List<String> references = new ArrayList<>(entity.getReferences(referenceName).size());
			for (final ReferenceContract reference : entity.getReferences(referenceName)) {
				references.add(reference.getReferenceKey() + " " + reference.getAttributeValues());
			}
			if (!keepOrder) {
				Collections.sort(references);
			}
			lines.put(pathPrefix + entity.getPrimaryKeyOrThrowException(), references.toString());
		}
	}

	/**
	 * Queries the owners of the collection in the scope matching the filter.
	 *
	 * @param session    the session to query in
	 * @param entityType the collection
	 * @param scope      the scope to query
	 * @param filter     the filter beside the scope
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> queryOwners(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull FilterConstraint filter
	) {
		return session.query(
			query(collection(entityType), filterBy(scope(scope), filter), require(page(1, PAGE_SIZE))),
			EntityReference.class
		);
	}

	/**
	 * Queries the owners of the collection in the scope in the order of the ordering.
	 *
	 * @param session    the session to query in
	 * @param entityType the collection
	 * @param scope      the scope to query
	 * @param selection  the filter beside the scope, `null` for every owner
	 * @param ordering   the ordering
	 * @param debugMode  the debug mode forcing the route of the ordering
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nullable FilterConstraint selection,
		@Nonnull OrderConstraint ordering,
		@Nonnull DebugMode debugMode
	) {
		return session.query(
			query(
				collection(entityType),
				filterBy(scope(scope), selection),
				orderBy(ordering),
				require(page(1, PAGE_SIZE), debug(debugMode))
			),
			EntityReference.class
		);
	}

	/**
	 * Returns every other element of an ascending collection, starting with the first.
	 *
	 * @param elements the elements, ascending
	 * @return every other element
	 */
	@Nonnull
	private static Integer[] everyOther(@Nonnull Collection<Integer> elements) {
		final List<Integer> everyOther = new ArrayList<>((elements.size() + 1) / 2);
		int index = 0;
		for (final Integer element : elements) {
			if (index++ % 2 == 0) {
				everyOther.add(element);
			}
		}
		return everyOther.toArray(Integer[]::new);
	}

	/**
	 * Returns TRUE for the attribute types whose values the bounded and exact lines can name - numbers and strings.
	 *
	 * @param plainType the plain type of the attribute
	 * @return TRUE when the type is comparable by value
	 */
	private static boolean isComparablePlainType(@Nonnull Class<?> plainType) {
		return String.class.equals(plainType) || Number.class.isAssignableFrom(plainType);
	}

	/**
	 * Returns TRUE when the reference targets a managed hierarchical entity - whose hierarchy is indexed in the scope,
	 * when one is given.
	 *
	 * @param session         the session reading the schemas
	 * @param referenceSchema the reference
	 * @param scope           the scope the hierarchy must be indexed in, `null` for any
	 * @return TRUE when the reference targets a hierarchy
	 */
	private static boolean isReferenceToHierarchy(
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Scope scope
	) {
		return referenceSchema.isReferencedEntityTypeManaged() &&
			session.getEntitySchema(referenceSchema.getReferencedEntityType())
				.map(it -> it.isWithHierarchy() && (scope == null || it.isHierarchyIndexedInScope(scope)))
				.orElse(false);
	}

	/**
	 * Returns TRUE when the reference has a counterpart the bidirectional rewrite could answer it from - it is a
	 * reflected reference, or the referenced entity reflects it.
	 *
	 * @param session         the session reading the schemas
	 * @param schema          the schema of the collection owning the reference
	 * @param referenceSchema the reference
	 * @return TRUE when the reference has a counterpart
	 */
	private static boolean hasCounterpart(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		if (referenceSchema instanceof ReflectedReferenceSchemaContract) {
			return true;
		}
		if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return false;
		}
		return session.getEntitySchema(referenceSchema.getReferencedEntityType())
			.map(
				target -> target.getReferences().values().stream().anyMatch(
					it -> it instanceof final ReflectedReferenceSchemaContract reflected &&
						schema.getName().equals(reflected.getReferencedEntityType()) &&
						referenceSchema.getName().equals(reflected.getReflectedReferenceName())
				)
			)
			.orElse(false);
	}

	/**
	 * Adds one line per extra result of a query response.
	 *
	 * @param lines    the lines to add to
	 * @param path     the path of the lines
	 * @param response the response carrying the extra results
	 */
	private static void describeExtraResults(
		@Nonnull Lines lines,
		@Nonnull String path,
		@Nonnull EvitaResponse<?> response
	) {
		for (final EvitaResponseExtraResult extraResult : response.getExtraResults().values()) {
			lines.put(path + "/" + extraResult.getClass().getSimpleName(), extraResult.toString());
		}
	}

	/**
	 * Adds the index-level lines of one collection and scope.
	 *
	 * @param lines      the lines to add to
	 * @param collection the collection whose indexes are walked
	 * @param schema     the schema of the collection
	 * @param scope      the scope whose indexes are walked
	 */
	private static void describeIndexes(
		@Nonnull Lines lines,
		@Nonnull EntityCollection collection,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Scope scope
	) {
		final String prefix = "index/" + schema.getName() + "/" + scope + "/";
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		if (globalIndex != null) {
			describeIndex(lines, prefix + "global", globalIndex);
		}
		for (final ReferenceSchemaContract referenceSchema : schema.getReferences().values()) {
			final String referenceName = referenceSchema.getName();
			for (final EntityIndexType family : new EntityIndexType[]{
				EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
			}) {
				final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
					new EntityIndexKey(family, scope, referenceName)
				);
				if (typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex) {
					describeReferencedTypeIndex(
						lines, prefix + family + "/" + referenceName, collection, referencedTypeIndex,
						family == EntityIndexType.REFERENCED_ENTITY_TYPE
					);
				}
			}
			if (globalIndex instanceof final GlobalEntityIndex typedGlobalIndex) {
				final ReducedIndexMembership membership = typedGlobalIndex.getReducedIndexMembership(referenceName);
				if (membership != null) {
					describeMembership(lines, prefix + "membership/" + referenceName, membership);
				}
			}
		}
	}

	/**
	 * Adds the lines of one referenced-type entity index and of every partition it advertises.
	 *
	 * @param lines        the lines to add to
	 * @param path         the path of the lines
	 * @param collection   the collection the partitions are resolved in
	 * @param typeIndex    the referenced-type entity index
	 * @param withRegistry TRUE for the type index of referenced entities, which carries the registry of single-owner
	 *                     partitions
	 */
	private static void describeReferencedTypeIndex(
		@Nonnull Lines lines,
		@Nonnull String path,
		@Nonnull EntityCollection collection,
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		boolean withRegistry
	) {
		lines.put(path + "/primaryKey", String.valueOf(typeIndex.getPrimaryKey()));
		lines.put(path + "/advertised", toString(typeIndex.getAllPrimaryKeys()));
		for (final Integer target : new TreeSet<>(typeIndex.getAllTrackedReferencedEntityPrimaryKeys())) {
			final int[] partitions = typeIndex.getAllReferenceIndexes(target).clone();
			Arrays.sort(partitions);
			lines.put(path + "/target/" + target, Arrays.toString(partitions));
		}
		describeAttributeIndexes(lines, path, typeIndex);
		final int[] advertised = typeIndex.getAllPrimaryKeys().getArray();
		// only the type index of referenced entities carries a registry
		final VirtualReferencePartitionIndex registry =
			withRegistry ? typeIndex.getVirtualReferencePartitionIndex() : null;
		for (final int partitionPrimaryKey : advertised) {
			final String partitionPath = path + "/partition/" + partitionPrimaryKey;
			if (registry != null && registry.getPartitionOwner(partitionPrimaryKey).isPresent()) {
				// a registered partition is its row - the registry lines describe it
				lines.put(partitionPath, "REGISTERED");
				continue;
			}
			final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
			if (partition == null) {
				lines.put(partitionPath, "MISSING");
			} else {
				describeIndex(lines, partitionPath, partition);
			}
		}
		if (registry != null) {
			describeRegistry(lines, path + "/registry", typeIndex.getIndexKey().scope(), registry);
		}
	}

	/**
	 * Adds the lines of a registry of single-owner partitions, independent of how its entries are laid out in pages:
	 * every owner row with its referenced entity and representative attribute values, the declared sort and chain
	 * keys, every sort value and chain predecessor of a registered partition and the entry count.
	 *
	 * @param lines    the lines to add to
	 * @param path     the path of the lines
	 * @param scope    the scope of the registry
	 * @param registry the registry
	 */
	private static void describeRegistry(
		@Nonnull Lines lines,
		@Nonnull String path,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry
	) {
		final SortedMap<Integer, Long> rows = new TreeMap<>();
		VirtualReferencePartitionIndexEntries.forEachOwnerRow(registry, rows::put);
		final SortedSet<String> sortKeys = new TreeSet<>();
		for (final AttributeIndexKey key : registry.getDeclaredSortKeys()) {
			sortKeys.add(key.toString());
		}
		final SortedSet<String> chainKeys = new TreeSet<>();
		for (final AttributeIndexKey key : registry.getDeclaredChainKeys()) {
			chainKeys.add(key.toString());
		}
		lines.put(path + "/sortKeys", sortKeys.toString());
		lines.put(path + "/chainKeys", chainKeys.toString());
		lines.put(path + "/entries", String.valueOf(VirtualReferencePartitionIndexEntries.count(registry)));
		for (final Entry<Integer, Long> row : rows.entrySet()) {
			final int partitionPrimaryKey = row.getKey();
			final int tupleId = registry.getRepresentativeTupleId(partitionPrimaryKey);
			final Serializable[] tuple = tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID ?
				new Serializable[0] : registry.getTuple(tupleId);
			final String rowPath = path + "/row/" + partitionPrimaryKey;
			lines.put(
				rowPath,
				"owner " + NumberUtils.unpackHigh(row.getValue()) + ", target " +
					NumberUtils.unpackLow(row.getValue()) + ", tuple " + CanonicalValue.encode(tuple) +
					", scope " + scope
			);
			for (final AttributeIndexKey key : registry.getDeclaredSortKeys()) {
				final int valueId = registry.getSortValueId(key, partitionPrimaryKey);
				if (valueId != VirtualReferencePartitionIndex.NO_SORT_VALUE_ID) {
					lines.put(
						rowPath + "/sort/" + key,
						CanonicalValue.encodeIndexed(registry.getNormalizedSortValue(key, valueId))
					);
				}
			}
			for (final AttributeIndexKey key : registry.getDeclaredChainKeys()) {
				final OptionalInt predecessor = registry.getChainPredecessor(key, partitionPrimaryKey);
				if (predecessor.isPresent()) {
					lines.put(rowPath + "/chain/" + key, String.valueOf(predecessor.getAsInt()));
				}
			}
		}
	}

	/**
	 * Adds the lines of one entity index: its key, members, languages and attribute indexes.
	 *
	 * @param lines    the lines to add to
	 * @param path     the path of the lines
	 * @param index    the index
	 */
	private static void describeIndex(@Nonnull Lines lines, @Nonnull String path, @Nonnull EntityIndex index) {
		lines.put(path + "/key", index.getIndexKey().toString());
		lines.put(path + "/primaryKey", String.valueOf(index.getPrimaryKey()));
		lines.put(path + "/members", toString(index.getAllPrimaryKeys()));
		final List<Locale> locales = new ArrayList<>(index.getLanguages());
		locales.sort(Comparator.comparing(Locale::toLanguageTag));
		for (final Locale locale : locales) {
			lines.put(
				path + "/language/" + locale.toLanguageTag(),
				toString(index.getRecordsWithLanguageFormula(locale).compute())
			);
		}
		describeAttributeIndexes(lines, path, index);
	}

	/**
	 * Adds the lines of every attribute index an entity index holds - the complete value-to-record mapping of a filter
	 * index, the record and value order of a sort index, the records of a unique index and the order of a chain index.
	 *
	 * @param lines    the lines to add to
	 * @param path     the path of the lines
	 * @param index    the entity index
	 */
	private static void describeAttributeIndexes(
		@Nonnull Lines lines,
		@Nonnull String path,
		@Nonnull EntityIndex index
	) {
		index.forEachAttributeIndexKey(AttributeIndexType.FILTER, key -> {
			final FilterIndex filterIndex = index.getFilterIndex(key);
			if (filterIndex != null) {
				final StringBuilder buckets = new StringBuilder(64);
				for (final ValueToRecord bucket : filterIndex.getHistogramOfAllRecords().getBuckets()) {
					buckets.append(CanonicalValue.encode(bucket.getValue()))
						.append('=')
						.append(toString(bucket.getRecordIds()))
						.append("; ");
				}
				lines.put(path + "/filter/" + key, buckets.toString());
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.SORT, key -> {
			final SortIndex sortIndex = index.getSortIndex(key);
			if (sortIndex != null) {
				lines.put(path + "/sort/" + key, describeSortIndex(sortIndex));
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.UNIQUE, key -> {
			final UniqueIndex uniqueIndex = index.getUniqueIndex(key);
			if (uniqueIndex != null) {
				lines.put(
					path + "/unique/" + key,
					uniqueIndex.size() + " records of " + uniqueIndex.getDistinctValueCount() + " values"
				);
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.CHAIN, key -> {
			final ChainIndex chainIndex = index.getChainIndex(key);
			if (chainIndex != null) {
				lines.put(path + "/chain/" + key, Arrays.toString(chainIndex.getUnorderedLookup().getArray()));
				lines.put(path + "/chain/" + key + "/predecessors", describeChainPredecessors(chainIndex));
			}
		});
	}

	/**
	 * Adds the lines of one reduced-index membership: the covered and residual partitions, the covered owners and the
	 * partitions each covered owner is a member of.
	 *
	 * @param lines      the lines to add to
	 * @param path       the path of the lines
	 * @param membership the membership
	 */
	private static void describeMembership(
		@Nonnull Lines lines,
		@Nonnull String path,
		@Nonnull ReducedIndexMembership membership
	) {
		lines.put(path + "/covered", toString(membership.getCoveredIndexPrimaryKeys()));
		lines.put(path + "/residual", toString(membership.getResidualIndexPrimaryKeys()));
		final Bitmap coveredOwners = membership.getCoveredOwners();
		lines.put(path + "/coveredOwners", toString(coveredOwners));
		for (final int owner : coveredOwners.getArray()) {
			lines.put(path + "/owner/" + owner, toString(membership.getIndexPrimaryKeys(owner)));
		}
	}

	/**
	 * Renders a sort index position by position - see {@link SortIndexWalk#describe()}.
	 *
	 * @param sortIndex the sort index
	 * @return its rendering
	 */
	@Nonnull
	static String describeSortIndex(@Nonnull SortIndex sortIndex) {
		return SortIndexWalk.of(sortIndex).describe();
	}

	/**
	 * Renders the predecessor every element of a chain index declares, by ascending element primary key.
	 *
	 * @param chainIndex the chain index
	 * @return its rendering
	 */
	@Nonnull
	static String describeChainPredecessors(@Nonnull ChainIndex chainIndex) {
		final int[] elements = chainIndex.getUnorderedLookup().getArray().clone();
		Arrays.sort(elements);
		final StringBuilder predecessors = new StringBuilder(16 * elements.length + 2);
		for (final int element : elements) {
			final OptionalInt predecessor = chainIndex.getPredecessorPrimaryKey(element);
			predecessors.append(element).append("<-")
				.append(predecessor.isPresent() ? String.valueOf(predecessor.getAsInt()) : "NONE")
				.append("; ");
		}
		return predecessors.toString();
	}

	/**
	 * Renders a bitmap by its elements.
	 *
	 * @param bitmap the bitmap
	 * @return its rendering
	 */
	@Nonnull
	private static String toString(@Nonnull Bitmap bitmap) {
		return Arrays.toString(bitmap.getArray());
	}

	/**
	 * Collects the primary keys of entity references in ascending order.
	 *
	 * @param references the entity references
	 * @return their primary keys
	 */
	@Nonnull
	private static List<Integer> primaryKeysOf(@Nonnull List<EntityReference> references) {
		final List<Integer> primaryKeys = new ArrayList<>(references.size());
		for (final EntityReference reference : references) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		Collections.sort(primaryKeys);
		return primaryKeys;
	}

	/**
	 * What the entity bodies of one collection in one scope hold that the query lines are built from: the owners, their
	 * locales, and per reference the targets, the groups and the values of every non-localized numeric or string
	 * attribute of its rows.
	 */
	private static final class ReferenceSample {
		/**
		 * The owner primary keys, ascending.
		 */
		private final SortedSet<Integer> owners = new TreeSet<>();
		/**
		 * The locales of the owners, by language tag.
		 */
		private final SortedMap<String, Locale> locales = new TreeMap<>();
		/**
		 * The referenced primary keys per reference, ascending.
		 */
		private final Map<String, SortedSet<Integer>> targets = new TreeMap<>();
		/**
		 * How many owners reference each target, per reference.
		 */
		private final Map<String, Map<Integer, Integer>> ownersPerTarget = new TreeMap<>();
		/**
		 * The group primary keys per reference, ascending.
		 */
		private final Map<String, SortedSet<Integer>> groups = new TreeMap<>();
		/**
		 * The targets whose row carries a value of the attribute, per reference and attribute name.
		 */
		private final Map<String, SortedSet<Integer>> targetsWithValue = new TreeMap<>();
		/**
		 * The distinct values per reference and attribute name, ascending.
		 */
		private final Map<String, SortedSet<Comparable<Object>>> attributeValues = new TreeMap<>();

		/**
		 * Adds what one owner's body holds.
		 *
		 * @param entity the owner
		 */
		@SuppressWarnings("unchecked")
		void addOwner(@Nonnull SealedEntity entity) {
			this.owners.add(entity.getPrimaryKeyOrThrowException());
			for (final Locale locale : entity.getAllLocales()) {
				this.locales.put(locale.toLanguageTag(), locale);
			}
			final Set<String> ownTargets = CollectionUtils.createHashSet(entity.getReferences().size());
			for (final ReferenceContract reference : entity.getReferences()) {
				final String referenceName = reference.getReferenceName();
				this.targets.computeIfAbsent(referenceName, __ -> new TreeSet<>())
					.add(reference.getReferencedPrimaryKey());
				// an owner of a reference allowing duplicates is counted once per target
				if (ownTargets.add(referenceName + "/" + reference.getReferencedPrimaryKey())) {
					this.ownersPerTarget.computeIfAbsent(referenceName, __ -> new TreeMap<>())
						.merge(reference.getReferencedPrimaryKey(), 1, Integer::sum);
				}
				reference.getGroup().ifPresent(
					group -> this.groups.computeIfAbsent(referenceName, __ -> new TreeSet<>())
						.add(group.getPrimaryKey())
				);
				for (final AttributeValue attributeValue : reference.getAttributeValues()) {
					final Serializable value = attributeValue.value();
					if (
						attributeValue.key().locale() == null &&
							(value instanceof String || value instanceof Number) && value instanceof Comparable<?>
					) {
						final String key = referenceName + "/" + attributeValue.key().attributeName();
						this.attributeValues.computeIfAbsent(key, __ -> new TreeSet<>()).add((Comparable<Object>) value);
						this.targetsWithValue.computeIfAbsent(key, __ -> new TreeSet<>())
							.add(reference.getReferencedPrimaryKey());
					}
				}
			}
		}

		/**
		 * Returns a target of the reference whose row carries a value of the attribute - one exactly one owner
		 * references when there is such a target.
		 *
		 * @param referenceName the reference
		 * @param attributeName the attribute
		 * @return the target, `null` when no row of the reference carries a value of the attribute
		 */
		@Nullable
		Integer getTargetWithValue(@Nonnull String referenceName, @Nonnull String attributeName) {
			final SortedSet<Integer> withValue = this.targetsWithValue.get(referenceName + "/" + attributeName);
			if (withValue == null || withValue.isEmpty()) {
				return null;
			}
			final Map<Integer, Integer> counts = this.ownersPerTarget.getOrDefault(referenceName, Map.of());
			for (final Integer target : withValue) {
				if (counts.getOrDefault(target, 0) == 1) {
					return target;
				}
			}
			return withValue.first();
		}

		/**
		 * Returns the owner primary keys.
		 *
		 * @return the owners, ascending
		 */
		@Nonnull
		List<Integer> getOwners() {
			return new ArrayList<>(this.owners);
		}

		/**
		 * Returns the locales of the owners.
		 *
		 * @return the locales, by language tag
		 */
		@Nonnull
		Collection<Locale> getLocales() {
			return this.locales.values();
		}

		/**
		 * Returns the targets of a reference.
		 *
		 * @param referenceName the reference
		 * @return the referenced primary keys, ascending
		 */
		@Nonnull
		Set<Integer> getTargets(@Nonnull String referenceName) {
			return this.targets.getOrDefault(referenceName, Collections.emptySortedSet());
		}

		/**
		 * Returns the groups of a reference.
		 *
		 * @param referenceName the reference
		 * @return the group primary keys, ascending
		 */
		@Nonnull
		Set<Integer> getGroups(@Nonnull String referenceName) {
			return this.groups.getOrDefault(referenceName, Collections.emptySortedSet());
		}

		/**
		 * Returns the distinct values of an attribute of a reference.
		 *
		 * @param referenceName the reference
		 * @param attributeName the attribute
		 * @return the values, ascending
		 */
		@Nonnull
		List<Serializable> getAttributeValues(@Nonnull String referenceName, @Nonnull String attributeName) {
			final SortedSet<Comparable<Object>> values = this.attributeValues.get(referenceName + "/" + attributeName);
			final List<Serializable> result = new ArrayList<>(values == null ? 0 : values.size());
			if (values != null) {
				for (final Comparable<Object> value : values) {
					result.add((Serializable) value);
				}
			}
			return result;
		}
	}

	/**
	 * A catalog, or a part of one, reduced to sorted `path -> value` lines.
	 */
	private static final class Lines {
		/**
		 * The lines, sorted by path.
		 */
		private final SortedMap<String, String> lines = new TreeMap<>();

		/**
		 * Adds a line, refusing a path that is already taken - two lines under one path would hide one of them.
		 *
		 * @param path  the path
		 * @param value the value
		 */
		void put(@Nonnull String path, @Nonnull String value) {
			final String previous = this.lines.put(path, value);
			if (previous != null) {
				fail("Description path `" + path + "` was described twice");
			}
		}

		/**
		 * Returns the lines.
		 *
		 * @return the lines, sorted by path
		 */
		@Nonnull
		SortedMap<String, String> asSortedMap() {
			return Collections.unmodifiableSortedMap(this.lines);
		}
	}
}
