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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.Scope;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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

	private final String type;
	private final EntitySchemaContract schema;
	private final Integer primaryKey;
	private Scope scope;
	@Delegate(types = AttributesContract.class)
	private final InitialEntityAttributesBuilder attributesBuilder;
	@Delegate(types = AssociatedDataContract.class)
	private final InitialAssociatedDataBuilder associatedDataBuilder;
	@Delegate(types = PricesContract.class, excludes = Versioned.class)
	private final InitialPricesBuilder pricesBuilder;
	private final Map<ReferenceKey, ReferenceContract> references;
	@Nullable private Integer parent;

	public InitialEntityBuilder(@Nonnull String type) {
		this.type = type;
		this.schema = EntitySchema._internalBuild(type);
		this.primaryKey = null;
		this.scope =  Scope.DEFAULT_SCOPE;
		this.attributesBuilder = new InitialEntityAttributesBuilder(this.schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(this.schema);
		this.pricesBuilder = new InitialPricesBuilder(this.schema);
		this.references = new LinkedHashMap<>();
	}

	public InitialEntityBuilder(@Nonnull EntitySchemaContract schema) {
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = null;
		this.scope =  Scope.DEFAULT_SCOPE;
		this.attributesBuilder = new InitialEntityAttributesBuilder(schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(schema);
		this.pricesBuilder = new InitialPricesBuilder(schema);
		this.references = new LinkedHashMap<>();
	}

	public InitialEntityBuilder(@Nonnull String type, @Nullable Integer primaryKey) {
		this.type = type;
		this.primaryKey = primaryKey;
		this.schema = EntitySchema._internalBuild(type);
		this.scope =  Scope.DEFAULT_SCOPE;
		this.attributesBuilder = new InitialEntityAttributesBuilder(this.schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(this.schema);
		this.pricesBuilder = new InitialPricesBuilder(this.schema);
		this.references = new LinkedHashMap<>();
	}

	public InitialEntityBuilder(@Nonnull EntitySchemaContract schema, @Nullable Integer primaryKey) {
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = primaryKey;
		this.scope =  Scope.DEFAULT_SCOPE;
		this.attributesBuilder = new InitialEntityAttributesBuilder(schema);
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(schema);
		this.pricesBuilder = new InitialPricesBuilder(schema);
		this.references = new LinkedHashMap<>();
	}

	public InitialEntityBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Integer primaryKey,
		@Nullable Scope scope,
		@Nonnull Collection<AttributeValue> attributeValues,
		@Nonnull Collection<AssociatedDataValue> associatedDataValues,
		@Nonnull Collection<ReferenceContract> referenceContracts,
		@Nullable PriceInnerRecordHandling priceInnerRecordHandling,
		@Nonnull Collection<PriceContract> prices
	) {
		this.type = entitySchema.getName();
		this.schema = entitySchema;
		this.primaryKey = primaryKey;
		this.scope = scope == null ? Scope.DEFAULT_SCOPE : scope;
		this.attributesBuilder = new InitialEntityAttributesBuilder(this.schema);
		for (AttributeValue attributeValue : attributeValues) {
			final AttributeKey attributeKey = attributeValue.key();
			if (attributeKey.localized()) {
				this.attributesBuilder.setAttribute(
					attributeKey.attributeName(),
					attributeKey.localeOrThrowException(),
					attributeValue.value()
				);
			} else {
				this.attributesBuilder.setAttribute(
					attributeKey.attributeName(),
					attributeValue.value()
				);
			}
		}
		this.associatedDataBuilder = new InitialAssociatedDataBuilder(this.schema);
		for (AssociatedDataValue associatedDataValue : associatedDataValues) {
			final AssociatedDataKey associatedDataKey = associatedDataValue.key();
			if (associatedDataKey.localized()) {
				this.associatedDataBuilder.setAssociatedData(
					associatedDataKey.associatedDataName(),
					associatedDataKey.localeOrThrowException(),
					associatedDataValue.value()
				);
			} else {
				this.associatedDataBuilder.setAssociatedData(
					associatedDataKey.associatedDataName(),
					associatedDataValue.value()
				);
			}
		}
		this.pricesBuilder = new InitialPricesBuilder(this.schema);
		ofNullable(priceInnerRecordHandling)
			.ifPresent(this.pricesBuilder::setPriceInnerRecordHandling);
		for (PriceContract price : prices) {
			this.pricesBuilder.setPrice(
				price.priceId(),
				price.priceList(),
				price.currency(),
				price.innerRecordId(),
				price.priceWithoutTax(),
				price.taxRate(),
				price.priceWithTax(),
				price.validity(),
				price.indexed()
			);
		}

		this.references = referenceContracts.stream()
			.collect(
				Collectors.toMap(
					ReferenceContract::getReferenceKey,
					Function.identity(),
					(o, o2) -> {
						throw new IllegalStateException("Duplicate key " + o);
					},
					LinkedHashMap::new
				)
			);
	}

	@Override
	public boolean dropped() {
		return false;
	}

	@Override
	public int version() {
		return 1;
	}

	@Override
	@Nonnull
	public String getType() {
		return this.type;
	}

	@Override
	@Nonnull
	public EntitySchemaContract getSchema() {
		return this.schema;
	}

	@Override
	@Nullable
	public Integer getPrimaryKey() {
		return this.primaryKey;
	}

	@Nonnull
	@Override
	public Scope getScope() {
		return this.scope;
	}

	@Override
	public boolean parentAvailable() {
		return true;
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		return ofNullable(this.parent)
			.map(it -> new EntityReferenceWithParent(this.type, it, null));
	}

	@Override
	public boolean referencesAvailable() {
		return true;
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return true;
	}
	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		return this.references.values();
	}

	@Nonnull
	@Override
	public Set<String> getReferenceNames() {
		return this.references.keySet()
			.stream()
			.map(ReferenceKey::referenceName)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		return this.references
			.values()
			.stream()
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	@Override
	public DataChunk<ReferenceContract> getReferenceChunk(@Nonnull String referenceName) throws ContextMissingException {
		return new PlainChunk<>(this.getReferences(referenceName));
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		return ofNullable(this.references.get(new ReferenceKey(referenceName, referencedEntityId)));
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull ReferenceKey referenceKey) throws ContextMissingException, ReferenceNotFoundException {
		return ofNullable(this.references.get(referenceKey));
	}

	@Nonnull
	public Set<Locale> getAllLocales() {
		return Stream.concat(
				this.attributesBuilder.getAttributeLocales().stream(),
				this.associatedDataBuilder.getAssociatedDataLocales().stream()
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
		this.attributesBuilder.removeAttribute(attributeName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nullable T attributeValue) {
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue) {
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		this.attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		this.attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		this.associatedDataBuilder.removeAssociatedData(associatedDataName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nullable T associatedDataValue) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		this.associatedDataBuilder.removeAssociatedData(associatedDataName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T associatedDataValue) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		this.associatedDataBuilder.mutateAssociatedData(mutation);
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
	public EntityBuilder setScope(@Nonnull Scope scope) {
		this.scope = scope;
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
		final InitialReferenceBuilder builder = new InitialReferenceBuilder(this.schema, referenceName, referencedPrimaryKey, cardinality, referencedEntityType);
		ofNullable(whichIs).ifPresent(it -> it.accept(builder));
		final Reference reference = builder.build();
		this.references.put(new ReferenceKey(referenceName, referencedPrimaryKey), reference);
		return this;
	}

	@Override
	public void addOrReplaceReferenceMutations(@Nonnull ReferenceBuilder referenceBuilder) {
		this.references.put(referenceBuilder.getReferenceKey(), referenceBuilder.build());
	}

	@Override
	public EntityBuilder removeReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		this.references.remove(new ReferenceKey(referenceName, referencedPrimaryKey));
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean indexed) {
		this.pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean indexed) {
		this.pricesBuilder.setPrice(priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, DateTimeRange validity, boolean indexed) {
		this.pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean indexed) {
		this.pricesBuilder.setPrice(priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		return this;
	}

	@Override
	public EntityBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		this.pricesBuilder.removePrice(priceId, priceList, currency);
		return this;
	}

	@Override
	public EntityBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		this.pricesBuilder.setPriceInnerRecordHandling(priceInnerRecordHandling);
		return this;
	}

	@Override
	public EntityBuilder removePriceInnerRecordHandling() {
		this.pricesBuilder.removePriceInnerRecordHandling();
		return this;
	}

	@Override
	public EntityBuilder removeAllNonTouchedPrices() {
		this.pricesBuilder.removeAllNonTouchedPrices();
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
						this.scope == Scope.LIVE ?
							Stream.<LocalMutation<?,?>>empty() : Stream.of(new SetEntityScopeMutation(this.scope)),
						ofNullable(this.parent)
							.map(SetParentMutation::new)
							.stream(),
						this.references.values()
							.stream()
							.flatMap(it -> Stream.concat(
									Stream.of(
											new InsertReferenceMutation(it.getReferenceKey(), it.getReferenceCardinality(), it.getReferencedEntityType()),
											it.getGroup().map(group -> new SetReferenceGroupMutation(it.getReferenceKey(), group.getType(), group.getPrimaryKey())).orElse(null)
										)
										.filter(Objects::nonNull),
									it.getAttributeValues()
										.stream()
										.filter(x -> Objects.nonNull(x.value()))
										.map(x ->
											new ReferenceAttributeMutation(
												it.getReferenceKey(),
												new UpsertAttributeMutation(x.key(), x.value())
											)
										)
								)
							),
						this.attributesBuilder
							.getAttributeValues()
							.stream()
							.filter(it -> it.value() != null)
							.map(it -> new UpsertAttributeMutation(it.key(), it.value())),
						this.associatedDataBuilder
							.getAssociatedDataValues()
							.stream()
							.map(it -> new UpsertAssociatedDataMutation(it.key(), it.valueOrThrowException())),
						Stream.of(
							new SetPriceInnerRecordHandlingMutation(this.pricesBuilder.getPriceInnerRecordHandling())
						),
						this.pricesBuilder
							.getPrices()
							.stream()
							.map(it -> new UpsertPriceMutation(it.priceKey(), it))
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
			this.primaryKey,
			version(),
			this.schema,
			this.parent,
			this.references.values(),
			this.attributesBuilder.build(),
			this.associatedDataBuilder.build(),
			this.pricesBuilder.build(),
			getAllLocales(),
			Stream.concat(
				this.schema.getReferences().keySet().stream(),
				this.references.keySet()
					.stream()
					.map(ReferenceKey::referenceName)
			).collect(Collectors.toSet()),
			this.schema.isWithHierarchy() || this.parent != null,
			false
		);
	}

	@Nonnull
	private ReferenceSchemaContract getReferenceSchemaOrThrowException(@Nonnull String referenceName) {
		return getSchema()
			.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotKnownException(referenceName));
	}

}
