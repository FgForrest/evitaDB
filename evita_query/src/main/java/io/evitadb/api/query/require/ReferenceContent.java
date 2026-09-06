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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * allowing different filtering/ordering configurations to be applied to the same reference type simultaneously. The
 * instance name is part of the requirement's key, so two aliases of one reference stay two independent output slots
 * while two occurrences of a single alias are folded together by the rule described next.
 *
 * ## Two referenceContent requirements in one entityFetch
 *
 * Several `referenceContent` requirements in a single `entityFetch` are not an error — the ones addressing the same
 * references are folded into the one requirement the query is executed with. "The same references" means an equal
 * **key**; see {@link #isCombinableWith(EntityContentRequire)} for its full definition. Within one key:
 *
 * - **reference attributes and the nested entity / group bodies are united**, recursively, so nothing either side
 *   asked for is lost
 * - **a disagreement on {@link ManagedReferencesBehaviour} narrows to {@link ManagedReferencesBehaviour#EXISTING}**,
 *   so a request to suppress references pointing at missing entities is never lost by merging
 * - **`filterBy` and chunking must agree, or be absent on both sides** — the two siblings share one output slot,
 *   so a filter or a page carried by only one of them has no union: dropping it would return references the client
 *   asked to exclude, honouring it would hide references the unrestricted sibling asked for. Both cases — one-sided
 *   and differing — are refused with an {@link EvitaInvalidUsageException}.
 * - **`orderBy` present on one side only is kept.** This is the single deliberate asymmetry: an order shapes the
 *   sequence without dropping any reference, so keeping the only order present hides nothing from either sibling.
 *   Two *different* orders are refused with an {@link EvitaInvalidUsageException}.
 *
 * The refusal is the **client-facing** rule, applied by
 * {@link EntityFetchRequire#combineDuplicateRequirements(EntityContentRequire[])} to the requirements the client
 * wrote. Its counterpart on the engine side is {@link #forPrefetch()}: a `referenceContent` contributed to
 * {@link DefaultPrefetchRequirementCollector} enters it without `filterBy`, `orderBy` and chunking, so the
 * requirements the query planner invents on the client's behalf never collide with his own.
 *
 * A `referenceContentAll…()` and a name-specific requirement carry different keys and are therefore never merged:
 * the specific one wins the lookup for the reference it names, the default one stays the fallback for the rest.
 *
 * Two requirements whose reference name sets merely **overlap** — `referenceContent("a", "b")` beside
 * `referenceContent("b", "c")` — carry different keys and are therefore not combinable as they stand. They are still
 * reconciled: when the request builds its per-reference lookup it projects every requirement onto each name it lists
 * ({@link #forReferenceName(String)}) and folds the projections per name, so `b` is fetched with the union of both
 * bodies while `a` and `c` keep theirs. Only a genuine disagreement inside the shared name — two different
 * `filterBy`, `orderBy` or chunking constraints — is refused.
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
	@Deprecated(since = "2025.2", forRemoval = true)
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

	/**
	 * Returns this requirement projected onto a single one of the references it names - a `referenceContent`
	 * addressing `referenceName` alone and carrying the very same {@link ManagedReferencesBehaviour}, reference
	 * attributes, entity and group bodies, `filterBy`, `orderBy` and chunking constraint.
	 *
	 * The projection is what makes two requirements with different - but overlapping - reference name sets
	 * reconcilable: `referenceContent("a", "b")` and `referenceContent("b", "c")` share no key and cannot be merged
	 * as they stand, while their projections onto `b` share the key `{b}` and fold through
	 * {@link #combineWith(EntityContentRequire)} like any other pair of siblings addressing one reference. This is
	 * how `EvitaRequest#getReferenceEntityFetch()` builds its per-reference lookup.
	 *
	 * The receiver is handed back unchanged when it already names `referenceName` and nothing else, so projecting
	 * the (overwhelmingly common) single-name requirement allocates nothing.
	 *
	 * @param referenceName name of the reference to project this requirement onto; must be one of the names this
	 *                      requirement lists, since a requirement carries no description of any other reference
	 * @return this very instance when it names `referenceName` alone, a new single-name requirement otherwise
	 */
	@Nonnull
	public ReferenceContent forReferenceName(@Nonnull String referenceName) {
		final String[] referenceNames = getReferenceNames();
		Assert.isPremiseValid(
			ArrayUtils.contains(referenceNames, referenceName),
			() -> "Reference `" + referenceName + "` is not named by requirement: " + this + "!"
		);
		if (referenceNames.length == 1) {
			return this;
		}
		return new ReferenceContent(
			getInstanceName(),
			getManagedReferencesBehaviour(),
			new String[]{referenceName},
			getChildren(),
			getAdditionalChildren()
		);
	}

	/**
	 * Returns this requirement as it matters for prefetching — the very same references, reference attributes and
	 * nested bodies, but without the `filterBy`, `orderBy` and chunking constraints.
	 *
	 * Those three are **output projections**: they shape which of the loaded references reach the response and in
	 * what order, and they say nothing about what has to be loaded to answer the query. A prefetch requirement is
	 * a lower bound ("load at least this"), so stripping them is a widening that can never make an answer wrong —
	 * and it is what keeps the requirements the query planner contributes on the client's behalf (a bare
	 * `referenceContent` for a filtered reference, a `referenceContentWithAttributes` for an ordered one) from
	 * colliding with the restricted requirement the client wrote for the same reference.
	 *
	 * The widened requirement is never observed by the client: the prefetched entity is narrowed back down from his
	 * own `EvitaRequest`, whose reference filter, order and chunking are read from the query he actually sent.
	 *
	 * The projection is **shallow on purpose**. A `referenceContent` nested inside this one's `entityFetch` keeps
	 * its own restrictions, because only top-level requirements are contributed by the query planner: two nested
	 * siblings that disagree were both written by the client, and refusing them is exactly the client-facing rule
	 * {@link #combineWith(EntityContentRequire)} is there to apply.
	 *
	 * @return this requirement without its `filterBy`, `orderBy` and chunking constraints, or this very instance
	 *         when it carries none of them
	 */
	@Nonnull
	@Override
	public ReferenceContent forPrefetch() {
		if (getFilterBy().isEmpty() && getOrderBy().isEmpty() && getChunking().isEmpty()) {
			return this;
		}
		return new ReferenceContent(
			getInstanceName(),
			getManagedReferencesBehaviour(),
			getReferenceNames(),
			Arrays.stream(getChildren())
				.filter(it -> !(it instanceof ChunkingRequireConstraint))
				.toArray(RequireConstraint[]::new),
			NO_ADDITIONAL_CHILDREN
		);
	}

	/**
	 * Two `referenceContent` requirements are combinable when they address exactly the same references, i.e. when
	 * they share the same **key**. The key is the pair *(instance name, set of reference names)*:
	 *
	 * - `referenceContentAll…()` — no instance name and an empty name set (the DEFAULT key)
	 * - `referenceContent("a", …)` / `referenceContent("a", "b", …)` — no instance name and the name set `{a}` /
	 *   `{a, b}`; the set is compared **order-insensitively**, so `referenceContent("a","b")` and
	 *   `referenceContent("b","a")` share one key
	 * - a named instance (an alias created through the constructor accepting an instance name) — the instance name
	 *   plus its name set; two aliases of one reference are therefore *not* combinable with each other
	 *
	 * Consequently a DEFAULT requirement is never combinable with a name-specific one: they select different
	 * reference sets, and merging them would silently widen or narrow the result. The
	 * {@link ManagedReferencesBehaviour} is deliberately **not** part of the key —
	 * {@link #combineWith(EntityContentRequire)} reconciles a difference there instead of refusing the merge.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @param <T> type of the requirement to be combined with
	 * @return true when `anotherRequirement` is a `referenceContent` carrying the same key
	 */
	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof ReferenceContent referenceContent &&
			hasSameKeyAs(referenceContent);
	}

	/**
	 * Returns true when everything this requirement asks for is already covered by `anotherRequirement`, so that this
	 * one can be dropped without changing the query result. Containment is a **superset** relation and is therefore
	 * wider than the key equality used by {@link #isCombinableWith(EntityContentRequire)} — a
	 * `referenceContent("a")` is contained within `referenceContentAll()`, but the two are not combinable.
	 *
	 * All of the following must hold:
	 *
	 * - neither side carries an instance name — an alias is a distinct output slot in the response and can never be
	 *   satisfied by another requirement
	 * - both sides share the same {@link ManagedReferencesBehaviour} — `EXISTING` suppresses references to missing
	 *   entities, so an `ANY` requirement does not satisfy an `EXISTING` one and vice versa
	 * - the other side requests either all references, or a superset of this side's reference names (a requirement
	 *   for all references is never contained within a name-specific one)
	 * - this side's reference attributes, entity bodies and group entity bodies are each contained within the
	 *   other side's
	 * - the other side's `filterBy` is **absent or equal** to this side's, and the same holds for the chunking
	 *   constraint — a requirement carrying neither asks for *every* reference and is therefore the superset of one
	 *   that filters or pages them, while two different filters (or two different pages) select unrelated subsets
	 * - the other side's `orderBy` is **equal** to this side's, or this side carries none — an order shapes the
	 *   sequence without dropping anything, so an unordered requirement is satisfied by an ordered one but not the
	 *   other way round
	 *
	 * This relation is consumed by {@link DefaultPrefetchRequirementCollector}, the prefetch union, where dropping
	 * a contained requirement is the intended behaviour because a superset is what prefetch asks for. It is
	 * deliberately **not** consulted by {@link EntityFetch#combineWith(EntityFetchRequire)}, which merges two bodies
	 * the client will actually receive and must therefore preserve the specific-over-default precedence.
	 *
	 * @param anotherRequirement another requirement to be checked for containment
	 * @param <T> the type of the requirement which extends EntityContentRequire
	 * @return true if this requirement is fully satisfied by `anotherRequirement`
	 */
	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (this.getInstanceName() != null) {
			return false;
		}
		if (anotherRequirement instanceof ReferenceContent referenceContent) {
			if (referenceContent.getInstanceName() != null) {
				return false;
			}
			if (getManagedReferencesBehaviour() != referenceContent.getManagedReferencesBehaviour()) {
				return false;
			}
			final String[] thatReferenceNames = referenceContent.getReferenceNames();
			if (thatReferenceNames.length > 0) {
				final String[] thisReferenceNames = getReferenceNames();
				if (thisReferenceNames.length == 0) {
					// this requirement asks for all references, the other one only for a few of them
					return false;
				}
				for (String referenceName : thisReferenceNames) {
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
			if (thatFilterBy.isPresent() && !thatFilterBy.equals(thisFilterBy)) {
				return false;
			}
			final Optional<ChunkingRequireConstraint> thatChunking = referenceContent.getChunking();
			final Optional<ChunkingRequireConstraint> thisChunking = getChunking();
			if (thatChunking.isPresent() && !thatChunking.equals(thisChunking)) {
				return false;
			}
			final Optional<OrderBy> thatOrderBy = referenceContent.getOrderBy();
			final Optional<OrderBy> thisOrderBy = getOrderBy();
			if (thisOrderBy.isPresent() && !thisOrderBy.equals(thatOrderBy)) {
				return false;
			}
			return true;
		}
		return false;
	}

	/**
	 * Merges this requirement with another one addressing the same references into a single requirement that covers
	 * both. The caller **must** have verified {@link #isCombinableWith(EntityContentRequire)} first; combining two
	 * requirements with different keys is a programming error and raises {@link GenericEvitaInternalError}, as does
	 * passing a requirement that is not a `referenceContent` at all.
	 *
	 * The parts are reconciled as follows:
	 *
	 * - **instance name and reference names** — taken over from this requirement; the key equality precondition
	 *   guarantees the other side carries the same ones
	 * - **{@link ManagedReferencesBehaviour}** — kept when both sides agree, otherwise narrowed to
	 *   {@link ManagedReferencesBehaviour#EXISTING}, so that a request to suppress references to missing entities is
	 *   never lost by merging
	 * - **reference attributes, entity body and group entity body** — the union of both sides (recursively for the
	 *   bodies); an "all" requirement absorbs a name-specific one
	 * - **`filterBy` and chunking** — kept when both sides carry an equal one, refused with
	 *   {@link EvitaInvalidUsageException} otherwise, the one-sided case included. The two merged requirements
	 *   describe a single output slot, and a restriction only one of them names has no union: dropping it returns
	 *   references the restricting side asked to exclude, honouring it hides references the unrestricted side asked
	 *   for. Neither reading may be picked silently, so the query is refused and the client says which one he meant.
	 * - **`orderBy`** — kept when both sides carry an equal one or only one side carries it, refused with
	 *   {@link EvitaInvalidUsageException} when the two differ. This is the single deliberate asymmetry against the
	 *   rule above: an order shapes the sequence without dropping any reference, so retaining the only order present
	 *   loses neither side's intent and hides nothing.
	 *
	 * This is the **client-facing** rule. The prefetch union does not go through it: every requirement entering
	 * {@link DefaultPrefetchRequirementCollector} is first stripped of its restrictions by {@link #forPrefetch()},
	 * so the bare `referenceContent` the query planner contributes for a filtered or ordered reference meets an
	 * equally bare client requirement there and never triggers this refusal.
	 *
	 * @param anotherRequirement another requirement to be combined with, must share this requirement's key
	 * @param <T> type of the requirement to be combined with
	 * @return a new requirement covering both this one and `anotherRequirement`
	 * @throws EvitaInvalidUsageException when the two sides disagree about the `filterBy` or the chunking constraint
	 *                                    (a different one, or one carried by a single side), or carry a different
	 *                                    `orderBy`
	 * @throws GenericEvitaInternalError when `anotherRequirement` is not a `referenceContent` or carries another key
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		if (!(anotherRequirement instanceof ReferenceContent anotherReferenceContent)) {
			throw new GenericEvitaInternalError(
				"Only reference content requirement can be combined with this one - but got: " +
					anotherRequirement.getClass(),
				"Only reference content requirement can be combined with this one!"
			);
		}
		if (!hasSameKeyAs(anotherReferenceContent)) {
			throw new GenericEvitaInternalError(
				"Only reference content requirements addressing the same references can be combined - but got: " +
					this + " and " + anotherRequirement,
				"Only reference content requirements addressing the same references can be combined!"
			);
		}

		final Optional<FilterBy> thisFilterBy = getFilterBy();
		final Optional<FilterBy> thatFilterBy = anotherReferenceContent.getFilterBy();
		assertRestrictionsIdentical("filter", thisFilterBy, thatFilterBy, anotherReferenceContent);
		// both sides carry the very same filter or neither carries one - anything else was refused above
		final FilterBy combinedFilterBy = thisFilterBy.orElse(null);

		final Optional<OrderBy> thisOrderBy = getOrderBy();
		final Optional<OrderBy> thatOrderBy = anotherReferenceContent.getOrderBy();
		assertOrdersCompatible(thisOrderBy, thatOrderBy, anotherReferenceContent);
		// an order drops no reference - the single order present is retained
		final OrderBy combinedOrderBy = thisOrderBy.or(() -> thatOrderBy).orElse(null);

		final Optional<ChunkingRequireConstraint> thisChunking = getChunking();
		final Optional<ChunkingRequireConstraint> thatChunking = anotherReferenceContent.getChunking();
		assertRestrictionsIdentical("chunking", thisChunking, thatChunking, anotherReferenceContent);
		// both sides carry the very same chunking or neither carries one - anything else was refused above
		final ChunkingRequireConstraint combinedChunking = thisChunking.orElse(null);

		final ManagedReferencesBehaviour managedReferencesBehaviour =
			getManagedReferencesBehaviour() == anotherReferenceContent.getManagedReferencesBehaviour() ?
				getManagedReferencesBehaviour() : ManagedReferencesBehaviour.EXISTING;

		return (T) new ReferenceContent(
			getInstanceName(),
			managedReferencesBehaviour,
			getReferenceNames(),
			Arrays.stream(
				new RequireConstraint[]{
					EntityContentRequire.combineRequirements(
						getAttributeContent().orElse(null),
						anotherReferenceContent.getAttributeContent().orElse(null)
					),
					combineRequirements(
						getEntityRequirement().orElse(null),
						anotherReferenceContent.getEntityRequirement().orElse(null)
					),
					combineRequirements(
						getGroupEntityRequirement().orElse(null),
						anotherReferenceContent.getGroupEntityRequirement().orElse(null)
					),
					combinedChunking
				}
			).filter(Objects::nonNull).toArray(RequireConstraint[]::new),
			Arrays.stream(
				new Constraint<?>[]{
					combinedFilterBy,
					combinedOrderBy
				}
			).filter(Objects::nonNull).toArray(Constraint[]::new)
		);
	}

	/**
	 * Verifies that a **restriction** - a sub-constraint that decides which of the reference records reach the
	 * response - is either carried by both merged requirements in the very same shape, or by neither of them.
	 * A restriction named by a single side is refused just as loudly as two different ones: the merged requirement
	 * fills one output slot, and neither returning the references the restricting side excluded nor hiding the ones
	 * the unrestricted side asked for may be chosen on the client's behalf.
	 *
	 * @param constraintName     name of the restriction as it appears in the refusal message
	 * @param thisConstraint     the restriction carried by this requirement, empty when it carries none
	 * @param anotherConstraint  the restriction carried by the other requirement, empty when it carries none
	 * @param anotherRequirement the other requirement, rendered into the refusal message
	 * @throws EvitaInvalidUsageException when the two restrictions differ, or when only one side carries one
	 */
	private void assertRestrictionsIdentical(
		@Nonnull String constraintName,
		@Nonnull Optional<? extends Constraint<?>> thisConstraint,
		@Nonnull Optional<? extends Constraint<?>> anotherConstraint,
		@Nonnull ReferenceContent anotherRequirement
	) {
		if (thisConstraint.equals(anotherConstraint)) {
			return;
		}
		final String reason = thisConstraint.isPresent() && anotherConstraint.isPresent() ?
			"Cannot combine multiple reference content requirements with different " + constraintName +
				" constraints" :
			"Cannot combine multiple reference content requirements when only one of them declares a " +
				constraintName + " constraint";
		throw new EvitaInvalidUsageException(
			reason + ": " + this + " and " + anotherRequirement,
			reason + "."
		);
	}

	/**
	 * Verifies that the `orderBy` constraints of the two merged requirements do not contradict each other. Unlike
	 * a restriction, an order carried by a single side only passes - it shapes the sequence of the references
	 * without dropping any of them, so {@link #combineWith(EntityContentRequire)} retains the only order present
	 * and loses neither side's intent.
	 *
	 * @param thisOrderBy        the order carried by this requirement, empty when it carries none
	 * @param anotherOrderBy     the order carried by the other requirement, empty when it carries none
	 * @param anotherRequirement the other requirement, rendered into the refusal message
	 * @throws EvitaInvalidUsageException when both sides carry an order and the two differ
	 */
	private void assertOrdersCompatible(
		@Nonnull Optional<OrderBy> thisOrderBy,
		@Nonnull Optional<OrderBy> anotherOrderBy,
		@Nonnull ReferenceContent anotherRequirement
	) {
		if (thisOrderBy.isPresent() && anotherOrderBy.isPresent() && !thisOrderBy.equals(anotherOrderBy)) {
			final String reason = "Cannot combine multiple reference content requirements with different order " +
				"constraints";
			throw new EvitaInvalidUsageException(
				reason + ": " + this + " and " + anotherRequirement,
				reason + "."
			);
		}
	}

	/**
	 * Determines whether both requirements address exactly the same references, i.e. whether they share the same key
	 * of *(instance name, set of reference names)*. See {@link #isCombinableWith(EntityContentRequire)} for the full
	 * description of the key.
	 *
	 * @param anotherReferenceContent another reference content requirement to compare the key with
	 * @return true if both requirements carry the same key
	 */
	private boolean hasSameKeyAs(@Nonnull ReferenceContent anotherReferenceContent) {
		return Objects.equals(getInstanceName(), anotherReferenceContent.getInstanceName()) &&
			getReferenceNamesAsSet().equals(anotherReferenceContent.getReferenceNamesAsSet());
	}

	/**
	 * Returns names of references which should be loaded along with entity as an order-insensitive set. An empty set
	 * means that all references are requested.
	 *
	 * @return set of reference names, empty when all references are requested
	 */
	@Nonnull
	private Set<String> getReferenceNamesAsSet() {
		final String[] referenceNames = getReferenceNames();
		final Set<String> result = CollectionUtils.createHashSet(referenceNames.length);
		Collections.addAll(result, referenceNames);
		return result;
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