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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.RemoveAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.*;
import io.evitadb.api.requestResponse.schema.mutation.reference.*;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.store.dataType.serializer.EnumNameSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.wal.data.EntityRemoveMutationSerializer;
import io.evitadb.store.wal.data.EntityUpsertMutationSerializer;
import io.evitadb.store.wal.data.associatedData.RemoveAssociatedDataMutationSerializer;
import io.evitadb.store.wal.data.associatedData.UpsertAssociatedDataMutationSerializer;
import io.evitadb.store.wal.data.attribute.ApplyDeltaAttributeMutationSerializer;
import io.evitadb.store.wal.data.attribute.RemoveAttributeMutationSerializer;
import io.evitadb.store.wal.data.attribute.UpsertAttributeMutationSerializer;
import io.evitadb.store.wal.data.parent.RemoveParentMutationSerializer;
import io.evitadb.store.wal.data.parent.SetParentMutationSerializer;
import io.evitadb.store.wal.data.price.RemovePriceMutationSerializer;
import io.evitadb.store.wal.data.price.SetPriceInnerRecordHandlingMutationSerializer;
import io.evitadb.store.wal.data.price.UpsertPriceMutationSerializer;
import io.evitadb.store.wal.data.reference.InsertReferenceMutationSerializer;
import io.evitadb.store.wal.data.reference.ReferenceAttributeMutationSerializer;
import io.evitadb.store.wal.data.reference.RemoveReferenceGroupMutationSerializer;
import io.evitadb.store.wal.data.reference.RemoveReferenceMutationSerializer;
import io.evitadb.store.wal.data.reference.SetReferenceGroupMutationSerializer;
import io.evitadb.store.wal.data.scope.SetEntityScopeMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.CreateAssociatedDataSchemaMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.ModifyAssociatedDataSchemaDescriptionMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.ModifyAssociatedDataSchemaNameMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.ModifyAssociatedDataSchemaTypeMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.RemoveAssociatedDataSchemaMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.SetAssociatedDataSchemaLocalizedMutationSerializer;
import io.evitadb.store.wal.schema.associatedData.SetAssociatedDataSchemaNullableMutationSerializer;
import io.evitadb.store.wal.schema.attribute.*;
import io.evitadb.store.wal.schema.catalog.*;
import io.evitadb.store.wal.schema.entity.*;
import io.evitadb.store.wal.schema.reference.*;
import io.evitadb.store.wal.schema.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationSerializer;
import io.evitadb.store.wal.schema.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationSerializer_2024_11;
import io.evitadb.store.wal.schema.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationSerializer;
import io.evitadb.store.wal.schema.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationSerializer;
import io.evitadb.store.wal.schema.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationSerializer;
import io.evitadb.store.wal.schema.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationSerializer;
import io.evitadb.store.wal.schema.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationSerializer;
import io.evitadb.store.wal.transaction.TransactionMutationSerializer;
import io.evitadb.utils.Assert;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize WAL (Write Ahead Log).
 */
public class WalKryoConfigurer implements Consumer<Kryo> {
	public static final WalKryoConfigurer INSTANCE = new WalKryoConfigurer();
	private static final int WAL_BASE = 400;

