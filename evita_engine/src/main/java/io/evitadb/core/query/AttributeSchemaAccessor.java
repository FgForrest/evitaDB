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

package io.evitadb.core.query;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot.ElementKind;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * Attribute schema accessor provides access to the {@link AttributeSchemaContract} by the attribute name taking
 * the current context into account. The accessor needs to accept also {@link EntitySchemaContract} provided from
 * outside which is used for localization of attributes in "prefetched" entities of different types (i.e. when
 * {@link io.evitadb.api.query.head.Collection} constraint is not specified in the query).
 *
 * # It is also where a query says which schema capabilities it needs
 *
 * Every filter and every ordering reaches its attribute or sortable compound through one of the four getters below,
 * and each of them states in {@link AttributeTrait} *why* it wants the schema - which is exactly the
 * `(element, capability)` pair the usage counters are keyed by. That makes this class the one place the whole query
 * side has to be instrumented, instead of the several dozen translators that call it, and the reason the recording
 * lives here rather than at a translator: a translator runs **once per candidate index set**, so a count taken there
 * would measure how many alternatives the planner considered.
 *
 * The recording is a no-op unless the accessor was built with a {@link QueryPlanningContext}, and that context decides
 * what it is willing to attribute - see {@link QueryPlanningContext#recordRequestedCapability}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AttributeSchemaAccessor {
	/**
	 * The trait array the two sortable-compound lookups verify against - shared rather than allocated per call,
	 * because it is a constant and those lookups sit on the ordering path of every query that orders by anything.
	 */
	private static final AttributeTrait[] SORTABLE_ONLY = {AttributeTrait.SORTABLE};
	/**
	 * Mandatory catalog schema where the {@link GlobalAttributeSchemaContract} are stored.
	 */
	@Nonnull private final CatalogSchemaContract catalogSchema;
	/**
	 * Optional {@link EntitySchemaContract} which might be null when {@link io.evitadb.api.query.head.Collection}
	 * constraint is not specified in the query
	 */
	@Nullable private final EntitySchemaContract entitySchema;
	/**
	 * Lambda that allows finding appropriate {@link ReferenceSchemaContract} from provided {@link EntitySchemaContract}.
	 * We need to use lambda because the entity schema may be different each time attribute schema is being looked up
	 * for.
	 */
	@Nullable private final Function<EntitySchemaContract, ReferenceSchemaContract> referenceSchemaAccessor;
	/**
	 * Context of the query being planned, which collects the capabilities this accessor hands out - NULL for an
	 * accessor built outside a query plan, and for the accessors serving another collection's structures, whose
	 * requests belong to a registry this one cannot reach. Such an accessor works exactly as before and records
	 * nothing.
	 */
	@Nullable private final QueryPlanningContext queryContext;

	/**
	 * Verifies that the provided attribute schema meets the required traits and returns it.
	 * Throws specific exceptions if the criteria are not met.
	 *
	 * @param attributeName   the name of the attribute to verify
	 * @param requestedScopes the scopes requested in the input query
	 * @param attributeSchema the attribute schema to verify
	 * @param catalogSchema   the catalog schema used for attribute lookup
	 * @param entitySchema    the optional entity schema used for additional context
	 * @param referenceSchema the optional reference schema used for additional context
	 * @param requiredTrait   the required traits that the attribute must satisfy
	 * @return the verified attribute schema
	 * @throws AttributeNotFoundException       when the attribute is not found
	 * @throws AttributeNotFilterableException  when filterable traits are requested but the attribute does not support them
	 * @throws AttributeNotSortableException    when sortable traits are requested but the attribute does not support them
	 * @throws ReferenceNotIndexedException     when a reference schema is provided but is not indexed in the requested scopes
	 */
	@Nonnull
	public static AttributeSchemaContract verifyAndReturn(
		@Nonnull String attributeName,
		@Nonnull Set<Scope> requestedScopes,
		@Nullable AttributeSchemaContract attributeSchema,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeTrait[] requiredTrait
	) {
		notNull(
			attributeSchema,
			() -> ofNullable(entitySchema)
				.map(it -> referenceSchema == null ? new AttributeNotFoundException(attributeName, it) : new AttributeNotFoundException(attributeName, referenceSchema, it))
				.orElseGet(() -> new AttributeNotFoundException(attributeName, catalogSchema))
		);
		EvitaInvalidUsageException exception = null;
		for (AttributeTrait attributeTrait : requiredTrait) {
			if (referenceSchema != null) {
				for (Scope scope : requestedScopes) {
					if (!referenceSchema.isIndexedInScope(scope)) {
						throw new ReferenceNotIndexedException(referenceSchema.getName(), Objects.requireNonNull(entitySchema), scope);
					}
				}
			}
			switch (attributeTrait) {
				case UNIQUE ->
					exception = requestedScopes.stream().allMatch(attributeSchema::isUniqueInScope) ?
						null :
						ofNullable(referenceSchema)
							.map(it -> new AttributeNotFilterableException(attributeName, it, Objects.requireNonNull(entitySchema)))
							.orElseGet(
								() -> ofNullable(entitySchema)
									.map(it -> new AttributeNotFilterableException(attributeName, it))
									.orElseGet(() -> new AttributeNotFilterableException(attributeName, catalogSchema))
							);
				case FILTERABLE ->
					exception = requestedScopes.stream().allMatch(attributeSchema::isFilterableInScope) || requestedScopes.stream().allMatch(attributeSchema::isUniqueInScope) ?
						null :
						ofNullable(referenceSchema)
							.map(it -> new AttributeNotFilterableException(attributeName, it, Objects.requireNonNull(entitySchema)))
							.orElseGet(
								() -> ofNullable(entitySchema)
									.map(it -> new AttributeNotFilterableException(attributeName, it))
									.orElseGet(() -> new AttributeNotFilterableException(attributeName, catalogSchema))
							);
				case SORTABLE ->
					exception = requestedScopes.stream().allMatch(attributeSchema::isSortableInScope) ?
						null :
						ofNullable(referenceSchema)
							.map(it -> new AttributeNotSortableException(attributeName, it, Objects.requireNonNull(entitySchema)))
							.orElseGet(
								() -> ofNullable(entitySchema)
									.map(it -> new AttributeNotSortableException(attributeName, it))
									.orElseGet(() -> new AttributeNotSortableException(attributeName, catalogSchema))
							);
			}
		}
		if (exception != null) {
			throw exception;
		}
		return attributeSchema;
	}

	/**
	 * Creates an accessor that records nothing - for looking a schema up outside a query plan, or for looking one up
	 * on behalf of a collection whose usage registry the enclosing plan does not own.
	 *
	 * @param catalogSchema the catalog schema holding the globally defined attributes
	 * @param entitySchema  the entity schema to resolve attributes against, NULL for a collection-less lookup
	 */
	public AttributeSchemaAccessor(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		this(catalogSchema, entitySchema, null, null);
	}

	/**
	 * Creates an accessor resolving attributes on one reference of the entity, recording nothing - see
	 * {@link #AttributeSchemaAccessor(CatalogSchemaContract, EntitySchemaContract)}.
	 *
	 * @param catalogSchema           the catalog schema holding the globally defined attributes
	 * @param entitySchema            the entity schema to resolve attributes against
	 * @param referenceSchemaAccessor lambda picking the reference the attributes are looked up on
	 */
	public AttributeSchemaAccessor(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable Function<EntitySchemaContract, ReferenceSchemaContract> referenceSchemaAccessor
	) {
		this(catalogSchema, entitySchema, referenceSchemaAccessor, null);
	}

	/**
	 * The complete constructor, and the only one that makes an accessor report what it hands out.
	 *
	 * @param catalogSchema           the catalog schema holding the globally defined attributes
	 * @param entitySchema            the entity schema to resolve attributes against, NULL for a collection-less lookup
	 * @param referenceSchemaAccessor lambda picking the reference the attributes are looked up on, NULL when they are
	 *                                the entity's own
	 * @param queryContext            the query being planned, which collects the requested capabilities - NULL turns
	 *                                the recording off entirely
	 */
	public AttributeSchemaAccessor(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable Function<EntitySchemaContract, ReferenceSchemaContract> referenceSchemaAccessor,
		@Nullable QueryPlanningContext queryContext
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.referenceSchemaAccessor = referenceSchemaAccessor;
		this.queryContext = queryContext;
	}

	/**
	 * Creates the accessor a query plans with - it resolves against the queried collection's schema and reports every
	 * capability it hands out back to the context.
	 *
	 * @param queryContext the query being planned
	 */
	public AttributeSchemaAccessor(@Nonnull QueryPlanningContext queryContext) {
		this(
			queryContext.getCatalogSchema(),
			queryContext.isEntityTypeKnown() ? queryContext.getSchema() : null,
			null,
			queryContext
		);
	}

	/**
	 * Returns {@link AttributeSchemaContract} of particular `attributeName` or throws exception.
	 * This method looks for the attributes in internal {@link #entitySchema} and doesn't allow provisioning of
	 * the schema from outside.
	 *
	 * @param attributeName name of the looked up attribute
	 * @param requestedScopes set of scopes that are requested in the input query
	 * @param requiredTrait set of required attribute traits to check before returning
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotFilterableException when filterable traits are requested but the attribute does not
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull AttributeTrait... requiredTrait
	) {
		if (this.entitySchema == null && this.referenceSchemaAccessor == null) {
			// a lookup that knows no collection resolves against the catalog schema, and its counters live with it
			final AttributeSchemaContract result = verifyAndReturn(
				attributeName, requestedScopes, this.catalogSchema.getAttribute(attributeName).orElse(null),
				this.catalogSchema, null, null, requiredTrait
			);
			recordRequestedTraits(
				this.queryContext, null, null, result.getName(), requestedScopes, requiredTrait
			);
			return result;
		} else {
			final ReferenceSchemaContract referenceSchema = getReferenceSchema();
			final AttributeSchemaProvider<?> attributeSchemaProvider = Objects.requireNonNull(referenceSchema == null ? this.entitySchema : referenceSchema);
			final AttributeSchemaContract result = verifyAndReturn(
				attributeName, requestedScopes, attributeSchemaProvider.getAttribute(attributeName).orElse(null),
				this.catalogSchema, this.entitySchema,
				referenceSchema,
				requiredTrait
			);
			recordRequestedTraits(this.entitySchema, referenceSchema, result.getName(), requestedScopes, requiredTrait);
			return result;
		}
	}

	/**
	 * Returns {@link AttributeSchemaContract} of particular `attributeName` or throws exception.
	 *
	 * @param entitySchema  the entity schema that should be used for attribute lookup
	 * @param attributeName name of the looked up attribute
	 * @param requestedScopes set of scopes that are requested in the input query
	 * @param requiredTrait set of required attribute traits to check before returning
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotFilterableException when filterable traits are requested but the attribute does not
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull AttributeTrait... requiredTrait
	) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchema();
		final AttributeSchemaContract attributeSchema;
		final Optional<GlobalAttributeSchemaContract> globalAttributeSchema = this.catalogSchema.getAttribute(attributeName);
		if (globalAttributeSchema.isPresent()) {
			attributeSchema = globalAttributeSchema.get();
		} else {
			attributeSchema = Objects.requireNonNullElse(referenceSchema, entitySchema)
				.getAttribute(attributeName)
				.orElse(null);
		}
		final AttributeSchemaContract result = verifyAndReturn(
			attributeName, requestedScopes, attributeSchema, this.catalogSchema, entitySchema, referenceSchema, requiredTrait
		);
		recordRequestedTraits(entitySchema, referenceSchema, result.getName(), requestedScopes, requiredTrait);
		return result;
	}

	/**
	 * Returns {@link AttributeSchemaContract} or {@link SortableAttributeCompoundSchemaContract} of particular
	 * `attributeName` or throws exception. This method looks for the attributes in internal {@link #entitySchema} and
	 * doesn't allow provisioning of the schema from outside.
	 *
	 * @param attributeName name of the looked up attribute
	 * @param requestedScopes set of scopes that are requested in the input query
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public NamedSchemaContract getAttributeSchemaOrSortableAttributeCompound(
		@Nonnull String attributeName,
		@Nonnull Set<Scope> requestedScopes
	) {
		if (this.entitySchema != null) {
			return getAttributeSchemaOrSortableAttributeCompound(this.entitySchema, attributeName, requestedScopes);
		} else {
			// a lookup that knows no collection resolves against the catalog schema, and its counters live with it
			final AttributeSchemaContract result = verifyAndReturn(
				attributeName, requestedScopes, this.catalogSchema.getAttribute(attributeName).orElse(null),
				this.catalogSchema, null, null, SORTABLE_ONLY
			);
			recordRequestedTraits(
				this.queryContext, null, null, result.getName(), requestedScopes, SORTABLE_ONLY
			);
			return result;
		}
	}

	/**
	 * Returns {@link AttributeSchemaContract} or {@link SortableAttributeCompoundSchemaContract} of particular
	 * `attributeName` or throws exception.
	 *
	 * @param entitySchema  the entity schema that should be used for attribute lookup
	 * @param attributeName name of the looked up attribute
	 * @param requestedScopes set of scopes that are requested in the input query
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public NamedSchemaContract getAttributeSchemaOrSortableAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nonnull Set<Scope> requestedScopes
	) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchema();
		final SortableAttributeCompoundSchemaContract compoundSchema;
		compoundSchema = Objects.requireNonNullElse(referenceSchema, entitySchema)
			.getSortableAttributeCompound(attributeName)
			.orElse(null);

		if (compoundSchema != null) {
			recordRequestedCompound(entitySchema, referenceSchema, compoundSchema, requestedScopes);
			return compoundSchema;
		}

		final AttributeSchemaContract resultSchema;
		final Optional<GlobalAttributeSchemaContract> globalAttributeSchema = this.catalogSchema.getAttribute(attributeName);
		if (globalAttributeSchema.isPresent()) {
			resultSchema = globalAttributeSchema.get();
		} else {
			resultSchema = Objects.requireNonNullElse(referenceSchema, entitySchema)
				.getAttribute(attributeName)
				.orElse(null);
		}
		final AttributeSchemaContract result = verifyAndReturn(
			attributeName, requestedScopes, resultSchema, this.catalogSchema, entitySchema, referenceSchema,
			SORTABLE_ONLY
		);
		recordRequestedTraits(entitySchema, referenceSchema, result.getName(), requestedScopes, SORTABLE_ONLY);
		return result;
	}

	/**
	 * Method creates new instance of the accessor with initialized lambda for retrieving {@link ReferenceSchemaContract}
	 * from {@link EntitySchemaContract} based on the `referenceName`.
	 *
	 * The derived accessor keeps reporting to the same query context, which is what makes a capability of a reference
	 * attribute countable at all - every one of them is reached through here.
	 */
	@Nonnull
	public AttributeSchemaAccessor withReferenceSchemaAccessor(@Nonnull String referenceName) {
		return new AttributeSchemaAccessor(
			this.catalogSchema, this.entitySchema,
			entitySchema -> entitySchema.getReferenceOrThrowException(referenceName),
			this.queryContext
		);
	}

	/**
	 * Reports every capability just handed to a translator, one per requested trait and per requested scope.
	 *
	 * The traits are the caller's own statement of why it wanted the schema, and {@link #verifyAndReturn} has already
	 * refused the lookup unless the attribute carries each of them **in every requested scope** - so by the time this
	 * runs, every `(trait, scope)` pair really is a capability the schema declares and the query needs. A lookup that
	 * passed no trait at all wanted the schema for something else (a type, a locale flag) and is not a capability
	 * request; it records nothing.
	 *
	 * # Which registry counts it
	 *
	 * `owner` decides, and it says nothing more than *what the lookup resolved against*: an attribute found on an
	 * entity schema counts on that collection, an attribute found on the catalog schema alone counts on the catalog.
	 * The second case is not a lesser one - a global attribute's flags are declared on the catalog schema and dropped
	 * by a catalog schema mutation, so the catalog is where the number an operator would act on belongs.
	 *
	 * @param queryContext    the query being planned, NULL outside a plan - which turns the whole recording off
	 * @param owner           the entity schema the attribute was resolved against, NULL when it was resolved against
	 *                        the catalog schema alone
	 * @param containerName   name of the reference declaring it, NULL when the entity (or the catalog) declares it
	 *                        directly
	 * @param attributeName   canonical name of the attribute, as its schema spells it
	 * @param requestedScopes scopes the query asked for
	 * @param requiredTraits  what the caller needed the attribute to be able to do
	 */
	public static void recordRequestedTraits(
		@Nullable QueryPlanningContext queryContext,
		@Nullable EntitySchemaContract owner,
		@Nullable String containerName,
		@Nonnull String attributeName,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull AttributeTrait[] requiredTraits
	) {
		if (queryContext == null || requiredTraits.length == 0) {
			return;
		}
		if (owner == null && containerName != null) {
			// a catalog declares no references, so such a pair describes an element no schema can correspond to -
			// recording it would mint an entry the next catalog schema adoption throws away, loudly
			throw new GenericEvitaInternalError(
				"Attribute `" + attributeName + "` of reference `" + containerName +
					"` cannot be resolved against a catalog schema."
			);
		}
		for (final AttributeTrait trait : requiredTraits) {
			// no `default` branch on purpose: a trait added later must fail to compile here rather than go uncounted
			final Capability capability = switch (trait) {
				case FILTERABLE -> Capability.FILTER;
				case UNIQUE -> Capability.UNIQUE;
				case SORTABLE -> Capability.SORT;
			};
			for (final Scope scope : requestedScopes) {
				if (owner == null) {
					queryContext.recordRequestedGlobalCapability(attributeName, capability, scope);
				} else {
					queryContext.recordRequestedCapability(
						owner, containerName, ElementKind.ATTRIBUTE, attributeName, capability, scope
					);
				}
			}
		}
	}

	/**
	 * Reports every capability an entity-schema lookup handed out - the instance form of
	 * {@link #recordRequestedTraits(QueryPlanningContext, EntitySchemaContract, String, String, Set, AttributeTrait[])}
	 * for the getters that resolved the attribute against a collection.
	 *
	 * @param owner           the entity schema the attribute was resolved against, NULL when the getter had none - in
	 *                        which case nothing is recorded, because such a getter has already resolved against the
	 *                        catalog schema on a branch that reports for itself
	 * @param referenceSchema the reference declaring it, NULL when the entity declares it directly
	 * @param attributeName   canonical name of the attribute, as its schema spells it
	 * @param requestedScopes scopes the query asked for
	 * @param requiredTraits  what the caller needed the attribute to be able to do
	 */
	private void recordRequestedTraits(
		@Nullable EntitySchemaContract owner,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull String attributeName,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull AttributeTrait[] requiredTraits
	) {
		if (owner == null) {
			return;
		}
		recordRequestedTraits(
			this.queryContext, owner, referenceSchema == null ? null : referenceSchema.getName(),
			attributeName, requestedScopes, requiredTraits
		);
	}

	/**
	 * Reports a sortable attribute compound just handed to the ordering translator.
	 *
	 * Unlike an attribute, a compound is returned without being verified, so the scope check has to happen here: an
	 * entry is recorded only for a scope the compound is actually indexed in, which is the same condition the registry
	 * prunes by. Recording it for the others would mint entries the next schema adoption throws away.
	 *
	 * @param owner           the entity schema the compound was resolved against
	 * @param referenceSchema the reference declaring it, NULL when the entity declares it directly
	 * @param compoundSchema  the compound that was handed out
	 * @param requestedScopes scopes the query asked for
	 */
	private void recordRequestedCompound(
		@Nonnull EntitySchemaContract owner,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Set<Scope> requestedScopes
	) {
		if (this.queryContext == null) {
			return;
		}
		final String containerName = referenceSchema == null ? null : referenceSchema.getName();
		for (final Scope scope : requestedScopes) {
			if (compoundSchema.isIndexedInScope(scope)) {
				this.queryContext.recordRequestedCapability(
					owner, containerName, ElementKind.SORTABLE_COMPOUND, compoundSchema.getName(),
					Capability.SORT, scope
				);
			}
		}
	}

	/**
	 * Retrieves the {@link ReferenceSchemaContract} associated with the current entity schema
	 * using the configured reference schema accessor. If no accessor is defined, or if the
	 * accessor does not produce a schema, this method returns null.
	 *
	 * @return the {@link ReferenceSchemaContract} for the current entity schema, or null if
	 *         no reference schema accessor is defined or if it does not resolve a schema.
	 */
	@Nullable
	public ReferenceSchemaContract getReferenceSchema() {
		return this.referenceSchemaAccessor == null ? null : this.referenceSchemaAccessor.apply(this.entitySchema);
	}

	/**
	 * Set of traits that the {@link AttributeSchemaContract} needs to fulfill in order the schema can be accepted for
	 * the caller. This mechanism allows centralizing all necessary exception handling in this class.
	 */
	public enum AttributeTrait {
		FILTERABLE, UNIQUE, SORTABLE;

	}
}
