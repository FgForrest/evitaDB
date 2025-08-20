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

package io.evitadb.performance.generators;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.HierarchySpecificationFilterConstraint;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.key.CompressiblePriceKey;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
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
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static java.util.Optional.ofNullable;

/**
 * This interface contains methods that allow generation of random filtering constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface RandomQueryGenerator {
	HierarchySpecificationFilterConstraint[] EMPTY_HSFC_ARRAY = new HierarchySpecificationFilterConstraint[0];

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
						if (localizedAttributeValue.value() instanceof Object[]) {
							final Serializable[] valueArray = (Serializable[]) localizedAttributeValue.value();
							for (Serializable valueItem : valueArray) {
								statistics.updateValue(valueItem, locale, random);
							}
						} else {
							statistics.updateValue(localizedAttributeValue.value(), locale, random);
						}
					});
				}
			} else {
				entity.getAttributeValue(attributeName).ifPresent(attributeValue -> {
					if (attributeValue.value() instanceof Object[]) {
						final Serializable[] valueArray = (Serializable[]) attributeValue.value();
						for (Serializable valueItem : valueArray) {
							statistics.updateValue(valueItem, random);
						}
					} else {
						statistics.updateValue(attributeValue.value(), random);
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
	default Query generateRandomAttributeQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull Map<String, AttributeStatistics> filterableAttributes, @Nonnull Set<String> sortableAttributes) {
		final Locale randomExistingLocale = getRandomExistingLocale(schema, random);

		return Query.query(
			collection(schema.getName()),
			filterBy(
				and(
					createRandomAttributeFilterBy(random, randomExistingLocale, filterableAttributes),
					entityLocaleEquals(randomExistingLocale)
				)
			),
			orderBy(
				createRandomAttributeOrderBy(random, sortableAttributes)
			),
			require(
				page(random.nextInt(5) + 1, 20)
			)
		);
	}

	/**
	 * Method generates random attribute histogram requirement and adds it to the passed `existingQuery`.
	 */
	default Query generateRandomAttributeHistogramQuery(@Nonnull Query existingQuery, @Nonnull Random random, @Nonnull Set<String> numericFilterableAttributes) {
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

		return Query.query(
			existingQuery.getCollection(),
			existingQuery.getFilterBy(),
			require(
				ArrayUtils.mergeArrays(
					existingQuery.getRequire().getChildren(),
					new RequireConstraint[]{
						attributeHistogram(10 + random.nextInt(20), attributes)
					}
				)
			),
			existingQuery.getOrderBy()
		);
	}

	/**
	 * Creates randomized query for passed entity schema based on passed statistics about `filteringAttributes` and
	 * `sortableAttributes`.
	 */
	default Query generateRandomPriceQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull GlobalPriceStatistics priceStats) {
		final Locale randomExistingLocale = getRandomExistingLocale(schema, random);

		return Query.query(
			collection(schema.getName()),
			filterBy(
				and(
					createRandomPriceFilterBy(random, priceStats, schema.getIndexedPricePlaces()),
					entityLocaleEquals(randomExistingLocale)
				)
			),
			orderBy(
				createRandomPriceOrderBy(random)
			),
			require(
				page(random.nextInt(5) + 1, 20)
			)
		);
	}

	/**
	 * Method generates random hierarchy filtering query and adds it to the passed `existingQuery`. While new
	 * query is created `categoryIds` collection is used to retrieve random category id specification.
	 */
	default Query generateRandomHierarchyQuery(@Nonnull Query existingQuery, @Nonnull Random random, @Nonnull List<Integer> categoryIds, @Nonnull String hierarchyEntityType) {
		final FilterConstraint hierarchyConstraint;
		final List<HierarchySpecificationFilterConstraint> specification = new ArrayList<>();
		final int rndKey = Math.abs(random.nextInt()) + 1;
		final Integer[] excludedIds;
		if (rndKey % 5 == 0) {
			excludedIds = new Integer[5];
			for (int i = 0; i < 5; i++) {
				excludedIds[i] = categoryIds.get(Math.abs(rndKey * (i + 1)) % (categoryIds.size()));
			}
			specification.add(excluding(entityPrimaryKeyInSet(excludedIds)));
		} else {
			excludedIds = null;
		}
		if (rndKey % 3 == 0) {
			specification.add(excludingRoot());
		} else if (rndKey % 7 == 0 && excludedIds == null) {
			specification.add(directRelation());
		}
		final int parentId = categoryIds.get(rndKey % categoryIds.size());
		hierarchyConstraint = hierarchyWithin(
			hierarchyEntityType,
			entityPrimaryKeyInSet(parentId),
			specification.toArray(EMPTY_HSFC_ARRAY)
		);

		return Query.query(
			existingQuery.getCollection(),
			filterBy(
				and(
					ArrayUtils.mergeArrays(
						new FilterConstraint[]{hierarchyConstraint},
						existingQuery.getFilterBy().getChildren()
					)
				)
			),
			existingQuery.getRequire(),
			existingQuery.getOrderBy()
		);
	}

	/**
	 * Method generates random price histogram requirement and adds it to the passed `existingQuery`.
	 */
	default Query generateRandomPriceHistogramQuery(@Nonnull Query existingQuery, @Nonnull Random random) {
		return Query.query(
			existingQuery.getCollection(),
			existingQuery.getFilterBy(),
			require(
				ArrayUtils.mergeArrays(
					existingQuery.getRequire().getChildren(),
					new RequireConstraint[]{
						priceHistogram(10 + random.nextInt(20))
					}
				)
			),
			existingQuery.getOrderBy()
		);
	}

	/**
	 * Creates randomized query for passed entity schema based on passed statistics about faceted `references`.
	 */
	default Query generateRandomFacetQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull Map<String, Set<Integer>> facetedReferences) {
		final int facetCount = random.nextInt(5) + 1;
		final Map<String, Set<Integer>> selectedFacets = new HashMap<>();
		for (int i = 0; i < facetCount; i++) {
			final String randomFacetType = getRandomItem(random, facetedReferences.keySet());
			final Integer randomFacetId = getRandomItem(random, facetedReferences.get(randomFacetType));
			selectedFacets.computeIfAbsent(randomFacetType, fType -> new HashSet<>()).add(randomFacetId);
		}
		return Query.query(
			collection(schema.getName()),
			filterBy(
				userFilter(
					selectedFacets.entrySet()
						.stream()
						.map(it -> facetHaving(it.getKey(), entityPrimaryKeyInSet(it.getValue().toArray(new Integer[0]))))
						.toArray(FilterConstraint[]::new)
				)
			),
			require(
				page(random.nextInt(5) + 1, 20)
			)
		);
	}

	/**
	 * Creates randomized query requiring {@link Hierarchy} computation for
	 * passed entity schema based on passed set.
	 */
	default Query generateRandomParentSummaryQuery(@Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull Set<String> referencedHierarchyEntities) {
		return Query.query(
			collection(schema.getName()),
			filterBy(
				entityLocaleEquals(getRandomExistingLocale(schema, random))
			),
			require(
				page(1, 20),
				hierarchyOfReference(pickRandom(random, referencedHierarchyEntities), fromRoot("megaMenu"))
			)
		);
	}

	/**
	 * Updates randomized query adding a request to facet summary computation juggling inter facet relations.
	 */
	default Query generateRandomFacetSummaryQuery(@Nonnull Query existingQuery, @Nonnull Random random, @Nonnull EntitySchemaContract schema, @Nonnull FacetStatisticsDepth depth, @Nonnull Map<String, Map<Integer, Integer>> facetGroupsIndex) {
		final List<FilterConstraint> facetFilters = FinderVisitor.findConstraints(existingQuery.getFilterBy(), FacetHaving.class::isInstance);
		final List<RequireConstraint> requireConstraints = new LinkedList<>();
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
						facetGroupsConjunction(facetHaving.getReferenceName(), filterBy(entityPrimaryKeyInSet(getRandomItem(random, groupIds))))
					);
				} else if (dice == 2) {
					requireConstraints.add(
						facetGroupsDisjunction(facetHaving.getReferenceName(), filterBy(entityPrimaryKeyInSet(getRandomItem(random, groupIds))))
					);
				} else if (dice == 3) {
					requireConstraints.add(
						facetGroupsNegation(facetHaving.getReferenceName(), filterBy(entityPrimaryKeyInSet(getRandomItem(random, groupIds))))
					);
				}
			}
		}
		return Query.query(
			collection(schema.getName()),
			existingQuery.getFilterBy(),
			existingQuery.getOrderBy(),
			require(
				ArrayUtils.mergeArrays(
					new RequireConstraint[]{
						page(random.nextInt(5) + 1, 20),
						facetSummary(depth)
					},
					requireConstraints.toArray(RequireConstraint[]::new)
				)

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
		throw new GenericEvitaInternalError("Should not ever happen!");
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
	default FilterConstraint createRandomAttributeFilterBy(@Nonnull Random random, @Nonnull Locale locale, @Nonnull Map<String, AttributeStatistics> filterableAttributes) {
		final AttributeStatistics attribute = pickRandom(random, filterableAttributes);
		if (String.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			return switch (random.nextInt(3)) {
				case 0 -> attributeIsNull(attribute.getName());
				case 1 -> attributeIsNotNull(attribute.getName());
				case 2 -> statistics == null ? attributeIsNotNull(attribute.getName()) : attributeEquals(attribute.getName(), statistics.getSomeValue(random));
				case 3 -> statistics == null ? attributeIsNotNull(attribute.getName()) : attributeInSet(attribute.getName(), statistics.getSomeValue(random), statistics.getSomeValue(random));
				default -> attributeIsNull(attribute.getName());
			};
		} else if (Boolean.class.equals(attribute.getType())) {
			return switch (random.nextInt(3)) {
				case 0 -> attributeIsNull(attribute.getName());
				case 1 -> attributeIsNotNull(attribute.getName());
				case 2 -> attributeEqualsTrue(attribute.getName());
				case 3 -> attributeEqualsFalse(attribute.getName());
				default -> attributeIsNull(attribute.getName());
			};
		} else if (Long.class.equals(attribute.getType()) && attribute.isArray()) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(5)) {
				case 0:
					return attributeIsNull(attribute.getName());
				case 1:
					return attributeIsNotNull(attribute.getName());
				case 2:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeEquals(attribute.getName(), statistics.getSomeValue(random));
				case 3:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeInSet(attribute.getName(), statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4: {
					if (statistics == null) {
						return attributeIsNotNull(attribute.getName());
					}
					final Long first = statistics.getSomeValue(random);
					final Long second = statistics.getSomeValue(random);
					return attributeBetween(attribute.getName(), first < second ? first : second, first < second ? second : first);
				}
				default:
					return attributeIsNull(attribute.getName());
			}
		} else if (Long.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(7)) {
				case 0:
					return attributeIsNull(attribute.getName());
				case 1:
					return attributeIsNotNull(attribute.getName());
				case 2:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeEquals(attribute.getName(), statistics.getSomeValue(random));
				case 3:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeInSet(attribute.getName(), statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeGreaterThan(attribute.getName(), statistics.getSomeValue(random));
				case 5:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeLessThan(attribute.getName(), statistics.getSomeValue(random));
				case 6: {
					if (statistics == null) {
						return attributeIsNotNull(attribute.getName());
					}
					final Long first = statistics.getSomeValue(random);
					final Long second = statistics.getSomeValue(random);
					return attributeBetween(attribute.getName(), first < second ? first : second, first < second ? second : first);
				}
				default:
					return attributeIsNull(attribute.getName());
			}
		} else if (BigDecimal.class.equals(attribute.getType()) && attribute.isArray()) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(5)) {
				case 0:
					return attributeIsNull(attribute.getName());
				case 1:
					return attributeIsNotNull(attribute.getName());
				case 2:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeEquals(attribute.getName(), statistics.getSomeValue(random));
				case 3:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeInSet(attribute.getName(), statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4: {
					if (statistics == null) {
						return attributeIsNotNull(attribute.getName());
					}
					final BigDecimal first = statistics.getSomeValue(random);
					final BigDecimal second = statistics.getSomeValue(random);
					return attributeBetween(attribute.getName(), first.compareTo(second) < 0 ? first : second, first.compareTo(second) < 0 ? second : first);
				}
				default:
					return attributeIsNull(attribute.getName());
			}
		} else if (BigDecimal.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(7)) {
				case 0:
					return attributeIsNull(attribute.getName());
				case 1:
					return attributeIsNotNull(attribute.getName());
				case 2:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeEquals(attribute.getName(), statistics.getSomeValue(random));
				case 3:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeInSet(attribute.getName(), statistics.getSomeValue(random), statistics.getSomeValue(random));
				case 4:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeGreaterThan(attribute.getName(), statistics.getSomeValue(random));
				case 5:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeLessThan(attribute.getName(), statistics.getSomeValue(random));
				case 6: {
					if (statistics == null) {
						return attributeIsNotNull(attribute.getName());
					}
					final BigDecimal first = statistics.getSomeValue(random);
					final BigDecimal second = statistics.getSomeValue(random);
					return attributeBetween(attribute.getName(), first.compareTo(second) < 0 ? first : second, first.compareTo(second) < 0 ? second : first);
				}
				default:
					return attributeIsNull(attribute.getName());
			}
		} else if (DateTimeRange.class.equals(attribute.getType())) {
			final Statistics statistics = attribute.getStatistics(locale);
			switch (random.nextInt(3)) {
				case 0:
					return attributeIsNull(attribute.getName());
				case 1:
					return attributeIsNotNull(attribute.getName());
				case 2:
					return statistics == null ? attributeIsNotNull(attribute.getName()) : attributeInRange(attribute.getName(), getRandomOffsetDateTimeBetween(random, statistics));
				case 3: {
					if (statistics == null) {
						return attributeIsNotNull(attribute.getName());
					}
					final OffsetDateTime first = getRandomOffsetDateTimeBetween(random, statistics);
					final OffsetDateTime second = getRandomOffsetDateTimeBetween(random, statistics);
					return attributeBetween(attribute.getName(), first.isBefore(second) ? first : second, first.isBefore(second) ? second : first);
				}
				default:
					return attributeIsNull(attribute.getName());
			}
		} else {
			return attributeIsNotNull(attribute.getName());
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
	default FilterConstraint createRandomPriceFilterBy(@Nonnull Random random, @Nonnull GlobalPriceStatistics priceStatistics, int decimalPlaces) {
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
			//noinspection ConstantConditions
			return and(
				priceInCurrency(currency),
				priceInPriceLists(priceLists),
				priceValidIn(validIn),
				priceBetween(fromLesserThanTo < 0 ? from : to, fromLesserThanTo < 0 ? to : from)
			);
		} else {
			// query prices with currency, price lists and validity
			//noinspection ConstantConditions
			return and(
				priceInCurrency(currency),
				priceInPriceLists(priceLists),
				priceValidIn(validIn)
			);
		}
	}

	/**
	 * Creates randomized order query that targets existing attribute. It picks random attribute from the
	 * `sortableAttributes` and creates ascending or descending order.
	 */
	@Nonnull
	default OrderConstraint createRandomAttributeOrderBy(@Nonnull Random random, @Nonnull Set<String> sortableAttributes) {
		final OrderConstraint randomOrderBy;
		if (random.nextBoolean()) {
			randomOrderBy = attributeNatural(pickRandom(random, sortableAttributes));
		} else {
			randomOrderBy = attributeNatural(pickRandom(random, sortableAttributes), DESC);
		}
		return randomOrderBy;
	}

	/**
	 * Creates randomized order query that targets prices. In 33% it returns ordering by price asc, 33% desc and
	 * for 33% of cases no ordering.
	 */
	@Nullable
	default OrderConstraint createRandomPriceOrderBy(@Nonnull Random random) {
		final int selectedType = random.nextInt(3);
		return switch (selectedType) {
			case 0 -> priceNatural();
			case 1 -> priceNatural(DESC);
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
			if (this.global == null) {
				this.global = new Statistics(value);
			} else {
				this.global.update(value, random);
			}
		}

		/**
		 * Records a value encountered in the dataset.
		 */
		public void updateValue(@Nonnull Serializable localizedValue, @Nonnull Locale locale, @Nonnull Random random) {
			Assert.isTrue(this.localized, "Attribute is not localized by schema!");
			if (this.localeSpecific == null) {
				this.localeSpecific = new HashMap<>();
			}
			final Statistics statistics = this.localeSpecific.get(locale);
			if (statistics == null) {
				this.localeSpecific.put(locale, new Statistics(localizedValue));
			} else {
				statistics.update(localizedValue, random);
			}
		}

		/**
		 * Returns statistics for the passed locale or global statistics if there are no locale specific statistics
		 * found.
		 */
		public Statistics getStatistics(Locale locale) {
			return ofNullable(this.localeSpecific).map(it -> it.get(locale)).orElse(this.global);
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
			if (value.indexed()) {
				final CompressiblePriceKey key = new CompressiblePriceKey(value.priceKey());
				final PriceStatistics priceStatistics = this.priceAndCurrencyStats.computeIfAbsent(key, PriceStatistics::new);
				priceStatistics.updateValue(value, random);
				this.currencies.add(key.getCurrency());
				final Set<String> priceLists = this.priceLists.computeIfAbsent(key.getCurrency(), currency -> new HashSet<>());
				priceLists.add(key.getPriceList());
			}
		}

		/**
		 * Returns price statistics for passed priceList and currency combination.
		 */
		public PriceStatistics getPriceStats(@Nonnull CompressiblePriceKey key) {
			return this.priceAndCurrencyStats.get(key);
		}

		/**
		 * Selects random currency from all available currencies.
		 */
		public Currency pickRandomCurrency(@Nonnull Random random) {
			final int index = random.nextInt(this.currencies.size());
			final Iterator<Currency> it = this.currencies.iterator();
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
				final PriceStatistics priceStatistics = this.priceAndCurrencyStats.get(new CompressiblePriceKey(priceList, currency));
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
			final Set<String> priceListsAvailable = this.priceLists.get(currency);
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
				final PriceStatistics statistics = this.priceAndCurrencyStats.get(key);
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
		 * Returns random price with tax belongs to the price spans of passed currency and price lists combinations.
		 */
		public BigDecimal pickRandomValue(@Nonnull Currency currency, @Nonnull String[] priceLists, @Nonnull Random random, int decimalPlaces) {
			BigDecimal min = null;
			BigDecimal max = null;
			for (String priceList : priceLists) {
				final CompressiblePriceKey key = new CompressiblePriceKey(priceList, currency);
				final PriceStatistics statistics = this.priceAndCurrencyStats.get(key);
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
		private Statistics priceWithoutTaxStatistics;
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
			if (this.priceWithoutTaxStatistics == null) {
				this.priceWithoutTaxStatistics = new Statistics(value.priceWithoutTax());
				this.priceWithTaxStatistics = new Statistics(value.priceWithTax());
			} else {
				this.priceWithoutTaxStatistics.update(value.priceWithoutTax(), random);
				this.priceWithTaxStatistics.update(value.priceWithTax(), random);
			}
			if (value.validity() != null) {
				if (this.validityStatistics == null) {
					this.validityStatistics = new Statistics(value.validity());
				} else {
					this.validityStatistics.update(value.validity(), random);
				}
			}
		}

		/**
		 * Returns statistics for the prices without tax.
		 * found.
		 */
		@Nullable
		public Statistics getPriceWithoutTaxStatistics() {
			return this.priceWithoutTaxStatistics;
		}

		/**
		 * Returns statistics for the prices without tax.
		 * found.
		 */
		@Nullable
		public Statistics getPriceWithTaxStatistics() {
			return this.priceWithTaxStatistics;
		}

		/**
		 * Returns statistics for datetime validity of the prices.
		 * found.
		 */
		@Nullable
		public Statistics getValidityStatistics() {
			return this.validityStatistics;
		}

		/**
		 * Returns count of prices in this price list and currency combination.
		 */
		public int getCount() {
			return this.priceWithoutTaxStatistics.getCount();
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
			if (this.minimalValue.compareTo(value) > 0) {
				this.minimalValue = (Comparable) value;
			}
			if (this.maximalValue.compareTo(value) < 0) {
				this.maximalValue = (Comparable) value;
			}
			this.count++;
			if (random.nextInt(5) == 0) {
				this.randomValues.add((Comparable) value);
				if (this.randomValues.size() > 50) {
					this.randomValues.removeFirst();
				}
			}
		}

		/**
		 * Returns random value that is present in the dataset.
		 */
		public <T extends Comparable<?> & Serializable> T getSomeValue(@Nonnull Random random) {
			return (T) this.randomValues.get(random.nextInt(this.randomValues.size()));
		}
	}

	/**
	 * Extracts facet primary keys from the constraint.
	 */
	@Nonnull
	static int[] extractFacetIds(@Nonnull FacetHaving facetHavingFilter) {
		for (FilterConstraint child : facetHavingFilter.getChildren()) {
			if (child instanceof EntityPrimaryKeyInSet epkis) {
				return epkis.getPrimaryKeys();
			} else {
				throw new IllegalArgumentException("Unsupported constraint in facet filter: " + child);
			}
		}
		return new int[0];
	}

}
