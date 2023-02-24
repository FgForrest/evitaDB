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

package io.evitadb.test.generator;

import com.github.javafaker.Commerce;
import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.AttributesEditor;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Multiple;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.ReflectionLookup.ArgumentKey;
import io.evitadb.utils.StringUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import one.edee.oss.pmptt.PMPTT;
import one.edee.oss.pmptt.dao.memory.MemoryStorage;
import one.edee.oss.pmptt.exception.MaxLevelExceeded;
import one.edee.oss.pmptt.exception.SectionExhausted;
import one.edee.oss.pmptt.model.Hierarchy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Helper class used to quickly setup test data. Allows to generate pseudo random data based on {@link com.github.javafaker.Faker}
 * library.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("ALL")
public class DataGenerator {
	public static final Locale CZECH_LOCALE = new Locale("cs", "CZ");
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_CODE = "code";
	public static final String ATTRIBUTE_URL = "url";
	public static final String ATTRIBUTE_EAN = "ean";
	public static final String ATTRIBUTE_PRIORITY = "priority";
	public static final String ATTRIBUTE_VALIDITY = "validity";
	public static final String ATTRIBUTE_QUANTITY = "quantity";
	public static final String ATTRIBUTE_ALIAS = "alias";
	public static final String ATTRIBUTE_CATEGORY_PRIORITY = "categoryPriority";
	public static final String ASSOCIATED_DATA_REFERENCED_FILES = "referencedFiles";
	public static final String ASSOCIATED_DATA_LABELS = "labels";
	public static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	public static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	public static final Currency CURRENCY_USD = Currency.getInstance("USD");
	public static final Currency CURRENCY_GBP = Currency.getInstance("GBP");
	public static final String PRICE_LIST_BASIC = "basic";
	public static final String PRICE_LIST_REFERENCE = "reference";
	public static final String PRICE_LIST_SELLOUT = "sellout";
	public static final String PRICE_LIST_VIP = "vip";
	public static final String PRICE_LIST_B2B = "b2b";
	public static final String PRICE_LIST_INTRODUCTION = "introduction";
	public static final String[] PRICE_LIST_NAMES = new String[]{
		PRICE_LIST_BASIC,
		PRICE_LIST_REFERENCE,
		PRICE_LIST_SELLOUT,
		PRICE_LIST_VIP,
		PRICE_LIST_B2B,
		PRICE_LIST_INTRODUCTION
	};
	public static final Currency[] CURRENCIES = new Currency[]{
		CURRENCY_CZK, CURRENCY_EUR, CURRENCY_USD, CURRENCY_GBP
	};
	public static final Set<Currency> CURRENCIES_SET = new LinkedHashSet<>(Arrays.asList(CURRENCIES));
	public static final Set<Locale> LOCALES_SET = new LinkedHashSet<>(Arrays.asList(CZECH_LOCALE, Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH));
	public static final Predicate<String> TRUE_PREDICATE = s -> true;
	private static final ReflectionLookup REFLECTION_LOOKUP = new ReflectionLookup(ReflectionCachingBehaviour.CACHE);
	private static final DateTimeRange[] DATE_TIME_RANGES = new DateTimeRange[]{
		DateTimeRange.between(LocalDateTime.MIN, LocalDateTime.MAX, ZoneOffset.UTC),
		DateTimeRange.between(LocalDateTime.of(2010, 1, 1, 0, 0), LocalDateTime.of(2012, 12, 31, 0, 0), ZoneOffset.UTC),
		DateTimeRange.between(LocalDateTime.of(2012, 1, 1, 0, 0), LocalDateTime.of(2014, 12, 31, 0, 0), ZoneOffset.UTC),
		DateTimeRange.between(LocalDateTime.of(2014, 1, 1, 0, 0), LocalDateTime.of(2016, 12, 31, 0, 0), ZoneOffset.UTC),
		DateTimeRange.between(LocalDateTime.of(2010, 1, 1, 0, 0), LocalDateTime.of(2014, 12, 31, 0, 0), ZoneOffset.UTC),
		DateTimeRange.between(LocalDateTime.of(2010, 1, 1, 0, 0), LocalDateTime.of(2016, 12, 31, 0, 0), ZoneOffset.UTC),
	};
	private static final BigDecimal TAX_RATE = new BigDecimal("21");
	private static final BigDecimal TAX_MULTIPLICATOR = TAX_RATE.setScale(2, RoundingMode.UNNECESSARY).divide(new BigDecimal("100.00"), RoundingMode.HALF_UP).add(BigDecimal.ONE);
	/**
	 * Holds information about number of unique values.
	 */
	final Map<Serializable, Map<Object, Integer>> uniqueSequencer = new HashMap<>();
	final Map<Serializable, SortableAttributesChecker> sortableAttributesChecker = new HashMap<>();
	final Map<Integer, Integer> parameterIndex = new HashMap<>();
	/**
	 * Holds function that is used for generating price inner record handling strategy.
	 */
	private final Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator;
	/**
	 * Holds information about created hierarchies for generated / modified entities indexed by their type.
	 */
	private final PMPTT hierarchies = new PMPTT(new MemoryStorage());

	/**
	 * Returns hierarchy connected with passed entity type.
	 */
	@Nonnull
	public static Hierarchy getHierarchy(@Nonnull PMPTT hierarchies, @Nonnull String entityType) {
		return hierarchies.getOrCreateHierarchy(entityType, (short) 5, (short) 10);
	}

	@Nonnull
	private static <T> List<T> pickRandomFromSet(@Nonnull Faker genericFaker, @Nonnull Set<T> set) {
		if (set.isEmpty()) {
			return Collections.emptyList();
		}
		final Integer itemsCount = genericFaker.random().nextInt(1, set.size());
		final List<T> usedItems = new ArrayList<>(itemsCount);
		while (usedItems.size() < itemsCount) {
			final Iterator<T> it = set.iterator();
			T itemToUse = null;
			for (int i = 0; i <= genericFaker.random().nextInt(set.size()); i++) {
				itemToUse = it.next();
			}
			if (!usedItems.contains(itemToUse)) {
				usedItems.add(itemToUse);
			}
		}
		return usedItems;
	}

	@Nonnull
	private static <T> T pickRandomOneFromSet(@Nonnull Faker genericFaker, @Nonnull Set<T> set) {
		if (set.isEmpty()) {
			throw new IllegalArgumentException("Input set is empty!");
		}
		final int index = genericFaker.random().nextInt(0, set.size() - 1);
		final Iterator<T> it = set.iterator();
		T itemToUse = null;
		for (int i = 0; i < set.size(); i++) {
			itemToUse = it.next();
			if (i == index) {
				return itemToUse;
			}
		}
		throw new IllegalStateException("Should not happen!");
	}

