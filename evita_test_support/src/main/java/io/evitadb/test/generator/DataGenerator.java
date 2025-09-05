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
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.ReflectionLookup.ArgumentKey;
import io.evitadb.utils.StringUtils;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
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
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
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
	static final Serializable GENERIC = Long.MAX_VALUE;
	private static final DecimalFormat PRICE_FORMAT;
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
	public static final BiPredicate<String, Faker> DEFAULT_PRICE_INDEXING_DECIDER = (priceList, faker) -> {
		final boolean randomIndexedFlag = faker.random().nextInt(8) == 0;
		final boolean indexed;
		if (PRICE_LIST_REFERENCE.equals(priceList)) {
			return false;
		} else if (PRICE_LIST_BASIC.equals(priceList)) {
			return true;
		} else {
			return randomIndexedFlag;
		}
	};
	public static final Function<Faker, PriceInnerRecordHandling> ALL_PRICE_INNER_RECORD_HANDLING_GENERATOR = faker -> {
		final int rndPIRH = faker.random().nextInt(10);
		if (rndPIRH < 6) {
			return PriceInnerRecordHandling.NONE;
		} else if (rndPIRH < 8) {
			return PriceInnerRecordHandling.LOWEST_PRICE;
		} else {
			return PriceInnerRecordHandling.SUM;
		}
	};
	public static final String PRICE_LIST_SELLOUT = "sellout";
	public static final String PRICE_LIST_VIP = "vip";
	public static final String PRICE_LIST_B2B = "b2b";
	public static final String PRICE_LIST_INTRODUCTION = "introduction";
	public static final Set<Locale> LOCALES_SET = new LinkedHashSet<>(Arrays.asList(CZECH_LOCALE, Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH));
	public static final Predicate<String> TRUE_PREDICATE = s -> true;
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
	final Map<Serializable, Map<Object, Integer>> uniqueSequencer = new ConcurrentHashMap<>();
	final Map<Serializable, SortableAttributesChecker> sortableAttributesChecker = new ConcurrentHashMap<>();
	final Map<String, Map<Integer, Integer>> parameterIndex = new ConcurrentHashMap<>();
	/**
	 * Holds function that is used for generating price inner record handling strategy.
	 */
	private final Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator;
	/**
	 * Holds predicate that resolves if newly generated price should be indexed.
	 */
	private final BiPredicate<String, Faker> priceIndexingDecider;
	/**
	 * Holds custom generators for specific entity attributes.
	 */
	private final Map<EntityAttribute, Function<Faker, Object>> valueGenerators = new ConcurrentHashMap<>();
	/**
	 * Holds custom generators for specific entity attributes depending on reference they're created for.
	 */
	private final Map<EntityAttribute, BiFunction<ReferenceKey, Faker, Object>> referenceValueGenerators = new ConcurrentHashMap<>();
	/**
	 * Holds information about created hierarchies for generated / modified entities indexed by their type.
	 */
	private final PMPTT hierarchies = new PMPTT(new MemoryStorage());
	/**
	 * Price list to generate prices for.
	 */
	@Setter private final String[] priceLists;
	/**
	 * Currency list to generate prices for.
	 */
	@Setter private final Currency[] currencies;

	static {
		// Prepare DecimalFormat with the locale
		PRICE_FORMAT = (DecimalFormat) DecimalFormat.getInstance(Locale.getDefault());
		PRICE_FORMAT.applyPattern("#0.00");
	}

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
				final boolean generateRoot = hierarchy.getRootItems().size() < 5 && genericFaker.random().nextBoolean();
				final Integer parentKey = generateRoot ? null : referencedEntityResolver.apply(schema.getName(), genericFaker);
				if (parentKey == null) {
					hierarchy.createRootItem(Objects.requireNonNull(detachedBuilder.getPrimaryKey()).toString());
				} else {
					hierarchy.createItem(Objects.requireNonNull(detachedBuilder.getPrimaryKey()).toString(), parentKey.toString());
					detachedBuilder.setParent(parentKey);
				}
			} catch (MaxLevelExceeded | SectionExhausted ignores) {
				// just repeat again
				generateRandomHierarchy(schema, referencedEntityResolver, hierarchy, genericFaker, detachedBuilder);
			}
		}
	}

	private static void generateRandomAttributes(
		@Nonnull String entityType,
		@Nonnull Collection<? extends AttributeSchemaContract> attributeSchema,
		@Nonnull Map<Object, Integer> globalUniqueSequencer,
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull SortableAttributesChecker sortableAttributesHolder,
		@Nonnull Predicate<String> attributeFilter, Function<Locale, Faker> localeFaker,
		@Nonnull Map<EntityAttribute, Function<Faker, Object>> valueGenerators,
		@Nonnull Map<EntityAttribute, BiFunction<ReferenceKey, Faker, Object>> referenceValueGenerators,
		@Nonnull Faker genericFaker,
		@Nonnull AttributesEditor<?, ?> attributesEditor,
		@Nonnull Set<Currency> currencies,
		@Nonnull Set<String> priceLists,
		@Nonnull Collection<Locale> usedLocales,
		@Nonnull Collection<Locale> allLocales,
		@Nullable ReferenceKey referenceKey
	) {
		for (AttributeSchemaContract attribute : attributeSchema) {
			final Class<? extends Serializable> type = attribute.getType();
			final String attributeName = attribute.getName();

			final Function<Faker, Object> genericValueGenerator = valueGenerators.get(new EntityAttribute(entityType, attributeName));
			final BiFunction<ReferenceKey, Faker, Object> referenceValueGenerator = referenceValueGenerators.get(new EntityAttribute(entityType, attributeName));
			final Function<Faker, Object> valueGenerator = referenceKey != null && referenceValueGenerator != null ?
				faker -> referenceValueGenerator.apply(referenceKey, faker) :
				genericValueGenerator;

			// if value generator is specified, the randomness should be implemented in the generator
			if (valueGenerator == null) {
				if (!attributeFilter.test(attributeName)) {
					continue;
				}
				if (!attribute.isUnique() && attribute.isNullable() && genericFaker.random().nextInt(10) == 0) {
					// randomly skip attributes
					continue;
				}
			}

			if (attribute.isLocalized()) {
				final Collection<Locale> localesToGenerate = attribute.isNullable() ? usedLocales : allLocales;
				for (Locale usedLocale : localesToGenerate) {
					generateAndSetAttribute(
						globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder,
						attributesEditor, attribute, currencies, priceLists, usedLocale, type, entityType, attributeName,
						valueGenerator,
						localeFaker.apply(usedLocale),
						value -> attributesEditor.setAttribute(attributeName, usedLocale, value)
					);
				}
			} else {
				generateAndSetAttribute(
					globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder,
					attributesEditor, attribute, currencies, priceLists, null, type, entityType, attributeName,
					valueGenerator, genericFaker,
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
		@Nonnull Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator,
		@Nonnull BiPredicate<String, Faker> priceIndexingDecider
	) {
		if (schema.isWithPrice()) {
			detachedBuilder.setPriceInnerRecordHandling(priceInnerRecordHandlingGenerator.apply(genericFaker));

			if (detachedBuilder.getPriceInnerRecordHandling() == PriceInnerRecordHandling.NONE) {
				generateRandomPrices(schema, null, uniqueSequencer, genericFaker, allCurrencies, allPriceLists, detachedBuilder, priceIndexingDecider);
			} else {
				final Integer numberOfInnerRecords = genericFaker.random().nextInt(2, 15);
				final Set<Integer> alreadyAssignedInnerIds = new HashSet<>();
				for (int i = 0; i < numberOfInnerRecords; i++) {
					int innerRecordId;
					do {
						innerRecordId = genericFaker.random().nextInt(1, numberOfInnerRecords + 1);
					} while (alreadyAssignedInnerIds.contains(innerRecordId));

					alreadyAssignedInnerIds.add(innerRecordId);
					generateRandomPrices(schema, innerRecordId, uniqueSequencer, genericFaker, allCurrencies, allPriceLists, detachedBuilder, priceIndexingDecider);
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
		@Nonnull EntityBuilder detachedBuilder,
		@Nonnull BiPredicate<String, Faker> priceIndexingDecider
	) {
		final List<Currency> usedCurrencies = pickRandomFromSet(genericFaker, allCurrencies);
		final Integer priceCount = genericFaker.random().nextInt(1, allPriceLists.size());
		final LinkedHashSet<String> priceListsToUse = new LinkedHashSet<>(allPriceLists);
		detachedBuilder.getPrices().stream().map(it -> it.priceList()).forEach(it -> priceListsToUse.remove(it));

		for (int i = 0; i < priceCount; i++) {
			if (priceListsToUse.isEmpty()) {
				return;
			}
			final String priceList = pickRandomOneFromSet(genericFaker, priceListsToUse);
			// avoid generating multiple prices for the same price list
			priceListsToUse.remove(priceList);
			final BigDecimal basePrice = new BigDecimal(genericFaker.commerce().price().replace(PRICE_FORMAT.getDecimalFormatSymbols().getDecimalSeparator(), '.'));
			final DateTimeRange validity = genericFaker.bool().bool() ? DATE_TIME_RANGES[genericFaker.random().nextInt(DATE_TIME_RANGES.length)] : null;
			final boolean indexed = priceIndexingDecider.test(priceList, genericFaker);
			final Integer priceId = uniqueSequencer.merge(new PriceKey(schema.getName()), 1, Integer::sum);
			final BigDecimal basePriceWithTax = basePrice.multiply(TAX_MULTIPLICATOR).setScale(2, RoundingMode.HALF_UP);
			for (Currency currency : usedCurrencies) {
				detachedBuilder.setPrice(
					priceId,
					priceList,
					currency,
					innerRecordId,
					basePrice,
					TAX_RATE,
					basePriceWithTax,
					validity,
					indexed
				);
			}
		}
	}

	private static void generateRandomReferences(
		@Nonnull EntitySchemaContract schema,
		@Nonnull BiFunction<String, Faker, Integer> referencedEntityResolver,
		@Nonnull Map<Object, Integer> globalUniqueSequencer,
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull Map<String, Map<Integer, Integer>> parameterGroupIndex,
		@Nonnull SortableAttributesChecker sortableAttributesHolder,
		@Nonnull Map<EntityAttribute, Function<Faker, Object>> valueGenerators,
		@Nonnull Map<EntityAttribute, BiFunction<ReferenceKey, Faker, Object>> referenceValueGenerators,
		@Nonnull Function<Locale, Faker> localeFaker,
		@Nonnull Faker genericFaker,
		@Nonnull EntityBuilder detachedBuilder,
		@Nonnull Set<Currency> currencies,
		@Nonnull Set<String> priceLists,
		@Nonnull Collection<Locale> usedLocales,
		@Nonnull Collection<Locale> allLocales
	) {
		final Set<String> referencableNames = schema.getReferences()
			.values()
			.stream()
			.filter(it -> !(it instanceof ReflectedReferenceSchemaContract))
			.map(ReferenceSchemaContract::getName)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		final List<String> referenceNames = Stream.concat(
				pickRandomFromSet(genericFaker, referencableNames).stream(),
				schema.getReferences().values()
					.stream()
					.filter(it -> !(it instanceof ReflectedReferenceSchemaContract))
					.filter(it -> it.isReferencedEntityTypeManaged())
					.filter(it -> it.getCardinality().getMin() == 1)
					.map(it -> it.getName())
			)
			.distinct()
			.toList();
		for (String referenceName : referenceNames) {
			final ReferenceSchemaContract referenceSchema = schema.getReference(referenceName).orElseThrow();
			final boolean multiple = referenceSchema.getCardinality().getMax() > 1;
			final String referencedType = referenceSchema.getReferencedEntityType();
			final int initialCount;
			if (Entities.CATEGORY.equals(referencedType) && multiple) {
				initialCount = genericFaker.random().nextInt(4);
			} else if (Entities.STORE.equals(referencedType) && multiple) {
				initialCount = genericFaker.random().nextInt(8);
			} else if (Entities.PARAMETER.equals(referencedType) && multiple) {
				initialCount = genericFaker.random().nextInt(16);
			} else if (Entities.PRICE_LIST.equals(referencedType) && multiple) {
				initialCount = genericFaker.random().nextInt(10);
			} else if (Entities.PRODUCT.equals(referencedType) && multiple) {
				initialCount = genericFaker.random().nextInt(30);
			} else if (multiple) {
				initialCount = 10;
			} else {
				initialCount = 1;
			}

			final int existingCount = detachedBuilder.getReferences(referenceName).size();
			final int count;

			switch (referenceSchema.getCardinality()) {
				case ZERO_OR_ONE -> count = Math.min(initialCount, 1 - existingCount);
				case EXACTLY_ONE -> count = 1 - existingCount;
				case ZERO_OR_MORE, ZERO_OR_MORE_WITH_DUPLICATES -> count = Math.min(initialCount, 30 - existingCount);
				case ONE_OR_MORE, ONE_OR_MORE_WITH_DUPLICATES -> count = Math.min(Math.max(initialCount, 1), 30 - existingCount);
				default -> throw new IllegalStateException("Unknown cardinality!");
			}

			final Predicate<String> attributePredicate = new SingleSortableAttributePredicate(
				referenceSchema, detachedBuilder
			);
			for (int i = 0; i < count; i++) {
				final Integer referencedEntity = referenceSchema.isReferencedEntityTypeManaged() ?
					referencedEntityResolver.apply(referencedType, genericFaker) : ((Integer) genericFaker.random().nextInt(100_000));
				if (referencedEntity != null) {
					detachedBuilder.setReference(
						referenceName,
						Objects.requireNonNull(referencedEntity),
						thatIs -> {
							if (referenceSchema.isReferencedGroupTypeManaged()) {
								thatIs.setGroup(
									referenceSchema.getReferencedGroupType(),
									parameterGroupIndex.computeIfAbsent(
										referencedType, __ -> new ConcurrentHashMap<>()
									).computeIfAbsent(
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
									localeFaker, valueGenerators, referenceValueGenerators,
									genericFaker, thatIs, currencies, priceLists, usedLocales, allLocales,
									new ReferenceKey(referenceName, referencedEntity)
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
		@Nonnull AttributesEditor<?, ?> attributesBuilder,
		@Nonnull AttributeSchemaContract attribute,
		@Nonnull Set<Currency> currencies,
		@Nonnull Set<String> priceLists,
		@Nullable Locale locale,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nullable Function<Faker, Object> valueGenerator,
		@Nonnull Faker fakerToUse,
		@Nonnull Consumer<T> generatedValueWriter
	) {
		Object value;
		int sanityCheck = 0;
		do {
			final Map<Object, Integer> chosenUniqueSequencer = attribute instanceof GlobalAttributeSchemaContract globalAttributeSchema && globalAttributeSchema.isUniqueGlobally() ?
				globalUniqueSequencer : uniqueSequencer;
			if (valueGenerator != null) {
				final Object generatedValue = valueGenerator.apply(fakerToUse);
				if (generatedValue == null) {
					Assert.isPremiseValid(attribute.isNullable(), "Attribute " + attributeName + " cannot be null!");
					return;
				}
				value = generatedValue;
			} else if (String.class.equals(type)) {
				value = generateRandomString(chosenUniqueSequencer, attributesBuilder, attribute, entityType, attributeName, priceLists, locale, fakerToUse);
			} else if (type.isArray() && String.class.equals(type.getComponentType())) {
				final String[] randomArray = new String[fakerToUse.random().nextInt(7) + 1];
				for (int i = 0; i < randomArray.length; i++) {
					randomArray[i] = generateRandomString(chosenUniqueSequencer, attributesBuilder, attribute, entityType, attributeName, priceLists, locale, fakerToUse);
				}
				value = randomArray;
			} else if (Boolean.class.equals(type)) {
				value = fakerToUse.bool().bool();
			} else if (Integer.class.equals(type)) {
				value = generateRandomInteger(chosenUniqueSequencer, attribute, attributeName, fakerToUse);
			} else if (Long.class.equals(type)) {
				value = generateRandomLong(chosenUniqueSequencer, attribute, attributeName, fakerToUse);
			} else if (BigDecimal.class.equals(type)) {
				value = generateRandomBigDecimal(fakerToUse, attribute.getIndexedDecimalPlaces());
			} else if (type.isArray() && BigDecimal.class.equals(type.getComponentType())) {
				final BigDecimal[] randomArray = new BigDecimal[fakerToUse.random().nextInt(7) + 1];
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
				value = pickRandomOneFromSet(fakerToUse, currencies);
			} else if (UUID.class.equals(type)) {
				value = new UUID(fakerToUse.random().nextLong(), fakerToUse.random().nextLong());
			} else if (Locale.class.equals(type)) {
				value = pickRandomOneFromSet(fakerToUse, LOCALES_SET);
			} else if (DateTimeRange.class.equals(type)) {
				value = generateRandomDateTimeRange(fakerToUse);
			} else if (IntegerNumberRange.class.equals(type)) {
				value = generateRandomNumberRange(fakerToUse);
			} else if (type.isArray() && IntegerNumberRange.class.equals(type.getComponentType())) {
				final IntegerNumberRange[] randomArray = new IntegerNumberRange[fakerToUse.random().nextInt(7) + 1];
				for (int i = 0; i < randomArray.length; i++) {
					randomArray[i] = generateRandomNumberRange(fakerToUse);
				}
				value = randomArray;
			} else if (type.isEnum()) {
				final Object[] values = type.getEnumConstants();
				value = values[fakerToUse.random().nextInt(values.length)];
			} else {
				throw new IllegalArgumentException("Unsupported auto-generated value type: " + type);
			}
			if (valueGenerator == null && attribute.isSortable() && !(value instanceof Currency || value instanceof Locale)) {
				value = sortableAttributesChecker.getUniqueAttribute(attributeName, value);
			}
		} while (value == null && sanityCheck++ < 1000);

		if (attribute.isSortable() && value == null) {
			throw new GenericEvitaInternalError("Cannot generate unique " + attributeName + " even in 1000 iterations!");
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
		@Nonnull AttributesEditor<?, ?> attributesBuilder,
		@Nonnull AttributeSchemaContract attribute,
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nonnull Set<String> priceLists,
		@Nullable Locale locale,
		@Nonnull Faker fakerToUse
	) {
		final T value;
		final String plainEntityType = entityType;
		final String suffix = getSuffix(uniqueSequencer, attribute, attributeName, locale);
		final Optional<String> assignedName = attributesBuilder.getAttributeValues()
			.stream()
			.filter(it -> ATTRIBUTE_NAME.equals(it.key().attributeName()))
			.map(AttributeValue::value)
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
				final int rndCnt = fakerToUse.random().nextInt(priceLists.size());
				final Iterator<String> it = priceLists.iterator();
				for (int i = 0; i < rndCnt; i++) {
					it.next();
				}
				value = (T) (it.next());
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
			value = (T) (fakerToUse.beer().name() + suffix);
		}
		return value;
	}

	/**
	 * Returns the suffix for a given attribute and locale, based on its uniqueness.
	 *
	 * @param uniqueSequencer a map of unique identifiers and their counts
	 * @param attribute       the attribute schema contract representing the attribute
	 * @param attributeName   the name of the attribute
	 * @param locale          the locale for which the uniqueness is being determined (can be null)
	 * @return the suffix for the attribute based on its uniqueness, or an empty string if the attribute is not unique
	 */
	@Nonnull
	private static String getSuffix(
		@Nonnull Map<Object, Integer> uniqueSequencer,
		@Nonnull AttributeSchemaContract attribute,
		@Nonnull String attributeName,
		@Nullable Locale locale
	) {
		final String suffix;
		if (attribute instanceof GlobalAttributeSchema globalAttribute && globalAttribute.isUniqueGlobally()) {
			if (globalAttribute.getGlobalUniquenessType() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE) {
				suffix = " " + uniqueSequencer.merge(new AttributeKey(attributeName, locale), 1, Integer::sum);
			} else if (globalAttribute.getGlobalUniquenessType() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG) {
				suffix = " " + uniqueSequencer.merge(new AttributeKey(attributeName), 1, Integer::sum);
			} else {
				suffix = "";
			}
		} else if (attribute.isUnique()) {
			if (attribute.getUniquenessType() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE) {
				suffix = " " + uniqueSequencer.merge(new AttributeKey(attributeName, locale), 1, Integer::sum);
			} else if (attribute.getUniquenessType() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION) {
				suffix = " " + uniqueSequencer.merge(new AttributeKey(attributeName), 1, Integer::sum);
			} else {
				suffix = "";
			}
		} else {
			suffix = "";
		}
		return suffix;
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
		@Nonnull Faker fakerToUse,
		@Nonnull Consumer<T> generatedValueWriter
	) {
		final Class<? extends Serializable> type = associatedData.getType();
		if (type.isArray()) {
			if (Integer.class.equals(type.getComponentType())) {
				final Integer[] newValue = new Integer[fakerToUse.random().nextInt(7) + 1];
				for (int i = 0; i < newValue.length; i++) {
					newValue[i] = fakerToUse.random().nextInt(10000);
				}
				generatedValueWriter.accept((T) newValue);
			} else if (String.class.equals(type.getComponentType())) {
				final String[] randomArray = new String[fakerToUse.random().nextInt(7) + 1];
				for (int i = 0; i < randomArray.length; i++) {
					randomArray[i] = fakerToUse.company().name();
				}
				generatedValueWriter.accept((T) randomArray);
			}
		} else {
			final Constructor<? extends Serializable> defaultConstructor = REFLECTION_LOOKUP.findConstructor(type, Set.of(new ArgumentKey("root", DataItem.class)));
			try {
				generatedValueWriter.accept(
					(T) defaultConstructor.newInstance(DataItemMap.EMPTY)
				);
			} catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
				throw new GenericEvitaInternalError("Test associated data class " + defaultConstructor.toGenericString() + " threw exception!", e);
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
		this.priceIndexingDecider = DEFAULT_PRICE_INDEXING_DECIDER;
		this.priceLists = PRICE_LIST_NAMES;
		this.currencies = CURRENCIES;
	}

	public DataGenerator(
		@Nonnull Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator,
		@Nonnull BiPredicate<String, Faker> priceIndexingDecider,
		@Nonnull String[] priceLists,
		@Nonnull Currency[] currencies,
		@Nonnull Map<EntityAttribute, Function<Faker, Object>> valueGenerators,
		@Nonnull Map<EntityAttribute, BiFunction<ReferenceKey, Faker, Object>> referenceValueGenerators
	) {
		this.priceInnerRecordHandlingGenerator = priceInnerRecordHandlingGenerator;
		this.priceIndexingDecider = priceIndexingDecider;
		this.priceLists = priceLists;
		this.currencies = currencies;
		this.valueGenerators.putAll(valueGenerators);
		this.referenceValueGenerators.putAll(referenceValueGenerators);
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
			GENERIC,
			serializable -> new ConcurrentHashMap<>()
		);
		final Map<Object, Integer> uniqueSequencer = this.uniqueSequencer.computeIfAbsent(
			schema.getName(),
			serializable -> new ConcurrentHashMap<>()
		);
		final SortableAttributesChecker sortableAttributesHolder = this.sortableAttributesChecker.computeIfAbsent(
			schema.getName(),
			serializable -> new SortableAttributesChecker()
		);
		final Map<Locale, Faker> localeFaker = new ConcurrentHashMap<>();
		final Function<Locale, Faker> localizedFakerFetcher = locale -> localeFaker.computeIfAbsent(locale, theLocale -> new Faker(new Random(seed)));
		final Faker genericFaker = new Faker(new Random(seed));
		final Set<Locale> allLocales = schema.getLocales();
		final Set<Currency> allCurrencies = new LinkedHashSet<>(Arrays.asList(currencies));
		final Set<String> allPriceLists = new LinkedHashSet<>(Arrays.asList(priceLists));
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

			generateRandomAttributes(
				schema.getName(), schema.getAttributes().values(),
				globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder, TRUE_PREDICATE, localizedFakerFetcher,
				this.valueGenerators, this.referenceValueGenerators,
				genericFaker, detachedBuilder, allCurrencies, allPriceLists, usedLocales, allLocales, null
			);
			generateRandomAssociatedData(schema, genericFaker, detachedBuilder, usedLocales, allLocales);

			generateRandomPrices(
				schema, uniqueSequencer, genericFaker, allCurrencies, allPriceLists,
				detachedBuilder, priceInnerRecordHandlingGenerator, priceIndexingDecider
			);
			generateRandomReferences(
				schema, referencedEntityResolver, globalUniqueSequencer, uniqueSequencer, parameterIndex,
				sortableAttributesHolder,
				this.valueGenerators, this.referenceValueGenerators,
				localizedFakerFetcher, genericFaker, detachedBuilder,
				allCurrencies, allPriceLists, usedLocales, allLocales
			);

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
		final Map<Locale, Faker> localeFaker = new ConcurrentHashMap<>();
		final Function<Locale, Faker> localizedFakerFetcher = locale -> localeFaker.computeIfAbsent(locale, theLocale -> new Faker(random));
		final Faker genericFaker = new Faker(random);
		final HashSet<Currency> allCurrencies = new LinkedHashSet<>(Arrays.asList(currencies));
		final Set<String> allPriceLists = new LinkedHashSet<>(Arrays.asList(priceLists));

		return new ModificationFunction(
			genericFaker, hierarchies, uniqueSequencer, sortableAttributesChecker, allCurrencies, allPriceLists,
			priceInnerRecordHandlingGenerator, priceIndexingDecider, referencedEntityResolver, localizedFakerFetcher, parameterIndex,
			valueGenerators, referenceValueGenerators
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
		return result instanceof EntitySchemaDecorator esd ?
			esd : new EntitySchemaDecorator(() -> catalogSchema, (EntitySchema) result);
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
	public SealedEntitySchema getSampleCategorySchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
		return getSampleCategorySchema(evitaSession, schemaUpdater, null);
	}

	@Nonnull
	public SealedEntitySchema getSampleCategorySchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater, @Nonnull Consumer<EntitySchemaEditor.EntitySchemaBuilder> schemaAlterLogic) {
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
	public SealedEntitySchema getSampleParameterSchema(@Nonnull EvitaSessionContract evitaSession, @Nonnull Function<EntitySchemaEditor.EntitySchemaBuilder, EntitySchemaContract> schemaUpdater) {
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
			.withPriceInCurrency(currencies)
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
					whichIs.indexedForFilteringAndPartitioning()
						.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class, thatIs -> thatIs.sortable().nullable())
			)
			/* for indexed facets we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
			)
			/* facets may be also represented be entities unknown to Evita */
			.withReferenceToEntity(
				Entities.STORE,
				Entities.STORE,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
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
	public Map<String, Map<Integer, Integer>> getParameterIndex() {
		return parameterIndex;
	}

	/**
	 * Registers attributes `code` and `url` that may be defined on the catalog level.
	 */
	private void addPossiblyGlobalAttributes(@Nonnull EvitaSessionContract evitaSession, @Nonnull EntitySchemaEditor.EntitySchemaBuilder schemaBuilder, @Nonnull String... attributeNames) {
		final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();
		if (Arrays.stream(attributeNames).anyMatch(it -> it.equals(ATTRIBUTE_CODE))) {
			if (catalogSchema.getAttribute(ATTRIBUTE_CODE).isEmpty()) {
				schemaBuilder.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique().nullable().representative());
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

	@NoArgsConstructor
	@Data
	public static class ReferencedFileSet implements Serializable {
		@Serial private static final long serialVersionUID = -1355676966187183143L;
		private String someField = "someValue";

		public ReferencedFileSet(String someField) {
			this.someField = someField;
		}
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
	@RequiredArgsConstructor
	private static class AttributeKey {
		private final String name;
		private final Locale locale;

		public AttributeKey(String name) {
			this.name = name;
			this.locale = null;
		}
	}

	@Data
	private static class PriceKey {
		private final String entityType;
	}

	private static class SortableAttributesChecker {
		private final Map<String, Map<Object, Integer>> sortableAttributes = new ConcurrentHashMap<>();
		private Predicate<Set<Object>> canAddAttribute;

		public Object getUniqueAttribute(@Nonnull String attributeName, @Nonnull Object value) {
			final Map<Object, Integer> uniqueValueMap = sortableAttributes.computeIfAbsent(attributeName, an -> new ConcurrentHashMap<>());
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

		public synchronized void executeWithPredicate(@Nonnull Predicate<Set<Object>> canAddAttribute, @Nonnull Runnable runnable) {
			Assert.isTrue(this.canAddAttribute == null, "Cannot nest predicate!");
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
		private final BiPredicate<String, Faker> priceIndexingDecider;
		private final BiFunction<String, Faker, Integer> referencedEntityResolver;
		private final Function<Locale, Faker> localizedFakerFetcher;
		private final Map<String, Map<Integer, Integer>> parameterIndex;
		private final Map<EntityAttribute, Function<Faker, Object>> valueGenerators;
		private final Map<EntityAttribute, BiFunction<ReferenceKey, Faker, Object>> referenceValueGenerators;

		@Override
		public EntityBuilder apply(@Nonnull SealedEntity existingEntity) {
			final EntityBuilder detachedBuilder = existingEntity.openForWrite();
			final EntitySchemaContract schema = existingEntity.getSchema();
			final Set<Locale> allLocales = schema.getLocales();
			final Map<Object, Integer> globalUniqueSequencer = this.uniqueSequencer.computeIfAbsent(
				DataGenerator.GENERIC,
				serializable -> new ConcurrentHashMap<>()
			);
			final Map<Object, Integer> uniqueSequencer = this.uniqueSequencer.computeIfAbsent(
				schema.getName(),
				serializable -> new ConcurrentHashMap<>()
			);
			final SortableAttributesChecker sortableAttributesHolder = this.sortableAttributesChecker.computeIfAbsent(
				schema.getName(),
				serializable -> new SortableAttributesChecker()
			);

			// randomly delete hierarchy placement
			if (detachedBuilder.getSchema().isWithHierarchy()) {
				if (detachedBuilder.getParentEntity().isPresent() && genericFaker.random().nextInt(3) == 0) {
					detachedBuilder.removeParent();
				}
				generateRandomHierarchy(schema, referencedEntityResolver, getHierarchyIfNeeded(hierarchies, schema), genericFaker, detachedBuilder);
			}

			final List<Locale> usedLocales = pickRandomFromSet(genericFaker, allLocales);

			// randomly delete attributes
			final Set<AttributesContract.AttributeKey> existingAttributeKeys = new TreeSet<>(detachedBuilder.getAttributeKeys());
			for (AttributesContract.AttributeKey existingAttributeKey : existingAttributeKeys) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removeAttribute(existingAttributeKey.attributeName(), existingAttributeKey.locale());
				}
			}
			generateRandomAttributes(
				schema.getName(), schema.getAttributes().values(), globalUniqueSequencer, uniqueSequencer, sortableAttributesHolder,
				TRUE_PREDICATE, localizedFakerFetcher, valueGenerators, referenceValueGenerators,
				genericFaker, detachedBuilder, allCurrencies, allPriceLists, usedLocales, allLocales, null
			);

			// randomly delete associated data
			final Set<AssociatedDataContract.AssociatedDataKey> existingAssociatedDataKeys = new TreeSet<>(detachedBuilder.getAssociatedDataKeys());
			for (AssociatedDataContract.AssociatedDataKey existingAssociatedDataKey : existingAssociatedDataKeys) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removeAssociatedData(existingAssociatedDataKey.associatedDataName(), existingAssociatedDataKey.locale());
				}
			}
			generateRandomAssociatedData(schema, genericFaker, detachedBuilder, usedLocales, allLocales);

			// randomly delete prices
			final List<Price.PriceKey> prices = detachedBuilder.getPrices().stream().map(PriceContract::priceKey).sorted().collect(Collectors.toList());
			for (Price.PriceKey price : prices) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removePrice(price.priceId(), price.priceList(), price.currency());
				}
			}
			generateRandomPrices(
				schema, uniqueSequencer, genericFaker, allCurrencies, allPriceLists,
				detachedBuilder, priceInnerRecordHandlingGenerator, priceIndexingDecider
			);

			// randomly delete references
			final Collection<ReferenceKey> references =
				detachedBuilder
					.getReferences()
					.stream()
					.map(ReferenceContract::getReferenceKey)
					.sorted(ReferenceKey.FULL_COMPARATOR)
					.collect(Collectors.toList());
			for (ReferenceKey reference : references) {
				if (genericFaker.random().nextInt(4) == 0) {
					detachedBuilder.removeReference(reference.referenceName(), reference.primaryKey());
				}
			}
			generateRandomReferences(
				schema, referencedEntityResolver, globalUniqueSequencer, uniqueSequencer, parameterIndex, sortableAttributesHolder,
				valueGenerators, referenceValueGenerators, localizedFakerFetcher, genericFaker, detachedBuilder,
				allCurrencies, allPriceLists, usedLocales, allLocales
			);

			return detachedBuilder;
		}
	}

	/**
	 * Tuple for entity type / attribute combination.
	 */
	private record EntityAttribute(
		@Nonnull String entityType,
		@Nonnull String attributeName
	) {
	}

	/**
	 * Builder class for configuring and creating instances with various settings.
	 */
	public static class Builder {
		private Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator = faker -> PriceInnerRecordHandling.NONE;
		private BiPredicate<String, Faker> priceIndexingDecider = DEFAULT_PRICE_INDEXING_DECIDER;
		private String[] priceLists = PRICE_LIST_NAMES;
		private Currency[] currencies = CURRENCIES;
		private Map<EntityAttribute, Function<Faker, Object>> valueGenerators = new HashMap<>();
		private Map<EntityAttribute, BiFunction<ReferenceKey, Faker, Object>> referenceValueGenerators = new HashMap<>();

		/**
		 * Sets the price inner record handling generator.
		 *
		 * @param priceInnerRecordHandlingGenerator the function to generate price inner record handling strategies
		 * @return the builder instance
		 */
		@Nonnull
		public Builder withPriceInnerRecordHandlingGenerator(@Nonnull Function<Faker, PriceInnerRecordHandling> priceInnerRecordHandlingGenerator) {
			this.priceInnerRecordHandlingGenerator = priceInnerRecordHandlingGenerator;
			return this;
		}

		/**
		 * Sets the price indexing decider.
		 *
		 * @param priceIndexingDecider the predicate to decide on price indexing
		 * @return the builder instance
		 */
		@Nonnull
		public Builder withPriceIndexingDecider(@Nonnull BiPredicate<String, Faker> priceIndexingDecider) {
			this.priceIndexingDecider = priceIndexingDecider;
			return this;
		}

		/**
		 * Sets the price lists.
		 *
		 * @param priceLists the array of price lists
		 * @return the builder instance
		 */
		@Nonnull
		public Builder withPriceLists(@Nonnull String... priceLists) {
			this.priceLists = priceLists;
			return this;
		}

		/**
		 * Sets the currencies.
		 *
		 * @param currencies the array of currencies
		 * @return the builder instance
		 */
		@Nonnull
		public Builder withCurrencies(@Nonnull Currency... currencies) {
			this.currencies = currencies;
			return this;
		}

		/**
		 * Registers value generator for passed entity type / attribute combination.
		 */
		@Nonnull
		public Builder registerValueGenerator(
			@Nonnull String entityType,
			@Nonnull String attributeName,
			@Nonnull Function<Faker, Object> valueGenerator
		) {
			this.valueGenerators.put(new EntityAttribute(entityType, attributeName), valueGenerator);
			return this;
		}

		/**
		 * Registers value generator for passed entity type / attribute combination that accepts {@link ReferenceKey}
		 * as well.
		 */
		@Nonnull
		public Builder registerValueGenerator(
			@Nonnull String entityType,
			@Nonnull String attributeName,
			@Nonnull BiFunction<ReferenceKey, Faker, Object> referenceValueGenerator
		) {
			this.referenceValueGenerators.put(new EntityAttribute(entityType, attributeName), referenceValueGenerator);
			return this;
		}

		/**
		 * Builds and returns an instance of {@link DataGenerator}.
		 *
		 * @return a new {@link DataGenerator} instance configured with the current builder settings
		 */
		@Nonnull
		public DataGenerator build() {
			return new DataGenerator(
				this.priceInnerRecordHandlingGenerator,
				this.priceIndexingDecider,
				this.priceLists,
				this.currencies,
				this.valueGenerators,
				this.referenceValueGenerators
			);
		}

	}

}