	@Override
	public void accept(Kryo kryo) {
		int index = WAL_BASE;

		kryo.register(EvolutionMode.class, new EnumNameSerializer<>(), index++);
		kryo.register(CatalogEvolutionMode.class, new EnumNameSerializer<>(), index++);
		kryo.register(Cardinality.class, new EnumNameSerializer<>(), index++);
		kryo.register(OrderDirection.class, new EnumNameSerializer<>(), index++);
		kryo.register(OrderBehaviour.class, new EnumNameSerializer<>(), index++);
		kryo.register(AttributeUniquenessType.class, new EnumNameSerializer<>(), index++);
		kryo.register(GlobalAttributeUniquenessType.class, new EnumNameSerializer<>(), index++);
		kryo.register(EntityExistence.class, new EnumNameSerializer<>(), index++);
		kryo.register(PriceInnerRecordHandling.class, new EnumNameSerializer<>(), index++);

		kryo.register(CreateAssociatedDataSchemaMutation.class, new SerialVersionBasedSerializer<>(new CreateAssociatedDataSchemaMutationSerializer(), CreateAssociatedDataSchemaMutation.class), index++);
		kryo.register(ModifyAssociatedDataSchemaDeprecationNoticeMutation.class, new SerialVersionBasedSerializer<>(new ModifyAssociatedDataSchemaDeprecationNoticeMutationSerializer(), ModifyAssociatedDataSchemaDeprecationNoticeMutation.class), index++);
		kryo.register(ModifyAssociatedDataSchemaDescriptionMutation.class, new SerialVersionBasedSerializer<>(new ModifyAssociatedDataSchemaDescriptionMutationSerializer(), ModifyAssociatedDataSchemaDescriptionMutation.class), index++);
		kryo.register(ModifyAssociatedDataSchemaNameMutation.class, new SerialVersionBasedSerializer<>(new ModifyAssociatedDataSchemaNameMutationSerializer(), ModifyAssociatedDataSchemaNameMutation.class), index++);
		kryo.register(ModifyAssociatedDataSchemaTypeMutation.class, new SerialVersionBasedSerializer<>(new ModifyAssociatedDataSchemaTypeMutationSerializer(), ModifyAssociatedDataSchemaTypeMutation.class), index++);
		kryo.register(RemoveAssociatedDataSchemaMutation.class, new SerialVersionBasedSerializer<>(new RemoveAssociatedDataSchemaMutationSerializer(), RemoveAssociatedDataSchemaMutation.class), index++);
		kryo.register(SetAssociatedDataSchemaLocalizedMutation.class, new SerialVersionBasedSerializer<>(new SetAssociatedDataSchemaLocalizedMutationSerializer(), SetAssociatedDataSchemaLocalizedMutation.class), index++);
		kryo.register(SetAssociatedDataSchemaNullableMutation.class, new SerialVersionBasedSerializer<>(new SetAssociatedDataSchemaNullableMutationSerializer(), SetAssociatedDataSchemaNullableMutation.class), index++);

		kryo.register(
			CreateAttributeSchemaMutation.class,
			new SerialVersionBasedSerializer<>(new CreateAttributeSchemaMutationSerializer(), CreateAttributeSchemaMutation.class)
				.addBackwardCompatibleSerializer(-7082514745878566818L, new CreateAttributeSchemaMutationSerializer_2024_11()),
			index++
		);
		kryo.register(
			CreateGlobalAttributeSchemaMutation.class,
			new SerialVersionBasedSerializer<>(new CreateGlobalAttributeSchemaMutationSerializer(), CreateGlobalAttributeSchemaMutation.class)
				.addBackwardCompatibleSerializer(-7082514745878566818L, new CreateGlobalAttributeSchemaMutationSerializer_2024_11()),
			index++
		);
		kryo.register(ModifyAttributeSchemaDefaultValueMutation.class, new SerialVersionBasedSerializer<>(new ModifyAttributeSchemaDefaultValueMutationSerializer(), ModifyAttributeSchemaDefaultValueMutation.class), index++);
		kryo.register(ModifyAttributeSchemaDeprecationNoticeMutation.class, new SerialVersionBasedSerializer<>(new ModifyAttributeSchemaDeprecationNoticeMutationSerializer(), ModifyAttributeSchemaDeprecationNoticeMutation.class), index++);
		kryo.register(ModifyAttributeSchemaDescriptionMutation.class, new SerialVersionBasedSerializer<>(new ModifyAttributeSchemaDescriptionMutationSerializer(), ModifyAttributeSchemaDescriptionMutation.class), index++);
		kryo.register(ModifyAttributeSchemaNameMutation.class, new SerialVersionBasedSerializer<>(new ModifyAttributeSchemaNameMutationSerializer(), ModifyAttributeSchemaNameMutation.class), index++);
		kryo.register(ModifyAttributeSchemaTypeMutation.class, new SerialVersionBasedSerializer<>(new ModifyAttributeSchemaTypeMutationSerializer(), ModifyAttributeSchemaTypeMutation.class), index++);
		kryo.register(RemoveAttributeSchemaMutation.class, new SerialVersionBasedSerializer<>(new RemoveAttributeSchemaMutationSerializer(), RemoveAttributeSchemaMutation.class), index++);
		kryo.register(
			SetAttributeSchemaFilterableMutation.class,
			new SerialVersionBasedSerializer<>(new SetAttributeSchemaFilterableMutationSerializer(), SetAttributeSchemaFilterableMutation.class)
				.addBackwardCompatibleSerializer(2640270593395210307L, new SetAttributeSchemaFilterableMutationSerializer_2024_11()),
			index++
		);
		kryo.register(
			SetAttributeSchemaGloballyUniqueMutation.class,
			new SerialVersionBasedSerializer<>(new SetAttributeSchemaGloballyUniqueMutationSerializer(), SetAttributeSchemaGloballyUniqueMutation.class)
				.addBackwardCompatibleSerializer(-2200571466479594746L, new SetAttributeSchemaGloballyUniqueMutationSerializer_2024_11()),
			index++
		);
		kryo.register(SetAttributeSchemaLocalizedMutation.class, new SerialVersionBasedSerializer<>(new SetAttributeSchemaLocalizedMutationSerializer(), SetAttributeSchemaLocalizedMutation.class), index++);
		kryo.register(SetAttributeSchemaNullableMutation.class, new SerialVersionBasedSerializer<>(new SetAttributeSchemaNullableMutationSerializer(), SetAttributeSchemaNullableMutation.class), index++);
		kryo.register(SetAttributeSchemaRepresentativeMutation.class, new SerialVersionBasedSerializer<>(new SetAttributeSchemaRepresentativeMutationSerializer(), SetAttributeSchemaRepresentativeMutation.class), index++);
		kryo.register(
			SetAttributeSchemaSortableMutation.class,
			new SerialVersionBasedSerializer<>(new SetAttributeSchemaSortableMutationSerializer(), SetAttributeSchemaSortableMutation.class)
				.addBackwardCompatibleSerializer(5362264895300132417L, new SetAttributeSchemaSortableMutationSerializer_2024_11()),
			index++
		);
		kryo.register(
			SetAttributeSchemaUniqueMutation.class,
			new SerialVersionBasedSerializer<>(new SetAttributeSchemaUniqueMutationSerializer(), SetAttributeSchemaUniqueMutation.class)
				.addBackwardCompatibleSerializer(9015269199183582415L, new SetAttributeSchemaUniqueMutationSerializer_2024_11()),
			index++
		);
		kryo.register(UseGlobalAttributeSchemaMutation.class, new SerialVersionBasedSerializer<>(new UseGlobalAttributeSchemaMutationSerializer(), UseGlobalAttributeSchemaMutation.class), index++);

		kryo.register(AllowEvolutionModeInCatalogSchemaMutation.class, new SerialVersionBasedSerializer<>(new AllowEvolutionModeInCatalogSchemaMutationSerializer(), AllowEvolutionModeInCatalogSchemaMutation.class), index++);
		kryo.register(CreateCatalogSchemaMutation.class, new SerialVersionBasedSerializer<>(new CreateCatalogSchemaMutationSerializer(), CreateCatalogSchemaMutation.class), index++);
		kryo.register(CreateEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new CreateEntitySchemaMutationSerializer(), CreateEntitySchemaMutation.class), index++);
		kryo.register(DisallowEvolutionModeInCatalogSchemaMutation.class, new SerialVersionBasedSerializer<>(new DisallowEvolutionModeInCatalogSchemaMutationSerializer(), DisallowEvolutionModeInCatalogSchemaMutation.class), index++);
		kryo.register(ModifyCatalogSchemaDescriptionMutation.class, new SerialVersionBasedSerializer<>(new ModifyCatalogSchemaDescriptionMutationSerializer(), ModifyCatalogSchemaDescriptionMutation.class), index++);
		kryo.register(ModifyCatalogSchemaMutation.class, new SerialVersionBasedSerializer<>(new ModifyCatalogSchemaMutationSerializer(), ModifyCatalogSchemaMutation.class), index++);
		kryo.register(ModifyCatalogSchemaNameMutation.class, new SerialVersionBasedSerializer<>(new ModifyCatalogSchemaNameMutationSerializer(), ModifyCatalogSchemaNameMutation.class), index++);
		kryo.register(ModifyEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new ModifyEntitySchemaMutationSerializer(), ModifyEntitySchemaMutation.class), index++);
		kryo.register(ModifyEntitySchemaNameMutation.class, new SerialVersionBasedSerializer<>(new ModifyEntitySchemaNameMutationSerializer(), ModifyEntitySchemaNameMutation.class), index++);
		kryo.register(RemoveCatalogSchemaMutation.class, new SerialVersionBasedSerializer<>(new RemoveCatalogSchemaMutationSerializer(), RemoveCatalogSchemaMutation.class), index++);
		kryo.register(RemoveEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new RemoveEntitySchemaMutationSerializer(), RemoveEntitySchemaMutation.class), index++);

		kryo.register(AllowCurrencyInEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new AllowCurrencyInEntitySchemaMutationSerializer(), AllowCurrencyInEntitySchemaMutation.class), index++);
		kryo.register(AllowEvolutionModeInEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new AllowEvolutionModeInEntitySchemaMutationSerializer(), AllowEvolutionModeInEntitySchemaMutation.class), index++);
		kryo.register(AllowLocaleInEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new AllowLocaleInEntitySchemaMutationSerializer(), AllowLocaleInEntitySchemaMutation.class), index++);
		kryo.register(DisallowCurrencyInEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new DisallowCurrencyInEntitySchemaMutationSerializer(), DisallowCurrencyInEntitySchemaMutation.class), index++);
		kryo.register(DisallowEvolutionModeInEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new DisallowEvolutionModeInEntitySchemaMutationSerializer(), DisallowEvolutionModeInEntitySchemaMutation.class), index++);
		kryo.register(DisallowLocaleInEntitySchemaMutation.class, new SerialVersionBasedSerializer<>(new DisallowLocaleInEntitySchemaMutationSerializer(), DisallowLocaleInEntitySchemaMutation.class), index++);
		kryo.register(ModifyEntitySchemaDeprecationNoticeMutation.class, new SerialVersionBasedSerializer<>(new ModifyEntitySchemaDeprecationNoticeMutationSerializer(), ModifyEntitySchemaDeprecationNoticeMutation.class), index++);
		kryo.register(ModifyEntitySchemaDescriptionMutation.class, new SerialVersionBasedSerializer<>(new ModifyEntitySchemaDescriptionMutationSerializer(), ModifyEntitySchemaDescriptionMutation.class), index++);
		kryo.register(SetEntitySchemaWithGeneratedPrimaryKeyMutation.class, new SerialVersionBasedSerializer<>(new SetEntitySchemaWithGeneratedPrimaryKeyMutationSerializer(), SetEntitySchemaWithGeneratedPrimaryKeyMutation.class), index++);
		kryo.register(
			SetEntitySchemaWithHierarchyMutation.class,
			new SerialVersionBasedSerializer<>(new SetEntitySchemaWithHierarchyMutationSerializer(), SetEntitySchemaWithHierarchyMutation.class)
				.addBackwardCompatibleSerializer(5706690342982246498L, new SetEntitySchemaWithHierarchyMutationSerializer_2024_11()),
			index++
		);
		kryo.register(
			SetEntitySchemaWithPriceMutation.class,
			new SerialVersionBasedSerializer<>(new SetEntitySchemaWithPriceMutationSerializer(), SetEntitySchemaWithPriceMutation.class),
			index++
		);

		kryo.register(
			CreateReferenceSchemaMutation.class,
			new SerialVersionBasedSerializer<>(new CreateReferenceSchemaMutationSerializer(), CreateReferenceSchemaMutation.class)
				.addBackwardCompatibleSerializer(-1736213837309810284L, new CreateReferenceSchemaMutationSerializer_2024_11())
				.addBackwardCompatibleSerializer(-5200773391501101688L, new CreateReferenceSchemaMutationSerializer_2025_5()),
			index++
		);
		kryo.register(ModifyReferenceAttributeSchemaMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceAttributeSchemaMutationSerializer(), ModifyReferenceAttributeSchemaMutation.class), index++);
		kryo.register(ModifyReferenceSchemaCardinalityMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceSchemaCardinalityMutationSerializer(), ModifyReferenceSchemaCardinalityMutation.class), index++);
		kryo.register(ModifyReferenceSchemaDeprecationNoticeMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceSchemaDeprecationNoticeMutationSerializer(), ModifyReferenceSchemaDeprecationNoticeMutation.class), index++);
		kryo.register(ModifyReferenceSchemaDescriptionMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceSchemaDescriptionMutationSerializer(), ModifyReferenceSchemaDescriptionMutation.class), index++);
		kryo.register(ModifyReferenceSchemaNameMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceSchemaNameMutationSerializer(), ModifyReferenceSchemaNameMutation.class), index++);
		kryo.register(ModifyReferenceSchemaRelatedEntityGroupMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceSchemaRelatedEntityGroupMutationSerializer(), ModifyReferenceSchemaRelatedEntityGroupMutation.class), index++);
		kryo.register(ModifyReferenceSchemaRelatedEntityMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceSchemaRelatedEntityMutationSerializer(), ModifyReferenceSchemaRelatedEntityMutation.class), index++);
		kryo.register(ModifyReferenceSortableAttributeCompoundSchemaMutation.class, new SerialVersionBasedSerializer<>(new ModifyReferenceSortableAttributeCompoundSchemaMutationSerializer(), ModifyReferenceSortableAttributeCompoundSchemaMutation.class), index++);
		kryo.register(RemoveReferenceSchemaMutation.class, new SerialVersionBasedSerializer<>(new RemoveReferenceSchemaMutationSerializer(), RemoveReferenceSchemaMutation.class), index++);
		kryo.register(
			SetReferenceSchemaFacetedMutation.class,
			new SerialVersionBasedSerializer<>(new SetReferenceSchemaFacetedMutationSerializer(), SetReferenceSchemaFacetedMutation.class)
				.addBackwardCompatibleSerializer(-8866197153007138452L, new SetReferenceSchemaFacetedMutationSerializer_2024_10())
				.addBackwardCompatibleSerializer(4847175066828277710L, new SetReferenceSchemaFacetedMutationSerializer_2024_11()),
			index++
		);
		kryo.register(
			SetReferenceSchemaIndexedMutation.class,
			new SerialVersionBasedSerializer<>(new SetReferenceSchemaIndexedMutationSerializer(), SetReferenceSchemaIndexedMutation.class)
				.addBackwardCompatibleSerializer(6302709513348603359L, new SetReferenceSchemaIndexedMutationSerializer_2024_10())
				.addBackwardCompatibleSerializer(-4329391051963284444L, new SetReferenceSchemaIndexedMutationSerializer_2024_11())
				.addBackwardCompatibleSerializer(9004841790854082119L, new SetReferenceSchemaIndexedMutationSerializer_2025_5()),
			index++
		);

		kryo.register(
			CreateSortableAttributeCompoundSchemaMutation.class,
			new SerialVersionBasedSerializer<>(new CreateSortableAttributeCompoundSchemaMutationSerializer(), CreateSortableAttributeCompoundSchemaMutation.class)
				.addBackwardCompatibleSerializer(5667962046673510848L, new CreateSortableAttributeCompoundSchemaMutationSerializer_2024_11()),
			index++
		);
		kryo.register(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class, new SerialVersionBasedSerializer<>(new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationSerializer(), ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class), index++);
		kryo.register(ModifySortableAttributeCompoundSchemaDescriptionMutation.class, new SerialVersionBasedSerializer<>(new ModifySortableAttributeCompoundSchemaDescriptionMutationSerializer(), ModifySortableAttributeCompoundSchemaDescriptionMutation.class), index++);
		kryo.register(ModifySortableAttributeCompoundSchemaNameMutation.class, new SerialVersionBasedSerializer<>(new ModifySortableAttributeCompoundSchemaNameMutationSerializer(), ModifySortableAttributeCompoundSchemaNameMutation.class), index++);
		kryo.register(RemoveSortableAttributeCompoundSchemaMutation.class, new SerialVersionBasedSerializer<>(new RemoveSortableAttributeCompoundSchemaMutationSerializer(), RemoveSortableAttributeCompoundSchemaMutation.class), index++);

		kryo.register(RemoveAssociatedDataMutation.class, new SerialVersionBasedSerializer<>(new RemoveAssociatedDataMutationSerializer(), RemoveAssociatedDataMutation.class), index++);
		kryo.register(UpsertAssociatedDataMutation.class, new SerialVersionBasedSerializer<>(new UpsertAssociatedDataMutationSerializer(), UpsertAssociatedDataMutation.class), index++);
		kryo.register(ApplyDeltaAttributeMutation.class, new SerialVersionBasedSerializer<>(new ApplyDeltaAttributeMutationSerializer(), ApplyDeltaAttributeMutation.class), index++);
		kryo.register(RemoveAttributeMutation.class, new SerialVersionBasedSerializer<>(new RemoveAttributeMutationSerializer(), RemoveAttributeMutation.class), index++);
		kryo.register(UpsertAttributeMutation.class, new SerialVersionBasedSerializer<>(new UpsertAttributeMutationSerializer(), UpsertAttributeMutation.class), index++);
		kryo.register(RemoveParentMutation.class, new SerialVersionBasedSerializer<>(new RemoveParentMutationSerializer(), RemoveParentMutation.class), index++);
		kryo.register(SetParentMutation.class, new SerialVersionBasedSerializer<>(new SetParentMutationSerializer(), SetParentMutation.class), index++);
		kryo.register(RemovePriceMutation.class, new SerialVersionBasedSerializer<>(new RemovePriceMutationSerializer(), RemovePriceMutation.class), index++);
		kryo.register(SetPriceInnerRecordHandlingMutation.class, new SerialVersionBasedSerializer<>(new SetPriceInnerRecordHandlingMutationSerializer(), SetPriceInnerRecordHandlingMutation.class), index++);
		kryo.register(UpsertPriceMutation.class, new SerialVersionBasedSerializer<>(new UpsertPriceMutationSerializer(), UpsertPriceMutation.class), index++);
		kryo.register(InsertReferenceMutation.class, new SerialVersionBasedSerializer<>(new InsertReferenceMutationSerializer(), InsertReferenceMutation.class), index++);
		kryo.register(ReferenceAttributeMutation.class, new SerialVersionBasedSerializer<>(new ReferenceAttributeMutationSerializer(), ReferenceAttributeMutation.class), index++);
		kryo.register(RemoveReferenceGroupMutation.class, new SerialVersionBasedSerializer<>(new RemoveReferenceGroupMutationSerializer(), RemoveReferenceGroupMutation.class), index++);
		kryo.register(RemoveReferenceMutation.class, new SerialVersionBasedSerializer<>(new RemoveReferenceMutationSerializer(), RemoveReferenceMutation.class), index++);
		kryo.register(SetReferenceGroupMutation.class, new SerialVersionBasedSerializer<>(new SetReferenceGroupMutationSerializer(), SetReferenceGroupMutation.class), index++);
		kryo.register(EntityRemoveMutation.class, new SerialVersionBasedSerializer<>(new EntityRemoveMutationSerializer(), EntityRemoveMutation.class), index++);
		kryo.register(EntityUpsertMutation.class, new SerialVersionBasedSerializer<>(new EntityUpsertMutationSerializer(), EntityUpsertMutation.class), index++);

		kryo.register(TransactionMutation.class, new SerialVersionBasedSerializer<>(new TransactionMutationSerializer(), TransactionMutation.class), index++);

		kryo.register(
			CreateReflectedReferenceSchemaMutation.class,
			new SerialVersionBasedSerializer<>(new CreateReflectedReferenceSchemaMutationSerializer(), CreateReflectedReferenceSchemaMutation.class)
				.addBackwardCompatibleSerializer(4075653645885678621L, new CreateReflectedReferenceSchemaMutationSerializer_2024_11())
				.addBackwardCompatibleSerializer(-2419676866574635677L, new CreateReflectedReferenceSchemaMutationSerializer_2025_5()),
			index++
		);
		kryo.register(ModifyReflectedReferenceAttributeInheritanceSchemaMutation.class, new SerialVersionBasedSerializer<>(new ModifyReflectedReferenceAttributeInheritanceSchemaMutationSerializer(), ModifyReflectedReferenceAttributeInheritanceSchemaMutation.class), index++);
		kryo.register(AttributeInheritanceBehavior.class, new EnumNameSerializer<>(), index++);

		kryo.register(SetEntityScopeMutation.class, new SerialVersionBasedSerializer<>(new SetEntityScopeMutationSerializer(), SetEntityScopeMutation.class), index++);
		kryo.register(SetSortableAttributeCompoundIndexedMutation.class, new SerialVersionBasedSerializer<>(new SetSortableAttributeCompoundIndexedMutationSerializer(), SetSortableAttributeCompoundIndexedMutation.class), index++);
		kryo.register(Scope.class, new EnumNameSerializer<>(), index++);
		kryo.register(ReferenceIndexType.class, new EnumNameSerializer<>(), index++);

		kryo.register(MakeCatalogAliveMutation.class, new SerialVersionBasedSerializer<>(new MakeCatalogAliveMutationSerializer(), MakeCatalogAliveMutation.class), index++);
		kryo.register(SetCatalogStateMutation.class, new SerialVersionBasedSerializer<>(new SetCatalogStateMutationSerializer(), SetCatalogStateMutation.class), index++);
		kryo.register(SetCatalogMutabilityMutation.class, new SerialVersionBasedSerializer<>(new SetCatalogMutabilityMutationSerializer(), SetCatalogMutabilityMutation.class), index++);
		kryo.register(DuplicateCatalogMutation.class, new SerialVersionBasedSerializer<>(new DuplicateCatalogMutationSerializer(), DuplicateCatalogMutation.class), index++);
		kryo.register(RestoreCatalogSchemaMutation.class, new SerialVersionBasedSerializer<>(new RestoreCatalogSchemaMutationSerializer(), RestoreCatalogSchemaMutation.class), index++);

		Assert.isPremiseValid(index < 801, "Index count overflow.");
	}

}