	private static void generateRandomHierarchy(
		@Nonnull EntitySchemaContract schema,
		@Nonnull BiFunction<String, Faker, Integer> referencedEntityResolver,
		@Nullable Hierarchy hierarchy,
		@Nonnull Faker genericFaker,
		@Nonnull EntityBuilder detachedBuilder
	) {
		if (hierarchy != null) {
			try {
				// when there are very few root items, force to create some by making next other one as root
				final Integer parentKey = hierarchy.getRootItems().size() < 5 && genericFaker.random().nextBoolean() ?
					null : referencedEntityResolver.apply(schema.getName(), genericFaker);
				if (parentKey == null) {
					hierarchy.createRootItem(Objects.requireNonNull(detachedBuilder.getPrimaryKey()).toString());
					detachedBuilder.setHierarchicalPlacement(
						// we can't easily randomize the position
						hierarchy.getRootItems().size()
					);
				} else {
					hierarchy.createItem(Objects.requireNonNull(detachedBuilder.getPrimaryKey()).toString(), parentKey.toString());
					detachedBuilder.setHierarchicalPlacement(
						parentKey,
						// we can't easily randomize the position
						hierarchy.getChildItems(parentKey.toString()).size()
					);
				}
			} catch (MaxLevelExceeded | SectionExhausted ignores) {
				// just repeat again
				generateRandomHierarchy(schema, referencedEntityResolver, hierarchy, genericFaker, detachedBuilder);
			}
		}
	}

	private static void generateRandomAttributes(
		@Nonnull String entityType,
		@Nonnull Collection<AttributeSchemaContract> attributeSchema,
		@Nonnull Map<Object, Integer> globalUniqueSequencer,
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull SortableAttributesChecker sortableAttributesHolder,
		@Nonnull Predicate<String> attributeFilter, Function<Locale, Faker> localeFaker,
		@Nonnull Faker genericFaker,
		@Nonnull AttributesEditor<?> attributesEditor,
		@Nonnull Collection<Locale> usedLocales,
		@Nonnull Collection<Locale> allLocales
	) {
		for (AttributeSchemaContract attribute : attributeSchema) {
			final Class<? extends Serializable> type = attribute.getType();
			final String attributeName = attribute.getName();
			if (!attributeFilter.test(attributeName)) {
				continue;
			}
			if (!attribute.isUnique() && attribute.isNullable() && genericFaker.random().nextInt(10) == 0) {
				// randomly skip attributes
				continue;
			}
			if (attribute.isLocalized()) {
				final Collection<Locale> localesToGenerate = attribute.isNullable() ? usedLocales : allLocales;
				for (Locale usedLocale : localesToGenerate) {
					generateAndSetAttribute(
						globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder,
						attributesEditor, attribute, type, entityType, attributeName,
						localeFaker.apply(usedLocale),
						value -> attributesEditor.setAttribute(attributeName, usedLocale, value)
					);
				}
			} else {
				generateAndSetAttribute(
					globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder,
					attributesEditor, attribute, type, entityType, attributeName, genericFaker,
					value -> attributesEditor.setAttribute(attributeName, value)
				);
			}
		}
	}

	private static void generateRandomAssociatedData(
		@Nonnull EntitySchemaContract schema,
		@Nonnull Faker genericFaker,
		@Nonnull EntityBuilder detachedBuilder,
		@Nonnull Collection<Locale> usedLocales,
		@Nonnull Collection<Locale> allLocales
	) {
		for (AssociatedDataSchemaContract associatedData : schema.getAssociatedData().values()) {
			final String associatedDataName = associatedData.getName();
			if (associatedData.isNullable() && genericFaker.random().nextInt(5) == 0) {
				// randomly skip associated data
				continue;
			}
			if (associatedData.isLocalized()) {
				final Collection<Locale> localesToGenerate = associatedData.isNullable() ? usedLocales : allLocales;
				for (Locale usedLocale : localesToGenerate) {
					generateAndSetAssociatedData(
						associatedData,
						genericFaker,
						value -> detachedBuilder.setAssociatedData(associatedDataName, usedLocale, value)
					);
				}
			} else {
				generateAndSetAssociatedData(
					associatedData,
					genericFaker,
					value -> detachedBuilder.setAssociatedData(associatedDataName, value)
				);
			}
		}
	}

	private static void generateRandomPrices(
		@Nonnull EntitySchemaContract schema,
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull Faker genericFaker,
		@Nonnull Set<Currency> allCurrencies,
		@Nonnull Set<String> allPriceLists,
		@Nonnull EntityBuilder detachedBuilder,
		@Nonnull Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator
	) {
		if (schema.isWithPrice()) {
			detachedBuilder.setPriceInnerRecordHandling(priceInnerRecordHandlingGenerator.apply(genericFaker));

			if (detachedBuilder.getPriceInnerRecordHandling() == PriceInnerRecordHandling.NONE) {
				generateRandomPrices(schema, null, uniqueSequencer, genericFaker, allCurrencies, allPriceLists, detachedBuilder);
			} else {
				final Integer numberOfInnerRecords = genericFaker.random().nextInt(2, 15);
				final Set<Integer> alreadyAssignedInnerIds = new HashSet<>();
				for (int i = 0; i < numberOfInnerRecords; i++) {
					int innerRecordId;
					do {
						innerRecordId = genericFaker.random().nextInt(1, numberOfInnerRecords + 1);
					} while (alreadyAssignedInnerIds.contains(innerRecordId));

					alreadyAssignedInnerIds.add(innerRecordId);
					generateRandomPrices(schema, innerRecordId, uniqueSequencer, genericFaker, allCurrencies, allPriceLists, detachedBuilder);
				}
			}
		}
	}

