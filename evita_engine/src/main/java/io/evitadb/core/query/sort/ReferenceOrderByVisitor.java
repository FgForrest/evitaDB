/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.sort;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.EntityGroupProperty;
import io.evitadb.api.query.order.EntityPrimaryKeyExact;
import io.evitadb.api.query.order.EntityPrimaryKeyNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderInScope;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.FetchRequirementCollector;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator.Link;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper;
import io.evitadb.core.query.sort.attribute.translator.AttributeNaturalTranslator;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.entity.translator.EntityGroupPropertyTranslator;
import io.evitadb.core.query.sort.entity.translator.EntityPropertyTranslator;
import io.evitadb.core.query.sort.reference.sorter.VirtualPartitionSortedRecords;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyExactTranslator;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyNaturalTranslator;
import io.evitadb.core.query.sort.translator.OrderByTranslator;
import io.evitadb.core.query.sort.translator.OrderInScopeTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * This {@link ConstraintVisitor} translates tree of {@link OrderConstraint} to a {@link OrderingDescriptor} record
 * containing reference comparators in declaration order. The visitor is used by {@link ReferenceFetcher}
 * implementations to plan and prepare reference orderings.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceOrderByVisitor implements ConstraintVisitor, FetchRequirementCollector {
	private static final Map<Class<? extends OrderConstraint>, ReferenceOrderingConstraintTranslator<? extends OrderConstraint>> TRANSLATORS;

	/* initialize list of all OrderConstraints handlers once for a lifetime */
	static {
		TRANSLATORS = CollectionUtils.createHashMap(8);
		TRANSLATORS.put(OrderBy.class, new OrderByTranslator());
		TRANSLATORS.put(OrderInScope.class, new OrderInScopeTranslator());
		TRANSLATORS.put(AttributeNatural.class, new AttributeNaturalTranslator());
		TRANSLATORS.put(EntityPrimaryKeyNatural.class, new EntityPrimaryKeyNaturalTranslator());
		TRANSLATORS.put(EntityPrimaryKeyExact.class, new EntityPrimaryKeyExactTranslator());
		TRANSLATORS.put(EntityProperty.class, new EntityPropertyTranslator());
		TRANSLATORS.put(EntityGroupProperty.class, new EntityGroupPropertyTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate private final QueryPlanningContext queryContext;
	/**
	 * Reference to the collector of requirements for entity (pre)fetch phase.
	 */
	private final FetchRequirementCollector fetchRequirementCollector;
	/**
	 * Reference schema of the reference being fetched and sorted.
	 */
	@Getter private final ReferenceSchema referenceSchema;
	/**
	 * Scopes an unwrapped chain ordering is translated for: the query's scopes plus the scopes of the owners being
	 * ordered, because a plain chain is read in the owner's own scope and an enriched owner may live outside the
	 * scopes its enrichment selects.
	 */
	private final Set<Scope> chainScopes;
	/**
	 * Provides access to the attribute schema or sortable attribute compound based on the attribute name.
	 */
	private final AttributeSchemaAccessor attributeSchemaAccessor;
	/**
	 * Comparator allowing to order references by sorter defined on referenced entity (requiring nested query). The
	 * fetch of the references initializes its sorters (and sets the locale of the reference filter) before it is used.
	 */
	private EntityNestedQueryComparator nestedQueryComparator;
	/**
	 * First link of the reference ordering.
	 */
	@Nullable private Link firstLink;
	/**
	 * Last link of the reference ordering, where the next comparator is appended.
	 */
	@Nullable private Link lastLink;
	/**
	 * Enclosing owner gate of the nested stage, or `null` when it is unwrapped.
	 */
	@Nullable private ScopedOrdering nestedComparatorScope;
	/**
	 * Holds the key data for retrieving the last chain index used in a query context.
	 * The key includes the reference partition, the attribute, and the index scope.
	 */
	private LastRetrievedChainIndexKey lastRetrievedChainIndexKey;
	/**
	 * The last retrieved chain index used in processing and determining the position of the chain in the reference order.
	 * This variable is internally updated during the traversal and comparison of constraints.
	 */
	@Nullable private SortedRecordsSupplierFactory lastRetrievedChainIndex;
	/**
	 * Active reference-level `inScope` containers.
	 */
	private final Deque<ScopedOrdering> ownerGates = new ArrayDeque<>(16);

	/**
	 * Translates the reference ordering into its chain of links before any reference row is read. Attributes and
	 * indexes are checked in the query's scopes, or in the scope of the enclosing `inScope` container.
	 *
	 * @param queryContext planning context of the owner request
	 * @param fetchRequirementCollector collector of attributes needed to order references
	 * @param orderBy ordering of the references
	 * @param entitySchema schema of the owner
	 * @param referenceSchema schema of the reference being ordered
	 * @param ownerScopes scopes of the owners whose references are ordered; an unwrapped plain chain ordering is
	 *                    translated for them too, because it is read in each owner's own scope.
	 * @return the translated ordering and its nested comparator, if any
	 */
	@Nonnull
	public static OrderingDescriptor getComparator(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull FetchRequirementCollector fetchRequirementCollector,
		@Nonnull OrderConstraint orderBy,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull Set<Scope> ownerScopes
		) {
		final ReferenceOrderByVisitor orderVisitor = new ReferenceOrderByVisitor(
			queryContext,
			fetchRequirementCollector,
			referenceSchema,
			unionOf(queryContext.getScopes(), ownerScopes),
			// the ordering is a part of the query, so it records the reference attributes it names - counted when the
			// ordering is checked while the query is planned, see `ReferencedEntityFetcher#verifyReferenceOrdering`
			new AttributeSchemaAccessor(
				queryContext.getCatalogSchema(),
				entitySchema,
				__ -> referenceSchema,
				queryContext
			)
		);
		orderBy.accept(orderVisitor);
		return orderVisitor.getComparator();
	}

	/**
	 * Returns the query scopes extended by the owner scopes the query does not select.
	 *
	 * @param queryScopes scopes selected by the query
	 * @param ownerScopes scopes of the owners whose references are ordered
	 * @return the query scopes when they contain every owner scope, otherwise the union of both
	 */
	@Nonnull
	private static Set<Scope> unionOf(@Nonnull Set<Scope> queryScopes, @Nonnull Set<Scope> ownerScopes) {
		if (queryScopes.containsAll(ownerScopes)) {
			return queryScopes;
		}
		final Set<Scope> union = EnumSet.noneOf(Scope.class);
		union.addAll(queryScopes);
		union.addAll(ownerScopes);
		return union;
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final OrderConstraint orderConstraint = (OrderConstraint) constraint;

		@SuppressWarnings("unchecked") final ReferenceOrderingConstraintTranslator<OrderConstraint> translator =
			(ReferenceOrderingConstraintTranslator<OrderConstraint>) TRANSLATORS.get(orderConstraint.getClass());
		isPremiseValid(
			translator != null,
			"No translator found for constraint `" + orderConstraint.getClass() + "`!"
		);

		// if query is a container query
		if (orderConstraint instanceof ConstraintContainer) {
			@SuppressWarnings("unchecked") final ConstraintContainer<OrderConstraint> container = (ConstraintContainer<OrderConstraint>) orderConstraint;
			// process children constraints
			if (!(translator instanceof SelfTraversingTranslator)) {
				for (OrderConstraint subConstraint : container) {
					subConstraint.accept(this);
				}
			}
			// process the container query itself
			translator.createComparator(orderConstraint, this);
		} else if (orderConstraint instanceof ConstraintLeaf) {
			// process the leaf query
			translator.createComparator(orderConstraint, this);
		} else {
			// sanity check only
			throw new GenericEvitaInternalError("Should never happen");
		}
	}

	@Override
	public void addRequirementsToPrefetch(@Nonnull EntityContentRequire... require) {
		AttributeContent attributeContent = null;
		for (EntityContentRequire entityContentRequire : require) {
			if (entityContentRequire instanceof AttributeContent attributeContentRequire) {
				attributeContent = attributeContentRequire;
				break;
			} else {
				throw new GenericEvitaInternalError("Should never happen");
			}
		}
		Assert.isPremiseValid(
			attributeContent != null,
			"Attribute content requirement not found in the provided requirements."
		);
		this.fetchRequirementCollector.addRequirementsToPrefetch(
			new ReferenceContent(
				this.referenceSchema.getName(),
				attributeContent
			)
		);
	}

	@Nonnull
	@Override
	public EntityContentRequire[] getRequirementsToPrefetch() {
		return this.fetchRequirementCollector.getRequirementsToPrefetch();
	}

	/**
	 * Exposes the ordering chain and its nested delegate for batch preparation.
	 *
	 * @return the ordering descriptor, using {@link ReferenceComparator#DEFAULT} when no link was declared
	 */
	@Nonnull
	public OrderingDescriptor getComparator() {
		return new OrderingDescriptor(
			this.firstLink == null ? ReferenceComparator.DEFAULT : this.firstLink,
			this.nestedQueryComparator
		);
	}

	/**
	 * Returns the shared nested comparator for entity and group ordering in the same owner gate.
	 *
	 * @return the nested query comparator used by the ordering chain
	 * @throws EvitaInvalidUsageException when nested orderings use different owner gates
	 */
	@Nonnull
	public EntityNestedQueryComparator getOrCreateNestedQueryComparator() {
		final ScopedOrdering ownerGate = this.ownerGates.peek();
		if (this.nestedQueryComparator == null) {
			this.nestedQueryComparator = new EntityNestedQueryComparator();
			this.nestedComparatorScope = ownerGate;
			this.addComparator(this.nestedQueryComparator);
		} else if (this.nestedComparatorScope != ownerGate) {
			throw new EvitaInvalidUsageException(
				"Nested orderings (`entityProperty` or `entityGroupProperty`) of one reference ordering must sit " +
					"in the same `inScope` container."
			);
		}
		return this.nestedQueryComparator;
	}

	/**
	 * Appends a comparator with the owner gate in effect at its declaration.
	 *
	 * @param comparator the delegate to append
	 */
	public void addComparator(@Nonnull ReferenceComparator comparator) {
		final ScopedOrdering ownerGate = this.ownerGates.peek();
		final Link link = new Link(ownerGate == null ? null : ownerGate.scope(), comparator);
		if (this.lastLink == null) {
			this.firstLink = link;
		} else {
			this.lastLink.append(link);
		}
		this.lastLink = link;
	}

	/**
	 * Retrieves an attribute schema or a sortable attribute compound based on the provided attribute name.
	 *
	 * @param attributeName the name of the attribute to retrieve the schema or compound for
	 * @return the attribute schema or sortable attribute compound corresponding to the attribute name
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public NamedSchemaContract getAttributeSchemaOrSortableAttributeCompound(
		@Nonnull String attributeName
	) {
		return this.attributeSchemaAccessor.getAttributeSchemaOrSortableAttributeCompound(
			attributeName, getScopes()
		);
	}

	/**
	 * Retrieves an attribute schema based on the provided attribute name and required traits.
	 *
	 * @param attributeName the name of the attribute to retrieve the schema for
	 * @param requiredTrait an optional list of traits that the attribute schema must fulfill
	 * @return the attribute schema corresponding to the specified attribute name and traits
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotFilterableException when filterable traits are requested but the attribute does not
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull AttributeTrait... requiredTrait
	) {
		return this.attributeSchemaAccessor.getAttributeSchema(
			attributeName, getScopes(), requiredTrait
		);
	}

	/**
	 * Determines whether an inherited referenced-entity predecessor is backed by a forward predecessor chain.
	 *
	 * @param attributeName ordered reference attribute
	 * @return whether the chain is stored on the forward reference
	 */
	public boolean usesForwardPredecessor(@Nonnull String attributeName) {
		return this.referenceSchema instanceof ReflectedReferenceSchema reflected &&
			reflected.getInheritedAttributes().contains(attributeName) &&
			reflected.getAttribute(attributeName)
				.map(attribute -> ReferencedEntityPredecessor.class.equals(attribute.getPlainType()))
				.orElse(false);
	}

	/**
	 * Validates a chain ordering against its index schemas in every applicable scope. A reflected ordering reads
	 * the forward reference in a single translated scope. A plain ordering reads the owner's scope.
	 *
	 * @param attributeName chained attribute
	 * @param scopes nonempty set of scopes the link applies to
	 * @return the validated schemas and the fixed lookup scope for a reflected reference
	 */
	@Nonnull
	public ChainLookup translateChainLookup(@Nonnull String attributeName, @Nonnull Set<Scope> scopes) {
		final EntitySchemaContract partitionEntitySchema;
		final ReferenceSchemaContract partitionReferenceSchema;
		final Scope reflectedScope;
		if (this.usesForwardPredecessor(attributeName) &&
			this.referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
			Assert.isTrue(
				scopes.size() == 1,
				() -> "Chain sort cannot be executed in multiple scopes (" +
					scopes.stream().map(Scope::name).collect(Collectors.joining(", ")) + ") simultaneously."
			);
			reflectedScope = scopes.iterator().next();
			partitionEntitySchema = this.queryContext.getSchema(this.referenceSchema.getReferencedEntityType());
			partitionReferenceSchema = partitionEntitySchema.getReferenceOrThrowException(
				reflectedReferenceSchema.getReflectedReferenceName()
			);
		} else {
			reflectedScope = null;
			partitionEntitySchema = this.getSchema();
			partitionReferenceSchema = this.referenceSchema;
		}
		for (final Scope indexScope : scopes) {
			HavingTranslatorHelper.assertEntityComponentIndexed(
				partitionEntitySchema, partitionReferenceSchema, indexScope, scopes
			);
		}
		final AttributeSchemaContract partitionAttributeSchema = partitionReferenceSchema
			.getAttribute(attributeName)
			.orElseThrow(() -> new AttributeNotFoundException(attributeName, this.referenceSchema, this.getSchema()));
		return new ChainLookup(partitionReferenceSchema, partitionAttributeSchema, reflectedScope);
	}

	/**
	 * Retrieves the chain ({@link ChainIndex} of a materialized partition, the singleton chain of a virtual one)
	 * associated with the given entity primary key, {@link ReferenceKey}, and {@link AttributeKey}. The partition is
	 * resolved by its key through the memo of the query (see {@link io.evitadb.index.PartitionKeyMemo}), so a query
	 * resolving the chains of many owners sharing one target builds the registered partitions of the target once.
	 * The last result is cached by scope and attribute, using the partition key or the reflected owner key.
	 *
	 * @param entityPrimaryKey owner primary key
	 * @param referenceKey reference key used to locate the partition
	 * @param attributeKey chained attribute
	 * @param lookupScope scope of the partition
	 * @param chainLookup schemas resolved at translation for the chain lookup
	 * @return the chain if found, or an empty optional
	 */
	@Nonnull
	public Optional<SortedRecordsSupplierFactory> getChainIndex(
		int entityPrimaryKey,
		@Nonnull RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Scope lookupScope,
		@Nonnull ChainLookup chainLookup
	) {
		final LastRetrievedChainIndexKey lastKey = this.lastRetrievedChainIndexKey;
		if (chainLookup.reflectedScope() != null) {
			final String forwardReferenceName = chainLookup.referenceSchema().getName();
			// we optimize the retrieval of the chain index by caching the last retrieved chain index
			// the references should be sorted by reference key and attribute key, so the caching should be efficient here
			if (lastKey != null && lastKey.scope() == lookupScope &&
				lastKey.referenceKey().referenceName().equals(forwardReferenceName) &&
				lastKey.referenceKey().primaryKey() == entityPrimaryKey &&
				lastKey.attributeKey().equals(attributeKey)) {
				return Optional.ofNullable(this.lastRetrievedChainIndex);
			} else {
				// else we have to retrieve the chain and cache it
				final RepresentativeReferenceKey theLookupReferenceKey = new RepresentativeReferenceKey(
					new ReferenceKey(forwardReferenceName, entityPrimaryKey),
					referenceKey.representativeAttributeValues()
				);
				// get the partition from the referenced entity collection using inverted key specification - read
				// through the memo of the query
				final Optional<SortedRecordsSupplierFactory> chainIndex = Optional.ofNullable(
					this.queryContext.resolvePartitionForReadIfExists(
						this.referenceSchema.getReferencedEntityType(), lookupScope, theLookupReferenceKey
					)
				).map(
					it -> this.getChainOfPartition(
						it, chainLookup.referenceSchema(), chainLookup.attributeSchema(), attributeKey.locale()
					)
				);
				// cache the result for future use
				this.lastRetrievedChainIndexKey = new LastRetrievedChainIndexKey(
					theLookupReferenceKey,
					attributeKey,
					lookupScope
				);
				this.lastRetrievedChainIndex = chainIndex.orElse(null);
				return chainIndex;
			}
		} else {
			// we optimize the retrieval of the chain index by caching the last retrieved chain index
			// the references should be sorted by reference key and attribute key, so the caching should be efficient here
			if (lastKey != null && lastKey.scope() == lookupScope &&
				lastKey.referenceKey().equals(referenceKey) && lastKey.attributeKey().equals(attributeKey)) {
				return Optional.ofNullable(this.lastRetrievedChainIndex);
			} else {
				// else we have to retrieve the chain and cache it
				// get the partition from this entity collection using the reference key, through the memo of the query
				final String entityType = this.queryContext.getEntityType();
				final Optional<SortedRecordsSupplierFactory> chainIndex = Optional.ofNullable(
					entityType == null ?
						this.queryContext.resolvePartitionIfExists(lookupScope, referenceKey) :
						this.queryContext.resolvePartitionForReadIfExists(entityType, lookupScope, referenceKey)
				).map(
					it -> this.getChainOfPartition(
						it, chainLookup.referenceSchema(), chainLookup.attributeSchema(), attributeKey.locale()
					)
				);
				// cache the result for future use
				this.lastRetrievedChainIndexKey = new LastRetrievedChainIndexKey(
					referenceKey, attributeKey, lookupScope
				);
				this.lastRetrievedChainIndex = chainIndex.orElse(null);
				return chainIndex;
			}
		}
	}

	/**
	 * Returns the chain of the attribute in the partition: the chain index of a materialized partition, the singleton
	 * chain read out of the registry row of a virtual one (see {@link VirtualPartitionSortedRecords#ofChainFactory}) -
	 * one `O(log n)` registry lookup. The chain of a partition is keyed by its owners.
	 *
	 * @param partition       the partition
	 * @param referenceSchema the reference of the partition's collection
	 * @param attributeSchema the chained attribute of that reference
	 * @param locale          the locale of the chain, `null` for a non-localized one
	 * @return the chain, or `null` when the partition is not chained under the attribute
	 */
	@Nullable
	private SortedRecordsSupplierFactory getChainOfPartition(
		@Nonnull ReferencePartition partition,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		return switch (partition) {
			case ReducedEntityIndex reducedEntityIndex ->
				reducedEntityIndex.getChainIndex(referenceSchema, attributeSchema, locale);
			case VirtualReferencePartition virtualPartition -> VirtualPartitionSortedRecords.ofChainFactory(
				virtualPartition,
				AttributeIndex.createAttributeKey(referenceSchema, attributeSchema, locale)
			);
		};
	}

	/**
	 * Returns the scopes the ordering being translated is checked in: the scope of the enclosing `inScope`
	 * container, or the query's scopes outside one. Attribute and index checks, the nested entity and group sorters
	 * and the admission of an `inScope` container read it. A chain ordering reads {@link #getChainScopes} instead,
	 * which outside a container adds the scopes of the owners being ordered.
	 *
	 * @return scopes of the ordering being translated
	 */
	@Nonnull
	public Set<Scope> getScopes() {
		final ScopedOrdering ownerGate = this.ownerGates.peek();
		return ownerGate == null ? this.queryContext.getScopes() : ownerGate.scopes();
	}

	/**
	 * Returns the scopes a chain ordering is translated for: the scope of the enclosing `inScope` container, or
	 * outside one the query's scopes. An ordering that reads the owner's partition also covers the owners' scopes.
	 *
	 * @param attributeName chained attribute
	 * @return scopes of the chain ordering being translated
	 */
	@Nonnull
	public Set<Scope> getChainScopes(@Nonnull String attributeName) {
		final ScopedOrdering ownerGate = this.ownerGates.peek();
		if (ownerGate != null) {
			return ownerGate.scopes();
		}
		return this.usesForwardPredecessor(attributeName) ? this.queryContext.getScopes() : this.chainScopes;
	}

	/**
	 * Translates the orderings of a reference-level `inScope` container. The links they append apply only to owners
	 * in `scopeToUse`, and their attributes and indexes are checked in it. The container is left even when the
	 * translation fails.
	 *
	 * @param scopeToUse scope required of the owner
	 * @param runnable translation of the orderings inside the container
	 */
	public void doWithScope(@Nonnull Scope scopeToUse, @Nonnull Runnable runnable) {
		this.ownerGates.push(new ScopedOrdering(scopeToUse));
		try {
			runnable.run();
		} finally {
			this.ownerGates.pop();
		}
	}

	/**
	 * Holds the ordering chain and its exact nested delegate for validation and batch preparation.
	 *
	 * @param comparator head of the reference ordering chain, or the default ordering
	 * @param nestedQueryComparator nested delegate in the chain, or `null` if absent
	 */
	public record OrderingDescriptor(
		@Nonnull ReferenceComparator comparator,
		@Nullable EntityNestedQueryComparator nestedQueryComparator
	) {
	}

	/**
	 * Identifies one enclosing reference-level `inScope`. Each container gets its own instance, so identity tells
	 * two containers of the same scope apart.
	 *
	 * @param scope scope required of the owner
	 */
	private record ScopedOrdering(@Nonnull Scope scope) {

		/**
		 * Returns the index scopes of the orderings inside the container.
		 *
		 * @return the single scope required of the owner
		 */
		@Nonnull
		Set<Scope> scopes() {
			return Set.of(this.scope);
		}
	}

	/**
	 * Describes the schema and fixed scope of a reference chain lookup after translation.
	 *
	 * @param referenceSchema reference on the collection holding the chain
	 * @param attributeSchema chained attribute on that reference
	 * @param reflectedScope fixed scope for a reflected chain, or `null` to use the owner's scope
	 */
	public record ChainLookup(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Scope reflectedScope
	) {
	}

	/**
	 * Identifies a chain index lookup within a reference partition.
	 *
	 * @param referenceKey key of the reference partition
	 * @param attributeKey key of the chained attribute, including its locale if applicable
	 * @param scope scope of the partition
	 */
	private record LastRetrievedChainIndexKey(
		@Nonnull RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Scope scope
	) {
	}
}
