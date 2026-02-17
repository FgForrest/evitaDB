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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainerWithSuffix;
import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.dataType.SupportedClass;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.api.query.require.EntityFetchRequire.combineRequirements;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * The `referenceContent` requirement fetches one or more named reference groups from the entity, returning the primary
 * keys of the referenced entities and, optionally, their full bodies. It must be placed inside {@link EntityFetch}.
 * Without it, references are not included in the result.
 *
 * evitaDB references model associations between entities (e.g., product → brand, product → categories, product →
 * parameter values). A reference record stores the target entity's primary key, an optional reference group,
 * and optional reference-level attributes (metadata about the relationship itself, such as "sortOrder" or
 * "isPrimary").
 *
 * ## Reference attribute variant
 *
 * The basic `referenceContent("name")` form returns only the reference primary keys (and any inline {@link EntityFetch}
 * / {@link EntityGroupFetch} bodies) but does **not** include the attributes stored on the reference record.
 * To retrieve reference attributes as well, use `referenceContentWithAttributes("name", attributeContent(...))` or
 * `referenceContentAllWithAttributes()`.
 *
 * ## Managed references behaviour
 *
 * By default ({@link ManagedReferencesBehaviour#ANY}), all references are returned regardless of whether the target
 * entity actually exists in the database. Set {@link ManagedReferencesBehaviour#EXISTING} to silently suppress
 * references pointing to non-existent entities — useful when referential integrity is not strictly enforced.
 *
 * Example — returning brand reference only if the brand entity exists:
 *
 * ```
 * entityFetch(
 *     referenceContent(EXISTING, "brand"),
 *     referenceContent(ANY, "categories")
 * )
 * ```
 *
 * ## Fetching referenced entity bodies
 *
 * Nested {@link EntityFetch} and {@link EntityGroupFetch} constraints inside `referenceContent` instruct the engine
 * to load the full bodies of the referenced entities and their group entities respectively:
 *
 * ```
 * referenceContent(
 *     "parameterValues",
 *     entityFetch(
 *         attributeContent("code")
 *     ),
 *     entityGroupFetch(
 *         attributeContent("code")
 *     )
 * )
 * ```
 *
 * ## Filtering references
 *
 * A nested `filterBy` constraint can reduce the returned reference set. Filter constraints inside `referenceContent`
 * implicitly target attributes on the reference record itself. To filter on the referenced entity's attributes, wrap
 * them in an {@link EntityHaving} container:
 *
 * ```
 * referenceContent(
 *     "parameterValues",
 *     filterBy(
 *         entityHaving(
 *             referenceHaving(
 *                 "parameter",
 *                 entityHaving(
 *                     attributeEquals("isVisibleInDetail", true)
 *                 )
 *             )
 *         )
 *     ),
 *     entityFetch(attributeContent("code")),
 *     entityGroupFetch(attributeContent("code", "isVisibleInDetail"))
 * )
 * ```
 *
 * ## Ordering references
 *
 * References are ordered by referenced entity primary key by default. A nested `orderBy` constraint can change this.
 * Like the filter, ordering implicitly operates on reference attributes; use `entityProperty` to sort by referenced
 * entity attributes:
 *
 * ```
 * referenceContent(
 *     "parameterValues",
 *     orderBy(
 *         entityProperty(
 *             attributeNatural("name", ASC)
 *         )
 *     ),
 *     entityFetch(attributeContent("name"))
 * )
 * ```
 *
 * ## Paginating large reference sets
 *
 * For entities with very large reference sets, a nested {@link Page} or {@link Strip} chunking constraint can
 * limit how many references are returned in a single request.
 *
 * ## Wildcard form
 *
 * `referenceContentAll()` returns all references of all types without specifying individual reference names. Use
 * `referenceContentAllWithAttributes()` to also include all reference-level attributes.
 *
 * ## Aliased instances
 *
 * The same reference type can appear multiple times in a single `entityFetch` under different logical names (aliases),
 * allowing different filtering/ordering configurations to be applied to the same reference type simultaneously.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching entity references and optionally their referenced entity bodies into the returned entities.",
	userDocsLink = "/documentation/query/requirements/fetching#reference-content",
	supportedIn = ConstraintDomain.ENTITY
)
public class ReferenceContent extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, ReferenceConstraint<RequireConstraint>,
	SeparateEntityContentRequireContainer, EntityContentRequire, ConstraintContainerWithSuffix {
	public static final ReferenceContent ALL_REFERENCES = new ReferenceContent(AttributeContent.ALL_ATTRIBUTES);
	@Serial private static final long serialVersionUID = 3374240925555151814L;
	private static final String SUFFIX_ALL = "all";
	private static final String SUFFIX_WITH_ATTRIBUTES = "withAttributes";
	private static final String SUFFIX_ALL_WITH_ATTRIBUTES = "allWithAttributes";

	/**
	 * Internal constructor used in GraphQL API to define multiple reference content definitions and for cloning purposes.
	 *
	 * @see <a href="https://github.com/FgForrest/evitaDB/issues/902">Issue #902</a>
	 */
	public ReferenceContent(
		@Nullable String name,
		@Nonnull ManagedReferencesBehaviour managedReferences,
		@Nonnull String[] referenceName,
		@Nonnull RequireConstraint[] requirements,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		super(
			ArrayUtils.mergeArrays(
				name == null ?
					new Serializable[]{managedReferences} :
					new Serializable[]{new ReferenceContentName(name), managedReferences},
				referenceName
			),
			requirements,
			additionalChildren
		);
	}

	@Creator(suffix = SUFFIX_ALL)
	public ReferenceContent() {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY}
		);
	}

	@Creator(suffix = SUFFIX_ALL_WITH_ATTRIBUTES)
	public ReferenceContent(@Nullable AttributeContent attributeContent) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			attributeContent == null ? AttributeContent.ALL_ATTRIBUTES : attributeContent
		);
	}

	public ReferenceContent(@Nonnull String... referenceName) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ManagedReferencesBehaviour.ANY},
				referenceName
			)
		);
	}

	public ReferenceContent(@Nonnull String referenceName, @Nullable AttributeContent attributeContent) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY, referenceName},
			attributeContent
		);
	}

	public ReferenceContent(
		@Nonnull String[] referenceNames,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ManagedReferencesBehaviour.ANY},
				referenceNames
			),
			entityRequirement,
			groupEntityRequirement
		);
	}

	@Creator
	public ReferenceContent(
		@Nonnull @Classifier String referenceName,
		@Nullable @AdditionalChild(domain = ConstraintDomain.INLINE_REFERENCE) FilterBy filterBy,
		@Nullable @AdditionalChild(domain = ConstraintDomain.INLINE_REFERENCE) OrderBy orderBy,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable @Child(uniqueChildren = true) ChunkingRequireConstraint chunking
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ManagedReferencesBehaviour.ANY},
				ofNullable(referenceName).map(it -> new Serializable[]{it}).orElse(NO_ARGS)
			),
			new RequireConstraint[]{entityFetch, entityGroupFetch, chunking},
			filterBy,
			orderBy
		);
	}

	@Creator(suffix = SUFFIX_WITH_ATTRIBUTES)
	public ReferenceContent(
		@Nonnull @Classifier String referenceName,
		@Nullable @AdditionalChild(domain = ConstraintDomain.INLINE_REFERENCE) FilterBy filterBy,
		@Nullable @AdditionalChild(domain = ConstraintDomain.INLINE_REFERENCE) OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ManagedReferencesBehaviour.ANY},
				ofNullable(referenceName).map(it -> new Serializable[]{it}).orElse(NO_ARGS)
			),
			new RequireConstraint[]{
				attributeContent,
				entityFetch,
				entityGroupFetch,
				chunking
			},
			filterBy,
			orderBy
		);
	}

	public ReferenceContent(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			entityRequirement,
			groupEntityRequirement
		);
	}

	public ReferenceContent(
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			attributeContent,
			entityRequirement,
			groupEntityRequirement
		);
	}

	public ReferenceContent(@Nullable ManagedReferencesBehaviour managedReferences) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)}
		);
	}

	public ReferenceContent(@Nullable ManagedReferencesBehaviour managedReferences, @Nonnull String... referenceName) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				referenceName
			)
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY), referenceName},
			attributeContent
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable AttributeContent attributeContent
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			attributeContent
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nonnull String[] referenceNames,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				referenceNames
			),
			entityRequirement,
			groupEntityRequirement
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			entityRequirement, groupEntityRequirement
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			ofNullable(attributeContent).orElse(new AttributeContent()),
			entityRequirement,
			groupEntityRequirement
		);
	}

	public ReferenceContent(@Nullable ChunkingRequireConstraint chunking) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			chunking
		);
	}

	public ReferenceContent(
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY, referenceName},
			attributeContent,
			chunking
		);
	}

	public ReferenceContent(
		@Nonnull String[] referenceNames,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ManagedReferencesBehaviour.ANY},
				referenceNames
			),
			entityRequirement,
			groupEntityRequirement,
			chunking
		);
	}

	public ReferenceContent(
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			entityRequirement,
			groupEntityRequirement,
			chunking
		);
	}

	public ReferenceContent(
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			attributeContent,
			entityRequirement,
			groupEntityRequirement,
			chunking
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			chunking
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable ChunkingRequireConstraint chunking,
		@Nonnull String... referenceName
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				referenceName
			),
			chunking
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY), referenceName},
			attributeContent,
			chunking
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			attributeContent,
			chunking
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nonnull String[] referenceNames,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				referenceNames
			),
			entityRequirement,
			groupEntityRequirement,
			chunking
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				new String[]{referenceName}
			),
			new RequireConstraint[]{entityFetch, entityGroupFetch, chunking},
			filterBy,
			orderBy
		);
	}

	/**
	 * This constructor causes ambiguity with the constructor that takes {@link AttributeContent} as the first parameter
	 * and is there only for backward compatibility and will be removed.
	 *
	 * @deprecated will be removed in the future
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				new String[]{referenceName}
			),
			new RequireConstraint[]{
				attributeContent,
				entityFetch,
				entityGroupFetch,
				null
			},
			filterBy,
			orderBy
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				new String[]{referenceName}
			),
			new RequireConstraint[]{
				attributeContent,
				entityFetch,
				entityGroupFetch,
				chunking
			},
			filterBy,
			orderBy
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			entityRequirement,
			groupEntityRequirement,
			chunking
		);
	}

	public ReferenceContent(
		@Nullable ManagedReferencesBehaviour managedReferences,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunking
	) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			attributeContent,
			entityRequirement,
			groupEntityRequirement,
			chunking
		);
	}

	/**
	 * Returns name of the reference instance (alias) if specified. This name allows multiple occurrences of the same
	 * reference content definition with different names (aliases) within single {@link EntityFetch} container.
	 *
	 * @return reference instance name or null
	 */
	@Nullable
	public String getInstanceName() {
		return Arrays.stream(getArguments())
			.filter(ReferenceContentName.class::isInstance)
			.map(ReferenceContentName.class::cast)
			.map(ReferenceContentName::name)
			.findFirst()
			.orElse(null);
	}

	/**
	 * Returns name of reference which should be loaded along with entity.
	 * Note: this can be used only if there is single reference name.
	 * Otherwise {@link #getReferenceNames()} should be used.
	 */
	@Nonnull
	public String getReferenceName() {
		final String[] referenceNames = getReferenceNames();
		Assert.isTrue(
			referenceNames.length == 1,
			"There are multiple reference names, cannot return single name."
		);
		return referenceNames[0];
	}

	/**
	 * Returns names of references which should be loaded along with entity.
	 */
	@Nonnull
	public String[] getReferenceNames() {
		return Arrays.stream(getArguments())
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	/**
	 * Returns the behaviour of the references targeting managed entities.
	 * Default is {@link ManagedReferencesBehaviour#ANY} which doesn't filter out references to non-existing entities.
	 */
	@Nonnull
	public ManagedReferencesBehaviour getManagedReferencesBehaviour() {
		return Arrays.stream(getArguments())
			.filter(ManagedReferencesBehaviour.class::isInstance)
			.map(ManagedReferencesBehaviour.class::cast)
			.findFirst()
			.orElse(ManagedReferencesBehaviour.ANY);
	}

	/**
	 * Returns attribute content requirement for reference attributes.
	 */
	@Nonnull
	public Optional<AttributeContent> getAttributeContent() {
		return Arrays.stream(getChildren())
			.filter(it -> AttributeContent.class.isAssignableFrom(it.getClass()))
			.map(AttributeContent.class::cast)
			.findFirst();
	}

	/**
	 * Returns requirements for entities.
	 */
	@AliasForParameter("entityFetch")
	@Nonnull
	public Optional<EntityFetch> getEntityRequirement() {
		return Arrays.stream(getChildren())
			.filter(it -> EntityFetch.class.isAssignableFrom(it.getClass()))
			.map(EntityFetch.class::cast)
			.findFirst();
	}

	/**
	 * Returns requirements for group entities.
	 */
	@AliasForParameter("entityGroupFetch")
	@Nonnull
	public Optional<EntityGroupFetch> getGroupEntityRequirement() {
		return Arrays.stream(getChildren())
			.filter(it -> EntityGroupFetch.class.isAssignableFrom(it.getClass()))
			.map(EntityGroupFetch.class::cast)
			.findFirst();
	}

	/**
	 * Returns requirements for reference paging.
	 */
	@Nonnull
	public Optional<ChunkingRequireConstraint> getChunking() {
		return Arrays.stream(getChildren())
			.filter(it -> ChunkingRequireConstraint.class.isAssignableFrom(it.getClass()))
			.map(ChunkingRequireConstraint.class::cast)
			.findFirst();
	}

	/**
	 * Returns requirements for reference paging.
	 */
	@AliasForParameter("page")
	@Nonnull
	public Optional<Page> getPage() {
		return Arrays.stream(getChildren())
			.filter(it -> Page.class.isAssignableFrom(it.getClass()))
			.map(Page.class::cast)
			.findFirst();
	}

	/**
	 * Returns requirements for reference paging.
	 */
	@AliasForParameter("strip")
	@Nonnull
	public Optional<Strip> getStrip() {
		return Arrays.stream(getChildren())
			.filter(it -> Strip.class.isAssignableFrom(it.getClass()))
			.map(Strip.class::cast)
			.findFirst();
	}

	/**
	 * Returns filter to filter list of returning references.
	 */
	@Nonnull
	public Optional<FilterBy> getFilterBy() {
		return getAdditionalChild(FilterBy.class);
	}

	/**
	 * Returns sorting to order list of returning references.
	 */
	@Nonnull
	public Optional<OrderBy> getOrderBy() {
		return getAdditionalChild(OrderBy.class);
	}

	/**
	 * Returns TRUE if all available references were requested to load.
	 */
	public boolean isAllRequested() {
		return ArrayUtils.isEmpty(getReferenceNames());
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != ManagedReferencesBehaviour.ANY)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == ManagedReferencesBehaviour.ANY;
	}

	@Override
	public boolean isArgumentImplicitForSuffix(int argumentPosition, @Nonnull Serializable argument) {
		return argument instanceof ManagedReferencesBehaviour mrb &&
			mrb == ManagedReferencesBehaviour.ANY;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		final String instanceName = getInstanceName();
		Assert.isPremiseValid(
			instanceName == null,
			() -> "Cannot clone ReferenceContent with instance name " + instanceName + " using this method!"
		);
		final ManagedReferencesBehaviour thisBehaviour = getManagedReferencesBehaviour();
		final ManagedReferencesBehaviour thatBehaviour = Arrays.stream(newArguments)
			.filter(ManagedReferencesBehaviour.class::isInstance)
			.map(ManagedReferencesBehaviour.class::cast)
			.findFirst()
			.orElse(ManagedReferencesBehaviour.ANY);
		return new ReferenceContent(
			null,
			thisBehaviour == thatBehaviour ? thisBehaviour : ManagedReferencesBehaviour.EXISTING,
			Arrays.stream(newArguments)
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.toArray(String[]::new),
			getChildren(),
			getAdditionalChildren()
		);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		if (isAllRequested() && getAttributeContent().isEmpty()) {
			return of(SUFFIX_ALL);
		}
		if (isAllRequested() && getAttributeContent().isPresent()) {
			return of(SUFFIX_ALL_WITH_ATTRIBUTES);
		}
		if (getAttributeContent().isPresent()) {
			return of(SUFFIX_WITH_ATTRIBUTES);
		}
		return empty();
	}

	@Override
	public boolean isChildImplicitForSuffix(@Nonnull Constraint<?> child) {
		return child instanceof AttributeContent attributeContent &&
			attributeContent.isAllRequested();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		if (additionalChildren.length > 2 ||
			(additionalChildren.length == 2 &&
				(!FilterConstraint.class.isAssignableFrom(additionalChildren[0].getType()) ||
				!OrderConstraint.class.isAssignableFrom(additionalChildren[1].getType())))) {
			throw new EvitaInvalidUsageException("Expected single or no additional filter and order child query.");
		}
		return new ReferenceContent(
			getInstanceName(),
			getManagedReferencesBehaviour(),
			getReferenceNames(),
			children,
			additionalChildren
		);
	}

	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof ReferenceContent referenceContent &&
			this.isSingleReference() && referenceContent.isSingleReference() &&
			this.getReferenceName().equals(referenceContent.getReferenceName()) &&
			this.getInstanceName() == null && referenceContent.getInstanceName() == null;
	}

	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (this.getInstanceName() != null) {
			return false;
		}
		if (anotherRequirement instanceof ReferenceContent referenceContent) {
			if (referenceContent.getInstanceName() != null) {
				return false;
			}
			final String[] thatReferenceNames = referenceContent.getReferenceNames();
			if (thatReferenceNames.length > 0) {
				for (String referenceName : getReferenceNames()) {
					if (Arrays.stream(thatReferenceNames).noneMatch(referenceName::equals)) {
						return false;
					}
				}
			}
			final Optional<AttributeContent> thatContent = referenceContent.getAttributeContent();
			final Optional<AttributeContent> thisContent = getAttributeContent();
			if (thisContent.isPresent()) {
				if (thatContent.isEmpty() || !thisContent.get().isFullyContainedWithin(thatContent.get())) {
					return false;
				}
			}
			final Optional<EntityFetch> thatEntityRequirement = referenceContent.getEntityRequirement();
			final Optional<EntityFetch> thisEntityRequirement = getEntityRequirement();
			if (thisEntityRequirement.isPresent()) {
				if (thatEntityRequirement.isEmpty() ||
				!thisEntityRequirement.get().isFullyContainedWithin(thatEntityRequirement.get())) {
					return false;
				}
			}
			final Optional<EntityGroupFetch> thatGroupEntityRequirement = referenceContent.getGroupEntityRequirement();
			final Optional<EntityGroupFetch> thisGroupEntityRequirement = getGroupEntityRequirement();
			if (thisGroupEntityRequirement.isPresent()) {
				if (thatGroupEntityRequirement.isEmpty() ||
				!thisGroupEntityRequirement.get().isFullyContainedWithin(thatGroupEntityRequirement.get())) {
					return false;
				}
			}
			final Optional<FilterBy> thatFilterBy = referenceContent.getFilterBy();
			final Optional<FilterBy> thisFilterBy = getFilterBy();
			if (!Objects.equals(thisFilterBy.orElse(null), thatFilterBy.orElse(null))) {
				return false;
			}
			final Optional<OrderBy> thatOrderBy = referenceContent.getOrderBy();
			final Optional<OrderBy> thisOrderBy = getOrderBy();
			if (!Objects.equals(thisOrderBy.orElse(null), thatOrderBy.orElse(null))) {
				return false;
			}
		}
		return false;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		Assert.isTrue(
			anotherRequirement instanceof ReferenceContent,
			"Only References requirement can be combined with this one!"
		);
		if (isAllRequested()) {
			return (T) this;
		} else {
			final ReferenceContent anotherReferenceContent = (ReferenceContent) anotherRequirement;
			final String instanceName = getInstanceName();
			Assert.isPremiseValid(
				instanceName == null,
				() -> "Cannot clone ReferenceContent with instance name " + instanceName + " using this method!"
			);
			Assert.isPremiseValid(
				anotherReferenceContent.getInstanceName() == null,
				() -> "Cannot combine ReferenceContent with instance name " + anotherReferenceContent.getInstanceName() + "!"
			);
			if (anotherReferenceContent.isAllRequested()) {
				if (getManagedReferencesBehaviour() == anotherReferenceContent.getManagedReferencesBehaviour()) {
					return anotherRequirement;
				} else {
					return (T) new ReferenceContent(
						ManagedReferencesBehaviour.EXISTING
					);
				}
			} else {
				final ManagedReferencesBehaviour managedReferencesBehaviour =
				getManagedReferencesBehaviour() == anotherReferenceContent.getManagedReferencesBehaviour() ?
					getManagedReferencesBehaviour() : ManagedReferencesBehaviour.EXISTING;
				final String[] referenceNames = isAllRequested() || anotherReferenceContent.isAllRequested() ?
					new String[0] :
					Stream.concat(
							Arrays.stream(getReferenceNames()),
							Arrays.stream(((ReferenceContent) anotherRequirement).getReferenceNames())
						)
						.distinct()
						.toArray(String[]::new);
				final EntityFetch combinedEntityRequirement;
				final EntityGroupFetch combinedGroupEntityRequirement;
				final AttributeContent combinedAttributeRequirement;
				if (referenceNames.length == 1) {
					combinedAttributeRequirement = EntityContentRequire.combineRequirements(
						getAttributeContent().orElse(null),
						anotherReferenceContent.getAttributeContent().orElse(null)
					);
					combinedEntityRequirement = combineRequirements(
						getEntityRequirement().orElse(null),
						anotherReferenceContent.getEntityRequirement().orElse(null)
					);
					combinedGroupEntityRequirement = combineRequirements(
						getGroupEntityRequirement().orElse(null),
						anotherReferenceContent.getGroupEntityRequirement().orElse(null)
					);
				} else if (
					!this.getAttributeContent().map(AttributeContent::isAllRequested).orElse(true) ||
						!anotherReferenceContent.getAttributeContent().map(AttributeContent::isAllRequested).orElse(true)) {
					throw new EvitaInvalidUsageException(
						"Cannot combine multiple attribute content requirements: " + this + " and " + anotherRequirement,
						"Cannot combine multiple attribute content requirements."
					);
				} else if (this.getFilterBy().isPresent() || anotherReferenceContent.getFilterBy().isPresent()) {
					throw new EvitaInvalidUsageException(
						"Cannot combine multiple filtered requirements: " + this + " and " + anotherRequirement,
						"Cannot combine multiple filtered requirements."
					);
				} else if (this.getOrderBy().isPresent() || anotherReferenceContent.getOrderBy().isPresent()) {
					throw new EvitaInvalidUsageException(
						"Cannot combine multiple ordered requirements: " + this + " and " + anotherRequirement,
						"Cannot combine multiple ordered requirements."
					);
				} else if (this.getEntityRequirement().isPresent() || anotherReferenceContent.getEntityRequirement().isPresent()) {
					throw new EvitaInvalidUsageException(
						"Cannot combine multiple requirements with entity fetch: " + this + " and " + anotherRequirement,
						"Cannot combine multiple requirements with entity fetch."
					);
				} else if (this.getGroupEntityRequirement().isPresent() ||
				anotherReferenceContent.getGroupEntityRequirement().isPresent()) {
					throw new EvitaInvalidUsageException(
						"Cannot combine multiple requirements with entity group fetch: " + this + " and " + anotherRequirement,
						"Cannot combine multiple requirements with entity group fetch."
					);
				} else {
					combinedAttributeRequirement = null;
					combinedEntityRequirement = null;
					combinedGroupEntityRequirement = null;
				}

				return (T) new ReferenceContent(
					null,
					managedReferencesBehaviour,
					referenceNames,
					Arrays.stream(
						new RequireConstraint[] {
							combinedAttributeRequirement,
							combinedEntityRequirement,
							combinedGroupEntityRequirement
						}
					).filter(Objects::nonNull).toArray(RequireConstraint[]::new),
					Arrays.stream(
						new Constraint<?>[]{
							getFilterBy().orElse(null),
							getOrderBy().orElse(null)
						}
					).filter(Objects::nonNull).toArray(Constraint[]::new)
				);
			}
		}
	}

	/**
	 * Determines whether the reference content has a single reference name.
	 *
	 * @return true if there is exactly one reference name, false otherwise.
	 */
	private boolean isSingleReference() {
		return this.getReferenceNames().length == 1;
	}

	/**
	 * Helper record to store name as argument and distinguish it from referenceNames (which are also strings).
	 * @param name name of the reference content instance
	 */
	@SupportedClass
	private record ReferenceContentName(
		@Nonnull String name
	) implements Serializable {

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

}