	private static void generateRandomPrices(
		@Nonnull EntitySchemaContract schema,
		@Nullable Integer innerRecordId,
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull Faker genericFaker,
		@Nonnull Set<Currency> allCurrencies,
		@Nonnull Set<String> allPriceLists,
		@Nonnull EntityBuilder detachedBuilder
	) {
		final List<Currency> usedCurrencies = pickRandomFromSet(genericFaker, allCurrencies);
		Iterator<Currency> currencyToUse = null;
		final Integer priceCount = genericFaker.random().nextInt(1, allPriceLists.size());
		final LinkedHashSet<String> priceListsToUse = new LinkedHashSet<>(allPriceLists);
		detachedBuilder.getPrices().stream().map(it -> it.getPriceList()).forEach(it -> priceListsToUse.remove(it));

		for (int i = 0; i < priceCount; i++) {
			if (currencyToUse == null || !currencyToUse.hasNext()) {
				currencyToUse = usedCurrencies.iterator();
			}
			if (priceListsToUse.isEmpty()) {
				return;
			}
			final String priceList = pickRandomOneFromSet(genericFaker, priceListsToUse);
			// avoid generating multiple prices for the same price list
			priceListsToUse.remove(priceList);
			final BigDecimal basePrice = new BigDecimal(genericFaker.commerce().price());
			final DateTimeRange validity = genericFaker.bool().bool() ? DATE_TIME_RANGES[genericFaker.random().nextInt(DATE_TIME_RANGES.length)] : null;
			final boolean randomSellableFlag = genericFaker.random().nextInt(8) == 0;
			final boolean sellable;
			if (PRICE_LIST_REFERENCE.equals(priceList)) {
				sellable = false;
			} else if (PRICE_LIST_BASIC.equals(priceList)) {
				sellable = true;
			} else {
				sellable = randomSellableFlag;
			}
			final Integer priceId = uniqueSequencer.merge(new PriceKey(schema.getName()), 1, Integer::sum);
			final Currency currency = currencyToUse.next();
			final BigDecimal basePriceWithTax = basePrice.multiply(TAX_MULTIPLICATOR).setScale(2, RoundingMode.HALF_UP);

			detachedBuilder.setPrice(
				priceId,
				priceList,
				currency,
				innerRecordId,
				basePrice,
				TAX_RATE,
				basePriceWithTax,
				validity,
				sellable
			);
		}
	}

