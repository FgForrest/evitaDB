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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintCreator;
import io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.SilentImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.descriptor.ConstraintValueStructure;
import io.evitadb.api.query.filter.AssociatedDataIn;
import io.evitadb.api.query.filter.AttributeIn;
import io.evitadb.api.query.filter.EntityIn;
import io.evitadb.api.query.filter.ReferenceIn;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.query.filter.TextMatchesTargetSpecificationFilterConstraint;
import io.evitadb.api.query.order.AssociatedDataWeight;
import io.evitadb.api.query.order.AttributeWeight;
import io.evitadb.api.query.order.EntityWeight;
import io.evitadb.api.query.order.ReferenceWeight;
import io.evitadb.api.query.order.RelevanceTextEntitySpecificationOrderConstraint;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.*;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.CLASSIFIER_NAMING_CONVENTION;
import static java.util.Optional.ofNullable;

/**
 * Builds part of the whole API schema for querying entities from {@link Constraint}s using {@link ConstraintDescriptor}s.
 * This is common ancestor for building schema for different parts of {@link io.evitadb.api.query.Query}.
 *
 * <h3>Building process</h3>
 * Firstly, root constraint has to be manually found (can be e.g. {@link io.evitadb.api.query.filter.FilterBy}.
 * For this constraint a constraint container equivalent object type is being build with its fields corresponding to
 * all its possible children. Children are passed a correct domain based on parent container property type.
 * Each child constraint is build separately into multiple fields of parent container (depending on number of creators).
 * For each child constraint, a key is constructed and format for its value is chosen
 * and subsequently even its value is built. If that child constraint is also constraint container, another nested
 * container is built in same way is the parent one. This is repeated until all constraints and container are built.
 * Finally, type reference to container is returned.
 * <p>
 * This builder also uses caching of built containers so that they can be reused. Even though each container can be different
 * (has different allowed children, different domain and so on) a lots of container seem to be same (i.e. have same
 * domain and same allowed children) and can be reused.
 *
 * <h3>Constraint key formats</h3>
 * Key can have one of 3 formats depending on descriptor data:
 * <ul>
 *     <li>`{fullName}` - if it's generic constraint without classifier</li>
 *     <li>`{propertyType}{fullName}` - if it's not generic constraint and doesn't have classifier</li>
 *     <li>`{propertyType}{classifier}{fullName}` - if it's not generic constraint and has classifier</li>
 * </ul>
 *
 * @param <CTX>          type of API-specific context object
 * @param <SIMPLE_TYPE>  type that references remote object or scalar and can be safely used anywhere, also this is output type of this builder
 * @param <OBJECT_TYPE>  type that holds actual full object that others reference to, needs to be registered
 * @param <OBJECT_FIELD> output type of schema of single field of parent object
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class ConstraintSchemaBuilder<CTX extends ConstraintSchemaBuildingContext<SIMPLE_TYPE, OBJECT_TYPE>, SIMPLE_TYPE, OBJECT_TYPE, OBJECT_FIELD> {

	protected static final String SINGLE_CHILD_CONSTRAINT_KEY = "_";
	@Nonnull protected final ConstraintKeyBuilder keyBuilder;
	@Nonnull protected final CTX sharedContext;
	@Nonnull private final DataLocatorResolver dataLocatorResolver;
	/**
	 * Map of additional builders for cross-building constraint schemas of different constraint types.
	 */
	@Nonnull private final Map<ConstraintType, AtomicReference<? extends ConstraintSchemaBuilder<CTX, SIMPLE_TYPE, OBJECT_TYPE, OBJECT_FIELD>>> additionalBuilders;
	/**
	 * Globally allowed constraints. Child constraints must fit into this set to be allowed.
	 */
	@Nonnull @Getter private final Set<Class<? extends Constraint<?>>> allowedConstraints;
	/**
	 * Globally forbidden constraints. Forbidden child constraints are merged with global ones.
	 */
	@Nonnull @Getter private final Set<Class<? extends Constraint<?>>> forbiddenConstraints;

	protected ConstraintSchemaBuilder(@Nonnull CTX sharedContext,
	                                  @Nonnull Map<ConstraintType, AtomicReference<? extends ConstraintSchemaBuilder<CTX, SIMPLE_TYPE, OBJECT_TYPE, OBJECT_FIELD>>> additionalBuilders,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> allowedConstraints,
	                                  @Nonnull Set<Class<? extends Constraint<?>>> forbiddenConstraints) {
		Assert.isPremiseValid(
			additionalBuilders.keySet()
				.stream()
				.noneMatch(it -> it.equals(getConstraintType())),
			() -> createSchemaBuildingError("Builder of type `" + getConstraintType() + "` cannot have additional builder of same type.")
		);

		this.sharedContext = sharedContext;
		this.keyBuilder = new ConstraintKeyBuilder();
		this.dataLocatorResolver = new DataLocatorResolver(sharedContext.getCatalog().getSchema());
		this.additionalBuilders = additionalBuilders;
		this.allowedConstraints = Collections.unmodifiableSet(allowedConstraints);
		this.forbiddenConstraints = Collections.unmodifiableSet(forbiddenConstraints);
	}

	/**
	 * Builds API schema equivalent to constraint tree starting with {@link #getDefaultRootConstraintContainerDescriptor()}
	 * as root in specified context.
	 *
	 * @param rootDataLocator defines data context for the root constraint container, ultimately defining which constraints
	 *                        will be available from root
	 */
	@Nonnull
	public SIMPLE_TYPE build(@Nonnull DataLocator rootDataLocator) {
		final ConstraintDescriptor rootDescriptor = getDefaultRootConstraintContainerDescriptor();
		return build(rootDataLocator, rootDescriptor);
	}

	/**
	 * Builds API schema equivalent to constraint tree starting with the specified constraint as root in specified context.
	 *
	 * @param rootDataLocator defines data context for the root constraint container, ultimately defining which constraints
	 *                        will be available from root
	 * @param constraintClass root constraint to build the tree from (must have only single variant, otherwise use {@link #build(DataLocator, ConstraintDescriptor)})
	 */
	@Nonnull
	public SIMPLE_TYPE build(@Nonnull DataLocator rootDataLocator, @Nonnull Class<? extends Constraint<?>> constraintClass) {
		return build(rootDataLocator, ConstraintDescriptorProvider.getConstraint(constraintClass));
	}

	/**
	 * Builds API schema equivalent to constraint tree starting with the specified constraint as root in specified context.
	 *
	 * @param rootDataLocator      defines data context for the root constraint container, ultimately defining which constraints
	 *                             will be available from root
	 * @param constraintDescriptor root constraint to build the tree from
	 */
	@Nonnull
	public SIMPLE_TYPE build(@Nonnull DataLocator rootDataLocator, @Nonnull ConstraintDescriptor constraintDescriptor) {
		return build(
			new ConstraintBuildContext(rootDataLocator),
			constraintDescriptor
		);
	}

	/**
	 * Builds API schema equivalent to constraint tree starting with the specified constraint as root in specified context.
	 *
	 * @param buildContext         context for the root constraint container, ultimately defining which constraints
	 *                             will be available from root
	 * @param constraintDescriptor root constraint to build the tree from
	 */
	@Nonnull
	protected SIMPLE_TYPE build(@Nonnull ConstraintBuildContext buildContext, @Nonnull ConstraintDescriptor constraintDescriptor) {
		return buildConstraintValue(buildContext, constraintDescriptor, null);
	}


	/**
	 * Defines which type of constraints will be considered when finding which constraint to build schema from.
	 */
	@Nonnull
	protected abstract ConstraintType getConstraintType();

	/**
	 * Returns root constraint container from which other nested constraints will be built.
	 */
	@Nonnull
	protected abstract ConstraintDescriptor getDefaultRootConstraintContainerDescriptor();

	/**
	 * Middle part of built container API schema object types.
	 */
	@Nonnull
	protected abstract String getContainerObjectTypeName();

	/**
	 * Middle part of built wrapper object API schema object types.
	 */
	@Nonnull
	protected String getWrapperObjectObjectTypeName() {
		return "WrapperObject";
	}

	/**
	 * Filters found attribute schema to filter out those which are not relevant for this builder.
	 */
	@Nonnull
	protected abstract Predicate<AttributeSchemaContract> getAttributeSchemaFilter();


	/**
	 * Returns specific constraint container with only allowed children in particular build context and creator.
	 *
	 * The container is either retrieved from cache or it is build new one.
	 */
	@Nonnull
	protected SIMPLE_TYPE obtainContainer(@Nonnull ConstraintBuildContext buildContext,
	                                      @Nonnull ChildParameterDescriptor childParameter) {
		final AllowedConstraintPredicate allowedChildrenPredicate = new AllowedConstraintPredicate(
			childParameter,
			this.allowedConstraints,
			this.forbiddenConstraints
		);

		final ContainerKey containerKey = new ContainerKey(
			getConstraintType(),
			buildContext.dataLocator(),
			allowedChildrenPredicate
		);

		// reuse already build container with same properties
		final SIMPLE_TYPE cachedContainer = this.sharedContext.getCachedContainer(containerKey);
		if (cachedContainer != null) {
			return cachedContainer;
		}

		// build new container
		return buildContainer(buildContext, containerKey, allowedChildrenPredicate);
	}

	/**
	 * Builds specific constraint container with only allowed children in particular build context and creator.
	 * Returns `null` if container does make sense in current build context and should be replaced with some placeholder
	 * value. Implementations should also cache the built container.
	 *
	 * <b>Note:</b> this method should not be used directly, instead use {@link #obtainContainer(ConstraintBuildContext, ChildParameterDescriptor)}.
	 */
	@Nonnull
	protected abstract SIMPLE_TYPE buildContainer(@Nonnull ConstraintBuildContext buildContext,
	                                              @Nonnull ContainerKey containerKey,
	                                              @Nonnull AllowedConstraintPredicate allowedChildrenPredicate);

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildChildren(@Nonnull AllowedConstraintPredicate allowedChildrenPredicate,
	                                           @Nonnull Set<ConstraintDescriptor> childConstraintDescriptors,
	                                           @Nonnull FieldFromConstraintDescriptorBuilder<OBJECT_FIELD> fieldBuilder) {
		return childConstraintDescriptors
			.stream()
			.filter(allowedChildrenPredicate)
			.map(fieldBuilder)
			.filter(Objects::nonNull)
			.toList();
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building non-dynamic (no classifier and no generic value types) children.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildBasicChildren(
		@Nonnull ConstraintBuildContext buildContext,
		@Nonnull AllowedConstraintPredicate allowedChildrenPredicate,
		@Nonnull ConstraintPropertyType propertyType
	) {
		final Set<ConstraintDescriptor> constraintDescriptors = ConstraintDescriptorProvider.getConstraints(
			getConstraintType(),
			propertyType,
			Objects.requireNonNull(buildContext.parentDataLocator()).targetDomain()
		);

		final FieldFromConstraintDescriptorBuilder<OBJECT_FIELD> fieldBuilder =
			constraintDescriptor -> buildFieldFromConstraintDescriptor(
				buildContext,
				constraintDescriptor,
				null,
				null
			);

		return buildChildren(allowedChildrenPredicate, constraintDescriptors, fieldBuilder);
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#GENERIC} property type.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildGenericChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                  @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		return buildBasicChildren(
			buildContext.switchToChildContext(buildContext.dataLocator()),
			allowedChildrenPredicate,
			ConstraintPropertyType.GENERIC
		);
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#ENTITY} property type.
	 *
	 * In TEXT, only an indexed composition reference to a managed target permits an entity transition.
	 * The descriptor guard then checks the target places allowed by its child marker.
	 *
	 * @param buildContext context of the reference or entity offering these children
	 * @param allowedChildrenPredicate constraints permitted by the enclosing creator
	 * @return fields targeting the entity, or no TEXT fields when composition is unavailable
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildEntityChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                 @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		final DataLocator parentDataLocator = buildContext.dataLocator();
		if (parentDataLocator.targetDomain() == ConstraintDomain.TEXT &&
			!hasEntityTransition(parentDataLocator)) {
			return List.of();
		}
		final DataLocator childDataLocator;
		if (parentDataLocator instanceof final DataLocatorWithReference dataLocatorWithReference) {
			final Optional<ReferenceSchemaContract> referenceSchema = ofNullable(dataLocatorWithReference.referenceName())
				.map(it -> this.sharedContext.getEntitySchemaOrThrowException(parentDataLocator.entityType())
					.getReferenceOrThrowException(it));
			childDataLocator = referenceSchema.map(referenceSchemaContract -> new EntityDataLocator(
				referenceSchemaContract.isReferencedEntityTypeManaged()
					? new ManagedEntityTypePointer(referenceSchemaContract.getReferencedEntityType())
					: new ExternalEntityTypePointer(referenceSchemaContract.getReferencedEntityType())
			)).orElseGet(() -> new EntityDataLocator(dataLocatorWithReference.entityTypePointer()));
		} else {
			childDataLocator = new EntityDataLocator(parentDataLocator.entityTypePointer());
		}

		return buildBasicChildren(
			buildContext.switchToChildContext(childDataLocator),
			allowedChildrenPredicate,
			ConstraintPropertyType.ENTITY
		);
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#GROUP} property type.
	 * Unlike {@link #buildEntityChildren}, there's no root-level fallback: a GROUP-typed constraint only makes
	 * sense when nested in a reference/facet scope whose reference schema actually declares a group type.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildGroupChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		final DataLocator parentDataLocator = buildContext.dataLocator();
		if (!(parentDataLocator instanceof final DataLocatorWithReference dataLocatorWithReference) ||
			dataLocatorWithReference.referenceName() == null) {
			return List.of();
		}

		final ReferenceSchemaContract referenceSchema = this.sharedContext
			.getEntitySchemaOrThrowException(parentDataLocator.entityType())
			.getReferenceOrThrowException(dataLocatorWithReference.referenceName());
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		if (referencedGroupType == null) {
			// the reference has no group type configured, so there's no group entity to filter/order/require by
			return List.of();
		}

		final DataLocator childDataLocator = new EntityDataLocator(
			referenceSchema.isReferencedGroupTypeManaged()
				? new ManagedEntityTypePointer(referencedGroupType)
				: new ExternalEntityTypePointer(referencedGroupType)
		);

		return buildBasicChildren(
			buildContext.switchToChildContext(childDataLocator),
			allowedChildrenPredicate,
			ConstraintPropertyType.GROUP
		);
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#ATTRIBUTE} property type.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildAttributeChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                    @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		if (buildContext.dataLocator().entityTypePointer() instanceof ExternalEntityTypePointer) {
			return List.of();
		}

		final List<OBJECT_FIELD> fields = new LinkedList<>();

		// build constraints without dynamic classifier
		final boolean wildcardsOffered = canOfferWildcards(buildContext.dataLocator(), allowedChildrenPredicate,
			ConstraintSchemaBuildingContext.TextPlaces::attributes);
		final Set<ConstraintDescriptor> constraintDescriptorsWithoutClassifier = ConstraintDescriptorProvider.getConstraints(
				getConstraintType(),
				ConstraintPropertyType.ATTRIBUTE,
				buildContext.dataLocator().targetDomain()
			)
			.stream()
			.filter(cd -> wildcardsOffered && !cd.creator().hasClassifierParameter())
			.collect(Collectors.toUnmodifiableSet());
		fields.addAll(
			buildChildren(
				allowedChildrenPredicate,
				constraintDescriptorsWithoutClassifier,
				constraintDescriptor -> buildFieldFromConstraintDescriptor(
					buildContext,
					constraintDescriptor,
					null,
					null
				)
			)
		);

		fields.addAll(
			findAttributeSchemas(buildContext.dataLocator())
				.stream()
				.flatMap(attributeSchema -> {
					// build constraint with dynamic classifiers only, others are currently not needed
					final Set<ConstraintDescriptor> constraintDescriptors = ConstraintDescriptorProvider.getConstraints(
							getConstraintType(),
							ConstraintPropertyType.ATTRIBUTE,
							buildContext.dataLocator().targetDomain(),
							attributeSchema.getPlainType(),
							attributeSchema.getType().isArray(),
							attributeSchema.isNullable()
						)
						.stream()
						.filter(cd -> cd.creator().hasClassifierParameter())
						.collect(Collectors.toUnmodifiableSet());

					final FieldFromConstraintDescriptorBuilder<OBJECT_FIELD> fieldBuilder =
						constraintDescriptor -> buildFieldFromConstraintDescriptor(
							buildContext, // attribute constraints doesn't support children, thus parent domain is used as the default
							constraintDescriptor,
							() -> attributeSchema.getNameVariant(CLASSIFIER_NAMING_CONVENTION),
							valueParameter -> {
								Class<? extends Serializable> plainType = attributeSchema.getPlainType();
								if (valueParameter.requiresPlainType() && Range.class.isAssignableFrom(plainType)) {
									//noinspection unchecked
									plainType = resolveRangeSupportedType((Class<? extends Range<?>>) plainType);
								}
								return plainType;
							}
						);

					return buildChildren(allowedChildrenPredicate, constraintDescriptors, fieldBuilder).stream();
				})
				.toList()
		);

		fields.addAll(
			findSortableAttributeCompoundSchemas(buildContext.dataLocator())
				.stream()
				.flatMap(sortableAttributeCompoundSchema -> {
					// build constraint with dynamic classifiers only, others are currently not needed
					final Set<ConstraintDescriptor> constraintDescriptors = ConstraintDescriptorProvider.getConstraintsSupportingCompounds(
							getConstraintType(),
							ConstraintPropertyType.ATTRIBUTE,
							buildContext.dataLocator().targetDomain()
						)
						.stream()
						.filter(cd -> cd.creator().hasClassifierParameter())
						.collect(Collectors.toUnmodifiableSet());

					final FieldFromConstraintDescriptorBuilder<OBJECT_FIELD> fieldBuilder =
						constraintDescriptor -> buildFieldFromConstraintDescriptor(
							buildContext, // attribute constraints doesn't support children, thus parent domain is used as the default
							constraintDescriptor,
							() -> sortableAttributeCompoundSchema.getNameVariant(CLASSIFIER_NAMING_CONVENTION),
							null
						);

					return buildChildren(allowedChildrenPredicate, constraintDescriptors, fieldBuilder).stream();
				})
				.toList()
		);

		return fields;
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#ASSOCIATED_DATA} property type.
	 *
	 * In TEXT, expands classifier constraints for searchable entity associated data.
	 * References have no associated data.
	 *
	 * @param buildContext context containing the entity or reference locator
	 * @param allowedChildrenPredicate constraints permitted by the enclosing creator
	 * @return associated-data fields available in this context
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildAssociatedDataChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                         @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		final DataLocator dataLocator = buildContext.dataLocator();
		if (dataLocator.entityTypePointer() instanceof ExternalEntityTypePointer ||
			(dataLocator.targetDomain() == ConstraintDomain.TEXT && dataLocator instanceof DataLocatorWithReference)) {
			return List.of();
		}

		final List<OBJECT_FIELD> fields = new LinkedList<>();

		// build constraints without dynamic classifier
		final boolean wildcardsOffered = canOfferWildcards(dataLocator, allowedChildrenPredicate,
			ConstraintSchemaBuildingContext.TextPlaces::associatedData);
		final Set<ConstraintDescriptor> constraintDescriptorsWithoutClassifier = ConstraintDescriptorProvider.getConstraints(
				getConstraintType(),
				ConstraintPropertyType.ASSOCIATED_DATA,
				buildContext.dataLocator().targetDomain()
			)
			.stream()
			.filter(cd -> wildcardsOffered && !cd.creator().hasClassifierParameter())
			.collect(Collectors.toUnmodifiableSet());
		fields.addAll(
			buildChildren(
				allowedChildrenPredicate,
				constraintDescriptorsWithoutClassifier,
				constraintDescriptor -> buildFieldFromConstraintDescriptor(
					buildContext, // associated data constraints doesn't support children, thus parent domain is used as the default
					constraintDescriptor,
					null,
					null
				)
			)
		);

		if (dataLocator.targetDomain() == ConstraintDomain.TEXT) {
			fields.addAll(findSearchableAssociatedDataSchemas(dataLocator).stream()
				.flatMap(schema -> {
					final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(
						getConstraintType(), ConstraintPropertyType.ASSOCIATED_DATA, ConstraintDomain.TEXT,
						schema.getPlainType(),
						schema.getType().isArray(), schema.isNullable()
					).stream().filter(it -> it.creator().hasClassifierParameter())
						.collect(Collectors.toUnmodifiableSet());
					return buildChildren(allowedChildrenPredicate, descriptors, descriptor ->
						buildFieldFromConstraintDescriptor(
							buildContext, descriptor,
							() -> schema.getNameVariant(CLASSIFIER_NAMING_CONVENTION),
							parameter -> schema.getType()
						)
					).stream();
				}).toList());
		}
		return fields;
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#PRICE} property type.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildPriceChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                @Nonnull AllowedConstraintPredicate allowedChildrenPredicate) {
		if (buildContext.dataLocator().entityTypePointer() instanceof ExternalEntityTypePointer) {
			return List.of();
		}

		if (this.sharedContext.getEntitySchemaOrThrowException(buildContext.dataLocator().entityType()).getCurrencies().isEmpty()) {
			// no prices, cannot operate on them
			return List.of();
		}
		return buildBasicChildren(
			buildContext.switchToChildContext(buildContext.dataLocator()), // price constraints doesn't support children, thus parent domain is used as the default
			allowedChildrenPredicate,
			ConstraintPropertyType.PRICE
		);
	}

	/**
	 * Expands reference constraints over indexed references, or searchable reference places in TEXT.
	 *
	 * @param buildContext entity context whose reference fields are expanded
	 * @param allowedChildrenPredicate constraints permitted by the enclosing creator
	 * @param referenceSchemas references declared by the current collection
	 * @return reference fields with searchable-place suppression applied to TEXT containers
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildReferenceChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                    @Nonnull AllowedConstraintPredicate allowedChildrenPredicate,
	                                                    @Nonnull Collection<ReferenceSchemaContract> referenceSchemas) {
		if (buildContext.dataLocator().entityTypePointer() instanceof ExternalEntityTypePointer) {
			return List.of();
		}

		final Set<ConstraintDescriptor> referenceConstraints = ConstraintDescriptorProvider.getConstraints(
			getConstraintType(),
			ConstraintPropertyType.REFERENCE,
			buildContext.dataLocator().targetDomain()
		);

		final List<OBJECT_FIELD> fields = new LinkedList<>();

		// build constraint without dynamic classifier
		final Set<ConstraintDescriptor> referenceConstraintsWithoutClassifier = referenceConstraints.stream()
			.filter(cd -> !cd.creator().hasClassifierParameter())
			.collect(Collectors.toUnmodifiableSet());

		fields.addAll(
			buildChildren(
				allowedChildrenPredicate,
				referenceConstraintsWithoutClassifier,
				constraintDescriptor -> buildFieldFromConstraintDescriptor(
					buildContext.switchToChildContext(new ReferenceDataLocator(
						buildContext.dataLocator().entityTypePointer(),
						(buildContext.dataLocator() instanceof DataLocatorWithReference dataLocatorWithReference) ? dataLocatorWithReference.referenceName() : null
					)),
					constraintDescriptor,
					null,
					null
				)
			)
		);

		// build constraint with dynamic classifier
		final Set<ConstraintDescriptor> referenceConstraintsWithDynamicClassifier = referenceConstraints.stream()
			.filter(cd -> cd.creator().hasClassifierParameter())
			.collect(Collectors.toUnmodifiableSet());

		fields.addAll(
			referenceSchemas
				.stream()
				.filter(referenceSchema -> buildContext.dataLocator().targetDomain() == ConstraintDomain.TEXT ||
					referenceSchema.isIndexedInAnyScope())
				.flatMap(referenceSchema -> {
					final FieldFromConstraintDescriptorBuilder<OBJECT_FIELD> fieldBuilder = constraintDescriptor -> buildFieldFromConstraintDescriptor(
						buildContext.switchToChildContext(new ReferenceDataLocator(
							buildContext.dataLocator().entityTypePointer(),
							referenceSchema.getName()
						)),
						constraintDescriptor,
						() -> referenceSchema.getNameVariant(CLASSIFIER_NAMING_CONVENTION),
						null
					);

					return buildChildren(allowedChildrenPredicate, referenceConstraintsWithDynamicClassifier, fieldBuilder).stream();
				})
				.toList()
		);

		return fields;
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#HIERARCHY} property type.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildHierarchyChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                    @Nonnull AllowedConstraintPredicate allowedChildrenPredicate,
	                                                    @Nonnull Collection<ReferenceSchemaContract> referenceSchemas) {
		if (buildContext.dataLocator().entityTypePointer() instanceof ExternalEntityTypePointer) {
			return List.of();
		}

		final Set<ConstraintDescriptor> hierarchyConstraints = ConstraintDescriptorProvider.getConstraints(
			getConstraintType(),
			ConstraintPropertyType.HIERARCHY,
			buildContext.dataLocator().targetDomain()
		);

		final List<OBJECT_FIELD> fields = new LinkedList<>();

		// build constraints with classifier of queried collection
		if (!(buildContext.dataLocator() instanceof DataLocatorWithReference) &&
			this.sharedContext.getEntitySchemaOrThrowException(buildContext.dataLocator().entityType()).isWithHierarchy()) {
			final Set<ConstraintDescriptor> hierarchyConstraintsWithSilentImplicitClassifier = hierarchyConstraints.stream()
				.filter(cd -> cd.creator().implicitClassifier().orElse(null) instanceof SilentImplicitClassifier)
				.collect(Collectors.toUnmodifiableSet());

			fields.addAll(
				buildChildren(
					allowedChildrenPredicate,
					hierarchyConstraintsWithSilentImplicitClassifier,
					constraintDescriptor -> buildFieldFromConstraintDescriptor(
						buildContext.switchToChildContext(new HierarchyDataLocator(buildContext.dataLocator().entityTypePointer())),
						constraintDescriptor,
						null,
						null
					)
				)
			);
		}

		// build constraints without dynamic classifier
		final Set<ConstraintDescriptor> hierarchyConstraintsWithoutDynamicClassifier = hierarchyConstraints.stream()
			.filter(cd -> !cd.creator().hasClassifierParameter() && !(cd.creator().implicitClassifier().orElse(null) instanceof SilentImplicitClassifier))
			.collect(Collectors.toUnmodifiableSet());
		fields.addAll(
			buildChildren(
				allowedChildrenPredicate,
				hierarchyConstraintsWithoutDynamicClassifier,
				constraintDescriptor -> buildFieldFromConstraintDescriptor(
					buildContext.switchToChildContext(new HierarchyDataLocator(
						buildContext.dataLocator().entityTypePointer(),
						(buildContext.dataLocator() instanceof DataLocatorWithReference dataLocatorWithReference) ? dataLocatorWithReference.referenceName() : null
					)),
					constraintDescriptor,
					null,
					null
				)
			)
		);

		// build constraints with dynamic classifier
		final Set<ConstraintDescriptor> hierarchyConstraintsWithClassifierParameter = hierarchyConstraints.stream()
			.filter(cd -> cd.creator().hasClassifierParameter())
			.collect(Collectors.toUnmodifiableSet());
		fields.addAll(
			referenceSchemas
				.stream()
				.filter(referenceSchema ->
					referenceSchema.isReferencedEntityTypeManaged() &&
						this.sharedContext
							.getCatalog()
							.getCollectionForEntityOrThrowException(referenceSchema.getReferencedEntityType())
							.getSchema()
							.isWithHierarchy())
				.flatMap(hierarchyReferenceSchema -> {
					final FieldFromConstraintDescriptorBuilder<OBJECT_FIELD> fieldBuilder = constraintDescriptor -> buildFieldFromConstraintDescriptor(
						buildContext.switchToChildContext(new HierarchyDataLocator(
							buildContext.dataLocator().entityTypePointer(),
							hierarchyReferenceSchema.getName()
						)),
						constraintDescriptor,
						() -> hierarchyReferenceSchema.getNameVariant(CLASSIFIER_NAMING_CONVENTION),
						null
					);

					return buildChildren(allowedChildrenPredicate, hierarchyConstraintsWithClassifierParameter, fieldBuilder).stream();
				})
				.toList()
		);

		return fields;
	}

	/**
	 * Builds fields representing children of constraint container from constraint descriptors of these children.
	 * This is shortcut method for building all children of {@link ConstraintPropertyType#FACET} property type.
	 */
	@Nonnull
	protected List<OBJECT_FIELD> buildFacetChildren(@Nonnull ConstraintBuildContext buildContext,
	                                                @Nonnull AllowedConstraintPredicate allowedChildrenPredicate,
	                                                @Nonnull Collection<ReferenceSchemaContract> referenceSchemas) {
		if (buildContext.dataLocator().entityTypePointer() instanceof ExternalEntityTypePointer) {
			return List.of();
		}

		final Set<ConstraintDescriptor> constraintsForFacets = ConstraintDescriptorProvider.getConstraints(
			getConstraintType(),
			ConstraintPropertyType.FACET,
			buildContext.dataLocator().targetDomain()
		);

		final List<OBJECT_FIELD> fields = new LinkedList<>();

		// build constraints without dynamic classifier
		fields.addAll(
			buildChildren(
				allowedChildrenPredicate,
				constraintsForFacets.stream()
					.filter(cd -> !cd.creator().hasClassifierParameter())
					.collect(Collectors.toUnmodifiableSet()),
				constraintDescriptor -> buildFieldFromConstraintDescriptor(
					buildContext.switchToChildContext(new FacetDataLocator(
						buildContext.dataLocator().entityTypePointer(),
						(buildContext.dataLocator() instanceof DataLocatorWithReference dataLocatorWithReference) ? dataLocatorWithReference.referenceName() : null
					)),
					constraintDescriptor,
					null,
					null
				)
			)
		);

		// build constraints with dynamic classifier only, others are currently not needed
		fields.addAll(
			referenceSchemas
				.stream()
				.filter(ReferenceSchemaContract::isFacetedInAnyScope)
				.flatMap(facetSchema -> {
					final FieldFromConstraintDescriptorBuilder<OBJECT_FIELD> fieldBuilder =
						constraintDescriptor -> buildFieldFromConstraintDescriptor(
							buildContext.switchToChildContext(new FacetDataLocator(
								buildContext.dataLocator().entityTypePointer(),
								facetSchema.getName()
							)),
							constraintDescriptor,
							() -> facetSchema.getNameVariant(CLASSIFIER_NAMING_CONVENTION),
							null
						);

					return buildChildren(
						allowedChildrenPredicate,
						constraintsForFacets.stream()
							.filter(cd -> cd.creator().hasClassifierParameter())
							.collect(Collectors.toUnmodifiableSet()),
						fieldBuilder
					).stream();
				})
				.toList()
		);

		return fields;
	}

	/**
	 * Builds field from single constraint descriptor.
	 *
	 * @param buildContext         build context
	 * @param constraintDescriptor constraint descriptor to build field from
	 * @param classifierSupplier   supplies concrete classifier for constraint key if required by descriptor
	 * @param valueTypeSupplier    supplies concrete value type for constraint values if value parameter has generic type
	 * @return field representing single constraint descriptor or null if constraint shouldn't be created in current
	 * context
	 */
	@Nullable
	protected OBJECT_FIELD buildFieldFromConstraintDescriptor(@Nonnull ConstraintBuildContext buildContext,
	                                                          @Nonnull ConstraintDescriptor constraintDescriptor,
	                                                          @Nullable Supplier<String> classifierSupplier,
	                                                          @Nullable ValueTypeSupplier valueTypeSupplier) {
		if (!canFieldBeCreatedFromConstraintDescriptor(buildContext, constraintDescriptor)) {
			// missing some data in current context, constraint descriptor shouldn't be created, it wouldn't make sense
			return null;
		}

		final String constraintKey = this.keyBuilder.build(buildContext, constraintDescriptor, classifierSupplier);
		final SIMPLE_TYPE constraintValue = buildConstraintValue(buildContext, constraintDescriptor, valueTypeSupplier);
		return buildFieldFromConstraintDescriptor(constraintDescriptor, constraintKey, constraintValue);
	}

	/**
	 * Builds field object from built constraint key and value.
	 *
	 * @param constraintDescriptor descriptor of original constraint
	 * @param constraintKey        built constraint key
	 * @param constraintValue      built constraint value
	 * @return output schema field object
	 */
	@Nullable
	protected abstract OBJECT_FIELD buildFieldFromConstraintDescriptor(@Nonnull ConstraintDescriptor constraintDescriptor,
	                                                                   @Nonnull String constraintKey,
	                                                                   @Nonnull SIMPLE_TYPE constraintValue);

	/**
	 * Builds field value representing possible values that can be passed to reconstruct the constraint
	 *
	 * @param buildContext         build context
	 * @param constraintDescriptor constraint descriptor with creator by which to create the value
	 * @param valueTypeSupplier    supplies concrete value type for constraint values if value parameter has generic type
	 * @return input type representing the field value
	 */
	@Nonnull
	protected SIMPLE_TYPE buildConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                           @Nonnull ConstraintDescriptor constraintDescriptor,
	                                           @Nullable ValueTypeSupplier valueTypeSupplier) {
		final ConstraintCreator creator = constraintDescriptor.creator();
		final ConstraintValueStructure constraintValueStructure = creator.valueStructure();

		final List<ValueParameterDescriptor> valueParameters = creator.valueParameters();
		final List<ChildParameterDescriptor> childParameters = creator.childParameters();
		final List<AdditionalChildParameterDescriptor> additionalChildParameters = creator.additionalChildParameters();

		if (constraintValueStructure == ConstraintValueStructure.NONE) {
			return buildNoneConstraintValue();
		} else if (constraintValueStructure == ConstraintValueStructure.PRIMITIVE) {
			return buildPrimitiveConstraintValue(buildContext, valueParameters.getFirst(), false, valueTypeSupplier);
		} else if (constraintValueStructure == ConstraintValueStructure.RANGE) {
			return buildWrapperRangeConstraintValue(buildContext, valueParameters, valueTypeSupplier);
		} else if (constraintValueStructure == ConstraintValueStructure.CONTAINER) {
			final Map<String, SIMPLE_TYPE> childConstraint = buildChildConstraintValue(buildContext, childParameters.getFirst());
			Assert.isPremiseValid(
				childConstraint.size() == 1,
				() -> createSchemaBuildingError("`" + ConstraintValueStructure.CONTAINER + "` structure should have exactly one child constraint.")
			);
			return childConstraint.get(SINGLE_CHILD_CONSTRAINT_KEY);
		} else if (constraintValueStructure == ConstraintValueStructure.COMPLEX) {
			return obtainWrapperObjectConstraintValue(
				buildContext,
				valueParameters,
				childParameters,
				additionalChildParameters,
				valueTypeSupplier
			);
		} else {
			throw createSchemaBuildingError("Unsupported constraint value structure `" + constraintValueStructure + "`.");
		}
	}

	/**
	 * Builds field value representing constraint value of constraint without any creator parameters.
	 */
	@Nonnull
	protected abstract SIMPLE_TYPE buildNoneConstraintValue();

	/**
	 * Builds field value representing constraint value of constraint with single creator value parameter.
	 */
	@Nonnull
	protected abstract SIMPLE_TYPE buildPrimitiveConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                             @Nonnull ValueParameterDescriptor valueParameter,
	                                                             boolean canBeRequired,
	                                                             @Nullable ValueTypeSupplier valueTypeSupplier);

	/**
	 * Builds field value representing constraint value of constraint with single creator value parameter.
	 */
	@Nonnull
	protected abstract SIMPLE_TYPE buildWrapperRangeConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                                @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                                @Nullable ValueTypeSupplier valueTypeSupplier);

	/**
	 * Builds field value representing constraint value of constraint with single creator child parameter.
	 */
	@Nonnull
	protected abstract Map<String, SIMPLE_TYPE> buildChildConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                                      @Nonnull ChildParameterDescriptor childParameter);

	/**
	 * Builds field value representing constraint value of constraint with additional child parameter.
	 * <b>Note: </b> currently, we assume that the type of child parameter is generic container with only one child
	 * parameter. Otherwise, there would have to be logic for another nested implicit container which currently doesn't make
	 * sense.
	 *
	 * @return additional child constraint value or empty if data for child constraint is missing, but it is still logically correct
	 */
	@Nonnull
	protected Optional<SIMPLE_TYPE> buildAdditionalChildConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                                    @Nonnull AdditionalChildParameterDescriptor additionalChildParameter) {
		final AtomicReference<? extends ConstraintSchemaBuilder<CTX, SIMPLE_TYPE, OBJECT_TYPE, OBJECT_FIELD>> additionalBuilder = this.additionalBuilders.get(additionalChildParameter.constraintType());
		Assert.isPremiseValid(
			additionalBuilder != null,
			() -> createSchemaBuildingError("Missing builder for additional child of type `" + additionalChildParameter.constraintType() + "`.")
		);

		// Because we don't have direct access to descriptor of additional child constraint, we assume that additional
		// child parameter has some generic constraint with single creator with single child parameter only
		//noinspection unchecked
		final ConstraintDescriptor additionalChildConstraintDescriptor = ConstraintDescriptorProvider.getConstraint(
			(Class<? extends Constraint<?>>) additionalChildParameter.type()
		);

		final Optional<DataLocator> childDataLocator = resolveChildDataLocator(buildContext, additionalChildParameter.domain());
		// if child data locator is empty, we are missing data for child constraint and we want to skip that parameter
		return childDataLocator.map(dataLocator -> additionalBuilder.get().build(
			dataLocator,
			additionalChildConstraintDescriptor
		));
	}

	/**
	 * Tries to resolve or switch domain of current constraint to desired domain for child constraints.
	 *
	 * @param buildContext       current context with current domain (data locator)
	 * @param desiredChildDomain desired domain for child constraints
	 * @return resolved child data locator or empty if data for the child locator is missing, but it is still logically correct
	 */
	@Nonnull
	protected Optional<DataLocator> resolveChildDataLocator(@Nonnull ConstraintBuildContext buildContext,
	                                                        @Nonnull ConstraintDomain desiredChildDomain) {
		return this.dataLocatorResolver.resolveChildParameterDataLocator(buildContext.dataLocator(), desiredChildDomain);
	}

	/**
	 * Returns field value representing constraint value of constraint with multiple creator value parameters or
	 * combination of value and child parameters, either from cache or newly built one.
	 *
	 * If returns null, parent constraint should be omitted, because there are no valid parameters to specify.
	 */
	@Nonnull
	protected SIMPLE_TYPE obtainWrapperObjectConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                         @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                         @Nonnull List<ChildParameterDescriptor> childParameters,
	                                                         @Nonnull List<AdditionalChildParameterDescriptor> additionalChildParameters,
	                                                         @Nullable ValueTypeSupplier valueTypeSupplier) {
		final WrapperObjectKey wrapperObjectKey = new WrapperObjectKey(
			getConstraintType(),
			buildContext.dataLocator(),
			valueParameters,
			childParameters.stream().collect(Collectors.toMap(
				Function.identity(),
				childParameter -> new AllowedConstraintPredicate(childParameter, this.allowedConstraints, this.forbiddenConstraints)
			)),
			additionalChildParameters.stream().collect(Collectors.toMap(
				Function.identity(),
				additionalChildParameter -> {
					final AtomicReference<? extends ConstraintSchemaBuilder<CTX, SIMPLE_TYPE, OBJECT_TYPE, OBJECT_FIELD>> builder =
						this.additionalBuilders.get(additionalChildParameter.constraintType());
					return new AllowedConstraintPredicate(additionalChildParameter, builder.get().getAllowedConstraints(), builder.get().getForbiddenConstraints());
				}
			))
		);

		// reuse already build wrapper object with same parameters
		final SIMPLE_TYPE cachedWrapperObject = this.sharedContext.getCachedWrapperObject(wrapperObjectKey);
		if (cachedWrapperObject != null) {
			return cachedWrapperObject;
		}

		return buildWrapperObjectConstraintValue(
			buildContext,
			wrapperObjectKey,
			valueParameters,
			childParameters,
			additionalChildParameters,
			valueTypeSupplier
		);
	}

	/**
	 * Builds field value representing constraint value of constraint with multiple creator value parameters or
	 * combination of value and child parameters.
	 * Implementation should cache the built objects for later reuse.
	 *
	 * If returns null, parent constraint should be omitted, because there are no valid parameters to specify.
	 *
	 * <b>Note:</b> this method should not be used directly, instead use {@link #obtainWrapperObjectConstraintValue(ConstraintBuildContext, List, List, List, ValueTypeSupplier)}.
	 */
	@Nonnull
	protected abstract SIMPLE_TYPE buildWrapperObjectConstraintValue(@Nonnull ConstraintBuildContext buildContext,
	                                                                 @Nonnull WrapperObjectKey wrapperObjectKey,
	                                                                 @Nonnull List<ValueParameterDescriptor> valueParameters,
	                                                                 @Nonnull List<ChildParameterDescriptor> childParameters,
	                                                                 @Nonnull List<AdditionalChildParameterDescriptor> additionalChildParameters,
	                                                                 @Nullable ValueTypeSupplier valueTypeSupplier);

	/**
	 * Find attribute schemas for specific data locator.
	 *
	 * TEXT uses searchable attributes in any scope, for both entities and reference bodies.
	 *
	 * @param dataLocator entity or reference whose attributes are requested
	 * @return attribute schemas permitted by the current domain and builder
	 */
	@Nonnull
	protected Collection<? extends AttributeSchemaContract> findAttributeSchemas(@Nonnull DataLocator dataLocator) {
		if (dataLocator.entityTypePointer() instanceof ExternalEntityTypePointer) {
			return Collections.emptyList();
		}
		if (dataLocator instanceof EntityDataLocator || dataLocator instanceof TextDataLocator) {
			return this.sharedContext.getEntitySchemaOrThrowException(dataLocator.entityType())
				.getAttributes()
				.values()
				.stream()
				.filter(attributeSchemaFilter(dataLocator))
				.toList();
		}
		if (dataLocator instanceof final AbstractReferenceDataLocator referenceDataLocator) {
			return ofNullable(referenceDataLocator.referenceName())
				.flatMap(it -> this.sharedContext.getEntitySchemaOrThrowException(referenceDataLocator.entityType()).getReference(it))
				.map(
					reference ->
					     reference.getAttributes()
							.values()
							.stream()
							.filter(attributeSchemaFilter(dataLocator))
							.toList()
				)
				.orElseGet(Collections::emptyList);
		}

		return Collections.emptyList();
	}

	/**
	 * Find sortable attribute compound schemas for specific data locator.
	 */
	@Nonnull
	protected Collection<? extends SortableAttributeCompoundSchemaContract> findSortableAttributeCompoundSchemas(@Nonnull DataLocator dataLocator) {
		if (dataLocator.entityTypePointer() instanceof ExternalEntityTypePointer) {
			return Collections.emptyList();
		}

		if (dataLocator instanceof final EntityDataLocator entityDataLocator) {
			return this.sharedContext.getEntitySchemaOrThrowException(entityDataLocator.entityType())
				.getSortableAttributeCompounds()
				.values()
				.stream()
				.filter(SortableAttributeCompoundSchemaContract::isIndexedInAnyScope)
				.toList();
		}

		return Collections.emptyList();
	}

	/**
	 * Find reference schemas for specific data locator
	 *
	 * @param dataLocator context in which named reference constraints are expanded
	 * @return entity references, or no references when already inside a reference or on an external entity
	 */
	@Nonnull
	protected Collection<ReferenceSchemaContract> findReferenceSchemas(@Nonnull DataLocator dataLocator) {
		if (dataLocator.entityTypePointer() instanceof ExternalEntityTypePointer) {
			return Collections.emptyList();
		}
		if (dataLocator instanceof GenericDataLocator || dataLocator instanceof EntityDataLocator ||
			dataLocator instanceof TextDataLocator) {
			return this.sharedContext
				.getCatalog()
				.getCollectionForEntityOrThrowException(dataLocator.entityType())
				.getSchema()
				.getReferences()
				.values();
		}

		return Collections.emptyList();
	}

	/**
	 * Finds searchable associated data only on managed entity contexts.
	 *
	 * @param dataLocator context whose associated data is requested
	 * @return associated data searchable in any declared scope, or no data on references/external entities
	 */
	@Nonnull
	private Collection<AssociatedDataSchemaContract> findSearchableAssociatedDataSchemas(
		@Nonnull DataLocator dataLocator
	) {
		if (dataLocator instanceof DataLocatorWithReference ||
			dataLocator.entityTypePointer() instanceof ExternalEntityTypePointer) {
			return List.of();
		}
		return this.sharedContext.getEntitySchemaOrThrowException(dataLocator.entityType())
			.getAssociatedData().values().stream()
			.filter(AssociatedDataSchemaContract::isSearchableInAnyScope)
			.toList();
	}

	/**
	 * Selects the attributes offered in the locator's domain. TEXT offers searchable attributes, independently of
	 * filterability and sortability and of the builder; every other domain uses the builder's own predicate.
	 *
	 * @param dataLocator context in which attributes are offered
	 * @return attribute selection predicate for this context
	 */
	@Nonnull
	private Predicate<AttributeSchemaContract> attributeSchemaFilter(@Nonnull DataLocator dataLocator) {
		return dataLocator.targetDomain() == ConstraintDomain.TEXT ?
			AttributeSchemaContract::isSearchableInAnyScope : getAttributeSchemaFilter();
	}

	/**
	 * Discovers direct places once per locator. Entity discovery visits reference bodies, never their targets.
	 *
	 * @param locator managed entity or reference text context
	 * @return direct places, keeping target-reference attributes distinct from addressable overrides
	 */
	@Nonnull
	private ConstraintSchemaBuildingContext.TextPlaces findTextPlaces(@Nonnull DataLocator locator) {
		return this.sharedContext.getTextPlaces(locator, () -> new ConstraintSchemaBuildingContext.TextPlaces(
			!findAttributeSchemas(locator).isEmpty(),
			!findSearchableAssociatedDataSchemas(locator).isEmpty(),
			findReferenceSchemas(locator).stream().anyMatch(reference -> findTextPlaces(
				new ReferenceTextDataLocator(locator.entityTypePointer(), reference.getName())).attributes())
		));
	}

	/**
	 * Decides whether a container may offer the classifier-less variants of a property type, which in TEXT are the
	 * wildcard selectors. The engine refuses a wildcard that selects no searchable place at its level, so in TEXT it
	 * is offered only with a place of its kind. The target container of `entityIn` over a target whose only places are
	 * reference attributes keeps both wildcards: they are its only fields, and dropping them would turn `entityIn`
	 * into the Boolean placeholder. The decision depends only on what the container's cache key holds.
	 *
	 * @param locator context of the container whose children are built
	 * @param allowed child marker and restrictions of that container
	 * @param ownPlace selects the kind of place the wildcard expands to
	 * @return true outside TEXT, otherwise whether the wildcard can select a place or must keep the container
	 */
	private boolean canOfferWildcards(@Nonnull DataLocator locator, @Nonnull AllowedConstraintPredicate allowed,
	                                  @Nonnull Predicate<ConstraintSchemaBuildingContext.TextPlaces> ownPlace) {
		if (locator.targetDomain() != ConstraintDomain.TEXT) {
			return true;
		}
		final ConstraintSchemaBuildingContext.TextPlaces places = findTextPlaces(locator);
		return ownPlace.test(places) ||
			(allowed.getBaseConstraintType() == TextMatchesTargetSpecificationFilterConstraint.class &&
				!places.attributes() && !places.associatedData());
	}

	/**
	 * Tests the same indexed composition transition used by fulltext source discovery, in any declared scope.
	 *
	 * @param locator reference whose target would be searched
	 * @return whether one composition hop to an available managed target is legal
	 */
	private boolean hasEntityTransition(@Nonnull DataLocator locator) {
		if (locator.entityTypePointer() instanceof ExternalEntityTypePointer ||
			!(locator instanceof DataLocatorWithReference referenceLocator) ||
			referenceLocator.referenceName() == null) {
			return false;
		}
		final ReferenceSchemaContract reference = this.sharedContext
			.getEntitySchemaOrThrowException(locator.entityType())
			.getReferenceOrThrowException(referenceLocator.referenceName());
		return reference.isReferencedEntityTypeManaged() &&
			(!(reference instanceof ReflectedReferenceSchemaContract reflected) ||
				reflected.isReflectedReferenceAvailable()) &&
			this.sharedContext.getEntitySchema(reference.getReferencedEntityType()).isPresent() &&
			reference.getSearchableThroughInScopes().entrySet().stream().anyMatch(entry ->
				entry.getValue() == ReferenceSearchMode.COMPOSITION && reference.isIndexedInScope(entry.getKey()) &&
					reference.getIndexedComponents(entry.getKey())
						.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
	}

	/**
	 * Checks marker-specific searchable places, following at most one composition hop. Direct metadata and eligibility
	 * are cached in the shared build context, so converging reference paths do not rediscover target schemas.
	 * Target filters include reference bodies through their empty form; target weights require addressable children.
	 *
	 * @param locator entity or reference text context
	 * @param allowed child marker and annotation restrictions of the container being offered
	 * @return whether the container can search or address an eligible place
	 */
	private boolean hasSearchablePlace(@Nonnull DataLocator locator, @Nonnull AllowedConstraintPredicate allowed) {
		if (locator.entityTypePointer() instanceof ExternalEntityTypePointer) {
			return false;
		}
		return this.sharedContext.hasTextPlace(new ContainerKey(getConstraintType(), locator, allowed), () -> {
			final ConstraintSchemaBuildingContext.TextPlaces places = findTextPlaces(locator);
			if (places.attributes() && (allows(allowed, AttributeIn.class) || allows(allowed, AttributeWeight.class)) ||
				places.associatedData() && (allows(allowed, AssociatedDataIn.class) ||
					allows(allowed, AssociatedDataWeight.class)) ||
				places.referenceAttributes() &&
					allowed.getBaseConstraintType() == TextMatchesTargetSpecificationFilterConstraint.class) {
				return true;
			}
			if (locator instanceof DataLocatorWithReference referenceLocator) {
				if ((!allows(allowed, EntityIn.class) && !allows(allowed, EntityWeight.class)) ||
					!hasEntityTransition(locator)) {
					return false;
				}
				final ReferenceSchemaContract reference = this.sharedContext
					.getEntitySchemaOrThrowException(locator.entityType())
					.getReferenceOrThrowException(referenceLocator.referenceName());
				return hasSearchablePlace(
					new TextDataLocator(new ManagedEntityTypePointer(reference.getReferencedEntityType())),
					textChildren(allows(allowed, EntityIn.class) ? EntityIn.class : EntityWeight.class));
			}
			// default relevance needs no addressable weight, only a place `textMatches` could search here
			final boolean defaultRelevance =
				allowed.getBaseConstraintType() == RelevanceTextEntitySpecificationOrderConstraint.class;
			if (defaultRelevance) {
				return hasSearchablePlace(locator, textChildren(TextMatches.class));
			}
			if (!allows(allowed, ReferenceIn.class) && !allows(allowed, ReferenceWeight.class)) {
				return false;
			}
			final AllowedConstraintPredicate referenceChildren = textChildren(
				allows(allowed, ReferenceIn.class) ? ReferenceIn.class : ReferenceWeight.class);
			return findReferenceSchemas(locator).stream().anyMatch(reference -> hasSearchablePlace(
				new ReferenceTextDataLocator(locator.entityTypePointer(), reference.getName()), referenceChildren));
		});
	}

	/**
	 * Tests a registered fulltext variant against a container's child restrictions.
	 *
	 * @param allowed marker and annotation restrictions
	 * @param constraint registered fulltext constraint class
	 * @return whether the child is permitted
	 */
	private boolean allows(@Nonnull AllowedConstraintPredicate allowed,
	                       @Nonnull Class<? extends Constraint<?>> constraint) {
		return ConstraintDescriptorProvider.getConstraints(constraint).stream().anyMatch(allowed);
	}

	/**
	 * Resolves child restrictions from the registered container's creator.
	 *
	 * @param constraint fulltext container with one TEXT child parameter
	 * @return child marker and its local restrictions
	 */
	@Nonnull
	private AllowedConstraintPredicate textChildren(@Nonnull Class<? extends Constraint<?>> constraint) {
		return new AllowedConstraintPredicate(ConstraintDescriptorProvider.getConstraint(constraint)
			.creator().childParameters().get(0), this.allowedConstraints, this.forbiddenConstraints);
	}

	/**
	 * Decides if this constraint descriptor should be used and field created from it.
	 * This should contain only generic checks, it shouldn't contain any checks specific to e.g. property type.
	 *
	 * Constraints with TEXT children are suppressed before creating their child types when no searchable place exists.
	 *
	 * @param buildContext resolved context of the descriptor whose field is considered
	 * @param constraintDescriptor constraint variant whose children and values are inspected
	 * @return whether the field can be represented in this context
	 */
	protected boolean canFieldBeCreatedFromConstraintDescriptor(@Nonnull ConstraintBuildContext buildContext,
	                                                            @Nonnull ConstraintDescriptor constraintDescriptor) {
		for (final ChildParameterDescriptor child : constraintDescriptor.creator().childParameters()) {
			if (child.domain() == ConstraintDomain.TEXT &&
				!this.dataLocatorResolver.resolveChildParameterDataLocator(buildContext.dataLocator(), child.domain())
					.map(locator -> hasSearchablePlace(locator,
						new AllowedConstraintPredicate(child, this.allowedConstraints, this.forbiddenConstraints)))
					.orElse(false)) {
				return false;
			}
		}
		final boolean isLocalized = constraintDescriptor.creator().valueParameters()
			.stream()
			.anyMatch(p -> {
				final Class<? extends Serializable> parameterType = p.type();
				return parameterType.equals(Locale.class) ||
					(parameterType.isArray() && parameterType.getComponentType().equals(Locale.class));
			});
		final boolean localesPresent = !(buildContext.dataLocator() instanceof DataLocatorWithReference) &&
			this.sharedContext.getEntitySchema(buildContext.dataLocator().entityType())
				.map(schema -> !schema.getLocales().isEmpty())
				.orElse(false);

		return !isLocalized || localesPresent;
	}

	/**
	 * Returns data type of range ends supported by specific range.
	 */
	@Nonnull
	protected Class<? extends Serializable> resolveRangeSupportedType(@Nonnull Class<? extends Range<?>> rangeType) {
		if (DateTimeRange.class.equals(rangeType)) {
			return OffsetDateTime.class;
		} else if (BigDecimalNumberRange.class.equals(rangeType)) {
			return BigDecimal.class;
		} else if (ByteNumberRange.class.equals(rangeType)) {
			return Byte.class;
		} else if (ShortNumberRange.class.equals(rangeType)) {
			return Short.class;
		} else if (IntegerNumberRange.class.equals(rangeType)) {
			return Integer.class;
		} else if (LongNumberRange.class.equals(rangeType)) {
			return Long.class;
		} else {
			throw createSchemaBuildingError("Unsupported range `" + rangeType.getName() + "`.");
		}
	}

	/**
	 * Checks if Evita's data type is pseudo generic i.e. it is a {@link java.io.Serializable} interface or is array
	 * of pseudo generic types. {@link Serializable} is common ancestor for all Evita data types.
	 */
	protected boolean isJavaTypeGeneric(@Nonnull Class<?> javaType) {
		final Class<?> componentType = javaType.isArray() ? javaType.getComponentType() : javaType;
		return componentType.equals(Serializable.class) || componentType.equals(Comparable.class);
	}

	/**
	 * Checks if Evita's data type is enum, or it is array of enums.
	 */
	protected boolean isJavaTypeEnum(@Nonnull Class<?> javaType) {
		return javaType.isEnum() || (javaType.isArray() && javaType.getComponentType().isEnum());
	}

	@Nonnull
	protected String constructContainerName(@Nonnull ContainerKey containerKey) {
		return getContainerObjectTypeName() + containerKey.toHash();
	}

	@Nonnull
	protected String constructWrapperObjectName(@Nonnull WrapperObjectKey wrapperObjectKey) {
		return getWrapperObjectObjectTypeName() + wrapperObjectKey.toHash();
	}

	/**
	 * Constructs full constraint description that functions as short documentation. It contains short description
	 * and link to full documentation.
	 */
	@Nonnull
	protected String constructConstraintDescription(@Nonnull ConstraintDescriptor constraintDescriptor) {
		final String deprecationPrefix = constraintDescriptor.deprecated() != null
			? "**Deprecated:** " + constraintDescriptor.deprecated() + " "
			: "";
		return deprecationPrefix + constraintDescriptor.shortDescription() + " [Check detailed documentation](" + constraintDescriptor.userDocsLink() + ")";
	}

	/**
	 * Creates API-specific schema building error.
	 */
	protected abstract <T extends ExternalApiInternalError> T createSchemaBuildingError(@Nonnull String message);

	/**
	 * Used to define way how to transform constraint descriptor to API equivalent field.
	 */
	@FunctionalInterface
	protected interface FieldFromConstraintDescriptorBuilder<FST> extends Function<ConstraintDescriptor, FST> {
	}

	/**
	 * Used to resolve actual value parameter data type based on that parameter.
	 */
	@FunctionalInterface
	protected interface ValueTypeSupplier extends Function<ValueParameterDescriptor, Class<?>> {
	}
}
