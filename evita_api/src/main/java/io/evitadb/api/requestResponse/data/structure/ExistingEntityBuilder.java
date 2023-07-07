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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.ParentMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate.ExistsPredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link Entity}. Entity is immutable object so there is need for another object
 * that would simplify the process of updating its contents. This is why the builder class exists.
 *
 * This builder is suitable for the situation when there already is some entity at place, and we need to alter it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExistingEntityBuilder implements EntityBuilder {
	@Serial private static final long serialVersionUID = -1422927537304173188L;

	/**
	 * This predicate filters out non-fetched locales.
	 */
	@Getter private final LocaleSerializablePredicate localePredicate;
	/**
	 * This predicate filters out attributes that were not fetched in query.
	 */
	@Getter private final AttributeValueSerializablePredicate attributePredicate;
	/**
	 * This predicate filters out associated data that were not fetched in query.
	 */
	@Getter private final AssociatedDataValueSerializablePredicate associatedDataPredicate;
	/**
	 * This predicate filters out references that were not fetched in query.
	 */
	@Getter private final ReferenceContractSerializablePredicate referencePredicate;
	/**
	 * This predicate filters out prices that were not fetched in query.
	 */
	@Getter private final PriceContractSerializablePredicate pricePredicate;

	private final Entity baseEntity;
	@Delegate(types = AttributesContract.class)
	private final ExistingAttributesBuilder attributesBuilder;
	@Delegate(types = AssociatedDataContract.class)
	private final ExistingAssociatedDataBuilder associatedDataBuilder;
	@Delegate(types = PricesContract.class)
	private final ExistingPricesBuilder pricesBuilder;
	private final Map<ReferenceKey, List<ReferenceMutation<?>>> referenceMutations;
	private final Set<ReferenceKey> removedReferences = new HashSet<>();
	private ParentMutation hierarchyMutation;

	private static void assertPricesFetched(PriceContractSerializablePredicate pricePredicate) {
		Assert.isTrue(
			pricePredicate.getPriceContentMode() == PriceContentMode.ALL,
			"Prices were not fetched and cannot be updated. Please enrich the entity first or load it with all the prices."
		);
	}

	public ExistingEntityBuilder(@Nonnull EntityDecorator baseEntity, @Nonnull Collection<LocalMutation<?, ?>> localMutations) {
		this.baseEntity = baseEntity.getDelegate();
		this.attributesBuilder = new ExistingAttributesBuilder(this.baseEntity.schema, null, this.baseEntity.attributes, baseEntity.getAttributePredicate());
		this.associatedDataBuilder = new ExistingAssociatedDataBuilder(this.baseEntity.schema, this.baseEntity.associatedData, baseEntity.getAssociatedDataPredicate());
		this.pricesBuilder = new ExistingPricesBuilder(this.baseEntity.schema, this.baseEntity.prices, baseEntity.getPricePredicate());
		this.referenceMutations = new HashMap<>();
		this.localePredicate = baseEntity.getLocalePredicate();
		this.attributePredicate = baseEntity.getAttributePredicate();
		this.associatedDataPredicate = baseEntity.getAssociatedDataPredicate();
		this.pricePredicate = baseEntity.getPricePredicate();
		this.referencePredicate = baseEntity.getReferencePredicate();
		for (LocalMutation<?, ?> localMutation : localMutations) {
			addMutation(localMutation);
		}
	}

	public ExistingEntityBuilder(@Nonnull EntityDecorator baseEntity) {
		this(baseEntity, Collections.emptyList());
	}

	public ExistingEntityBuilder(@Nonnull Entity baseEntity, @Nonnull Collection<LocalMutation<?, ?>> localMutations) {
		this.baseEntity = baseEntity;
		this.attributesBuilder = new ExistingAttributesBuilder(this.baseEntity.schema, null, this.baseEntity.attributes, ExistsPredicate.instance());
		this.associatedDataBuilder = new ExistingAssociatedDataBuilder(this.baseEntity.schema, this.baseEntity.associatedData, ExistsPredicate.instance());
		this.pricesBuilder = new ExistingPricesBuilder(this.baseEntity.schema, this.baseEntity.prices, new PriceContractSerializablePredicate());
		this.referenceMutations = new HashMap<>();
		this.localePredicate = LocaleSerializablePredicate.DEFAULT_INSTANCE;
		this.attributePredicate = AttributeValueSerializablePredicate.DEFAULT_INSTANCE;
		this.associatedDataPredicate = AssociatedDataValueSerializablePredicate.DEFAULT_INSTANCE;
		this.pricePredicate = PriceContractSerializablePredicate.DEFAULT_INSTANCE;
		this.referencePredicate = ReferenceContractSerializablePredicate.DEFAULT_INSTANCE;
		for (LocalMutation<?, ?> localMutation : localMutations) {
			addMutation(localMutation);
		}
	}

	public ExistingEntityBuilder(@Nonnull Entity baseEntity) {
		this(baseEntity, Collections.emptyList());
	}

	public void addMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		if (localMutation instanceof ParentMutation hierarchicalPlacementMutation) {
			this.hierarchyMutation = hierarchicalPlacementMutation;
		} else if (localMutation instanceof AttributeMutation attributeMutation) {
			this.attributesBuilder.addMutation(attributeMutation);
		} else if (localMutation instanceof AssociatedDataMutation associatedDataMutation) {
			this.associatedDataBuilder.addMutation(associatedDataMutation);
		} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
			this.referenceMutations
				.computeIfAbsent(referenceMutation.getReferenceKey(), rk -> new LinkedList<>())
				.add(referenceMutation);
		} else if (localMutation instanceof PriceMutation priceMutation) {
			this.pricesBuilder.addMutation(priceMutation);
		} else if (localMutation instanceof SetPriceInnerRecordHandlingMutation innerRecordHandlingMutation) {
			this.pricesBuilder.addMutation(innerRecordHandlingMutation);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new EvitaInternalError("Unknown mutation: " + localMutation.getClass());
		}
	}

	@Override
	public boolean isDropped() {
		return false;
	}

	@Override
	public int getVersion() {
		return baseEntity.getVersion() + 1;
	}

	@Override
	@Nonnull
	public String getType() {
		return baseEntity.getType();
	}

	@Override
	@Nonnull
	public EntitySchemaContract getSchema() {
		return baseEntity.getSchema();
	}

	@Override
	@Nullable
	public Integer getPrimaryKey() {
		return baseEntity.getPrimaryKey();
	}

	@Nonnull
	@Override
	public OptionalInt getParent() {
		return ofNullable(hierarchyMutation)
			.map(it -> it.mutateLocal(this.baseEntity.schema, this.baseEntity.getParent()))
			.orElseGet(this.baseEntity::getParent);
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		return Stream.concat(
				baseEntity.getReferences()
					.stream()
					.filter(Droppable::exists)
					.map(it ->
						ofNullable(this.referenceMutations.get(it.getReferenceKey()))
							.map(mutations -> evaluateReferenceMutations(it, mutations))
							.filter(mutatedReference -> mutatedReference.differsFrom(it))
							.orElse(it)
					),
				this.referenceMutations
					.entrySet()
					.stream()
					.filter(it -> baseEntity.getReference(it.getKey().referenceName(), it.getKey().primaryKey()).isEmpty())
					.map(Entry::getValue)
					.map(it -> evaluateReferenceMutations(null, it))
			)
			.filter(referencePredicate)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		return getReferences()
			.stream()
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		final ReferenceKey entityReferenceContract = new ReferenceKey(referenceName, referencedEntityId);
		final Optional<ReferenceContract> reference = baseEntity.getReference(referenceName, referencedEntityId)
			.map(it -> ofNullable(this.referenceMutations.get(entityReferenceContract))
				.map(mutations -> evaluateReferenceMutations(it, mutations))
				.orElse(it)
			)
			.or(() ->
				ofNullable(this.referenceMutations.get(entityReferenceContract))
					.map(mutations -> evaluateReferenceMutations(null, mutations))
			);
		return reference.filter(referencePredicate);
	}

	@Nonnull
	@Override
	public Set<Locale> getAllLocales() {
		return Stream.concat(
				attributesBuilder.getAttributeLocales().stream(),
				associatedDataBuilder.getAssociatedDataLocales().stream()
			)
			.collect(Collectors.toSet());
	}

	@Nonnull
	public Set<Locale> getLocales() {
		return Stream.concat(
				attributesBuilder.getAttributeLocales().stream(),
				associatedDataBuilder.getAssociatedDataLocales().stream()
			)
			.filter(localePredicate)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName) {
		Assert.isTrue(
			attributePredicate.test(new AttributeValue(new AttributeKey(attributeName), -1)),
			"Attribute " + attributeName + " was not fetched and cannot be removed. Please enrich the entity first or load it with attributes."
		);
		attributesBuilder.removeAttribute(attributeName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nullable T attributeValue) {
		Assert.isTrue(
			attributePredicate.test(new AttributeValue(new AttributeKey(attributeName), -1)),
			"Attributes were not fetched and cannot be updated. Please enrich the entity first or load it with attributes. Please enrich the entity first or load it with attributes."
		);
		attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue) {
		Assert.isTrue(
			attributePredicate.test(new AttributeValue(new AttributeKey(attributeName), -1)),
			"Attributes were not fetched and cannot be updated. Please enrich the entity first or load it with attributes. Please enrich the entity first or load it with attributes."
		);
		attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		Assert.isTrue(
			attributePredicate.test(new AttributeValue(new AttributeKey(attributeName, locale), -1)),
			"Attribute " + attributeName + " in locale " + locale + " was not fetched and cannot be removed. Please enrich the entity first or load it with attributes."
		);
		attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		Assert.isTrue(
			attributePredicate.test(new AttributeValue(new AttributeKey(attributeName, locale), -1)),
			"Attributes in locale " + locale + " were not fetched and cannot be updated. Please enrich the entity first or load it with attributes."
		);
		attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		Assert.isTrue(
			attributePredicate.test(new AttributeValue(new AttributeKey(attributeName, locale), -1)),
			"Attributes in locale " + locale + " were not fetched and cannot be updated. Please enrich the entity first or load it with attributes."
		);
		attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		Assert.isTrue(
			attributePredicate.test(new AttributeValue(mutation.getAttributeKey(), -1)),
			"Attribute " + mutation.getAttributeKey() + " was not fetched and cannot be updated. Please enrich the entity first or load it with attributes."
		);
		attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		Assert.isTrue(
			associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be removed. Please enrich the entity first or load it with the associated data."
		);
		associatedDataBuilder.removeAssociatedData(associatedDataName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nullable T associatedDataValue) {
		Assert.isTrue(
			associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		Assert.isTrue(
			associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		Assert.isTrue(
			associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName, locale), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be removed. Please enrich the entity first or load it with the associated data."
		);
		associatedDataBuilder.removeAssociatedData(associatedDataName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T associatedDataValue) {
		Assert.isTrue(
			associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName, locale), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		Assert.isTrue(
			associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName, locale), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		Assert.isTrue(
			associatedDataPredicate.test(new AssociatedDataValue(mutation.getAssociatedDataKey(), -1)),
			"Associated data " + mutation.getAssociatedDataKey() + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		associatedDataBuilder.mutateAssociatedData(mutation);
		return this;
	}

	@Override
	public EntityBuilder setParent(int parentPrimaryKey) {
		this.hierarchyMutation = !Objects.equals(this.baseEntity.getParent(), OptionalInt.of(parentPrimaryKey)) ?
			new SetParentMutation(parentPrimaryKey) : null;
		return this;
	}

	@Override
	public EntityBuilder removeParent() {
		Assert.notNull(baseEntity.getParent(), "Cannot remove parent that is not present!");
		this.hierarchyMutation = this.baseEntity.getParent().isPresent() ? new RemoveParentMutation() : null;
		return this;
	}

	@Override
	public EntityBuilder setReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		return setReference(referenceName, referencedPrimaryKey, null);
	}

	@Override
	public EntityBuilder setReference(@Nonnull String referenceName, int referencedPrimaryKey, @Nullable Consumer<ReferenceBuilder> whichIs) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(referenceName);
		return setReference(referenceName, referenceSchema.getReferencedEntityType(), referenceSchema.getCardinality(), referencedPrimaryKey, whichIs);
	}

	@Override
	public EntityBuilder setReference(@Nonnull String referenceName, @Nonnull String referencedEntityType, @Nonnull Cardinality cardinality, int referencedPrimaryKey) {
		return setReference(referenceName, referencedEntityType, cardinality, referencedPrimaryKey, null);
	}

	@Override
	public EntityBuilder setReference(@Nonnull String referenceName, @Nonnull String referencedEntityType, @Nonnull Cardinality cardinality, int referencedPrimaryKey, @Nullable Consumer<ReferenceBuilder> whichIs) {
		Assert.isTrue(
			referencePredicate.test(new Reference(getSchema(), referenceName, referencedPrimaryKey, referencedEntityType, cardinality, null)),
			"References were not fetched and cannot be updated. Please enrich the entity first or load it with the references."
		);
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
		final EntitySchemaContract schema = getSchema();
		final Optional<ReferenceContract> existingReference = ofNullable(baseEntity.getReference(referenceKey));
		final ReferenceBuilder referenceBuilder = existingReference
			.map(it -> (ReferenceBuilder) new ExistingReferenceBuilder(it, schema))
			.filter(referencePredicate)
			.orElseGet(
				() -> new InitialReferenceBuilder(
					schema, referenceName, referencedPrimaryKey, cardinality, referencedEntityType
				)
			);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceBuilder));
		if (existingReference.isPresent()) {
			final Optional<ReferenceContract> referenceInBaseEntity = this.baseEntity.getReference(referencedEntityType, referencedPrimaryKey)
				.filter(Droppable::exists);
			final List<ReferenceMutation<?>> changeSet = referenceBuilder.buildChangeSet().collect(Collectors.toList());
			if (referenceInBaseEntity.map(it -> it.exists() && !removedReferences.contains(referenceKey)).orElse(true)) {
				this.referenceMutations.put(
					referenceKey,
					changeSet
				);
			} else {
				this.referenceMutations.put(
					referenceKey,
					Stream.concat(
							Stream.concat(
								referenceInBaseEntity
									.flatMap(ReferenceContract::getGroup)
									.filter(Droppable::exists)
									.stream()
									.map(it -> new RemoveReferenceGroupMutation(referenceKey)),
								referenceInBaseEntity
									.map(AttributesContract::getAttributeValues)
									.orElse(Collections.emptyList())
									.stream()
									.filter(Droppable::exists)
									.map(it ->
										new ReferenceAttributeMutation(
											referenceKey,
											new RemoveAttributeMutation(it.getKey())
										)
									)
							),
							changeSet.stream()
						)
						.collect(Collectors.toList())
				);
			}
		} else {
			this.referenceMutations.put(
				referenceKey,
				referenceBuilder.buildChangeSet().collect(Collectors.toList())
			);
		}
		return this;
	}

	@Override
	public EntityBuilder removeReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(referenceName);
		Assert.isTrue(
			referencePredicate.test(new Reference(getSchema(), referenceName, referencedPrimaryKey, referenceSchema.getReferencedEntityType(), referenceSchema.getCardinality(), null)),
			"References were not fetched and cannot be updated. Please enrich the entity first or load it with the references."
		);
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
		Assert.isTrue(getReference(referenceName, referencedPrimaryKey).isPresent(), "There's no reference of type " + referenceName + " and primary key " + referencedPrimaryKey + "!");
		final Optional<ReferenceContract> theReference = ofNullable(baseEntity.getReference(referenceKey))
			.filter(referencePredicate);
		Assert.isTrue(
			theReference.isPresent(),
			"Reference to " + referenceName + " and primary key " + referenceKey +
				" is not present on the entity " + baseEntity.getType() + " and id " +
				baseEntity.getPrimaryKey() + "!"
		);
		this.referenceMutations.put(
			referenceKey,
			Collections.singletonList(
				new RemoveReferenceMutation(referenceKey)
			)
		);
		this.removedReferences.add(referenceKey);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean sellable) {
		Assert.isTrue(
			pricePredicate.test(new Price(new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, false)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, sellable);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean sellable) {
		Assert.isTrue(
			pricePredicate.test(new Price(new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, false)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		pricesBuilder.setPrice(priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, sellable);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean sellable) {
		Assert.isTrue(
			pricePredicate.test(new Price(new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, false)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, validity, sellable);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean sellable) {
		Assert.isTrue(
			pricePredicate.test(new Price(new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, false)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		pricesBuilder.setPrice(priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, sellable);
		return this;
	}

	@Override
	public EntityBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		Assert.isTrue(
			pricePredicate.test(new Price(new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, false)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		pricesBuilder.removePrice(priceId, priceList, currency);
		return this;
	}

	@Override
	public EntityBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		assertPricesFetched(pricePredicate);
		pricesBuilder.setPriceInnerRecordHandling(priceInnerRecordHandling);
		return this;
	}

	@Override
	public EntityBuilder removePriceInnerRecordHandling() {
		assertPricesFetched(pricePredicate);
		pricesBuilder.removePriceInnerRecordHandling();
		return this;
	}

	@Override
	public EntityBuilder removeAllNonTouchedPrices() {
		assertPricesFetched(pricePredicate);
		pricesBuilder.removeAllNonTouchedPrices();
		return this;
	}

	@Nonnull
	@Override
	public Optional<EntityMutation> toMutation() {
		final Map<ReferenceKey, ReferenceContract> builtReferences = new HashMap<>(baseEntity.references);
		final List<? extends LocalMutation<?, ? extends Comparable<?>>> mutations = Stream.of(
				Stream.of(hierarchyMutation).filter(Objects::nonNull),
				attributesBuilder.buildChangeSet(),
				associatedDataBuilder.buildChangeSet(),
				pricesBuilder.buildChangeSet(),
				referenceMutations.values()
					.stream()
					.flatMap(Collection::stream)
					.filter(it -> {
						final ReferenceContract existingReference = builtReferences.get(it.getReferenceKey());
						final ReferenceContract newReference = it.mutateLocal(getSchema(), existingReference);
						builtReferences.put(it.getReferenceKey(), newReference);
						return existingReference == null || newReference.getVersion() > existingReference.getVersion();
					})
			)
			.flatMap(it -> it)
			.filter(Objects::nonNull)
			.sorted()
			.collect(Collectors.toList());

		if (mutations.isEmpty()) {
			return Optional.empty();
		} else {
			return Optional.of(
				new EntityUpsertMutation(
					baseEntity.getType(),
					Objects.requireNonNull(baseEntity.getPrimaryKey()),
					EntityExistence.MUST_EXIST,
					mutations
				)
			);
		}
	}

	@Nonnull
	@Override
	public Entity toInstance() {
		return toMutation()
			.map(it -> it.mutate(baseEntity.getSchema(), baseEntity))
			.orElse(baseEntity);
	}

	private ReferenceContract evaluateReferenceMutations(@Nullable ReferenceContract reference, @Nonnull List<ReferenceMutation<?>> mutations) {
		ReferenceContract mutatedReference = reference;
		for (ReferenceMutation<?> mutation : mutations) {
			mutatedReference = mutation.mutateLocal(this.baseEntity.schema, mutatedReference);
		}
		return mutatedReference != null && mutatedReference.differsFrom(reference) ? mutatedReference : reference;
	}

	@Nonnull
	private ReferenceSchemaContract getReferenceSchemaOrThrowException(@Nonnull String referenceName) {
		return getSchema().getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotKnownException(referenceName));
	}

	private class ReferenceUniqueAttributeCheck implements BiPredicate<String, String>, Serializable {
		@Serial private static final long serialVersionUID = 3153392405996708805L;

		@Override
		public boolean test(String referenceName, String attributeName) {
			return getReferences()
				.stream()
				.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
				.filter(Droppable::exists)
				.anyMatch(it -> it.getAttributeValue(attributeName).map(Droppable::exists).orElse(false));
		}

	}

}
