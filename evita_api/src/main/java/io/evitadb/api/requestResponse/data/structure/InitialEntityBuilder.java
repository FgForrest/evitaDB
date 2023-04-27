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
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.DateTimeRange;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to create the entity.
 * Due to performance reasons (see {@link DirectWriteOrOperationLog} microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InitialEntityBuilder implements EntityBuilder {
	@Serial private static final long serialVersionUID = -3674623071115207036L;
	private final ReferenceUniqueAttributeCheck referenceUniqueAttributeCheck = new ReferenceUniqueAttributeCheck();

	private final String type;
	private final EntitySchemaContract schema;
	private final Integer primaryKey;
	@Delegate(types = AttributesContract.class)
	private final AttributesBuilder attributesBuilder;
	@Delegate(types = AssociatedDataContract.class)
	private final AssociatedDataBuilder associatedDataBuilder;
	@Delegate(types = PricesContract.class)
	private final PricesBuilder pricesBuilder;
	private final Map<ReferenceKey, ReferenceContract> references;
	private Integer parent;

	public InitialEntityBuilder(@Nonnull String type) {
		this.type = type;
		this.schema = EntitySchema._internalBuild(type);
		this.primaryKey = null;
		this.attributesBuilder = new InitialAttributesBuilder(schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(schema);
		this.pricesBuilder = new InitialPricesBuilder();
		this.references = new HashMap<>();
	}

	public InitialEntityBuilder(@Nonnull EntitySchemaContract schema) {
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = null;
		this.attributesBuilder = new InitialAttributesBuilder(schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(schema);
		this.pricesBuilder = new InitialPricesBuilder();
		this.references = new HashMap<>();
	}

	public InitialEntityBuilder(@Nonnull String type, @Nullable Integer primaryKey) {
		this.type = type;
		this.primaryKey = primaryKey;
		this.schema = EntitySchema._internalBuild(type);
		this.attributesBuilder = new InitialAttributesBuilder(schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(schema);
		this.pricesBuilder = new InitialPricesBuilder();
		this.references = new HashMap<>();
	}

	public InitialEntityBuilder(@Nonnull EntitySchemaContract schema, @Nullable Integer primaryKey) {
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = primaryKey;
		this.attributesBuilder = new InitialAttributesBuilder(schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(schema);
		this.pricesBuilder = new InitialPricesBuilder();
		this.references = new HashMap<>();
	}

	public InitialEntityBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Integer primaryKey,
		@Nonnull Collection<AttributeValue> attributeValues,
		@Nonnull Collection<AssociatedDataValue> associatedDataValues,
		@Nonnull Collection<ReferenceContract> referenceContracts,
		@Nullable PriceInnerRecordHandling priceInnerRecordHandling,
		@Nonnull Collection<PriceContract> prices
	) {
		this.type = entitySchema.getName();
		this.schema = entitySchema;
		this.primaryKey = primaryKey;
		this.attributesBuilder = new InitialAttributesBuilder(schema);
		for (AttributeValue attributeValue : attributeValues) {
			final AttributeKey attributeKey = attributeValue.getKey();
			if (attributeKey.isLocalized()) {
				this.attributesBuilder.setAttribute(
					attributeKey.getAttributeName(),
					attributeKey.getLocale(),
					(Serializable) attributeValue.getValue()
				);
			} else {
				this.attributesBuilder.setAttribute(
					attributeKey.getAttributeName(),
					(Serializable) attributeValue.getValue()
				);
			}
		}
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(schema);
		for (AssociatedDataValue associatedDataValue : associatedDataValues) {
			final AssociatedDataKey associatedDataKey = associatedDataValue.getKey();
			if (associatedDataKey.isLocalized()) {
				this.associatedDataBuilder.setAssociatedData(
					associatedDataKey.getAssociatedDataName(),
					associatedDataKey.getLocale(),
					associatedDataValue.getValue()
				);
			} else {
				this.associatedDataBuilder.setAssociatedData(
					associatedDataKey.getAssociatedDataName(),
					associatedDataValue.getValue()
				);
			}
		}
		this.pricesBuilder = new InitialPricesBuilder();
		ofNullable(priceInnerRecordHandling)
			.ifPresent(this.pricesBuilder::setPriceInnerRecordHandling);
		for (PriceContract price : prices) {
			this.pricesBuilder.setPrice(
				price.getPriceId(),
				price.getPriceList(),
				price.getCurrency(),
				price.getInnerRecordId(),
				price.getPriceWithoutTax(),
				price.getTaxRate(),
				price.getPriceWithTax(),
				price.getValidity(),
				price.isSellable()
			);
		}

		this.references = referenceContracts.stream()
			.collect(
				Collectors.toMap(
					ReferenceContract::getReferenceKey,
					Function.identity()
				)
			);
	}

	@Override
	public boolean isDropped() {
		return false;
	}

	@Override
	public int getVersion() {
		return 1;
	}

	@Override
	@Nonnull
	public String getType() {
		return type;
	}

	@Override
	@Nonnull
	public EntitySchemaContract getSchema() {
		return schema;
	}

	@Override
	@Nullable
	public Integer getPrimaryKey() {
		return primaryKey;
	}

	@Nonnull
	@Override
	public OptionalInt getParent() {
		return parent == null ? OptionalInt.empty() : OptionalInt.of(parent);
	}

	@Nonnull
	@Override
	public Optional<EntityClassifier> getParentEntity() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		return references.values();
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		return references
			.values()
			.stream()
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		return ofNullable(references.get(new ReferenceKey(referenceName, referencedEntityId)));
	}

	@Nonnull
	public Set<Locale> getAllLocales() {
		return Stream.concat(
				attributesBuilder.getAttributeLocales().stream(),
				associatedDataBuilder.getAssociatedDataLocales().stream()
			)
			.collect(Collectors.toSet());
	}

	@Nonnull
	public Set<Locale> getLocales() {
		return getAllLocales();
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName) {
		attributesBuilder.removeAttribute(attributeName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nullable T attributeValue) {
		attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue) {
		attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		associatedDataBuilder.removeAssociatedData(associatedDataName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nullable T associatedDataValue) {
		associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		associatedDataBuilder.removeAssociatedData(associatedDataName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T associatedDataValue) {
		associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		associatedDataBuilder.mutateAssociatedData(mutation);
		return this;
	}

	@Override
	public EntityBuilder setParent(int parentPrimaryKey) {
		this.parent = parentPrimaryKey;
		return this;
	}

	@Override
	public EntityBuilder removeParent() {
		this.parent = null;
		return this;
	}

	@Override
	public EntityBuilder setReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(referenceName);
		return setReference(referenceName, referenceSchema.getReferencedEntityType(), referenceSchema.getCardinality(), referencedPrimaryKey, null);
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
		final InitialReferenceBuilder builder = new InitialReferenceBuilder(this.schema, referenceName, referencedPrimaryKey, cardinality, referencedEntityType, referenceUniqueAttributeCheck);
		ofNullable(whichIs).ifPresent(it -> it.accept(builder));
		final Reference reference = builder.build();
		references.put(new ReferenceKey(referenceName, referencedPrimaryKey), reference);
		return this;
	}

	@Override
	public EntityBuilder removeReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		this.references.remove(new ReferenceKey(referenceName, referencedPrimaryKey));
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean sellable) {
		pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, sellable);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean sellable) {
		pricesBuilder.setPrice(priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, sellable);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, DateTimeRange validity, boolean sellable) {
		pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, validity, sellable);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean sellable) {
		pricesBuilder.setPrice(priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, sellable);
		return this;
	}

	@Override
	public EntityBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		pricesBuilder.removePrice(priceId, priceList, currency);
		return this;
	}

	@Override
	public EntityBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		pricesBuilder.setPriceInnerRecordHandling(priceInnerRecordHandling);
		return this;
	}

	@Override
	public EntityBuilder removePriceInnerRecordHandling() {
		pricesBuilder.removePriceInnerRecordHandling();
		return this;
	}

	@Override
	public EntityBuilder removeAllNonTouchedPrices() {
		pricesBuilder.removeAllNonTouchedPrices();
		return this;
	}

	@Nonnull
	@Override
	public Optional<EntityMutation> toMutation() {
		return of(
			new EntityUpsertMutation(
				getType(),
				getPrimaryKey(),
				EntityExistence.MUST_NOT_EXIST,
				Stream.of(
						ofNullable(parent)
							.map(SetParentMutation::new)
							.stream(),
						references.values()
							.stream()
							.flatMap(it -> Stream.concat(
									Stream.of(
											new InsertReferenceMutation(it.getReferenceKey(), it.getReferenceCardinality(), it.getReferencedEntityType()),
											it.getGroup().map(group -> new SetReferenceGroupMutation(it.getReferenceKey(), group.getType(), group.getPrimaryKey())).orElse(null)
										)
										.filter(Objects::nonNull),
									it.getAttributeValues()
										.stream()
										.filter(x -> Objects.nonNull(x.getValue()))
										.map(x ->
											new ReferenceAttributeMutation(
												it.getReferenceKey(),
												new UpsertAttributeMutation(x.getKey(), x.getValue())
											)
										)
								)
							),
						attributesBuilder
							.getAttributeValues()
							.stream()
							.filter(it -> it.getValue() != null)
							.map(it -> new UpsertAttributeMutation(it.getKey(), it.getValue())),
						associatedDataBuilder
							.getAssociatedDataValues()
							.stream()
							.map(it -> new UpsertAssociatedDataMutation(it.getKey(), it.getValue())),
						Stream.of(
							new SetPriceInnerRecordHandlingMutation(pricesBuilder.getPriceInnerRecordHandling())
						),
						pricesBuilder
							.getPrices()
							.stream()
							.map(it -> new UpsertPriceMutation(it.getPriceKey(), it))
					)
					.flatMap(it -> it)
					.filter(Objects::nonNull)
					.collect(Collectors.toList())
			)
		);
	}

	@Nonnull
	@Override
	public Entity toInstance() {
		return Entity._internalBuild(
			primaryKey,
			getVersion(),
			schema,
			parent,
			references.values(),
			attributesBuilder.build(),
			associatedDataBuilder.build(),
			pricesBuilder.build(),
			getAllLocales()
		);
	}

	@Nonnull
	private ReferenceSchemaContract getReferenceSchemaOrThrowException(@Nonnull String referenceName) {
		return getSchema()
			.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotKnownException(referenceName));
	}

	private class ReferenceUniqueAttributeCheck implements BiPredicate<String, String>, Serializable {
		@Serial private static final long serialVersionUID = -1720123020048815327L;

		@Override
		public boolean test(@Nonnull String referenceName, @Nonnull String attributeName) {
			return getReferences()
				.stream()
				.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
				.anyMatch(it -> it.getAttribute(attributeName) != null);
		}

	}

}
