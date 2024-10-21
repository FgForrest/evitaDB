/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
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

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
 * `referenceContentWithAttributes` variant of it.
 *
 * Example:
 *
 * <pre>
 * entityFetch(
 *    attributeContent("code"),
 *    referenceContent("brand"),
 *    referenceContent("categories")
 * )
 * </pre>
 *
 * ## Excluding references to non-existing managed references
 *
 * By default, the referenceContent requirement returns all references, regardless of whether the target entity exists
 * in the database. If you want to filter out references to non-existing entities, you can specify argument controlling
 * this behavior.
 *
 * Example:
 *
 * <pre>
 * entityFetch(
 *    attributeContent("code"),
 *    referenceContent(EXISTING, "brand"),
 *    referenceContent(ANY, "categories")
 * )
 * </pre>
 *
 * This query will return brand entity only if the target brand entity is present in the database while categories
 * reference will be returned regardless of the target entity existence.
 *
 * ## Referenced entity (group) fetching
 *
 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
 * a product:
 *
 * <pre>
 * referenceContent(
 *     "parameterValues",
 *     entityFetch(
 *         attributeContent("code")
 *     ),
 *     entityGroupFetch(
 *         attributeContent("code")
 *     )
 * )
 * </pre>
 *
 * ## Filtering references
 *
 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
 * you can use the filter constraint to filter out the references you don't need.
 *
 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
 * you must wrap them in the {@link EntityHaving} container constraint.
 *
 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
 * use the following query:
 *
 * <pre>
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
 *     entityFetch(
 *         attributeContent("code")
 *     ),
 *     entityGroupFetch(
 *         attributeContent("code", "isVisibleInDetail")
 *     )
 * )
 * </pre>
 *
 * ##Ordering references
 *
 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
 * the references by a different property - either the attribute set on the reference itself or the property of the
 * referenced entity - you can use the order constraint inside the referenceContent requirement.
 *
 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
 * you must wrap them in the entityHaving container constraint.
 *
 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
 * query:
 *
 * <pre>
 * referenceContent(
 *     "parameterValues",
 *     orderBy(
 *         entityProperty(
 *             attributeNatural("name", ASC)
 *         )
 *     ),
 *     entityFetch(
 *         attributeContent("name")
 *     )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching referenced entity bodies into returned main entities.",
	userDocsLink = "/documentation/query/requirements/fetching#reference-content",
	supportedIn = ConstraintDomain.ENTITY
)
public class ReferenceContent extends AbstractRequireConstraintContainer
	implements ReferenceConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, EntityContentRequire, ConstraintContainerWithSuffix {
	public static final ReferenceContent ALL_REFERENCES = new ReferenceContent(AttributeContent.ALL_ATTRIBUTES);
	@Serial private static final long serialVersionUID = 3374240925555151814L;
	private static final String SUFFIX_ALL = "all";
	private static final String SUFFIX_WITH_ATTRIBUTES = "withAttributes";
	private static final String SUFFIX_ALL_WITH_ATTRIBUTES = "allWithAttributes";

	@Nullable
	private static EntityFetch combineRequirements(@Nullable EntityFetch a, @Nullable EntityFetch b) {
		if (a == null) {
			return b;
		} else if (b == null) {
			return a;
		} else {
			return a.combineWith(b);
		}
	}

	@Nullable
	private static EntityGroupFetch combineRequirements(@Nullable EntityGroupFetch a, @Nullable EntityGroupFetch b) {
		if (a == null) {
			return b;
		} else if (b == null) {
			return a;
		} else {
			return a.combineWith(b);
		}
	}

	private ReferenceContent(
		@Nonnull ManagedReferencesBehaviour managedReferences,
		@Nonnull String[] referenceName,
		@Nonnull RequireConstraint[] requirements,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{managedReferences},
				referenceName
			),
			requirements, additionalChildren
		);
	}

	@Creator(suffix = SUFFIX_ALL)
	public ReferenceContent() {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY}
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
			ofNullable(attributeContent).orElse(new AttributeContent()));
	}

	@Creator(suffix = SUFFIX_ALL_WITH_ATTRIBUTES)
	public ReferenceContent(@Nullable AttributeContent attributeContent) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			ofNullable(attributeContent).orElse(new AttributeContent())
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
		@Nullable EntityGroupFetch entityGroupFetch
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ManagedReferencesBehaviour.ANY},
				ofNullable(referenceName).map(it -> new Serializable[]{it}).orElse(NO_ARGS)
			),
			new RequireConstraint[]{entityFetch, entityGroupFetch}, filterBy, orderBy
		);
	}

	@Creator(suffix = SUFFIX_WITH_ATTRIBUTES)
	public ReferenceContent(
		@Nonnull @Classifier String referenceName,
		@Nullable @AdditionalChild(domain = ConstraintDomain.INLINE_REFERENCE) FilterBy filterBy,
		@Nullable @AdditionalChild(domain = ConstraintDomain.INLINE_REFERENCE) OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ManagedReferencesBehaviour.ANY},
				ofNullable(referenceName).map(it -> new Serializable[]{it}).orElse(NO_ARGS)
			),
			new RequireConstraint[]{
				ofNullable(attributeContent).orElse(new AttributeContent()),
				entityFetch,
				entityGroupFetch
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

	public ReferenceContent(@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		super(
			new Serializable[]{ManagedReferencesBehaviour.ANY},
			ofNullable(attributeContent).orElse(new AttributeContent()),
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

	public ReferenceContent(@Nullable ManagedReferencesBehaviour managedReferences, @Nonnull String referenceName, @Nullable AttributeContent attributeContent) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY), referenceName},
			ofNullable(attributeContent).orElse(new AttributeContent())
		);
	}

	public ReferenceContent(@Nullable ManagedReferencesBehaviour managedReferences, @Nullable AttributeContent attributeContent) {
		super(
			new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
			ofNullable(attributeContent).orElse(new AttributeContent())
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
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[]{ofNullable(managedReferences).orElse(ManagedReferencesBehaviour.ANY)},
				new String[]{referenceName}
			),
			new RequireConstraint[]{entityFetch, entityGroupFetch}, filterBy, orderBy
		);
	}

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
				ofNullable(attributeContent).orElse(new AttributeContent()),
				entityFetch,
				entityGroupFetch
			},
			filterBy,
			orderBy
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

	/**
	 * Returns name of reference which should be loaded along with entity.
	 * Note: this can be used only if there is single reference name. Otherwise {@link #getReferenceNames()} should be used.
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
			.map(it -> (AttributeContent) it)
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
			.map(it -> (EntityFetch) it)
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
			.map(it -> (EntityGroupFetch) it)
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
		return Arrays.stream(super.getArgumentsExcludingDefaults())
			.filter(it -> it != ManagedReferencesBehaviour.ANY)
			.toArray(Serializable[]::new);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		final ManagedReferencesBehaviour thisBehaviour = getManagedReferencesBehaviour();
		final ManagedReferencesBehaviour thatBehaviour = Arrays.stream(newArguments)
			.filter(ManagedReferencesBehaviour.class::isInstance)
			.map(ManagedReferencesBehaviour.class::cast)
			.findFirst()
			.orElse(ManagedReferencesBehaviour.ANY);
		return new ReferenceContent(
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
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		if (additionalChildren.length > 2 || (additionalChildren.length == 2 && !FilterConstraint.class.isAssignableFrom(additionalChildren[0].getType()) && !OrderConstraint.class.isAssignableFrom(additionalChildren[1].getType()))) {
			throw new EvitaInvalidUsageException("Expected single or no additional filter and order child query.");
		}
		return new ReferenceContent(getManagedReferencesBehaviour(), getReferenceNames(), children, additionalChildren);
	}

	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof ReferenceContent referenceContent &&
			this.isSingleReference() && referenceContent.isSingleReference() &&
			this.getReferenceName().equals(referenceContent.getReferenceName());
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		Assert.isTrue(anotherRequirement instanceof ReferenceContent, "Only References requirement can be combined with this one!");
		if (isAllRequested()) {
			return (T) this;
		} else {
			final ReferenceContent anotherReferenceContent = (ReferenceContent) anotherRequirement;
			if (anotherReferenceContent.isAllRequested()) {
				if (getManagedReferencesBehaviour() == anotherReferenceContent.getManagedReferencesBehaviour()) {
					return anotherRequirement;
				} else {
					return (T) new ReferenceContent(
						ManagedReferencesBehaviour.EXISTING
					);
				}
			} else {
				final EntityFetch combinedEntityRequirement = combineRequirements(getEntityRequirement().orElse(null), anotherReferenceContent.getEntityRequirement().orElse(null));
				final EntityGroupFetch combinedGroupEntityRequirement = combineRequirements(getGroupEntityRequirement().orElse(null), anotherReferenceContent.getGroupEntityRequirement().orElse(null));
				final ManagedReferencesBehaviour managedReferencesBehaviour = getManagedReferencesBehaviour() == anotherReferenceContent.getManagedReferencesBehaviour() ?
					getManagedReferencesBehaviour() : ManagedReferencesBehaviour.EXISTING;
				final String[] referenceNames = Stream.concat(
						Arrays.stream(getReferenceNames()),
						Arrays.stream(((ReferenceContent) anotherRequirement).getReferenceNames())
					)
					.distinct()
					.toArray(String[]::new);
				return (T) new ReferenceContent(
					managedReferencesBehaviour,
					referenceNames,
					Arrays.stream(
						new RequireConstraint[]{
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
}