	private static void generateRandomReferences(
		@Nonnull EntitySchemaContract schema,
		@Nonnull BiFunction<String, Faker, Integer> referencedEntityResolver,
		@Nonnull Map<Object, Integer> globalUniqueSequencer,
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull Map<Integer, Integer> parameterGroupIndex,
		@Nonnull SortableAttributesChecker sortableAttributesHolder,
		@Nonnull Function<Locale, Faker> localeFaker,
		@Nonnull Faker genericFaker,
		@Nonnull EntityBuilder detachedBuilder,
		@Nonnull Collection<Locale> usedLocales,
		@Nonnull Collection<Locale> allLocales
	) {
		final Set<String> referencableEntityTypes = schema.getReferences()
			.values()
			.stream()
			.map(ReferenceSchemaContract::getReferencedEntityType)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		final List<String> referencedTypes = Stream.concat(
			pickRandomFromSet(genericFaker, referencableEntityTypes).stream(),
			schema.getReferences().values()
				.stream()
				.filter(it -> it.isReferencedEntityTypeManaged())
				.filter(it -> it.getCardinality() == Cardinality.ONE_OR_MORE || it.getCardinality() == Cardinality.EXACTLY_ONE)
				.map(it -> it.getReferencedEntityType())
		)
			.distinct()
			.toList();
		for (String referencedType : referencedTypes) {
			final ReferenceSchemaContract referenceSchema = schema.getReference(referencedType).orElseThrow();
			final int initialCount;
			if (Entities.CATEGORY.equals(referencedType)) {
				initialCount = genericFaker.random().nextInt(4);
			} else if (Entities.STORE.equals(referencedType)) {
				initialCount = genericFaker.random().nextInt(8);
			} else if (Entities.PARAMETER.equals(referencedType)) {
				initialCount = genericFaker.random().nextInt(16);
			} else {
				initialCount = 1;
			}

			final int existingCount = detachedBuilder.getReferences(referencedType).size();
			final int count;

			switch (referenceSchema.getCardinality()) {
				case ZERO_OR_ONE -> count = Math.min(initialCount, 1 - existingCount);
				case EXACTLY_ONE -> count = 1 - existingCount;
				case ZERO_OR_MORE -> count = Math.min(initialCount, 30 - existingCount);
				case ONE_OR_MORE -> count = Math.min(Math.max(initialCount, 1), 30 - existingCount);
				default -> throw new IllegalStateException("Unknown cardinality!");
			}

			final Predicate<String> attributePredicate = new SingleSortableAttributePredicate(
				referenceSchema, detachedBuilder
			);
			for (int i = 0; i < count; i++) {
				final Integer referencedEntity = referencedEntityResolver.apply(referencedType, genericFaker);
				if (referencedEntity != null) {
					detachedBuilder.setReference(
						referencedType,
						Objects.requireNonNull(referencedEntity),
						thatIs -> {
							if (referenceSchema.isReferencedGroupTypeManaged()) {
								thatIs.setGroup(
									referenceSchema.getReferencedGroupType(),
									parameterGroupIndex.computeIfAbsent(
										referencedEntity,
										parameterId -> referencedEntityResolver.apply(referenceSchema.getReferencedGroupType(), genericFaker)
									)
								);
							}
							sortableAttributesHolder.executeWithPredicate(
								Set::isEmpty,
								() -> generateRandomAttributes(
									schema.getName(), referenceSchema.getAttributes().values(),
									globalUniqueSequencer,
									uniqueSequencer,
									sortableAttributesHolder,
									attributePredicate,
									localeFaker, genericFaker, thatIs, usedLocales, allLocales
								)
							);
						}
					);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Serializable> void generateAndSetAttribute(
		@Nonnull Map<Object, Integer> globalUniqueSequencer,
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull SortableAttributesChecker sortableAttributesChecker,
		@Nonnull AttributesEditor<?> attributesBuilder,
		@Nonnull AttributeSchemaContract attribute,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nonnull Faker fakerToUse,
		@Nonnull Consumer<T> generatedValueWriter
	) {
		Object value;
		int sanityCheck = 0;
		do {
			final Map<Object, Integer> chosenUniqueSequencer = attribute instanceof GlobalAttributeSchemaContract globalAttributeSchema && globalAttributeSchema.isUniqueGlobally() ?
				globalUniqueSequencer : uniqueSequencer;
			if (String.class.equals(type)) {
				value = generateRandomString(chosenUniqueSequencer, attributesBuilder, attribute, entityType, attributeName, fakerToUse);
			} else if (Boolean.class.equals(type)) {
				value = fakerToUse.bool().bool();
			} else if (Integer.class.equals(type)) {
				value = generateRandomInteger(chosenUniqueSequencer, attribute, attributeName, fakerToUse);
			} else if (Long.class.equals(type)) {
				value = generateRandomLong(chosenUniqueSequencer, attribute, attributeName, fakerToUse);
			} else if (BigDecimal.class.equals(type)) {
				value = generateRandomBigDecimal(fakerToUse, attribute.getIndexedDecimalPlaces());
			} else if (type.isArray() && BigDecimal.class.equals(type.getComponentType())) {
				final BigDecimal[] randomArray = new BigDecimal[fakerToUse.random().nextInt(8)];
				for (int i = 0; i < randomArray.length; i++) {
					randomArray[i] = generateRandomBigDecimal(fakerToUse, attribute.getIndexedDecimalPlaces());
				}
				value = randomArray;
			} else if (OffsetDateTime.class.equals(type)) {
				value = generateRandomOffsetDateTime(fakerToUse);
			} else if (LocalDateTime.class.equals(type)) {
				value = generateRandomLocalDateTime(fakerToUse);
			} else if (LocalDate.class.equals(type)) {
				value = generateRandomLocalDate(fakerToUse);
			} else if (LocalTime.class.equals(type)) {
				value = generateRandomLocalTime(fakerToUse);
			} else if (Currency.class.equals(type)) {
				value = pickRandomOneFromSet(fakerToUse, CURRENCIES_SET);
			} else if (Locale.class.equals(type)) {
				value = pickRandomOneFromSet(fakerToUse, LOCALES_SET);
			} else if (DateTimeRange.class.equals(type)) {
				value = generateRandomDateTimeRange(fakerToUse);
			} else if (IntegerNumberRange.class.equals(type)) {
				value = generateRandomNumberRange(fakerToUse);
			} else if (type.isArray() && IntegerNumberRange.class.equals(type.getComponentType())) {
				final IntegerNumberRange[] randomArray = new IntegerNumberRange[fakerToUse.random().nextInt(8)];
				for (int i = 0; i < randomArray.length; i++) {
					randomArray[i] = generateRandomNumberRange(fakerToUse);
				}
				value = randomArray;
			} else if (Multiple.class.equals(type)) {
				value = new Multiple(fakerToUse.random().nextInt(10000), fakerToUse.random().nextInt(10000));
			} else if (type.isEnum()) {
				final Object[] values = type.getEnumConstants();
				value = values[fakerToUse.random().nextInt(values.length)];
			} else {
				throw new IllegalArgumentException("Unsupported auto-generated value type: " + type);
			}
			if (attribute.isSortable()) {
				value = sortableAttributesChecker.getUniqueAttribute(attributeName, value);
			}
		} while (value == null && sanityCheck++ < 1000);

		if (attribute.isSortable() && value == null) {
			throw new EvitaInternalError("Cannot generate unique " + attributeName + " even in 1000 iterations!");
		}

		generatedValueWriter.accept((T) value);
	}

	private static <T extends Serializable> T generateRandomDateTimeRange(@Nonnull Faker fakerToUse) {
		final T value;
		value = (T) (DATE_TIME_RANGES[fakerToUse.random().nextInt(DATE_TIME_RANGES.length)]);
		return value;
	}

	private static <T extends Serializable> T generateRandomNumberRange(@Nonnull Faker fakerToUse) {
		final T value;
		final int from = fakerToUse.number().numberBetween(1, 100);
		final int to = fakerToUse.number().numberBetween(from, from + 100);
		value = (T) (IntegerNumberRange.between(from, to));
		return value;
	}

	@Nonnull
	private static <T extends Serializable> T generateRandomLong(
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull AttributeSchemaContract attribute,
		@Nonnull String attributeName,
		@Nonnull Faker fakerToUse
	) {
		final T value;
		if (attribute.isUnique()) {
			value = (T) uniqueSequencer.merge(new AttributeKey(attributeName), 1, Integer::sum);
		} else {
			value = (T) (Long) fakerToUse.number().numberBetween(1L, 1000000L);
		}
		return value;
	}

	@Nonnull
	private static <T extends Serializable> T generateRandomInteger(
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull AttributeSchemaContract attribute,
		@Nonnull String attributeName,
		@Nonnull Faker fakerToUse
	) {
		final T value;
		if (attribute.isUnique()) {
			value = (T) uniqueSequencer.merge(new AttributeKey(attributeName), 1, Integer::sum);
		} else {
			value = (T) (Integer) fakerToUse.number().numberBetween(1, 2000);
		}
		return value;
	}

	private static <T extends Serializable> T generateRandomString(
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull AttributesEditor<?> attributesBuilder,
		@Nonnull AttributeSchemaContract attribute,
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nonnull Faker fakerToUse
	) {
		final T value;
		final String plainEntityType = entityType;
		final String suffix = attribute.isUnique() ? " " + uniqueSequencer.merge(new AttributeKey(attributeName), 1, Integer::sum) : "";
		final Optional<String> assignedName = attributesBuilder.getAttributeValues()
			.stream()
			.filter(it -> ATTRIBUTE_NAME.equals(it.getKey().getAttributeName()))
			.map(AttributeValue::getValue)
			.map(Objects::toString)
			.findFirst();
		if (Objects.equals(attributeName, ATTRIBUTE_CODE)) {
			if (assignedName.isPresent()) {
				value = (T) StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(assignedName.get() + suffix, '-', "-/");
			} else if (Objects.equals(Entities.BRAND, plainEntityType)) {
				value = (T) StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(fakerToUse.company().name() + suffix, '-', "-/");
			} else if (Objects.equals(Entities.CATEGORY, plainEntityType)) {
				value = (T) StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(fakerToUse.commerce().department() + suffix, '-', "-/");
			} else if (Objects.equals(Entities.PRODUCT, plainEntityType)) {
				value = (T) StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(fakerToUse.commerce().productName() + suffix, '-', "-/");
			} else if (Objects.equals(Entities.PARAMETER_GROUP, plainEntityType)) {
				final Commerce commerce = fakerToUse.commerce();
				value = (T) StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(commerce.promotionCode() + " " + commerce.material() + suffix, '-', "-/");
			} else if (Objects.equals(Entities.PARAMETER, plainEntityType)) {
				final Commerce commerce = fakerToUse.commerce();
				value = (T) StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(commerce.promotionCode() + " " + commerce.material() + " " + commerce.color() + suffix, '-', "-/");
			} else {
				value = (T) StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(fakerToUse.beer().name() + suffix, '-', "-/");
			}
		} else if (Objects.equals(attributeName, ATTRIBUTE_NAME)) {
			if (Objects.equals(Entities.BRAND, plainEntityType)) {
				value = (T) (fakerToUse.company().name() + suffix);
			} else if (Objects.equals(Entities.CATEGORY, plainEntityType)) {
				value = (T) (fakerToUse.commerce().department() + suffix);
			} else if (Objects.equals(Entities.PRODUCT, plainEntityType)) {
				value = (T) (fakerToUse.commerce().productName() + suffix);
			} else if (Objects.equals(Entities.PRICE_LIST, plainEntityType)) {
				value = (T) (PRICE_LIST_NAMES[fakerToUse.random().nextInt(PRICE_LIST_NAMES.length)]);
			} else if (Objects.equals(Entities.PARAMETER_GROUP, plainEntityType)) {
				final Commerce commerce = fakerToUse.commerce();
				value = (T) (commerce.promotionCode() + " " + commerce.material() + suffix);
			} else if (Objects.equals(Entities.PARAMETER, plainEntityType)) {
				final Commerce commerce = fakerToUse.commerce();
				value = (T) (commerce.promotionCode() + " " + commerce.material() + " " + commerce.color() + suffix);
			} else {
				value = (T) (fakerToUse.beer().name() + suffix);
			}
		} else if (Objects.equals(attributeName, ATTRIBUTE_URL)) {
			if (assignedName.isPresent()) {
				value = (T) url(fakerToUse, assignedName.get() + suffix);
			} else if (Objects.equals(Entities.BRAND, plainEntityType)) {
				value = (T) (fakerToUse.company().url() + StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(suffix, '-', "-/"));
			} else if (Objects.equals(Entities.CATEGORY, plainEntityType)) {
				value = (T) url(fakerToUse, fakerToUse.commerce().department() + suffix);
			} else if (Objects.equals(Entities.PRODUCT, plainEntityType)) {
				value = (T) url(fakerToUse, fakerToUse.commerce().productName() + suffix);
			} else if (Objects.equals(Entities.PARAMETER_GROUP, plainEntityType)) {
				final Commerce commerce = fakerToUse.commerce();
				value = (T) url(fakerToUse, commerce.promotionCode() + " " + commerce.material() + suffix);
			} else if (Objects.equals(Entities.PARAMETER, plainEntityType)) {
				final Commerce commerce = fakerToUse.commerce();
				value = (T) url(fakerToUse, commerce.promotionCode() + " " + commerce.material() + " " + commerce.color() + suffix);
			} else {
				value = (T) url(fakerToUse, fakerToUse.beer().name() + suffix);
			}
		} else {
			value = (T) fakerToUse.beer().name();
		}
		return value;
	}

	@Nonnull
	private static <T extends Serializable> T generateRandomBigDecimal(
		@Nonnull Faker fakerToUse,
		@Nonnull int indexedDecimalPlaces
	) {
		Assert.isTrue(indexedDecimalPlaces >= 0, "Indexed decimal places must be positive or zero!");
		final BigDecimal value;
		final long decimalNumber = fakerToUse.number().numberBetween(
			50 * Math.round(Math.pow(10, indexedDecimalPlaces)),
			1000 * Math.round(Math.pow(10, indexedDecimalPlaces))
		);
		value = BigDecimal.valueOf(decimalNumber);
		//noinspection unchecked
		return (T) (indexedDecimalPlaces > 0 ? value.setScale(indexedDecimalPlaces, RoundingMode.UNNECESSARY).divide(BigDecimal.valueOf(Math.pow(10, indexedDecimalPlaces)).setScale(0), RoundingMode.UNNECESSARY) : value);
	}

	@Nonnull
	private static <T extends Serializable> T generateRandomOffsetDateTime(@Nonnull Faker fakerToUse) {
		return (T) OffsetDateTime.of(
			fakerToUse.number().numberBetween(2000, 2020),
			fakerToUse.number().numberBetween(1, 12),
			fakerToUse.number().numberBetween(1, 28),
			fakerToUse.number().numberBetween(0, 23),
			fakerToUse.number().numberBetween(0, 59),
			fakerToUse.number().numberBetween(0, 59),
			0,
			ZoneOffset.UTC
		);
	}

	@Nonnull
	private static <T extends Serializable> T generateRandomLocalDateTime(@Nonnull Faker fakerToUse) {
		return (T) LocalDateTime.of(
			fakerToUse.number().numberBetween(2000, 2020),
			fakerToUse.number().numberBetween(1, 12),
			fakerToUse.number().numberBetween(1, 28),
			fakerToUse.number().numberBetween(0, 23),
			fakerToUse.number().numberBetween(0, 59),
			fakerToUse.number().numberBetween(0, 59),
			0
		);
	}

	@Nonnull
	private static <T extends Serializable> T generateRandomLocalDate(@Nonnull Faker fakerToUse) {
		return (T) LocalDate.of(
			fakerToUse.number().numberBetween(2000, 2020),
			fakerToUse.number().numberBetween(1, 12),
			fakerToUse.number().numberBetween(1, 28)
		);
	}

	@Nonnull
	private static <T extends Serializable> T generateRandomLocalTime(@Nonnull Faker fakerToUse) {
		return (T) LocalTime.of(
			fakerToUse.number().numberBetween(0, 23),
			fakerToUse.number().numberBetween(0, 59),
			fakerToUse.number().numberBetween(0, 59),
			0
		);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Serializable> void generateAndSetAssociatedData(
		@Nonnull AssociatedDataSchemaContract associatedData,
		@Nonnull Faker genericFaker,
		@Nonnull Consumer<T> generatedValueWriter
	) {
		if (associatedData.getType().isArray()) {
			if (Integer.class.equals(associatedData.getType().getComponentType())) {
				final Integer[] newValue = new Integer[genericFaker.random().nextInt(8)];
				for (int i = 0; i < newValue.length; i++) {
					newValue[i] = genericFaker.random().nextInt(10000);
				}
				generatedValueWriter.accept((T) newValue);
			}
		} else {
			final Constructor<? extends Serializable> defaultConstructor = REFLECTION_LOOKUP.findConstructor(associatedData.getType(), Set.of(new ArgumentKey("root", DataItem.class)));
			try {
				generatedValueWriter.accept(
					(T) defaultConstructor.newInstance(DataItemMap.EMPTY)
				);
			} catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
				throw new EvitaInternalError("Test associated data class " + defaultConstructor.toGenericString() + " threw exception!", e);
			}
		}
	}

	private static String url(@Nonnull Faker faker, @Nonnull String name) {
		return "https://www.evita." + faker.resolve("internet.domain_suffix") + "/" + StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(name, '-', "-/");
	}

	@Nullable
	private static Hierarchy getHierarchyIfNeeded(@Nonnull PMPTT hierarchies, @Nonnull EntitySchemaContract schema) {
		return schema.isWithHierarchy() ? getHierarchy(hierarchies, schema.getName()) : null;
	}

	public DataGenerator() {
		this.priceInnerRecordHandlingGenerator = faker -> PriceInnerRecordHandling.NONE;
	}

	public DataGenerator(Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator) {
		this.priceInnerRecordHandlingGenerator = priceInnerRecordHandlingGenerator;
	}

	/**
	 * Clears internal data structures.
	 */
	public void clear() {
		this.uniqueSequencer.clear();
		this.sortableAttributesChecker.clear();
		this.parameterIndex.clear();
		this.hierarchies
			.getExistingHierarchyCodes()
			.forEach(this.hierarchies::removeHierarchy);
	}

	/**
	 * Generates requested number of Evita entities fully setup with data according to passed schema definition.
	 * Initialization occurs randomly, but respects passed seed so that when called multiple times with same configuration
	 * in passed arguments the result is same.
	 *
	 * @param referencedEntityResolver for accessing random referenced entities
	 * @param seed                     makes generation pseudorandom
	 */
	public Stream<EntityBuilder> generateEntities(
		@Nonnull EntitySchemaContract schema,
		@Nonnull BiFunction<String, Faker, Integer> referencedEntityResolver,
		long seed
	) {
		final Map<Object, Integer> globalUniqueSequencer = this.uniqueSequencer.computeIfAbsent(
			null,
			serializable -> new HashMap<>()
		);
		final Map<Object, Integer> uniqueSequencer = this.uniqueSequencer.computeIfAbsent(
			schema.getName(),
			serializable -> new HashMap<>()
		);
		final SortableAttributesChecker sortableAttributesHolder = this.sortableAttributesChecker.computeIfAbsent(
			schema.getName(),
			serializable -> new SortableAttributesChecker()
		);
		final Map<Locale, Faker> localeFaker = new HashMap<>();
		final Function<Locale, Faker> localizedFakerFetcher = locale -> localeFaker.computeIfAbsent(locale, theLocale -> new Faker(new Random(seed)));
		final Faker genericFaker = new Faker(new Random(seed));
		final Set<Locale> allLocales = schema.getLocales();
		final Set<Currency> allCurrencies = new LinkedHashSet<>(Arrays.asList(CURRENCIES));
		final Set<String> allPriceLists = new LinkedHashSet<>(Arrays.asList(PRICE_LIST_NAMES));
		final Hierarchy hierarchy = getHierarchyIfNeeded(hierarchies, schema);

		return Stream.generate(() -> {
			// create new entity of desired type
			final EntityBuilder detachedBuilder = new InitialEntityBuilder(
				schema,
				// generate unique primary key (only when required by schema)
				schema.isWithGeneratedPrimaryKey() ? null : uniqueSequencer.merge(new SchemaKey(schema.getName()), 1, Integer::sum)
			);

			generateRandomHierarchy(schema, referencedEntityResolver, hierarchy, genericFaker, detachedBuilder);

			final List<Locale> usedLocales = pickRandomFromSet(genericFaker, allLocales);

			generateRandomAttributes(schema.getName(), schema.getAttributes().values(), globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder, TRUE_PREDICATE, localizedFakerFetcher, genericFaker, detachedBuilder, usedLocales, allLocales);
			generateRandomAssociatedData(schema, genericFaker, detachedBuilder, usedLocales, allLocales);

			generateRandomPrices(schema, uniqueSequencer, genericFaker, allCurrencies, allPriceLists, detachedBuilder, priceInnerRecordHandlingGenerator);
			generateRandomReferences(schema, referencedEntityResolver, globalUniqueSequencer, uniqueSequencer, parameterIndex, sortableAttributesHolder, localizedFakerFetcher, genericFaker, detachedBuilder, usedLocales, allLocales);

			return detachedBuilder;
		});
	}

	/**
	 * Creates function that randomly modifies contents of the existing entity and returns modified builder.
	 */
	@Nonnull
	public ModificationFunction createModificationFunction(
		@Nonnull BiFunction<String, Faker, Integer> referencedEntityResolver,
		@Nonnull Random random
	) {
		final Map<Locale, Faker> localeFaker = new HashMap<>();
		final Function<Locale, Faker> localizedFakerFetcher = locale -> localeFaker.computeIfAbsent(locale, theLocale -> new Faker(random));
		final Faker genericFaker = new Faker(random);
		final HashSet<Currency> allCurrencies = new LinkedHashSet<>(Arrays.asList(CURRENCIES));
		final Set<String> allPriceLists = new LinkedHashSet<>(Arrays.asList(PRICE_LIST_NAMES));

		return new ModificationFunction(
			genericFaker, hierarchies, uniqueSequencer, sortableAttributesChecker, allCurrencies, allPriceLists,
			priceInnerRecordHandlingGenerator, referencedEntityResolver, localizedFakerFetcher, parameterIndex
		);
	}

	@Nonnull
	public SealedEntitySchema getSamplePriceListSchema(@Nonnull EvitaSessionContract evitaSession) {
		return getSamplePriceListSchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			}
		);
	}

	@Nonnull
	public SealedEntitySchema getSamplePriceListSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.PRICE_LIST)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* let Evita generates the key */
			.withGeneratedPrimaryKey()
			/* price lists are not organized in the tree */
			.withoutHierarchy()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, CZECH_LOCALE)
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable())
			.withAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable());

		addPossiblyGlobalAttributes(evitaSession, schemaBuilder, ATTRIBUTE_CODE);

		/* finally apply schema changes */
		final EntitySchemaContract result = schemaUpdater.apply(schemaBuilder);
		return result instanceof EntitySchemaDecorator esd ? esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
	}

	@Nonnull
	public SealedEntitySchema getSampleCategorySchema(@Nonnull EvitaSessionContract evitaSession) {
		return getSampleCategorySchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			}
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleCategorySchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Consumer<EntitySchemaEditor.EntitySchemaBuilder> schemaAlterLogic) {
		return getSampleCategorySchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			},
			schemaAlterLogic
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleCategorySchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull  Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		return getSampleCategorySchema(evitaSession, schemaUpdater, null);
	}

	@Nonnull
	public SealedEntitySchema getSampleCategorySchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull  Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater, @Nonnull Consumer<EntitySchemaEditor.EntitySchemaBuilder> schemaAlterLogic) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.CATEGORY)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* for sake of generating hierarchies we need to generate keys by ourselves */
			.withoutGeneratedPrimaryKey()
			/* categories are organized in the tree */
			.withHierarchy()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, CZECH_LOCALE)
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable())
			.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable())
			.withAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable());

		addPossiblyGlobalAttributes(evitaSession, schemaBuilder, ATTRIBUTE_CODE, ATTRIBUTE_URL);
		ofNullable(schemaAlterLogic).ifPresent(it -> it.accept(schemaBuilder));

		/* finally apply schema changes */
		final EntitySchemaContract result = schemaUpdater.apply(schemaBuilder);
		return result instanceof EntitySchemaDecorator esd ? esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
	}

	@Nonnull
	public SealedEntitySchema getSampleBrandSchema(@Nonnull EvitaSessionContract evitaSession) {
		return getSampleBrandSchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			}
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleBrandSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.BRAND)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* let Evita generates the key */
			.withGeneratedPrimaryKey()
			/* brands are not organized in the tree */
			.withoutHierarchy()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, CZECH_LOCALE)
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable());

		addPossiblyGlobalAttributes(evitaSession, schemaBuilder, ATTRIBUTE_CODE, ATTRIBUTE_URL);

		/* finally apply schema changes */
		final EntitySchemaContract result = schemaUpdater.apply(schemaBuilder);
		return result instanceof EntitySchemaDecorator esd ? esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
	}

	@Nonnull
	public SealedEntitySchema getSampleStoreSchema(@Nonnull EvitaSessionContract evitaSession) {
		return getSampleStoreSchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			}
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleStoreSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.STORE)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* let Evita generates the key */
			.withGeneratedPrimaryKey()
			/* stores are not organized in the tree */
			.withoutHierarchy()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, CZECH_LOCALE)
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable());

		addPossiblyGlobalAttributes(evitaSession, schemaBuilder, ATTRIBUTE_CODE);

		/* finally apply schema changes */
		final EntitySchemaContract result = schemaUpdater.apply(schemaBuilder);

		return result instanceof EntitySchemaDecorator esd ? esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
	}

	@Nonnull
	public SealedEntitySchema getSampleParameterGroupSchema(@Nonnull EvitaSessionContract evitaSession) {
		return getSampleParameterGroupSchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			}
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleParameterGroupSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.PARAMETER_GROUP)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* let Evita generates the key */
			.withGeneratedPrimaryKey()
			/* stores are not organized in the tree */
			.withoutHierarchy()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, CZECH_LOCALE)
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable());

		addPossiblyGlobalAttributes(evitaSession, schemaBuilder, ATTRIBUTE_CODE);

		/* finally apply schema changes */
		final EntitySchemaContract result = schemaUpdater.apply(schemaBuilder);
		return result instanceof EntitySchemaDecorator esd ? esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
	}

	@Nonnull
	public SealedEntitySchema getSampleParameterSchema(@Nonnull EvitaSessionContract evitaSession) {
		return getSampleParameterSchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			}
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleParameterSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull  Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.PARAMETER)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* let Evita generates the key */
			.withGeneratedPrimaryKey()
			/* stores are not organized in the tree */
			.withoutHierarchy()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, CZECH_LOCALE)
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable());

		addPossiblyGlobalAttributes(evitaSession, schemaBuilder, ATTRIBUTE_CODE);

		/* finally apply schema changes */
		final EntitySchemaContract result = schemaUpdater.apply(schemaBuilder);
		return result instanceof EntitySchemaDecorator esd ? esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
	}

	@Nonnull
	public SealedEntitySchema getSampleProductSchema(@Nonnull EvitaSessionContract evitaSession) {
		return getSampleProductSchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			}
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleProductSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Consumer<EntitySchemaEditor.EntitySchemaBuilder> schemaAlterLogic) {
		return getSampleProductSchema(
			evitaSession,
			schemaMutation -> {
				evitaSession.defineEntitySchema(schemaMutation.getName());
				return evitaSession.updateAndFetchEntitySchema(schemaMutation);
			},
			schemaAlterLogic
		);
	}

	@Nonnull
	public SealedEntitySchema getSampleProductSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		return getSampleProductSchema(evitaSession, schemaUpdater, null);
	}

	@Nonnull
	public SealedEntitySchema getSampleProductSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater, @Nonnull Consumer<EntitySchemaEditor.EntitySchemaBuilder> schemaAlterLogic) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* let Evita generates the key */
			.withGeneratedPrimaryKey()
			/* product are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPriceInCurrency(CURRENCIES)
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, CZECH_LOCALE)
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable().nullable())
			.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable())
			.withAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable())
			.withAttribute(ATTRIBUTE_ALIAS, Boolean.class, whichIs -> whichIs.filterable().withDefaultValue(false))
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, whichIs -> whichIs.nullable())
			.withAssociatedData(ASSOCIATED_DATA_LABELS, Labels.class, whichIs -> whichIs.localized().nullable())
			/* here we define facets that relate to another entities stored in Evita */
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.filterable()
						.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class, thatIs -> thatIs.sortable().nullable())
			)
			/* for indexed facets we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.filterable().faceted()
			)
			/* facets may be also represented be entities unknown to Evita */
			.withReferenceToEntity(
				Entities.STORE,
				Entities.STORE,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.filterable().faceted()
			);

		addPossiblyGlobalAttributes(evitaSession, schemaBuilder, ATTRIBUTE_CODE, ATTRIBUTE_URL);

		/* apply custom logic if passed */
		ofNullable(schemaAlterLogic)
			.ifPresent(it -> it.accept(schemaBuilder));

		/* finally apply schema changes */
		final EntitySchemaContract result = schemaUpdater.apply(schemaBuilder);
		return result instanceof EntitySchemaDecorator esd ? esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
	}

	/**
	 * Returns hierarchy connected with passed entity type.
	 */
	@Nonnull
	public Hierarchy getHierarchy(@Nonnull String entityType) {
		return hierarchies.getOrCreateHierarchy(entityType, (short) 5, (short) 10);
	}

	/**
	 * Returns hierarchy connected with passed entity type.
	 */
	@Nonnull
	public Hierarchy getHierarchy(@Nonnull Enum<?> entityType) {
		return hierarchies.getOrCreateHierarchy(entityType.toString(), (short) 5, (short) 10);
	}

	/**
	 * Returns index that maps parameters to their groups.
	 */
	public Map<Integer, Integer> getParameterIndex() {
		return parameterIndex;
	}

	/**
	 * Registers attributes `code` and `url` that may be defined on the catalog level.
	 */
	private void addPossiblyGlobalAttributes(@Nonnull EvitaSessionContract evitaSession, @Nonnull EntitySchemaEditor.EntitySchemaBuilder schemaBuilder, @Nonnull String... attributeNames) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		if (Arrays.stream(attributeNames).anyMatch(it -> it.equals(ATTRIBUTE_CODE))) {
			if (catalogSchema.getAttribute(ATTRIBUTE_CODE).isEmpty()) {
				schemaBuilder.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique().nullable());
			} else {
				schemaBuilder.withGlobalAttribute(ATTRIBUTE_CODE);
			}
		}

		if (Arrays.stream(attributeNames).anyMatch(it -> it.equals(ATTRIBUTE_URL))) {
			if (catalogSchema.getAttribute(ATTRIBUTE_URL).isEmpty()) {
				schemaBuilder.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.unique().localized().nullable());
			} else {
				schemaBuilder.withGlobalAttribute(ATTRIBUTE_URL);
			}
		}
	}

	@Data
	public static class ReferencedFileSet implements Serializable {
		@Serial private static final long serialVersionUID = -1355676966187183143L;
		private String someField = "someValue";

	}

	@Data
	public static class Labels implements Serializable {
		@Serial private static final long serialVersionUID = 1121150156843379388L;
		private String someField = "someValue";

	}

	@Data
	private static class SchemaKey {
		private final String type;
	}

	@Data
	private static class AttributeKey {
		private final String name;
	}

	@Data
	private static class PriceKey {
		private final String entityType;
	}

	private static class SortableAttributesChecker {
		private final Map<String, Map<Object, Integer>> sortableAttributes = new HashMap<>();
		private Predicate<Set<Object>> canAddAttribute;

		public Object getUniqueAttribute(@Nonnull String attributeName, @Nonnull Object value) {
			final Map<Object, Integer> uniqueValueMap = sortableAttributes.computeIfAbsent(attributeName, an -> new HashMap<>());
			final Integer count = uniqueValueMap.get(value);
			if (count != null) {
				if (value instanceof String) {
					final Integer newCount = count + 1;
					uniqueValueMap.put(value, newCount);
					return value + "_" + newCount;
				} else {
					return null;
				}
			} else {
				uniqueValueMap.put(value, 1);
			}
			return value;
		}

		public void executeWithPredicate(@Nonnull Predicate<Set<Object>> canAddAttribute, @Nonnull Runnable runnable) {
			Assert.isTrue(this.canAddAttribute == null, "Cannot nest predicated!");
			try {
				this.canAddAttribute = canAddAttribute;
				runnable.run();
			} finally {
				this.canAddAttribute = null;
			}
		}
	}

	@RequiredArgsConstructor
	private static class SingleSortableAttributePredicate implements Predicate<String> {
		private final ReferenceSchemaContract reference;
		private final Set<String> alreadyGenerated = new HashSet<>();

		public SingleSortableAttributePredicate(@Nonnull ReferenceSchemaContract reference, @Nonnull EntityBuilder entityBuilder) {
			this.reference = reference;
			entityBuilder
				.getReferences(reference.getReferencedEntityType())
				.forEach(it ->
					reference.getAttributes()
						.values()
						.stream()
						.filter(AttributeSchemaContract::isSortable)
						.forEach(attr -> alreadyGenerated.add(attr.getName()))
				);
		}

		@Override
		public boolean test(@Nonnull String attributeName) {
			final AttributeSchemaContract attributeSchema = reference.getAttribute(attributeName).orElseThrow();
			if (attributeSchema.isSortable() && attributeSchema.isNullable()) {
				if (alreadyGenerated.contains(attributeName)) {
					return false;
				} else {
					alreadyGenerated.add(attributeName);
				}
			}
			return true;
		}

	}

	@RequiredArgsConstructor
	public static class ModificationFunction implements Function<SealedEntity, EntityBuilder> {
		private final Faker genericFaker;
		private final PMPTT hierarchies;
		private final Map<Serializable, Map<Object, Integer>> uniqueSequencer;
		private final Map<Serializable, SortableAttributesChecker> sortableAttributesChecker;
		private final Set<Currency> allCurrencies;
		private final Set<String> allPriceLists;
		private final Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator;
		private final BiFunction<String, Faker, Integer> referencedEntityResolver;
		private final Function<Locale, Faker> localizedFakerFetcher;
		private final Map<Integer, Integer> parameterIndex;

		@Override
		public EntityBuilder apply(@Nonnull SealedEntity existingEntity) {
			final EntityBuilder detachedBuilder = existingEntity.openForWrite();
			final EntitySchemaContract schema = existingEntity.getSchema();
			final Set<Locale> allLocales = schema.getLocales();
			final Map<Object, Integer> globalUniqueSequencer = this.uniqueSequencer.computeIfAbsent(
				null,
				serializable -> new HashMap<>()
			);
			final Map<Object, Integer> uniqueSequencer = this.uniqueSequencer.computeIfAbsent(
				schema.getName(),
				serializable -> new HashMap<>()
			);
			final SortableAttributesChecker sortableAttributesHolder = this.sortableAttributesChecker.computeIfAbsent(
				schema.getName(),
				serializable -> new SortableAttributesChecker()
			);

			// randomly delete hierarchy placement
			if (detachedBuilder.getHierarchicalPlacement().isPresent() && genericFaker.random().nextInt(3) == 0) {
				detachedBuilder.removeHierarchicalPlacement();
			}
			generateRandomHierarchy(schema, referencedEntityResolver, getHierarchyIfNeeded(hierarchies, schema), genericFaker, detachedBuilder);

			final List<Locale> usedLocales = pickRandomFromSet(genericFaker, allLocales);

			// randomly delete attributes
			final Set<AttributesContract.AttributeKey> existingAttributeKeys = new TreeSet<>(detachedBuilder.getAttributeKeys());
			for (AttributesContract.AttributeKey existingAttributeKey : existingAttributeKeys) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removeAttribute(existingAttributeKey.getAttributeName(), existingAttributeKey.getLocale());
				}
			}
			generateRandomAttributes(
				schema.getName(), schema.getAttributes().values(), globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder,
				TRUE_PREDICATE, localizedFakerFetcher, genericFaker, detachedBuilder, usedLocales, allLocales
			);

			// randomly delete associated data
			final Set<AssociatedDataContract.AssociatedDataKey> existingAssociatedDataKeys = new TreeSet<>(detachedBuilder.getAssociatedDataKeys());
			for (AssociatedDataContract.AssociatedDataKey existingAssociatedDataKey : existingAssociatedDataKeys) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removeAssociatedData(existingAssociatedDataKey.getAssociatedDataName(), existingAssociatedDataKey.getLocale());
				}
			}
			generateRandomAssociatedData(schema, genericFaker, detachedBuilder, usedLocales, allLocales);

			// randomly delete prices
			final List<Price.PriceKey> prices = detachedBuilder.getPrices().stream().map(PriceContract::getPriceKey).sorted().collect(Collectors.toList());
			for (Price.PriceKey price : prices) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removePrice(price.getPriceId(), price.getPriceList(), price.getCurrency());
				}
			}
			generateRandomPrices(schema, uniqueSequencer, genericFaker, allCurrencies, allPriceLists, detachedBuilder, priceInnerRecordHandlingGenerator);

			// randomly delete references
			final Collection<ReferenceKey> references = detachedBuilder.getReferences().stream().map(ReferenceContract::getReferenceKey).sorted().collect(Collectors.toList());
			for (ReferenceKey reference : references) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removeReference(reference.referenceName(), reference.primaryKey());
				}
			}
			generateRandomReferences(
				schema, referencedEntityResolver, globalUniqueSequencer, uniqueSequencer, parameterIndex, sortableAttributesHolder,
				localizedFakerFetcher, genericFaker, detachedBuilder, usedLocales, allLocales
			);

			return detachedBuilder;
		}
	}

}
