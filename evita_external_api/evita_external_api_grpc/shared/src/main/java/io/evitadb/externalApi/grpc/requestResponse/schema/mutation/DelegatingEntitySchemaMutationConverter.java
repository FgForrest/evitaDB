
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.RemoveAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.*;
import io.evitadb.api.requestResponse.schema.mutation.reference.*;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutation;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation.MutationCase;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.RemoveAssociatedDataSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaNullableMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.entity.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationConverter;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation.MutationCase.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Can convert between any implementation of {@link EntitySchemaMutation} and {@link GrpcEntitySchemaMutation} by delegating
 * each mutation to specific converter.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DelegatingEntitySchemaMutationConverter implements SchemaMutationConverter<EntitySchemaMutation, GrpcEntitySchemaMutation> {
	public static final DelegatingEntitySchemaMutationConverter INSTANCE = new DelegatingEntitySchemaMutationConverter();

	private static final Map<Class<? extends EntitySchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(250);
		TO_GRPC_CONVERTERS.put(CreateEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setCreateEntitySchemaMutation((GrpcCreateEntitySchemaMutation) m), CreateEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveEntitySchemaMutation((GrpcRemoveEntitySchemaMutation) m), RemoveEntitySchemaMutationConverter.INSTANCE));
		// associated data schema mutations
		TO_GRPC_CONVERTERS.put(CreateAssociatedDataSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateAssociatedDataSchemaMutation((GrpcCreateAssociatedDataSchemaMutation) m), CreateAssociatedDataSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaDeprecationNoticeMutation((GrpcModifyAssociatedDataSchemaDeprecationNoticeMutation) m), ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaDescriptionMutation((GrpcModifyAssociatedDataSchemaDescriptionMutation) m), ModifyAssociatedDataSchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaNameMutation((GrpcModifyAssociatedDataSchemaNameMutation) m), ModifyAssociatedDataSchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaTypeMutation((GrpcModifyAssociatedDataSchemaTypeMutation) m), ModifyAssociatedDataSchemaTypeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveAssociatedDataSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAssociatedDataSchemaMutation((GrpcRemoveAssociatedDataSchemaMutation) m), RemoveAssociatedDataSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAssociatedDataSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAssociatedDataSchemaLocalizedMutation((GrpcSetAssociatedDataSchemaLocalizedMutation) m), SetAssociatedDataSchemaLocalizedMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAssociatedDataSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAssociatedDataSchemaNullableMutation((GrpcSetAssociatedDataSchemaNullableMutation) m), SetAssociatedDataSchemaNullableMutationConverter.INSTANCE));
		// attribute schema mutations
		TO_GRPC_CONVERTERS.put(CreateAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateAttributeSchemaMutation((GrpcCreateAttributeSchemaMutation) m), CreateAttributeSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDefaultValueMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDefaultValueMutation((GrpcModifyAttributeSchemaDefaultValueMutation) m), ModifyAttributeSchemaDefaultValueMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDeprecationNoticeMutation((GrpcModifyAttributeSchemaDeprecationNoticeMutation) m), ModifyAttributeSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDescriptionMutation((GrpcModifyAttributeSchemaDescriptionMutation) m), ModifyAttributeSchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaNameMutation((GrpcModifyAttributeSchemaNameMutation) m), ModifyAttributeSchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaTypeMutation((GrpcModifyAttributeSchemaTypeMutation) m), ModifyAttributeSchemaTypeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeSchemaMutation((GrpcRemoveAttributeSchemaMutation) m), RemoveAttributeSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaFilterableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaFilterableMutation((GrpcSetAttributeSchemaFilterableMutation) m), SetAttributeSchemaFilterableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaLocalizedMutation((GrpcSetAttributeSchemaLocalizedMutation) m), SetAttributeSchemaLocalizedMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaNullableMutation((GrpcSetAttributeSchemaNullableMutation) m), SetAttributeSchemaNullableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaRepresentativeMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaRepresentativeMutation((GrpcSetAttributeSchemaRepresentativeMutation) m), SetAttributeSchemaRepresentativeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaSortableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaSortableMutation((GrpcSetAttributeSchemaSortableMutation) m), SetAttributeSchemaSortableMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaUniqueMutation((GrpcSetAttributeSchemaUniqueMutation) m), SetAttributeSchemaUniqueMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(UseGlobalAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setUseGlobalAttributeSchemaMutation((GrpcUseGlobalAttributeSchemaMutation) m), UseGlobalAttributeSchemaMutationConverter.INSTANCE));
		// sortable attribute compound schema mutations
		TO_GRPC_CONVERTERS.put(CreateSortableAttributeCompoundSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateSortableAttributeCompoundSchemaMutation((GrpcCreateSortableAttributeCompoundSchemaMutation) m), CreateSortableAttributeCompoundSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifySortableAttributeCompoundSchemaDeprecationNoticeMutation((GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation) m), ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifySortableAttributeCompoundSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifySortableAttributeCompoundSchemaDescriptionMutation((GrpcModifySortableAttributeCompoundSchemaDescriptionMutation) m), ModifySortableAttributeCompoundSchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifySortableAttributeCompoundSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifySortableAttributeCompoundSchemaNameMutation((GrpcModifySortableAttributeCompoundSchemaNameMutation) m), ModifySortableAttributeCompoundSchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveSortableAttributeCompoundSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveSortableAttributeCompoundSchemaMutation((GrpcRemoveSortableAttributeCompoundSchemaMutation) m), RemoveSortableAttributeCompoundSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetSortableAttributeCompoundIndexedMutation.class, new ToGrpc((b, m) -> b.setSetSortableAttributeCompoundIndexedMutation((GrpcSetSortableAttributeCompoundIndexedMutation) m), SetSortableAttributeCompoundIndexedMutationConverter.INSTANCE));
		// entity schema mutations
		TO_GRPC_CONVERTERS.put(AllowCurrencyInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setAllowCurrencyInEntitySchemaMutation((GrpcAllowCurrencyInEntitySchemaMutation) m), AllowCurrencyInEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(AllowEvolutionModeInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setAllowEvolutionModeInEntitySchemaMutation((GrpcAllowEvolutionModeInEntitySchemaMutation) m), AllowEvolutionModeInEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(AllowLocaleInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setAllowLocaleInEntitySchemaMutation((GrpcAllowLocaleInEntitySchemaMutation) m), AllowLocaleInEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(DisallowCurrencyInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setDisallowCurrencyInEntitySchemaMutation((GrpcDisallowCurrencyInEntitySchemaMutation) m), DisallowCurrencyInEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(DisallowEvolutionModeInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setDisallowEvolutionModeInEntitySchemaMutation((GrpcDisallowEvolutionModeInEntitySchemaMutation) m), DisallowEvolutionModeInEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(DisallowLocaleInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setDisallowLocaleInEntitySchemaMutation((GrpcDisallowLocaleInEntitySchemaMutation) m), DisallowLocaleInEntitySchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaNameMutation((GrpcModifyEntitySchemaNameMutation) m), ModifyEntitySchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaDeprecationNoticeMutation((GrpcModifyEntitySchemaDeprecationNoticeMutation) m), ModifyEntitySchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaDescriptionMutation((GrpcModifyEntitySchemaDescriptionMutation) m), ModifyEntitySchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetEntitySchemaWithGeneratedPrimaryKeyMutation.class, new ToGrpc((b, m) -> b.setSetEntitySchemaWithGeneratedPrimaryKeyMutation((GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation) m), SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetEntitySchemaWithHierarchyMutation.class, new ToGrpc((b, m) -> b.setSetEntitySchemaWithHierarchyMutation((GrpcSetEntitySchemaWithHierarchyMutation) m), SetEntitySchemaWithHierarchyMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetEntitySchemaWithPriceMutation.class, new ToGrpc((b, m) -> b.setSetEntitySchemaWithPriceMutation((GrpcSetEntitySchemaWithPriceMutation) m), SetEntitySchemaWithPriceMutationConverter.INSTANCE));
		// reference schema mutations
		TO_GRPC_CONVERTERS.put(CreateReferenceSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateReferenceSchemaMutation((GrpcCreateReferenceSchemaMutation) m), CreateReferenceSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(CreateReflectedReferenceSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateReflectedReferenceSchemaMutation((GrpcCreateReflectedReferenceSchemaMutation) m), CreateReflectedReferenceSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceAttributeSchemaMutation((GrpcModifyReferenceAttributeSchemaMutation) m), ModifyReferenceAttributeSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaCardinalityMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaCardinalityMutation((GrpcModifyReferenceSchemaCardinalityMutation) m), ModifyReferenceSchemaCardinalityMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaDeprecationNoticeMutation((GrpcModifyReferenceSchemaDeprecationNoticeMutation) m), ModifyReferenceSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaDescriptionMutation((GrpcModifyReferenceSchemaDescriptionMutation) m), ModifyReferenceSchemaDescriptionMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaNameMutation((GrpcModifyReferenceSchemaNameMutation) m), ModifyReferenceSchemaNameMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaRelatedEntityGroupMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaRelatedEntityGroupMutation((GrpcModifyReferenceSchemaRelatedEntityGroupMutation) m), ModifyReferenceSchemaRelatedEntityGroupMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaRelatedEntityMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaRelatedEntityMutation((GrpcModifyReferenceSchemaRelatedEntityMutation) m), ModifyReferenceSchemaRelatedEntityMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReflectedReferenceAttributeInheritanceSchemaMutation.class, new ToGrpc((b, m) -> b.setModifyReflectedReferenceAttributeInheritanceSchemaMutation((GrpcModifyReflectedReferenceAttributeInheritanceSchemaMutation) m), ModifyReflectedReferenceAttributeInheritanceSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(RemoveReferenceSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveReferenceSchemaMutation((GrpcRemoveReferenceSchemaMutation) m), RemoveReferenceSchemaMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetReferenceSchemaFacetedMutation.class, new ToGrpc((b, m) -> b.setSetReferenceSchemaFacetedMutation((GrpcSetReferenceSchemaFacetedMutation) m), SetReferenceSchemaFacetedMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(SetReferenceSchemaIndexedMutation.class, new ToGrpc((b, m) -> b.setSetReferenceSchemaIndexedMutation((GrpcSetReferenceSchemaIndexedMutation) m), SetReferenceSchemaIndexedMutationConverter.INSTANCE));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSortableAttributeCompoundSchemaMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSortableAttributeCompoundSchemaMutation((GrpcModifyReferenceSortableAttributeCompoundSchemaMutation) m), ModifyReferenceSortableAttributeCompoundSchemaMutationConverter.INSTANCE));

		TO_JAVA_CONVERTERS = createHashMap(250);
		TO_JAVA_CONVERTERS.put(CREATEENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateEntitySchemaMutation, CreateEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveEntitySchemaMutation, RemoveEntitySchemaMutationConverter.INSTANCE));
		// associated data schema mutations
		TO_JAVA_CONVERTERS.put(CREATEASSOCIATEDDATASCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateAssociatedDataSchemaMutation, CreateAssociatedDataSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaDeprecationNoticeMutation, ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaDescriptionMutation, ModifyAssociatedDataSchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaNameMutation, ModifyAssociatedDataSchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMATYPEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaTypeMutation, ModifyAssociatedDataSchemaTypeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEASSOCIATEDDATASCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveAssociatedDataSchemaMutation, RemoveAssociatedDataSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETASSOCIATEDDATASCHEMALOCALIZEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAssociatedDataSchemaLocalizedMutation, SetAssociatedDataSchemaLocalizedMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETASSOCIATEDDATASCHEMANULLABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAssociatedDataSchemaNullableMutation, SetAssociatedDataSchemaNullableMutationConverter.INSTANCE));
		// attribute schema mutations
		TO_JAVA_CONVERTERS.put(CREATEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateAttributeSchemaMutation, CreateAttributeSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEFAULTVALUEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaDefaultValueMutation, ModifyAttributeSchemaDefaultValueMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaDeprecationNoticeMutation, ModifyAttributeSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaDescriptionMutation, ModifyAttributeSchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaNameMutation, ModifyAttributeSchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMATYPEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaTypeMutation, ModifyAttributeSchemaTypeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveAttributeSchemaMutation, RemoveAttributeSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAFILTERABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaFilterableMutation, SetAttributeSchemaFilterableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMALOCALIZEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaLocalizedMutation, SetAttributeSchemaLocalizedMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMANULLABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaNullableMutation, SetAttributeSchemaNullableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAREPRESENTATIVEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaRepresentativeMutation, SetAttributeSchemaRepresentativeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMASORTABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaSortableMutation, SetAttributeSchemaSortableMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAUNIQUEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaUniqueMutation, SetAttributeSchemaUniqueMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(USEGLOBALATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getUseGlobalAttributeSchemaMutation, UseGlobalAttributeSchemaMutationConverter.INSTANCE));
		// sortable attribute compound schema mutations
		TO_JAVA_CONVERTERS.put(CREATESORTABLEATTRIBUTECOMPOUNDSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateSortableAttributeCompoundSchemaMutation, CreateSortableAttributeCompoundSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYSORTABLEATTRIBUTECOMPOUNDSCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifySortableAttributeCompoundSchemaDeprecationNoticeMutation, ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYSORTABLEATTRIBUTECOMPOUNDSCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifySortableAttributeCompoundSchemaDescriptionMutation, ModifySortableAttributeCompoundSchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYSORTABLEATTRIBUTECOMPOUNDSCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifySortableAttributeCompoundSchemaNameMutation, ModifySortableAttributeCompoundSchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVESORTABLEATTRIBUTECOMPOUNDSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveSortableAttributeCompoundSchemaMutation, RemoveSortableAttributeCompoundSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETSORTABLEATTRIBUTECOMPOUNDINDEXEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetSortableAttributeCompoundIndexedMutation, SetSortableAttributeCompoundIndexedMutationConverter.INSTANCE));
		// entity schema mutations
		TO_JAVA_CONVERTERS.put(ALLOWCURRENCYINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getAllowCurrencyInEntitySchemaMutation, AllowCurrencyInEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(ALLOWEVOLUTIONMODEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getAllowEvolutionModeInEntitySchemaMutation, AllowEvolutionModeInEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(ALLOWLOCALEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getAllowLocaleInEntitySchemaMutation, AllowLocaleInEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(DISALLOWCURRENCYINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getDisallowCurrencyInEntitySchemaMutation, DisallowCurrencyInEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(DISALLOWEVOLUTIONMODEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getDisallowEvolutionModeInEntitySchemaMutation, DisallowEvolutionModeInEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(DISALLOWLOCALEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getDisallowLocaleInEntitySchemaMutation, DisallowLocaleInEntitySchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyEntitySchemaNameMutation, ModifyEntitySchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyEntitySchemaDeprecationNoticeMutation, ModifyEntitySchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyEntitySchemaDescriptionMutation, ModifyEntitySchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETENTITYSCHEMAWITHGENERATEDPRIMARYKEYMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetEntitySchemaWithGeneratedPrimaryKeyMutation, SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETENTITYSCHEMAWITHHIERARCHYMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetEntitySchemaWithHierarchyMutation, SetEntitySchemaWithHierarchyMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETENTITYSCHEMAWITHPRICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetEntitySchemaWithPriceMutation, SetEntitySchemaWithPriceMutationConverter.INSTANCE));
		// reference schema mutations
		TO_JAVA_CONVERTERS.put(CREATEREFERENCESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateReferenceSchemaMutation, CreateReferenceSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(CREATEREFLECTEDREFERENCESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateReflectedReferenceSchemaMutation, CreateReflectedReferenceSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceAttributeSchemaMutation, ModifyReferenceAttributeSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMACARDINALITYMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaCardinalityMutation, ModifyReferenceSchemaCardinalityMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaDeprecationNoticeMutation, ModifyReferenceSchemaDeprecationNoticeMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaDescriptionMutation, ModifyReferenceSchemaDescriptionMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaNameMutation, ModifyReferenceSchemaNameMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMARELATEDENTITYGROUPMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaRelatedEntityGroupMutation, ModifyReferenceSchemaRelatedEntityGroupMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMARELATEDENTITYMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaRelatedEntityMutation, ModifyReferenceSchemaRelatedEntityMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFLECTEDREFERENCEATTRIBUTEINHERITANCESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReflectedReferenceAttributeInheritanceSchemaMutation, ModifyReflectedReferenceAttributeInheritanceSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(REMOVEREFERENCESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveReferenceSchemaMutation, RemoveReferenceSchemaMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETREFERENCESCHEMAFACETEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetReferenceSchemaFacetedMutation, SetReferenceSchemaFacetedMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(SETREFERENCESCHEMAINDEXEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetReferenceSchemaIndexedMutation, SetReferenceSchemaIndexedMutationConverter.INSTANCE));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESORTABLEATTRIBUTECOMPOUNDSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSortableAttributeCompoundSchemaMutation, ModifyReferenceSortableAttributeCompoundSchemaMutationConverter.INSTANCE));
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public GrpcEntitySchemaMutation convert(@Nonnull EntitySchemaMutation mutation) {
		final ToGrpc conversionDescriptor = TO_GRPC_CONVERTERS.get(mutation.getClass());
		Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getClass().getName());
		final SchemaMutationConverter<EntitySchemaMutation, ?> converter =
			(SchemaMutationConverter<EntitySchemaMutation, ?>) conversionDescriptor.converter();

		final GrpcEntitySchemaMutation.Builder builder = GrpcEntitySchemaMutation.newBuilder();
		final BiConsumer<GrpcEntitySchemaMutation.Builder, Message> mutationSetter = (BiConsumer<GrpcEntitySchemaMutation.Builder, Message>) conversionDescriptor.mutationSetter();
		mutationSetter.accept(builder, converter.convert(mutation));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public EntitySchemaMutation convert(@Nonnull GrpcEntitySchemaMutation mutation) {
	    final ToJava conversionDescriptor = TO_JAVA_CONVERTERS.get(mutation.getMutationCase());
	    Assert.notNull(conversionDescriptor, "Unknown mutation type: " + mutation.getMutationCase());

	    final Function<GrpcEntitySchemaMutation, Message> extractor =
			 (Function<GrpcEntitySchemaMutation, Message>) conversionDescriptor.mutationExtractor();
	    final SchemaMutationConverter<EntitySchemaMutation, Message> converter =
			 (SchemaMutationConverter<EntitySchemaMutation, Message>) conversionDescriptor.converter();
	    return converter.convert(extractor.apply(mutation));
	}

	private record ToJava(@Nonnull Function<GrpcEntitySchemaMutation, ? extends Message> mutationExtractor,
	                      @Nonnull SchemaMutationConverter<? extends EntitySchemaMutation, ?> converter) {}

	private record ToGrpc(@Nonnull BiConsumer<GrpcEntitySchemaMutation.Builder, ? extends Message> mutationSetter,
	                      @Nonnull SchemaMutationConverter<? extends EntitySchemaMutation, ?> converter) {}
}
