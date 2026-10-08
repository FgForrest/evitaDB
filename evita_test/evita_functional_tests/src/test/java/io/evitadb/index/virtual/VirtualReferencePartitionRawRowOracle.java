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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the registry of single-owner reference partitions of one collection against the stored entity bodies - the
 * rows the registry must describe, derived from what the owners say they reference, not from any index.
 *
 * {@link VirtualReferencePartitionOracle} compares the registry with the real partitions and the differential engines
 * compare an engine maintaining the registry with one that does not. Both are blind to a defect of the indexing path
 * the two engines and the mirror share: a sortable value the path never writes is missing from the real partition,
 * from the registry and from the other engine alike, while the body still holds it. This oracle reads the bodies
 * through the public query API and derives, for every reference shadowed in a scope:
 *
 * - the partitions: the rows of the owners in the scope grouped by referenced entity and - for a reference allowing
 *   duplicates - by the values of its representative attributes, a missing one taking its default. A group of one
 *   owner is a single-owner partition, which must have an owner row naming that owner, the referenced entity and the
 *   representative attribute values; no other partition may have one;
 * - the sort values: for every attribute of the reference sortable in the scope, the value of the owner's row at its
 *   locale, and for every sortable attribute compound of the reference indexed in the scope, its elements - per
 *   attribute locale of the owner for a localized compound - unless all of them are missing. The registry must hold
 *   exactly these values under exactly these keys;
 * - the chain predecessors: for every chainable attribute of the reference sortable in the scope, the predecessor
 *   the owner's row declares.
 *
 * A `ReferencedEntityPredecessor` chain is left out: the real chain index keys it by the referenced entity, and a
 * single-owner partition that had a second owner may have lost the predecessor (#1691) - the registry copies the real
 * chain, which {@link VirtualReferencePartitionOracle} checks.
 *
 * A body value is compared in the space the index stores it in. It is taken through the conversions an indexed value
 * goes through - {@link NumberUtils#normalizeForIndexing(Serializable, int)} at the attribute's indexed decimal places,
 * then the normalizer of the sort index - and compared by {@link CanonicalValue#encodeIndexed(Object)}. Those are pure
 * functions of a value; which values reach which index, and for which partition, is what the oracle derives on its
 * own.
 *
 * The oracle holds for histories without drift only: a schema change that leaves existing partitions as they were -
 * an attribute turned sortable, a reference turned shadowed or another scale - makes the bodies and the partitions
 * disagree by design.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VirtualReferencePartitionRawRowOracle {
	/**
	 * How many bodies of one collection and scope are read at most.
	 */
	private static final int PAGE_SIZE = 10_000;
	/**
	 * Returned by the partition lookup when the key has no partition - partition primary keys are positive.
	 */
	private static final int NO_PARTITION = 0;

	/**
	 * One single-owner partition as the bodies describe it.
	 *
	 * @param key   the key of the partition
	 * @param owner the owner and its row referencing the entity
	 */
	private record ExpectedPartition(@Nonnull RepresentativeReferenceKey key, @Nonnull OwnerRow owner) {
	}

	/**
	 * One row of a reference in the body of its owner.
	 *
	 * @param entity    the body of the owner
	 * @param reference the row
	 */
	private record OwnerRow(@Nonnull SealedEntity entity, @Nonnull ReferenceContract reference) {
	}

	private VirtualReferencePartitionRawRowOracle() {
	}

	/**
	 * Fails when the registry of the collection disagrees with the bodies of its entities, listing every disagreement.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param session    the session to read the bodies in - inside an open session the one its steps ran in
	 * @param collection the collection
	 */
	static void assertConsistent(
		@Nonnull String label,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityCollection collection
	) {
		final List<String> findings = inspect(session, collection);
		if (!findings.isEmpty()) {
			fail(
				label + ": the virtual reference partitions of `" + collection.getEntityType() + "` disagree with " +
					"the entity bodies in " + findings.size() + " place(s):\n" + String.join("\n", findings)
			);
		}
	}

	/**
	 * Checks the registry of every shadowed reference of the collection in every scope against the bodies.
	 *
	 * @param session    the session to read the bodies in
	 * @param collection the collection
	 * @return every disagreement found; empty when the registry agrees with the bodies
	 */
	@Nonnull
	static List<String> inspect(@Nonnull EvitaSessionContract session, @Nonnull EntityCollection collection) {
		final List<String> findings = new ArrayList<>(4);
		final String entityType = collection.getEntityType();
		final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(entityType);
		for (final Scope scope : Scope.values()) {
			final List<SealedEntity> bodies = session.queryList(
				query(collection(entityType), filterBy(scope(scope)), require(page(1, PAGE_SIZE), entityFetchAll())),
				SealedEntity.class
			);
			for (final ReferenceSchemaContract referenceSchema : schema.getReferences().values()) {
				if (VirtualReferencePartitionOracle.isShadowed(referenceSchema, scope)) {
					inspect(collection, referenceSchema, scope, bodies, findings);
				}
			}
		}
		return findings;
	}

	/**
	 * Checks the registry of one shadowed reference in one scope against the bodies.
	 *
	 * @param collection      the collection resolving the partitions
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param bodies          the bodies of every entity of the collection in the scope
	 * @param findings        receives the disagreements
	 */
	private static void inspect(
		@Nonnull EntityCollection collection,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull List<SealedEntity> bodies,
		@Nonnull List<String> findings
	) {
		final String referenceName = referenceSchema.getName();
		final String prefix = referenceName + "/" + scope + ": ";
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
		);
		final SortedMap<Integer, ExpectedPartition> expected = new TreeMap<>();
		for (final List<OwnerRow> group : groupRows(referenceSchema, bodies).values()) {
			if (group.size() != 1) {
				continue;
			}
			final OwnerRow row = group.get(0);
			final RepresentativeReferenceKey key = keyOf(referenceSchema, row.reference());
			final int partitionPrimaryKey = findPartitionPrimaryKey(collection, typeIndex, scope, key);
			if (partitionPrimaryKey == NO_PARTITION) {
				findings.add(prefix + "the single owner " + primaryKeyOf(row) + " of " + key + " has no partition");
			} else {
				expected.put(partitionPrimaryKey, new ExpectedPartition(key, row));
			}
		}

		if (!(typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex)) {
			if (!expected.isEmpty()) {
				findings.add(prefix + "single-owner partitions " + expected.keySet() + " but no registry");
			}
			return;
		}
		final VirtualReferencePartitionIndex registry = referencedTypeIndex.getVirtualReferencePartitionIndex();
		inspectRows(registry, expected, prefix, findings);
		inspectSortValues(referenceSchema, scope, registry, expected, prefix, findings);
		inspectChains(referenceSchema, scope, registry, expected, prefix, findings);
	}

	/**
	 * Finds the primary key of the partition of a key: the primary key of its index object, or - in a catalog writing
	 * its single-owner partitions virtually, where such a partition has no index object - of its registry row.
	 *
	 * @param collection the collection resolving the partitions
	 * @param typeIndex  the referenced-type entity index of the reference in the scope, `null` when there is none
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @return the primary key of the partition, or {@link #NO_PARTITION} when it has neither an index object nor a row
	 */
	private static int findPartitionPrimaryKey(
		@Nonnull EntityCollection collection,
		@Nullable EntityIndex typeIndex,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityIndex partition = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key)
		);
		if (partition != null) {
			return partition.getPrimaryKey();
		}
		// TODO JNO - remove at the end of #1615
		if (typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex) {
			final VirtualReferencePartition registered = ReferencePartitions.findRegisteredForWrite(
				referencedTypeIndex, key
			);
			if (registered != null) {
				return registered.getPrimaryKey();
			}
		}
		return NO_PARTITION;
	}

	/**
	 * Groups the rows of a reference by the partition they fall into.
	 *
	 * @param referenceSchema the reference
	 * @param bodies          the bodies of the owners
	 * @return the rows by the canonical encoding of their partition key
	 */
	@Nonnull
	private static Map<String, List<OwnerRow>> groupRows(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<SealedEntity> bodies
	) {
		final Map<String, List<OwnerRow>> groups = new TreeMap<>();
		for (final SealedEntity body : bodies) {
			for (final ReferenceContract reference : body.getReferences(referenceSchema.getName())) {
				final RepresentativeReferenceKey key = keyOf(referenceSchema, reference);
				groups.computeIfAbsent(
					key.primaryKey() + "/" + CanonicalValue.encode(key.representativeAttributeValues()),
					__ -> new ArrayList<>(2)
				).add(new OwnerRow(body, reference));
			}
		}
		return groups;
	}

	/**
	 * Derives the key of the partition a row falls into: the referenced entity and, for a reference allowing
	 * duplicates, the values of its representative attributes in the order of their names, a missing one taking the
	 * attribute's default.
	 *
	 * @param referenceSchema the reference
	 * @param reference       the row
	 * @return the partition key
	 */
	@Nonnull
	private static RepresentativeReferenceKey keyOf(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceContract reference
	) {
		final ReferenceKey referenceKey = new ReferenceKey(
			referenceSchema.getName(), reference.getReferencedPrimaryKey()
		);
		if (!referenceSchema.getCardinality().allowsDuplicates()) {
			return new RepresentativeReferenceKey(referenceKey);
		}
		final List<String> names = referenceSchema.getAttributes()
			.values()
			.stream()
			.filter(AttributeSchemaContract::isRepresentative)
			.map(AttributeSchemaContract::getName)
			.sorted()
			.toList();
		final Serializable[] values = new Serializable[names.size()];
		for (int i = 0; i < values.length; i++) {
			final String name = names.get(i);
			values[i] = reference.getAttributeValue(name)
				.filter(AttributeValue::exists)
				.map(AttributeValue::value)
				.orElseGet(() -> referenceSchema.getAttribute(name).orElseThrow().getDefaultValue());
		}
		return new RepresentativeReferenceKey(referenceKey, values);
	}

	/**
	 * Checks the owner rows and representative tuples of the registry: one per single-owner partition, naming its
	 * owner, referenced entity and representative attribute values, and none for any other partition.
	 *
	 * @param registry the registry
	 * @param expected the single-owner partitions by primary key
	 * @param prefix   the prefix of every finding
	 * @param findings receives the disagreements
	 */
	private static void inspectRows(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull SortedMap<Integer, ExpectedPartition> expected,
		@Nonnull String prefix,
		@Nonnull List<String> findings
	) {
		final Set<Integer> owned = new TreeSet<>();
		registry.forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER, (partition, payload) -> owned.add(partition)
		);
		for (final Integer partition : owned) {
			if (!expected.containsKey(partition)) {
				findings.add(
					prefix + "partition " + partition + " has an owner row, but no single owner in the bodies"
				);
			}
		}
		for (final Map.Entry<Integer, ExpectedPartition> entry : expected.entrySet()) {
			final int partition = entry.getKey();
			final ExpectedPartition expectedPartition = entry.getValue();
			final OptionalLong row = registry.getPartitionOwner(partition);
			final int owner = primaryKeyOf(expectedPartition.owner());
			final int target = expectedPartition.key().primaryKey();
			if (row.isEmpty()) {
				findings.add(
					prefix + "partition " + partition + " of " + expectedPartition.key() + " has no owner row, its " +
						"single owner is " + owner
				);
				continue;
			}
			final int actualOwner = NumberUtils.unpackHigh(row.getAsLong());
			final int actualTarget = NumberUtils.unpackLow(row.getAsLong());
			if (actualOwner != owner || actualTarget != target) {
				findings.add(
					prefix + "partition " + partition + " has the owner row " + actualOwner + " -> " + actualTarget +
						", the bodies say " + owner + " -> " + target
				);
			}
			final int tupleId = registry.getRepresentativeTupleId(partition);
			final Serializable[] tuple = tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID ?
				new Serializable[0] : registry.getTuple(tupleId);
			final Serializable[] expectedTuple = expectedPartition.key().representativeAttributeValues();
			if (!CanonicalValue.encode(tuple).equals(CanonicalValue.encode(expectedTuple))) {
				findings.add(
					prefix + "partition " + partition + " has the tuple " + Arrays.toString(tuple) + ", the bodies " +
						"say " + Arrays.toString(expectedTuple)
				);
			}
		}
	}

	/**
	 * Checks the sort values of the registry: under every key it declares or the bodies call for, exactly the values
	 * the single owners' rows hold.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param registry        the registry
	 * @param expected        the single-owner partitions by primary key
	 * @param prefix          the prefix of every finding
	 * @param findings        receives the disagreements
	 */
	private static void inspectSortValues(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull SortedMap<Integer, ExpectedPartition> expected,
		@Nonnull String prefix,
		@Nonnull List<String> findings
	) {
		final Map<AttributeIndexKey, Map<Integer, Serializable>> expectedValues = new TreeMap<>();
		for (final Map.Entry<Integer, ExpectedPartition> entry : expected.entrySet()) {
			collectSortValues(referenceSchema, scope, entry.getKey(), entry.getValue().owner(), expectedValues);
		}
		final Set<AttributeIndexKey> declared = registry.getDeclaredSortKeys();
		final Set<AttributeIndexKey> keys = new TreeSet<>(declared);
		keys.addAll(expectedValues.keySet());
		for (final AttributeIndexKey key : keys) {
			final OwnerSortIndex sortIndex = declared.contains(key) ? registry.getSortIndex(key) : null;
			final Map<Integer, Serializable> actual = new TreeMap<>();
			if (sortIndex != null) {
				final SortIndexWalk walk = SortIndexWalk.of(sortIndex);
				for (int i = 0; i < walk.records().length; i++) {
					actual.put(walk.records()[i], walk.values()[i]);
				}
			}
			final Map<Integer, Serializable> expectedOfKey = expectedValues.getOrDefault(key, Map.of());
			final Set<Integer> partitions = new TreeSet<>(actual.keySet());
			partitions.addAll(expectedOfKey.keySet());
			for (final Integer partition : partitions) {
				final Serializable expectedValue = expectedOfKey.get(partition);
				final Serializable actualValue = actual.get(partition);
				final String expectedEncoding = expectedValue == null ?
					"absent" : CanonicalValue.encodeIndexed(expectedValue);
				final String actualEncoding = actualValue == null ?
					"absent" : CanonicalValue.encodeIndexed(actualValue);
				if (!expectedEncoding.equals(actualEncoding)) {
					findings.add(
						prefix + "sort key " + key + " of partition " + partition + ": the registry holds " +
							actualEncoding + ", the body of its owner holds " + expectedEncoding
					);
				}
			}
		}
	}

	/**
	 * Adds the sort values the row of a single owner calls for: its sortable attributes and compounds.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param partition       the partition primary key
	 * @param row             the row of the single owner
	 * @param expectedValues  receives the values by key and partition, in the space the index stores them in
	 */
	private static void collectSortValues(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		int partition,
		@Nonnull OwnerRow row,
		@Nonnull Map<AttributeIndexKey, Map<Integer, Serializable>> expectedValues
	) {
		final String referenceName = referenceSchema.getName();
		for (final AttributeValue attributeValue : row.reference().getAttributeValues()) {
			final AttributeSchemaContract attribute = referenceSchema
				.getAttribute(attributeValue.key().attributeName())
				.orElse(null);
			if (
				attribute == null || !attributeValue.exists() || attributeValue.value() == null ||
					!attribute.isSortableInScope(scope) ||
					ChainableType.class.isAssignableFrom(attribute.getPlainType())
			) {
				continue;
			}
			final UnaryOperator<Serializable> normalizer = SortIndex.createNormalizerFor(
				new ComparatorSource(attribute.getPlainType(), OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				attribute.getIndexedDecimalPlaces()
			).orElseGet(UnaryOperator::identity);
			expectedValues
				.computeIfAbsent(
					new AttributeIndexKey(referenceName, attribute.getName(), attributeValue.key().locale()),
					__ -> new TreeMap<>()
				)
				.put(partition, normalizer.apply(indexed(attribute, attributeValue.value())));
		}
		for (final SortableAttributeCompoundSchemaContract compound :
			referenceSchema.getSortableAttributeCompounds().values()) {
			if (!compound.isIndexedInScope(scope)) {
				continue;
			}
			final List<AttributeElement> elements = compound.getAttributeElements();
			final boolean localized = elements.stream().anyMatch(
				it -> referenceSchema.getAttribute(it.attributeName()).map(AttributeSchemaContract::isLocalized)
					.orElse(false)
			);
			// a localized compound is indexed per attribute locale of the owner, a language-agnostic one once
			final List<Locale> locales = localized ?
				new ArrayList<>(attributeLocalesOf(row.entity())) : Arrays.asList((Locale) null);
			for (final Locale locale : locales) {
				final Serializable[] values = compoundValues(referenceSchema, row.reference(), elements, locale);
				if (Arrays.stream(values).allMatch(Objects::isNull)) {
					continue;
				}
				expectedValues
					.computeIfAbsent(
						new AttributeIndexKey(referenceName, compound.getName(), locale), __ -> new TreeMap<>()
					)
					.put(partition, new ComparableArray(values));
			}
		}
	}

	/**
	 * Returns the attribute locales of an entity as its body keeps them: the locales of its own attributes and of the
	 * attributes of every reference it has.
	 *
	 * @param entity the body
	 * @return the attribute locales
	 */
	@Nonnull
	private static Set<Locale> attributeLocalesOf(@Nonnull SealedEntity entity) {
		final Set<Locale> locales = new HashSet<>(entity.getAttributeLocales());
		for (final ReferenceContract reference : entity.getReferences()) {
			locales.addAll(reference.getAttributeLocales());
		}
		return locales;
	}

	/**
	 * Builds the value of a sortable attribute compound from a row, in the space the index stores it in.
	 *
	 * @param referenceSchema the reference
	 * @param reference       the row
	 * @param elements        the elements of the compound
	 * @param locale          the locale of a localized compound, `null` for a language-agnostic one
	 * @return the values of the elements, `null` for a missing one
	 */
	@Nonnull
	private static Serializable[] compoundValues(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceContract reference,
		@Nonnull List<AttributeElement> elements,
		@Nullable Locale locale
	) {
		final Serializable[] values = new Serializable[elements.size()];
		final ComparatorSource[] comparatorBase = new ComparatorSource[elements.size()];
		for (int i = 0; i < values.length; i++) {
			final AttributeElement element = elements.get(i);
			final AttributeSchemaContract attribute = referenceSchema.getAttribute(element.attributeName())
				.orElseThrow();
			comparatorBase[i] = new ComparatorSource(
				attribute.getPlainType(), element.direction(), element.behaviour()
			);
			final Serializable value = (
				attribute.isLocalized() ?
					reference.getAttributeValue(element.attributeName(), locale) :
					reference.getAttributeValue(element.attributeName())
			)
				.filter(AttributeValue::exists)
				.map(AttributeValue::value)
				.orElse(null);
			values[i] = value == null ? null : indexed(attribute, value);
		}
		return (Serializable[]) SortIndex.createNormalizerFor(comparatorBase, 0).apply(values);
	}

	/**
	 * Takes a value through the conversion every indexed attribute value goes through before an index receives it.
	 *
	 * @param attribute the attribute
	 * @param value     the value as the body holds it
	 * @return the value an index receives
	 */
	@Nonnull
	private static Serializable indexed(@Nonnull AttributeSchemaContract attribute, @Nonnull Serializable value) {
		return NumberUtils.normalizeForIndexing(value, attribute.getIndexedDecimalPlaces());
	}

	/**
	 * Checks the chain predecessors of the registry: for every chain key it declares or the bodies call for, the
	 * predecessor each single owner's row declares, or none.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param registry        the registry
	 * @param expected        the single-owner partitions by primary key
	 * @param prefix          the prefix of every finding
	 * @param findings        receives the disagreements
	 */
	private static void inspectChains(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull SortedMap<Integer, ExpectedPartition> expected,
		@Nonnull String prefix,
		@Nonnull List<String> findings
	) {
		final String referenceName = referenceSchema.getName();
		final Map<AttributeIndexKey, Map<Integer, Integer>> expectedPredecessors = new TreeMap<>();
		for (final AttributeSchemaContract attribute : referenceSchema.getAttributes().values()) {
			if (isComparedChain(attribute, scope)) {
				expectedPredecessors.put(
					new AttributeIndexKey(referenceName, attribute.getName(), null), new TreeMap<>()
				);
			}
		}
		for (final Map.Entry<Integer, ExpectedPartition> entry : expected.entrySet()) {
			for (final AttributeIndexKey key : expectedPredecessors.keySet()) {
				entry.getValue().owner().reference().getAttributeValue(key.attributeName())
					.filter(AttributeValue::exists)
					.map(AttributeValue::value)
					.ifPresent(
						value -> expectedPredecessors.get(key)
							.put(entry.getKey(), ((ChainableType) value).predecessorPk())
					);
			}
		}
		final Set<AttributeIndexKey> declared = registry.getDeclaredChainKeys();
		final Set<AttributeIndexKey> keys = new TreeSet<>(expectedPredecessors.keySet());
		for (final AttributeIndexKey key : declared) {
			// a chain the oracle leaves out, see the class documentation
			final boolean leftOut = referenceSchema.getAttribute(key.attributeName())
				.map(it -> !isComparedChain(it, scope))
				.orElse(false);
			if (!leftOut) {
				keys.add(key);
			}
		}
		for (final AttributeIndexKey key : keys) {
			final Map<Integer, Integer> expectedOfKey = expectedPredecessors.getOrDefault(key, Map.of());
			for (final Integer partition : expected.keySet()) {
				final Integer expectedPredecessor = expectedOfKey.get(partition);
				final OptionalInt actual = declared.contains(key) ?
					registry.getChainPredecessor(key, partition) : OptionalInt.empty();
				final OptionalInt expectedOptional = expectedPredecessor == null ?
					OptionalInt.empty() : OptionalInt.of(expectedPredecessor);
				if (!expectedOptional.equals(actual)) {
					findings.add(
						prefix + "chain key " + key + " of partition " + partition + ": the registry holds " + actual +
							", the body of its owner declares " + expectedOptional
					);
				}
			}
		}
	}

	/**
	 * Tells whether the oracle compares the chain of an attribute - a chainable attribute sortable in the scope, but
	 * not a `ReferencedEntityPredecessor`, see the class documentation.
	 *
	 * @param attribute the attribute of the reference
	 * @param scope     the scope
	 * @return true for a compared chain
	 */
	private static boolean isComparedChain(@Nonnull AttributeSchemaContract attribute, @Nonnull Scope scope) {
		return attribute.isSortableInScope(scope) &&
			ChainableType.class.isAssignableFrom(attribute.getPlainType()) &&
			!ReferencedEntityPredecessor.class.isAssignableFrom(attribute.getPlainType());
	}

	/**
	 * Returns the primary key of the owner of a row.
	 *
	 * @param row the row
	 * @return the owner's primary key
	 */
	private static int primaryKeyOf(@Nonnull OwnerRow row) {
		return row.entity().getPrimaryKeyOrThrowException();
	}
}
