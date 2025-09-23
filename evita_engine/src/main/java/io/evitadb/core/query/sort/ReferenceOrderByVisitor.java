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
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.sort.attribute.translator.AttributeNaturalTranslator;
import io.evitadb.core.query.sort.entity.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.entity.translator.EntityGroupPropertyTranslator;
import io.evitadb.core.query.sort.entity.translator.EntityPropertyTranslator;
import io.evitadb.core.query.sort.translator.OrderByTranslator;
import io.evitadb.core.query.sort.translator.OrderInScopeTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.ReducedEntityIndex;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.ofNullable;

/**
 * This {@link ConstraintVisitor} translates tree of {@link OrderConstraint} to a {@link OrderingDescriptor} record
 * allowing to access the comparator for reference attributes and for referenced entity as well. The visitor is used
 * only from {@link ReferenceFetcher} implementations.
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
	 * Provides access to the attribute schema or sortable attribute compound based on the attribute name.
	 */
	private final AttributeSchemaAccessor attributeSchemaAccessor;
	/**
	 * Pre-initialized comparator initialized during entity filtering (if it's performed) allowing to order references
	 * by sorter defined on referenced entity (requiring nested query).
	 */
	private EntityNestedQueryComparator nestedQueryComparator;
	/**
	 * Contains the created comparator from the ordering query source tree.
	 */
	private ReferenceComparator comparator;
	/**
	 * Holds the key data for retrieving the last chain index used in a query context.
	 * This key comprises a reference key and an attribute key, ensuring the exact chain index
	 * can be retrieved or identified during the query processing stage.
	 */
	private LastRetrievedChainIndexKey lastRetrievedChainIndexKey;
	/**
	 * The last retrieved chain index used in processing and determining the position of the chain in the reference order.
	 * This variable is internally updated during the traversal and comparison of constraints.
	 */
	@Nullable private ChainIndex lastRetrievedChainIndex;
	/**
	 * Contemporary stack for scopes used on each level of the ordering query tree.
	 */
	private final Deque<Set<Scope>> scope = new ArrayDeque<>(16);

	/**
	 * Extracts {@link OrderingDescriptor} from the passed `orderBy` constraint using passed `queryContext` for
	 * extraction.
	 */
	@Nonnull
	public static OrderingDescriptor getComparator(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull FetchRequirementCollector fetchRequirementCollector,
		@Nonnull OrderConstraint orderBy,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchema referenceSchema
		) {
		final ReferenceOrderByVisitor orderVisitor = new ReferenceOrderByVisitor(
			queryContext,
			fetchRequirementCollector,
			referenceSchema,
			new AttributeSchemaAccessor(
				queryContext.getCatalogSchema(),
				entitySchema,
				__ -> referenceSchema
			)
		);
		orderBy.accept(orderVisitor);
		return orderVisitor.getComparator();
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
	 * Returns the created sorter from the ordering query source tree or default {@link ReferenceComparator#DEFAULT}
	 * instance.
	 */
	@Nonnull
	public OrderingDescriptor getComparator() {
		return new OrderingDescriptor(
			ofNullable(this.comparator).orElse(ReferenceComparator.DEFAULT),
			this.nestedQueryComparator
		);
	}

	/**
	 * Method returns a nested query comparator for sorting along properties of referenced entity or group.
	 * @return nested query comparator or null if no nested query comparator was created
	 */
	@Nonnull
	public EntityNestedQueryComparator getOrCreateNestedQueryComparator() {
		if (this.nestedQueryComparator == null) {
			this.nestedQueryComparator = new EntityNestedQueryComparator();
			this.addComparator(this.nestedQueryComparator);
		}
		return this.nestedQueryComparator;
	}

	/**
	 * Method appends a comparator for comparing the attributes.
	 * @param comparator comparator to be appended
	 */
	public void addComparator(@Nonnull ReferenceComparator comparator) {
		if (this.comparator == null) {
			this.comparator = comparator;
		} else {
			this.comparator = this.comparator.andThen(comparator);
		}
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
			attributeName, this.scope.isEmpty() ? this.getScopes() : this.scope.peek()
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
			attributeName, this.getScopes(), requiredTrait
		);
	}

	/**
	 * Retrieves the {@link ChainIndex} associated with the given entity primary key, {@link ReferenceKey}, and {@link AttributeKey}.
	 * The method employs caching mechanisms to improve performance by reusing previously retrieved results when the parameters match.
	 *
	 * The chain index is retrieved from the query context based on the reference schema and entity schema. If the reference schema
	 * is a {@link ReflectedReferenceSchemaContract}, the method retrieves the chain index from the entity index based on the
	 * reference key and attribute key. If the reference schema is a {@link ReferenceSchemaContract}, the method retrieves the chain
	 * index from the entity index based on the reference key and attribute key.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the chain index is being requested. May be null.
	 * @param referenceKey the key representing the reference under which the chain index is categorized.
	 * @param attributeKey the key representing the attribute for which the chain index is requested.
	 * @return an {@link Optional} containing the {@link ChainIndex} if found, or an empty {@link Optional} if not found.
	 */
	@Nonnull
	public Optional<ChainIndex> getChainIndex(
		@Nullable Integer entityPrimaryKey,
		@Nonnull RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey
	) {
		if (this.referenceSchema instanceof ReflectedReferenceSchemaContract reflectedReferenceSchema) {
			// we optimize the retrieval of the chain index by caching the last retrieved chain index
			// the references should be sorted by reference key and attribute key, so the caching should be efficient here
			if (this.lastRetrievedChainIndexKey != null &&
				this.lastRetrievedChainIndexKey.referenceKey().referenceName().equals(reflectedReferenceSchema.getReflectedReferenceName()) &&
				Objects.equals(this.lastRetrievedChainIndexKey.referenceKey().primaryKey(), entityPrimaryKey) &&
				this.lastRetrievedChainIndexKey.attributeKey().equals(attributeKey)) {
				return Optional.ofNullable(this.lastRetrievedChainIndex);
			} else {
				// else we have to retrieve the chain and cache it
				Assert.notNull(entityPrimaryKey, "Entity primary key must not be null for reflected reference schema.");
				final RepresentativeReferenceKey theLookupReferenceKey = new RepresentativeReferenceKey(
					new ReferenceKey(
						reflectedReferenceSchema.getReflectedReferenceName(),
						entityPrimaryKey
					),
					referenceKey.representativeAttributeValues()
				);
				final Set<Scope> scopes = this.getScopes();
				Assert.isTrue(
					scopes.isEmpty() || scopes.size() == 1,
					() -> "Chain sort cannot be executed in multiple scopes (" +
						scopes.stream().map(Scope::name).collect(Collectors.joining(", ")) + ") simultaneously."
				);
				// get the index from the referenced entity collection using inverted key specification
				final Optional<ChainIndex> chainIndex = this.queryContext.getEntityIndex(
					this.referenceSchema.getReferencedEntityType(),
					new EntityIndexKey(
						EntityIndexType.REFERENCED_ENTITY,
						scopes.isEmpty() ? Scope.DEFAULT_SCOPE : scopes.iterator().next(),
						// this would fail if the entity primary key is null, but it should never happen, and if so, exception is thrown
						theLookupReferenceKey
					),
					EntityIndex.class
				).map(it -> it.getChainIndex(attributeKey));
				// cache the result for future use
				this.lastRetrievedChainIndexKey = new LastRetrievedChainIndexKey(
					theLookupReferenceKey,
					attributeKey
				);
				this.lastRetrievedChainIndex = chainIndex.orElse(null);
				return chainIndex;
			}
		} else {
			// we optimize the retrieval of the chain index by caching the last retrieved chain index
			// the references should be sorted by reference key and attribute key, so the caching should be efficient here
			if (this.lastRetrievedChainIndexKey != null &&
				this.lastRetrievedChainIndexKey.referenceKey().equals(referenceKey) &&
				this.lastRetrievedChainIndexKey.attributeKey().equals(attributeKey)) {
				return Optional.ofNullable(this.lastRetrievedChainIndex);
			} else {
				final Set<Scope> scopes = this.getScopes();
				Assert.isTrue(
					scopes.isEmpty() || scopes.size() == 1,
					() -> "Chain sort cannot be executed in multiple scopes (" +
						scopes.stream().map(Scope::name).collect(Collectors.joining(", ")) + ") simultaneously."
				);
				// else we have to retrieve the chain and cache it
				// get the index from this entity collection using the reference key
				final Optional<ChainIndex> chainIndex = this.queryContext.getIndexIfExists(
					new EntityIndexKey(
						EntityIndexType.REFERENCED_ENTITY,
						scopes.isEmpty() ? Scope.DEFAULT_SCOPE : scopes.iterator().next(),
						referenceKey
					),
					ReducedEntityIndex.class
				).map(it -> it.getChainIndex(attributeKey));
				// cache the result for future use
				this.lastRetrievedChainIndexKey = new LastRetrievedChainIndexKey(referenceKey, attributeKey);
				this.lastRetrievedChainIndex = chainIndex.orElse(null);
				return chainIndex;
			}
		}
	}

	/**
	 * Retrieves the current set of scopes being applied in the query context.
	 * If the scope stack is empty, this method delegates to the query context to retrieve the scopes.
	 * Otherwise, it returns the top scope from the stack.
	 *
	 * @return a set of {@link Scope} objects representing the current query context scopes
	 */
	@Nonnull
	public Set<Scope> getScopes() {
		return this.scope.isEmpty() ? this.queryContext.getScopes() : this.scope.peek();
	}

	/**
	 * Executes the given {@link Runnable} within the context of the specified {@link Scope scopes}.
	 * The method temporarily pushes the provided scopes to the current scope stack,
	 * executes the runnable, and ensures the stack is restored to its previous state
	 * afterward, even if the runnable throws an exception.
	 *
	 * @param scopesToUse the set of scopes to apply during the execution of the runnable
	 * @param runnable the code to be executed within the given scopes
	 */
	public void doWithScope(@Nonnull Set<Scope> scopesToUse, @Nonnull Runnable runnable) {
		try {
			this.scope.push(scopesToUse);
			runnable.run();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * DTO record enveloping comparators both for attributes on reference itself and the referenced entity.
	 * Currently, the sorting allows to use only simple ordering constraints either on reference attributes or
	 * the referenced entity itself. It doesn't allow to combine them or create more complex orderings.
	 * This is the work in progress ...
	 */
	public record OrderingDescriptor(
		@Nonnull ReferenceComparator comparator,
		@Nullable EntityNestedQueryComparator nestedQueryComparator
	) {
	}

	/**
	 * The LastRetrievedChainIndexKey record represents a composite key used to uniquely identify a chain index
	 * based on a combination of a reference key and an attribute key.
	 *
	 * @param referenceKey The reference key represents a unique identifier of a reference within the schema.
	 * @param attributeKey The attribute key represents a unique identifier of an attribute, which may also be locale-specific.
	 */
	private record LastRetrievedChainIndexKey(
		@Nonnull RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey
	) {}

}
