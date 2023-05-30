/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.performance.generators;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.FacetGroupsConjunction;
import io.evitadb.api.query.require.FacetGroupsDisjunction;
import io.evitadb.api.query.require.FacetGroupsNegation;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.key.CompressiblePriceKey;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintBuildContext;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintKeyBuilder;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.graphql.api.dataType.coercing.AnyCoercing;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.performance.generators.RandomQueryGenerator.extractFacetIds;
import static java.util.Optional.ofNullable;

/**
 * This interface contains methods that allow generation of random filtering constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface GraphQLRandomQueryGenerator {
	GraphQLConstraint[] EMPTY_HSFC_ARRAY = new GraphQLConstraint[0];
	AnyCoercing VALUE_CONVERTER = new AnyCoercing();

	/**
	 * Returns random locale from the set of available locales.
	 */
	private static Locale getRandomExistingLocale(@Nonnull EntitySchemaContract schema, @Nonnull Random random) {
		return schema
			.getLocales()
			.stream()
			.skip(random.nextInt(schema.getLocales().size()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No locales found!"));
	}

	/**
	 * Returns random element from the set.
	 */
	@Nonnull
	private static <T> T pickRandom(@Nonnull Random random, @Nonnull Set<T> theSet) {
		Assert.isTrue(theSet.size() >= 1, "There are no values to choose from!");
		final int index = theSet.size() == 1 ? 0 : random.nextInt(theSet.size() - 1) + 1;
		final Iterator<T> it = theSet.iterator();
		for (int i = 0; i < index; i++) {
			it.next();
		}
		return it.next();
	}

	@Nonnull
	private static AttributeStatistics pickRandom(@Nonnull Random random, @Nonnull Map<String, AttributeStatistics> filterableAttributes) {
		final int index = random.nextInt(filterableAttributes.size() - 1) + 1;
		final Iterator<AttributeStatistics> it = filterableAttributes.values().iterator();
		for (int i = 0; i < index; i++) {
			it.next();
		}
		return it.next();
	}

	private static OffsetDateTime getRandomOffsetDateTimeBetween(@Nonnull Random random, @Nonnull Statistics statistics) {
		final DateTimeRange min = (DateTimeRange) statistics.getMinimalValue();
		final DateTimeRange max = (DateTimeRange) statistics.getMaximalValue();
		final long from = min.getPreciseFrom() == null ? min.getTo() : min.getFrom();
		final long to = max.getPreciseTo() == null ? max.getFrom() : max.getTo();
		final long inBetween = Math.round(from + random.nextDouble() * (to - from));
		final Instant instant = Instant.ofEpochMilli(inBetween);
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	/**
	 * Method gathers statistics about faceted reference ids in processed dataset. This information is necessary to
	 * generate random query using {@link #generateRandomFacetQuery(Random, EntitySchemaContract, Map)} method.
	 */
	default void updateFacetStatistics(@Nonnull SealedEntity entity, @Nonnull Map<String, Set<Integer>> facetedReferences, @Nonnull Map<String, Map<Integer, Integer>> facetGroupsIndex) {
		entity.getReferences()
			.stream()
			.filter(it -> facetedReferences.containsKey(it.getReferenceName()))
			.filter(it -> it.getReferenceSchemaOrThrow().isFaceted())
			.forEach(it -> {
				facetedReferences.get(it.getReferenceName()).add(it.getReferencedPrimaryKey());
				it.getGroup().ifPresent(group -> {
					facetGroupsIndex.get(it.getReferenceName())
						.put(it.getReferencedPrimaryKey(), group.getPrimaryKey());
				});
			});
	}

	/**
	 * Method gathers statistics about attribute values in processed dataset. This information is necessary to generate
	 * random query using {@link #generateRandomAttributeQuery(Random, EntitySchemaContract, Map, Set)} method.
	 */
	default void updateAttributeStatistics(@Nonnull SealedEntity entity, @Nonnull Random random, @Nonnull Map<String, AttributeStatistics> filterableAttributes) {
		for (Entry<String, AttributeStatistics> entry : filterableAttributes.entrySet()) {
			final String attributeName = entry.getKey();
			final AttributeStatistics statistics = entry.getValue();
			if (statistics.isLocalized()) {
				for (Locale locale : entity.getAttributeLocales()) {
					entity.getAttributeValue(attributeName, locale).ifPresent(localizedAttributeValue -> {
						if (localizedAttributeValue.getValue() instanceof Object[]) {
							final Serializable[] valueArray = (Serializable[]) localizedAttributeValue.getValue();
							for (Serializable valueItem : valueArray) {
								statistics.updateValue(valueItem, locale, random);
							}
						} else {
							statistics.updateValue(localizedAttributeValue.getValue(), locale, random);
						}
					});
				}
			} else {
				entity.getAttributeValue(attributeName).ifPresent(attributeValue -> {
					if (attributeValue.getValue() instanceof Object[]) {
						final Serializable[] valueArray = (Serializable[]) attributeValue.getValue();
						for (Serializable valueItem : valueArray) {
							statistics.updateValue(valueItem, random);
						}
					} else {
						statistics.updateValue(attributeValue.getValue(), random);
					}
				});
			}
		}
	}

	/**
	 * Method gathers statistics about price values in processed dataset. This information is necessary to generate
	 * random query using {@link #generateRandomPriceQuery(Random, EntitySchemaContract, GlobalPriceStatistics)} method.
	 */
	default void updatePriceStatistics(@Nonnull SealedEntity entity, @Nonnull Random random, @Nonnull GlobalPriceStatistics priceStats) {
		for (PriceContract price : entity.getPrices()) {
			priceStats.updateValue(price, random);
		}
	}

	/**
	 * Creates randomized query for passed entity schema based on passed statistics about `filteringAttributes` and
	 * `sortableAttributes`.
	 */
	default GraphQLQuery generateRandomAttributeQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull Map<String, AttributeStatistics> filterableAttributes, @Nonnull Set<String> sortableAttributes) {
		final Locale randomExistingLocale = getRandomExistingLocale(schema, random);

		return new GraphQLQuery(
			null,
			schema.getName(),
			new GraphQLConstraint(
				And.class,
				createRandomAttributeFilterBy(random, randomExistingLocale, filterableAttributes),
				new GraphQLConstraint(EntityLocaleEquals.class, randomExistingLocale)
			),
			createRandomAttributeOrderBy(random, sortableAttributes),
			new GraphQLConstraint[0],
			random.nextInt(5) + 1,
			20,
			new String[0]
		);
	}

	/**
	 * Method generates random attribute histogram requirement and adds it to the passed `existingQuery`.
	 */
	default GraphQLQuery generateRandomAttributeHistogramQuery(@Nonnull GraphQLQuery existingQuery, @Nonnull Random random, @Nonnull Set<String> numericFilterableAttributes) {
		Assert.isTrue(numericFilterableAttributes.size() >= 1, "There are no numeric attributes!");
		final int histogramCount = numericFilterableAttributes.size() == 1 ? 1 : 1 + random.nextInt(numericFilterableAttributes.size() - 1);
		final String[] attributes = new String[histogramCount];
		final Set<String> alreadySelected = new HashSet<>(histogramCount);
		for (int i = 0; i < histogramCount; i++) {
			String attribute = pickRandom(random, numericFilterableAttributes);
			while (alreadySelected.contains(attribute)) {
				attribute = pickRandom(random, numericFilterableAttributes);
			}
			attributes[i] = attribute;
			alreadySelected.add(attribute);
		}

		return new GraphQLQuery(
			null,
			existingQuery.collectionName(),
			existingQuery.filterBy(),
			existingQuery.orderBy(),
			new GraphQLConstraint[0],
			existingQuery.pageNumber(),
			existingQuery.pageSize(),
			ArrayUtils.mergeArrays(
				existingQuery.extraResultFields(),
				new String[] {
					String.format(
						"""
	                    attributeHistogram {
	                        %s
	                    }
						""",
						Arrays.stream(attributes)
							.map(it -> String.format(
								"""
								%s {
									min
			                        max
			                        overallCount
			                        buckets(requestedCount: %d) {
			                            index
			                            threshold
			                            occurrences
			                        }
								}
								""",
								StringUtils.toCamelCase(it),
								10 + random.nextInt(20)
							))
							.collect(Collectors.joining("\n"))
					)
				}
			)
		);
	}

	/**
	 * Creates randomized query for passed entity schema based on passed statistics about `filteringAttributes` and
	 * `sortableAttributes`.
	 */
	default GraphQLQuery generateRandomPriceQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull GlobalPriceStatistics priceStats) {
		final Locale randomExistingLocale = getRandomExistingLocale(schema, random);

		return new GraphQLQuery(
			null,
			schema.getName(),
			new GraphQLConstraint(
				And.class,
				createRandomPriceFilterBy(random, priceStats, schema.getIndexedPricePlaces()),
				new GraphQLConstraint(EntityLocaleEquals.class, randomExistingLocale)
			),
			createRandomPriceOrderBy(random),
			new GraphQLConstraint[0],
			random.nextInt(5) + 1,
			20,
			new String[0]
		);
	}

	/**
	 * Method generates random hierarchy filtering query and adds it to the passed `existingQuery`. While new
	 * query is created `categoryIds` collection is used to retrieve random category id specification.
	 */
	default GraphQLQuery generateRandomHierarchyQuery(@Nonnull GraphQLQuery existingQuery, @Nonnull Random random, @Nonnull List<Integer> categoryIds, @Nonnull String hierarchyEntityType) {
		final GraphQLConstraint hierarchyConstraint;
		final List<GraphQLConstraint> specification = new ArrayList<>();
		final int rndKey = Math.abs(random.nextInt()) + 1;
		final Integer[] excludedIds;
		if (rndKey % 5 == 0) {
			excludedIds = new Integer[5];
			for (int i = 0; i < 5; i++) {
				excludedIds[i] = categoryIds.get(Math.abs(rndKey * (i + 1)) % (categoryIds.size()));
			}
			specification.add(new GraphQLConstraint(
				HierarchyExcluding.class,
				new GraphQLConstraint(EntityPrimaryKeyInSet.class, excludedIds)
			));
		} else {
			excludedIds = null;
		}
		if (rndKey % 3 == 0) {
			specification.add(new GraphQLConstraint(HierarchyExcludingRoot.class, true));
		} else if (rndKey % 7 == 0 && excludedIds == null) {
			specification.add(new GraphQLConstraint(HierarchyDirectRelation.class, true));
		}
		final int parentId = categoryIds.get(rndKey % categoryIds.size());
		hierarchyConstraint = new GraphQLConstraint(
			hierarchyEntityType,
			HierarchyWithin.class,
			Map.of(
				"ofParent",
				new GraphQLConstraint(EntityPrimaryKeyInSet.class, parentId),
				"with",
				specification.toArray(EMPTY_HSFC_ARRAY)
			)
		);

		return new GraphQLQuery(
			null,
			existingQuery.collectionName(),
			new GraphQLConstraint(
				And.class,
				hierarchyConstraint,
				existingQuery.filterBy()
			),
			existingQuery.orderBy(),
			new GraphQLConstraint[0],
			existingQuery.pageNumber(),
			existingQuery.pageSize(),
			existingQuery.extraResultFields()
		);
	}

	/**
	 * Method generates random price histogram requirement and adds it to the passed `existingQuery`.
	 */
	default GraphQLQuery generateRandomPriceHistogramQuery(@Nonnull GraphQLQuery existingQuery, @Nonnull Random random) {
		return new GraphQLQuery(
			null,
			existingQuery.collectionName(),
			existingQuery.filterBy(),
			existingQuery.orderBy(),
			new GraphQLConstraint[0],
			existingQuery.pageNumber(),
			existingQuery.pageSize(),
			ArrayUtils.mergeArrays(
				existingQuery.extraResultFields(),
				new String[] {
					String.format(
						"""
	                    priceHistogram {
	                        min
	                        max
	                        overallCount
	                        buckets(requestedCount: %d) {
	                            index
	                            threshold
	                            occurrences
	                        }
	                    }
						""",
						10 + random.nextInt(20)
					)
				}
			)
		);
	}

	/**
	 * Creates randomized query for passed entity schema based on passed statistics about faceted `references`.
	 */
	default GraphQLQuery generateRandomFacetQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull Map<String, Set<Integer>> facetedReferences) {
		final int facetCount = random.nextInt(5) + 1;
		final Map<String, Set<Integer>> selectedFacets = new HashMap<>();
		for (int i = 0; i < facetCount; i++) {
			final String randomFacetType = getRandomItem(random, facetedReferences.keySet());
			final Integer randomFacetId = getRandomItem(random, facetedReferences.get(randomFacetType));
			selectedFacets.computeIfAbsent(randomFacetType, fType -> new HashSet<>()).add(randomFacetId);
		}

		return new GraphQLQuery(
			Query.query(
				collection(schema.getName()),
				filterBy(
					userFilter(
						selectedFacets.entrySet()
							.stream()
							.map(it -> facetHaving(it.getKey(), entityPrimaryKeyInSet(it.getValue().toArray(new Integer[0]))))
							.toArray(FilterConstraint[]::new)
					)
				)
			),
			schema.getName(),
			new GraphQLConstraint(
				UserFilter.class,
				selectedFacets.entrySet()
					.stream()
					.map(it -> new GraphQLConstraint(it.getKey(), FacetHaving.class, new GraphQLConstraint(EntityPrimaryKeyInSet.class, it.getValue().toArray(new Integer[0]))))
					.toArray(GraphQLConstraint[]::new)
			),
			null,
			new GraphQLConstraint[0],
			random.nextInt(5) + 1,
			20,
			new String[0]
		);
	}

	/**
	 * Creates randomized query requiring {@link Hierarchy} computation for
	 * passed entity schema based on passed set.
	 */
	default GraphQLQuery generateRandomHierarchyQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull Set<String> referencedHierarchyEntities) {
		return new GraphQLQuery(
			null,
			schema.getName(),
			new GraphQLConstraint(
				EntityLocaleEquals.class,
				getRandomExistingLocale(schema, random)
			),
			null,
			new GraphQLConstraint[0],
			1,
			20,
			new String[] {
				String.format(
					"""
                    hierarchy {
                        %s
                    }
					""",
					Optional.of(pickRandom(random, referencedHierarchyEntities))
						.map(it -> String.format(
							"""
                            %s {
                                megaMenu: fromRoot {
	                                parentPrimaryKey
                                    level
	                                entity {
	                                    primaryKey
	                                }
	                                hasChildren
                                }
                            }
							""",
							StringUtils.toCamelCase(it)
						))
						.orElseThrow()
				)
			}
		);
	}

	/**
	 * Updates randomized query adding a request to facet summary computation juggling inter facet relations.
	 */
	default GraphQLQuery generateRandomFacetSummaryQuery(@Nonnull GraphQLQuery existingQuery, @Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull FacetStatisticsDepth depth, @Nonnull Map<String, Map<Integer, Integer>> facetGroupsIndex) {
		final List<FilterConstraint> facetFilters = FinderVisitor.findConstraints(existingQuery.originalQuery().getFilterBy(), FacetHaving.class::isInstance);
		final List<GraphQLConstraint> requireConstraints = new LinkedList<>();
		for (FilterConstraint facetFilter : facetFilters) {
			final FacetHaving facetHaving = (FacetHaving) facetFilter;
			final int dice = random.nextInt(4);
			final Map<Integer, Integer> entityTypeGroups = facetGroupsIndex.get(facetHaving.getReferenceName());
			final Set<Integer> groupIds = Arrays.stream(extractFacetIds(facetHaving))
				.mapToObj(entityTypeGroups::get)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
			if (!groupIds.isEmpty()) {
				if (dice == 1) {
					requireConstraints.add(
						new GraphQLConstraint(
							facetHaving.getReferenceName(),
							FacetGroupsConjunction.class,
							new GraphQLConstraint(EntityPrimaryKeyInSet.class, getRandomItem(random, groupIds))
						)
					);
				} else if (dice == 2) {
					requireConstraints.add(
						new GraphQLConstraint(
							facetHaving.getReferenceName(),
							FacetGroupsDisjunction.class,
							new GraphQLConstraint(EntityPrimaryKeyInSet.class, getRandomItem(random, groupIds))
						)
					);
				} else if (dice == 3) {
					requireConstraints.add(
						new GraphQLConstraint(
							facetHaving.getReferenceName(),
							FacetGroupsNegation.class,
							new GraphQLConstraint(EntityPrimaryKeyInSet.class, getRandomItem(random, groupIds))
						)
					);
				}
			}
		}

		return new GraphQLQuery(
			null,
			existingQuery.collectionName(),
			existingQuery.filterBy(),
			existingQuery.orderBy(),
			requireConstraints.toArray(GraphQLConstraint[]::new),
			random.nextInt(5) + 1,
			20,
			ArrayUtils.mergeArrays(
				existingQuery.extraResultFields(),
				new String[] {
					String.format(
						"""
                        facetSummary {
                            %s
                        }
						""",
						schema.getReferences()
							.values()
							.stream()
							.filter(ReferenceSchemaContract::isFaceted)
							.map(it ->
								String.format(
									"""
									%s {
									    groupEntity {
									        primaryKey
									        type
									    }
									    facetStatistics {
									        facetEntity {
									            primaryKey
									            type
									        }
									        requested
									        %s
									    }
									}
									""",
									it.getNameVariant(NamingConvention.CAMEL_CASE),
									depth == FacetStatisticsDepth.COUNTS ?
										"count" :
										"""
										impact {
											difference
											matchCount
											hasSense
										}
										"""
								)
							)
							.collect(Collectors.joining("\n"))
					)
				}
			)
		);
	}

	/**
	 * Returns random item from passed collection.
	 */
	default <T> T getRandomItem(@Nonnull Random random, @Nonnull Collection<T> collection) {
		final int position = random.nextInt(collection.size());
		final Iterator<T> it = collection.iterator();
		int i = 0;
		while (it.hasNext()) {
			final T next = it.next();
			if (i++ == position) {
				return next;
			}
		}
		throw new EvitaInternalError("Should not ever happen!");
	}

	/**
	 * Returns random items from passed collection.
	 */
	default <T> Set<T> getRandomItems(@Nonnull Random random, @Nonnull Collection<T> collection) {
		final Set<T> result = new LinkedHashSet<>();
		for (T next : collection) {
			if (random.nextBoolean()) {
				result.add(next);
			}
		}
		if (result.isEmpty()) {
			result.add(collection.iterator().next());
		}
		return result;
	}

	/**
	 * Creates randomized filter query that targets existing attribute. It picks random attribute from the
	 * `filterableAttributes` and creates one of the following constraints based on the attribute type:
	 *
	 * - isNull
	 * - isNotNull
	 * - eq
	 * - inSet
	 * - isTrue
	 * - isFalse
	 * - greaterThan
	 * - lesserThan
	 * - inRange
	 * - between
	 */
	@Nonnull
	default GraphQLConstraint createRandomAttributeFilterBy(@Nonnull Random random, @Nonnull Locale locale, @Nonnull Map<String, AttributeStatistics> filterableAttributes) {
		final AttributeStatistics attribute = pickRandom(random, filterableAttributes);
		if (String.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			return switch (random.nextInt(3)) {
				case 0 -> new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
				case 1 -> new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
				case 2 -> statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL.name()) : new GraphQLConstraint(attribute.getName(), AttributeEquals.class, (Object) statistics.getSomeValue(random));
				case 3 -> statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL.name()) : new GraphQLConstraint(attribute.getName(), AttributeInSet.class, statistics.getSomeValue(random), statistics.getSomeValue(random));
				default -> new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
			};
		} else if (Boolean.class.equals(attribute.getType())) {
			return switch (random.nextInt(3)) {
				case 0 -> new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
				case 1 -> new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
				case 2 -> new GraphQLConstraint(attribute.getName(), AttributeEquals.class, true);
				case 3 -> new GraphQLConstraint(attribute.getName(), AttributeEquals.class, false);
				default -> new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
			};
		} else if (Long.class.equals(attribute.getType()) && attribute.isArray()) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(5)) {
				case 0:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
				case 1:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
				case 2:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeEquals.class, statistics.getSomeValue(random));
				case 3:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeInSet.class, statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4: {
					if (statistics == null) {
						return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
					}
					final Long first = statistics.getSomeValue(random);
					final Long second = statistics.getSomeValue(random);
					return new GraphQLConstraint(attribute.getName(), AttributeBetween.class, first < second ? first : second, first < second ? second : first);
				}
				default:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
			}
		} else if (Long.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(7)) {
				case 0:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
				case 1:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
				case 2:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeEquals.class, statistics.getSomeValue(random));
				case 3:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeInSet.class, statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeGreaterThan.class, statistics.getSomeValue(random));
				case 5:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeLessThan.class, statistics.getSomeValue(random));
				case 6: {
					if (statistics == null) {
						return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
					}
					final Long first = statistics.getSomeValue(random);
					final Long second = statistics.getSomeValue(random);
					return new GraphQLConstraint(attribute.getName(), AttributeBetween.class, first < second ? first : second, first < second ? second : first);
				}
				default:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
			}
		} else if (BigDecimal.class.equals(attribute.getType()) && attribute.isArray()) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(5)) {
				case 0:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
				case 1:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
				case 2:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeEquals.class, statistics.getSomeValue(random));
				case 3:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeInSet.class, statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4: {
					if (statistics == null) {
						return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
					}
					final BigDecimal first = statistics.getSomeValue(random);
					final BigDecimal second = statistics.getSomeValue(random);
					return new GraphQLConstraint(attribute.getName(), AttributeBetween.class, first.compareTo(second) < 0 ? first : second, first.compareTo(second) < 0 ? second : first);
				}
				default:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
			}
		} else if (BigDecimal.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(7)) {
				case 0:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
				case 1:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
				case 2:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeEquals.class, statistics.getSomeValue(random));
				case 3:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeInSet.class, statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeGreaterThan.class, statistics.getSomeValue(random));
				case 5:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeLessThan.class, statistics.getSomeValue(random));
				case 6: {
					if (statistics == null) {
						return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
					}
					final BigDecimal first = statistics.getSomeValue(random);
					final BigDecimal second = statistics.getSomeValue(random);
					return new GraphQLConstraint(attribute.getName(), AttributeBetween.class, first.compareTo(second) < 0 ? first : second, first.compareTo(second) < 0 ? second : first);
				}
				default:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
			}
		} else if (DateTimeRange.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(3)) {
				case 0:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
				case 1:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
				case 2:
					return statistics == null ? new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL) : new GraphQLConstraint(attribute.getName(), AttributeInRange.class, getRandomOffsetDateTimeBetween(random, statistics));
				case 3: {
					if (statistics == null) {
						return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
					}
					final OffsetDateTime first = getRandomOffsetDateTimeBetween(random, statistics);
					final OffsetDateTime second = getRandomOffsetDateTimeBetween(random, statistics);
					return new GraphQLConstraint(attribute.getName(), AttributeBetween.class, first.isBefore(second) ? first : second, first.isBefore(second) ? second : first);
				}
				default:
					return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NULL);
			}
		} else {
			return new GraphQLConstraint(attribute.getName(), AttributeIs.class, AttributeSpecialValue.NOT_NULL);
		}
	}

	/**
	 * Creates randomized filter by query for prices. Filter by always contain filter for:
	 *
	 * - random currency
	 * - the biggest price list + random number of additional price lists in that currency
	 * - random validity query
	 *
	 * In 40% of cases also `priceBetween` query that further limits the output results.
	 */
	@Nonnull
	default GraphQLConstraint createRandomPriceFilterBy(@Nonnull Random random, @Nonnull GlobalPriceStatistics priceStatistics, int decimalPlaces) {
		final int queryType = random.nextInt(10);
		final Currency currency = priceStatistics.pickRandomCurrency(random);
		final String biggestPriceList = priceStatistics.getBiggestPriceListFor(currency);
		final String[] additionalPriceLists = priceStatistics.pickRandomPriceLists(random, random.nextInt(6), currency, biggestPriceList);
		final String[] priceLists = ArrayUtils.mergeArrays(additionalPriceLists, new String[]{biggestPriceList});
		final OffsetDateTime validIn = priceStatistics.pickRandomDateTimeFor(currency, priceLists, random);
		if (queryType < 4) {
			final BigDecimal from = priceStatistics.pickRandomValue(currency, priceLists, random, decimalPlaces);
			final BigDecimal to = priceStatistics.pickRandomValue(currency, priceLists, random, decimalPlaces);
			// query prices with price between
			final int fromLesserThanTo = from.compareTo(to);
			return new GraphQLConstraint(And.class,
				new GraphQLConstraint(PriceInCurrency.class, currency),
				new GraphQLConstraint(PriceInPriceLists.class, priceLists),
				new GraphQLConstraint(PriceValidIn.class, validIn),
				new GraphQLConstraint(PriceBetween.class, fromLesserThanTo < 0 ? from : to, fromLesserThanTo < 0 ? to : from)
			);
		} else {
			// query prices with currency, price lists and validity
			return new GraphQLConstraint(And.class,
				new GraphQLConstraint(PriceInCurrency.class, currency),
				new GraphQLConstraint(PriceInPriceLists.class, priceLists),
				new GraphQLConstraint(PriceValidIn.class, validIn)
			);
		}
	}

	/**
	 * Creates randomized order query that targets existing attribute. It picks random attribute from the
	 * `sortableAttributes` and creates ascending or descending order.
	 */
	@Nonnull
	default GraphQLConstraint createRandomAttributeOrderBy(@Nonnull Random random, @Nonnull Set<String> sortableAttributes) {
		final GraphQLConstraint randomOrderBy;
		if (random.nextBoolean()) {
			randomOrderBy = new GraphQLConstraint(pickRandom(random, sortableAttributes), AttributeNatural.class, ASC);
		} else {
			randomOrderBy = new GraphQLConstraint(pickRandom(random, sortableAttributes), AttributeNatural.class, DESC);
		}
		return randomOrderBy;
	}

	/**
	 * Creates randomized order query that targets prices. In 33% it returns ordering by price asc, 33% desc and
	 * for 33% of cases no ordering.
	 */
	@Nullable
	default GraphQLConstraint createRandomPriceOrderBy(@Nonnull Random random) {
		final int selectedType = random.nextInt(3);
		return switch (selectedType) {
			case 0 -> new GraphQLConstraint(PriceNatural.class, ASC);
			case 1 -> new GraphQLConstraint(PriceNatural.class, DESC);
			default -> null;
		};
	}

	/**
	 * This class contains statistical information about attribute data that is necessary to create valid queries.
	 * This DTO is locale / global attribute ambiguous - it aggregates all locale specific or global attributes.
	 */
	class AttributeStatistics {
		/**
		 * Holds name of the attribute.
		 */
		@Getter private final String name;
		/**
		 * Holds type of the attribute.
		 */
		@Getter private final Class<? extends Serializable> type;
		/**
		 * Holds true if type represents array.
		 */
		@Getter private final boolean array;
		/**
		 * Holds true if attribute is locale specific.
		 */
		@Getter private final boolean localized;
		/**
		 * Holds global statistics (only if attribute is not localized).
		 */
		private Statistics global;
		/**
		 * Holds local specific statistics (only if attribute is localized).
		 */
		private Map<Locale, Statistics> localeSpecific;

		public AttributeStatistics(@Nonnull AttributeSchemaContract attributeSchema) {
			this.name = attributeSchema.getName();
			final Class<? extends Serializable> theType = attributeSchema.getType();
			//noinspection unchecked
			this.type = theType.isArray() ? (Class<? extends Serializable>) theType.getComponentType() : theType;
			this.array = theType.isArray();
			this.localized = attributeSchema.isLocalized();
		}

		/**
		 * Records a value encountered in the dataset.
		 */
		public void updateValue(@Nonnull Serializable value, @Nonnull Random random) {
			Assert.isTrue(!this.localized, "Attribute is localized by schema!");
			if (global == null) {
				global = new Statistics(value);
			} else {
				global.update(value, random);
			}
		}

		/**
		 * Records a value encountered in the dataset.
		 */
		public void updateValue(@Nonnull Serializable localizedValue, @Nonnull Locale locale, @Nonnull Random random) {
			Assert.isTrue(this.localized, "Attribute is not localized by schema!");
			if (localeSpecific == null) {
				localeSpecific = new HashMap<>();
			}
			final Statistics statistics = localeSpecific.get(locale);
			if (statistics == null) {
				localeSpecific.put(locale, new Statistics(localizedValue));
			} else {
				statistics.update(localizedValue, random);
			}
		}

		/**
		 * Returns statistics for the passed locale or global statistics if there are no locale specific statistics
		 * found.
		 */
		public Statistics getStatistics(Locale locale) {
			return ofNullable(localeSpecific).map(it -> it.get(locale)).orElse(global);
		}

	}

	/**
	 * This class contains statistical information about price data that is necessary to create valid queries.
	 */
	class GlobalPriceStatistics {
		private final Map<CompressiblePriceKey, PriceStatistics> priceAndCurrencyStats = new HashMap<>();
		@Getter private final Map<Currency, Set<String>> priceLists = new HashMap<>();
		@Getter private final Set<Currency> currencies = new HashSet<>();

		/**
		 * Indexes new price.
		 */
		public void updateValue(@Nonnull PriceContract value, @Nonnull Random random) {
			if (value.isSellable()) {
				final CompressiblePriceKey key = new CompressiblePriceKey(value.getPriceKey());
				final PriceStatistics priceStatistics = priceAndCurrencyStats.computeIfAbsent(key, PriceStatistics::new);
				priceStatistics.updateValue(value, random);
				currencies.add(key.getCurrency());
				final Set<String> priceLists = this.priceLists.computeIfAbsent(key.getCurrency(), currency -> new HashSet<>());
				priceLists.add(key.getPriceList());
			}
		}

		/**
		 * Returns price statistics for passed priceList and currency combination.
		 */
		public PriceStatistics getPriceStats(@Nonnull CompressiblePriceKey key) {
			return priceAndCurrencyStats.get(key);
		}

		/**
		 * Selects random currency from all available currencies.
		 */
		public Currency pickRandomCurrency(@Nonnull Random random) {
			final int index = random.nextInt(currencies.size());
			final Iterator<Currency> it = currencies.iterator();
			int i = -1;
			while (++i < index) {
				it.next();
			}
			return it.next();
		}

		/**
		 * Returns price list with passed `currency` that has the most prices in it. This price list is probably
		 * "the basic" price list that contains prices for all items.
		 */
		public String getBiggestPriceListFor(@Nonnull Currency currency) {
			final Set<String> priceLists = this.priceLists.get(currency);
			String biggestOne = null;
			int biggestCount = 0;
			for (String priceList : priceLists) {
				final PriceStatistics priceStatistics = priceAndCurrencyStats.get(new CompressiblePriceKey(priceList, currency));
				if (priceStatistics.getCount() > biggestCount) {
					biggestOne = priceList;
					biggestCount = priceStatistics.getCount();
				}
			}
			return biggestOne;
		}

		/**
		 * Selects set of random price lists except of specified one. Price lists are selected only for passed currency.
		 */
		public String[] pickRandomPriceLists(@Nonnull Random random, int count, @Nonnull Currency currency, @Nonnull String except) {
			final Set<String> priceListsAvailable = priceLists.get(currency);
			final Set<String> pickedPriceLists = new HashSet<>();
			final int requestedCount = Math.min(count, priceListsAvailable.size() - 1);
			do {
				final int index = random.nextInt(priceListsAvailable.size());
				final Iterator<String> it = priceListsAvailable.iterator();
				int i = -1;
				while (++i < index) {
					it.next();
				}
				final String pickedPriceList = it.next();
				if (!except.equals(pickedPriceList)) {
					pickedPriceLists.add(pickedPriceList);
				}
			} while (pickedPriceLists.size() < requestedCount);

			final String[] priceLists = pickedPriceLists.toArray(new String[0]);
			ArrayUtils.shuffleArray(random, priceLists);
			return priceLists;
		}

		/**
		 * Returns random date time that belongs to the validity intervals of passed currency and price lists combinations.
		 */
		public OffsetDateTime pickRandomDateTimeFor(@Nonnull Currency currency, @Nonnull String[] priceLists, @Nonnull Random random) {
			OffsetDateTime randomValue = null;
			for (String priceList : priceLists) {
				final CompressiblePriceKey key = new CompressiblePriceKey(priceList, currency);
				final PriceStatistics statistics = priceAndCurrencyStats.get(key);
				final Statistics validityStatistics = ofNullable(statistics).map(PriceStatistics::getValidityStatistics).orElse(null);
				if (validityStatistics != null) {
					final LinkedList<Comparable> randomValues = validityStatistics.getRandomValues();
					final int index = random.nextInt(randomValues.size());
					final Iterator<Comparable> it = randomValues.iterator();
					int i = -1;
					while (++i < index) {
						it.next();
					}
					final DateTimeRange range = (DateTimeRange) it.next();
					randomValue = random.nextBoolean() ? range.getPreciseFrom() : range.getPreciseTo();
				}
			}
			if (randomValue == null || randomValue.getYear() > 2090 || randomValue.getYear() < 1950) {
				return OffsetDateTime.now();
			} else {
				return randomValue;
			}
		}

		/**
		 * Returns random price with VAT belongs to the price spans of passed currency and price lists combinations.
		 */
		public BigDecimal pickRandomValue(@Nonnull Currency currency, @Nonnull String[] priceLists, @Nonnull Random random, int decimalPlaces) {
			BigDecimal min = null;
			BigDecimal max = null;
			for (String priceList : priceLists) {
				final CompressiblePriceKey key = new CompressiblePriceKey(priceList, currency);
				final PriceStatistics statistics = priceAndCurrencyStats.get(key);
				final Statistics priceWithTaxStats = ofNullable(statistics).map(PriceStatistics::getPriceWithTaxStatistics).orElse(null);
				if (priceWithTaxStats != null) {
					final BigDecimal priceListMinPrice = (BigDecimal) priceWithTaxStats.getMinimalValue();
					if (min == null || min.compareTo(priceListMinPrice) > 0) {
						min = priceListMinPrice;
					}
					final BigDecimal priceListMaxPrice = (BigDecimal) priceWithTaxStats.getMaximalValue();
					if (max == null || max.compareTo(priceListMaxPrice) < 0) {
						max = priceListMaxPrice;
					}
				}
			}
			if (min == null && max == null) {
				return null;
			} else {
				final BigDecimal diff = max.subtract(min);
				return min.add(diff.multiply(BigDecimal.valueOf(random.nextFloat())))
					.setScale(decimalPlaces, RoundingMode.HALF_UP);
			}
		}
	}

	/**
	 * This class contains statistical information about price data that is necessary to create valid queries.
	 * This DTO is specific to the price list / currency combination.
	 */
	@RequiredArgsConstructor
	class PriceStatistics {
		/**
		 * Holds identification of the price list and currency combination.
		 */
		@Getter private final CompressiblePriceKey key;
		/**
		 * Holds statistics for the price.
		 */
		private Statistics priceWithoutVatStatistics;
		/**
		 * Holds statistics for the price.
		 */
		private Statistics priceWithTaxStatistics;
		/**
		 * Holds statistics for the price.
		 */
		private Statistics validityStatistics;

		/**
		 * Records a value encountered in the dataset.
		 */
		public void updateValue(@Nonnull PriceContract value, @Nonnull Random random) {
			if (priceWithoutVatStatistics == null) {
				priceWithoutVatStatistics = new Statistics(value.getPriceWithoutTax());
				priceWithTaxStatistics = new Statistics(value.getPriceWithTax());
			} else {
				priceWithoutVatStatistics.update(value.getPriceWithoutTax(), random);
				priceWithTaxStatistics.update(value.getPriceWithTax(), random);
			}
			if (value.getValidity() != null) {
				if (validityStatistics == null) {
					validityStatistics = new Statistics(value.getValidity());
				} else {
					validityStatistics.update(value.getValidity(), random);
				}
			}
		}

		/**
		 * Returns statistics for the prices without VAT.
		 * found.
		 */
		@Nullable
		public Statistics getPriceWithoutVatStatistics() {
			return priceWithoutVatStatistics;
		}

		/**
		 * Returns statistics for the prices without VAT.
		 * found.
		 */
		@Nullable
		public Statistics getPriceWithTaxStatistics() {
			return priceWithTaxStatistics;
		}

		/**
		 * Returns statistics for datetime validity of the prices.
		 * found.
		 */
		@Nullable
		public Statistics getValidityStatistics() {
			return validityStatistics;
		}

		/**
		 * Returns count of prices in this price list and currency combination.
		 */
		public int getCount() {
			return priceWithoutVatStatistics.getCount();
		}
	}

	/**
	 * This class contains statistical information about attribute data that is necessary to create valid queries.
	 * This class is locale specific.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Data
	class Statistics {
		/**
		 * Holds information about minimal value encountered for this attribute.
		 */
		private Comparable minimalValue;
		/**
		 * Holds information about maximal value encountered for this attribute.
		 */
		private Comparable maximalValue;
		/**
		 * Holds information about count of all attributes of this name in the dataset.
		 */
		private int count;
		/**
		 * Contains random 50 values of this attribute from the actual dataset.
		 * Values can be used for equality or threshold filtering.
		 */
		private LinkedList<Comparable> randomValues = new LinkedList<>();

		public Statistics(@Nonnull Serializable value) {
			this.minimalValue = (Comparable) value;
			this.maximalValue = (Comparable) value;
			this.count = 1;
			this.randomValues.add((Comparable) value);
		}

		/**
		 * Records a value encountered in the dataset.
		 */
		public void update(@Nonnull Serializable value, @Nonnull Random random) {
			if (minimalValue.compareTo(value) > 0) {
				this.minimalValue = (Comparable) value;
			}
			if (maximalValue.compareTo(value) < 0) {
				this.maximalValue = (Comparable) value;
			}
			this.count++;
			if (random.nextInt(5) == 0) {
				randomValues.add((Comparable) value);
				if (randomValues.size() > 50) {
					randomValues.removeFirst();
				}
			}
		}

		/**
		 * Returns random value that is present in the dataset.
		 */
		public <T extends Comparable<?> & Serializable> T getSomeValue(@Nonnull Random random) {
			return (T) randomValues.get(random.nextInt(randomValues.size()));
		}
	}

	record GraphQLQuery(@Nullable Query originalQuery,
	                    @Nonnull String collectionName,
	                    @Nullable GraphQLConstraint filterBy,
	                    @Nullable GraphQLConstraint orderBy,
	                    @Nonnull GraphQLConstraint[] require,
	                    int pageNumber,
	                    int pageSize,
	                    @Nonnull String[] extraResultFields) {

		public String toString() {
			return String.format(
				"""
                    query {
			            query%s(
			                %s
			                %s
			                %s
			            ) {
			                recordPage(number: %d, size: %d) {
			                    data {
			                        primaryKey
			                        type
			                    }
			                }
			                %s
			            }
		            }
					""",
				StringUtils.toPascalCase(collectionName),
				filterBy != null ?
					new GraphQLConstraint(
						FilterBy.class,
						filterBy
					).toString() :
					"",
				orderBy != null ?
					new GraphQLConstraint(
						OrderBy.class,
						orderBy
					).toString() :
					"",
				require.length != 0 ?
					new GraphQLConstraint(
						Require.class,
						require
					).toString() :
					"",
				pageNumber,
				pageSize,
				extraResultFields.length > 0 ?
					String.format(
						"""
						extraResults {
							%s
						}
						""",
						String.join("\n", extraResultFields)
					) :
					""
			);
		}
	}

	class GraphQLConstraint {

		@Nonnull
		private final ConstraintDescriptor constraintDescriptor;
		@Nullable
		private final String classifier;
		@Nonnull
		private final String value;

		private GraphQLConstraint(@Nonnull Class<? extends Constraint<?>> constraintClass, @Nonnull Serializable value) {
			final ConstraintDescriptor constraintDescriptor = ConstraintDescriptorProvider.getConstraints(constraintClass)
				.stream()
				.filter(it -> !it.creator().hasClassifierParameter())
				.sorted((o1, o2) -> {
					if (!o1.creator().hasImplicitClassifier()) {
						return -1;
					} else if (!o2.creator().hasImplicitClassifier()) {
						return 1;
					}
					return 0;
				})
				.min(Comparator.comparing(ConstraintDescriptor::fullName))
				.orElseThrow();

			this.constraintDescriptor = constraintDescriptor;
			this.classifier = null;
			this.value = convertValue(value);
		}

		private GraphQLConstraint(@Nonnull Class<? extends Constraint<?>> constraintClass, @Nonnull Serializable... values) {
			final ConstraintDescriptor constraintDescriptor = ConstraintDescriptorProvider.getConstraints(constraintClass)
				.stream()
				.filter(it -> !it.creator().hasClassifierParameter())
				.sorted((o1, o2) -> {
					if (!o1.creator().hasImplicitClassifier()) {
						return -1;
					} else if (!o2.creator().hasImplicitClassifier()) {
						return 1;
					}
					return 0;
				})
				.min(Comparator.comparing(ConstraintDescriptor::fullName))
				.orElseThrow();

			this.constraintDescriptor = constraintDescriptor;
			this.classifier = null;
			this.value = convertValues(values);
		}

		private GraphQLConstraint(@Nonnull Class<? extends Constraint<?>> constraintClass, @Nonnull GraphQLConstraint child) {
			this(constraintClass, new GraphQLConstraint[] {child});
		}

		private GraphQLConstraint(@Nonnull Class<? extends Constraint<?>> constraintClass, @Nonnull GraphQLConstraint... children) {
			final ConstraintDescriptor constraintDescriptor = ConstraintDescriptorProvider.getConstraints(constraintClass)
				.stream()
				.filter(it -> !it.creator().hasClassifierParameter())
				.findFirst()
				.orElseThrow();

			this.constraintDescriptor = constraintDescriptor;
			this.classifier = null;
			this.value = convertChildren(children, constraintDescriptor);
		}

		private GraphQLConstraint(@Nonnull String classifier, @Nonnull Class<? extends Constraint<?>> constraintClass, @Nonnull Object value) {
			final ConstraintDescriptor constraintDescriptor = ConstraintDescriptorProvider.getConstraints(constraintClass)
				.stream()
				.filter(it -> it.creator().hasClassifierParameter())
				.findFirst()
				.orElseThrow();

			this.constraintDescriptor = constraintDescriptor;
			this.classifier = classifier;
			if (value instanceof Map<?,?> wrapperObject) {
				//noinspection unchecked
				this.value = convertWrapperObject((Map<String, Object>) wrapperObject, constraintDescriptor);
			} else {
				this.value = convertValue(value);
			}
		}

		private GraphQLConstraint(@Nonnull String classifier, @Nonnull Class<? extends Constraint<?>> constraintClass, @Nonnull Object... values) {
			final ConstraintDescriptor constraintDescriptor = ConstraintDescriptorProvider.getConstraints(constraintClass)
				.stream()
				.filter(it -> it.creator().hasClassifierParameter())
				.findFirst()
				.orElseThrow();

			this.constraintDescriptor = constraintDescriptor;
			this.classifier = classifier;
			this.value = convertValues(values);
		}

		private GraphQLConstraint(@Nonnull String classifier, @Nonnull Class<? extends Constraint<?>> constraintClass, @Nonnull GraphQLConstraint children) {
			final ConstraintDescriptor constraintDescriptor = ConstraintDescriptorProvider.getConstraints(constraintClass)
				.stream()
				.filter(it -> it.creator().hasClassifierParameter())
				.findFirst()
				.orElseThrow();

			this.constraintDescriptor = constraintDescriptor;
			this.classifier = classifier;
			this.value = convertChildren(new GraphQLConstraint[] {children}, constraintDescriptor);
		}

		@Nonnull
		private static String convertValue(@Nonnull Object value) {
			if (value instanceof Enum<?> enumValue) {
				return enumValue.name();
			} else if (value instanceof Locale localeValue) {
				return localeValue.toString();
			} else {
				final Object serializedValue = VALUE_CONVERTER.serialize(value);
				if (serializedValue instanceof String && !(value instanceof Locale) && !(value instanceof Currency)) {
					return "\"" + serializedValue + "\"";
				}
				return serializedValue.toString();
			}
		}

		@Nonnull
		private static String convertValues(@Nonnull Object[] values) {
			return "[" +
				Arrays.stream(values)
					.map(GraphQLConstraint::convertValue)
					.collect(Collectors.joining(",")) +
				"]";
		}

		@Nonnull
		private static String convertChildren(@Nonnull GraphQLConstraint[] children, @Nonnull ConstraintDescriptor constraintDescriptor) {
			final ChildParameterDescriptor childrenParameterDescriptor = constraintDescriptor.creator().childParameters().get(0);
			if (childrenParameterDescriptor.uniqueChildren() || constraintDescriptor.type() == ConstraintType.ORDER || constraintDescriptor.type() == ConstraintType.REQUIRE) {
				return "{\n" +
					Arrays.stream(children)
						.map(GraphQLConstraint::toString)
						.collect(Collectors.joining(",\n")) +
					"}";
			}
			if (childrenParameterDescriptor.type().isArray()) {
				return "[\n" +
					Arrays.stream(children)
						.map(it -> "{" + it.toString() + "}")
						.collect(Collectors.joining(",\n")) +
					"]";
			} else {
				if (children.length == 0) {
					return "{}";
				} else {
					return "{" + children[0].toString() + "}";
				}
			}
		}

		@Nonnull
		private String convertWrapperObject(@Nonnull Map<String, Object> wrapperObject, @Nonnull ConstraintDescriptor constraintDescriptor) {
			return "{\n" +
				wrapperObject.entrySet().stream()
					.map(it -> {
						final String value;
						if (it.getValue() instanceof GraphQLConstraint[] constraints) {
							value = convertChildren(constraints, constraintDescriptor);
						} else if (it.getValue() instanceof GraphQLConstraint constraint) {
							value = convertChildren(new GraphQLConstraint[] {constraint}, constraintDescriptor);
						} else if (it.getValue().getClass().isArray()) {
							value = convertValues((Object[]) it.getValue());
						} else {
							value = convertValue(it.getValue());
						}
						return it.getKey() + ": " + value;
					})
					.collect(Collectors.joining("\n")) +
				"}";
		}

		@Override
		public String toString() {
			final Class<?> constraintClass = constraintDescriptor.constraintClass();
			final ConstraintBuildContext buildContext;
			if (HierarchyExcluding.class.isAssignableFrom(constraintClass) ||
				HierarchyExcludingRoot.class.isAssignableFrom(constraintClass) ||
				HierarchyDirectRelation.class.isAssignableFrom(constraintClass) ||
				HierarchyHaving.class.isAssignableFrom(constraintClass)) {
				buildContext = new ConstraintBuildContext(new HierarchyDataLocator("")).switchToChildContext(new HierarchyDataLocator(""));
			} else {
				buildContext = new ConstraintBuildContext(new GenericDataLocator(""));
			}
			final String key = new ConstraintKeyBuilder().build(buildContext, constraintDescriptor, () -> classifier);
			return key + ": " + value;
		}
	}

}
