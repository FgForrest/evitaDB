
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
import io.evitadb.api.requestResponse.schema.mutation.entity.*;
import io.evitadb.api.requestResponse.schema.mutation.reference.*;
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
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.entity.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference.*;
import io.evitadb.utils.Assert;

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
public class DelegatingEntitySchemaMutationConverter implements SchemaMutationConverter<EntitySchemaMutation, GrpcEntitySchemaMutation> {

	private static final Map<Class<? extends EntitySchemaMutation>, ToGrpc> TO_GRPC_CONVERTERS;
	private static final Map<MutationCase, ToJava> TO_JAVA_CONVERTERS;

	static {
		TO_GRPC_CONVERTERS = createHashMap(50);
		// associated data schema mutations
		TO_GRPC_CONVERTERS.put(CreateAssociatedDataSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateAssociatedDataSchemaMutation((GrpcCreateAssociatedDataSchemaMutation) m), new CreateAssociatedDataSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaDeprecationNoticeMutation((GrpcModifyAssociatedDataSchemaDeprecationNoticeMutation) m), new ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaDescriptionMutation((GrpcModifyAssociatedDataSchemaDescriptionMutation) m), new ModifyAssociatedDataSchemaDescriptionMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaNameMutation((GrpcModifyAssociatedDataSchemaNameMutation) m), new ModifyAssociatedDataSchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAssociatedDataSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAssociatedDataSchemaTypeMutation((GrpcModifyAssociatedDataSchemaTypeMutation) m), new ModifyAssociatedDataSchemaTypeMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveAssociatedDataSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAssociatedDataSchemaMutation((GrpcRemoveAssociatedDataSchemaMutation) m), new RemoveAssociatedDataSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAssociatedDataSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAssociatedDataSchemaLocalizedMutation((GrpcSetAssociatedDataSchemaLocalizedMutation) m), new SetAssociatedDataSchemaLocalizedMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAssociatedDataSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAssociatedDataSchemaNullableMutation((GrpcSetAssociatedDataSchemaNullableMutation) m), new SetAssociatedDataSchemaNullableMutationConverter()));
		// attribute schema mutations
		TO_GRPC_CONVERTERS.put(CreateAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateAttributeSchemaMutation((GrpcCreateAttributeSchemaMutation) m), new CreateAttributeSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDefaultValueMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDefaultValueMutation((GrpcModifyAttributeSchemaDefaultValueMutation) m), new ModifyAttributeSchemaDefaultValueMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDeprecationNoticeMutation((GrpcModifyAttributeSchemaDeprecationNoticeMutation) m), new ModifyAttributeSchemaDeprecationNoticeMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaDescriptionMutation((GrpcModifyAttributeSchemaDescriptionMutation) m), new ModifyAttributeSchemaDescriptionMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaNameMutation((GrpcModifyAttributeSchemaNameMutation) m), new ModifyAttributeSchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyAttributeSchemaTypeMutation.class, new ToGrpc((b, m) -> b.setModifyAttributeSchemaTypeMutation((GrpcModifyAttributeSchemaTypeMutation) m), new ModifyAttributeSchemaTypeMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveAttributeSchemaMutation((GrpcRemoveAttributeSchemaMutation) m), new RemoveAttributeSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaFilterableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaFilterableMutation((GrpcSetAttributeSchemaFilterableMutation) m), new SetAttributeSchemaFilterableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaLocalizedMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaLocalizedMutation((GrpcSetAttributeSchemaLocalizedMutation) m), new SetAttributeSchemaLocalizedMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaNullableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaNullableMutation((GrpcSetAttributeSchemaNullableMutation) m), new SetAttributeSchemaNullableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaRepresentativeMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaRepresentativeMutation((GrpcSetAttributeSchemaRepresentativeMutation) m), new SetAttributeSchemaRepresentativeMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaSortableMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaSortableMutation((GrpcSetAttributeSchemaSortableMutation) m), new SetAttributeSchemaSortableMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetAttributeSchemaUniqueMutation.class, new ToGrpc((b, m) -> b.setSetAttributeSchemaUniqueMutation((GrpcSetAttributeSchemaUniqueMutation) m), new SetAttributeSchemaUniqueMutationConverter()));
		TO_GRPC_CONVERTERS.put(UseGlobalAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setUseGlobalAttributeSchemaMutation((GrpcUseGlobalAttributeSchemaMutation) m), new UseGlobalAttributeSchemaMutationConverter()));
		// entity schema mutations
		TO_GRPC_CONVERTERS.put(AllowCurrencyInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setAllowCurrencyInEntitySchemaMutation((GrpcAllowCurrencyInEntitySchemaMutation) m), new AllowCurrencyInEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(AllowEvolutionModeInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setAllowEvolutionModeInEntitySchemaMutation((GrpcAllowEvolutionModeInEntitySchemaMutation) m), new AllowEvolutionModeInEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(AllowLocaleInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setAllowLocaleInEntitySchemaMutation((GrpcAllowLocaleInEntitySchemaMutation) m), new AllowLocaleInEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(DisallowCurrencyInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setDisallowCurrencyInEntitySchemaMutation((GrpcDisallowCurrencyInEntitySchemaMutation) m), new DisallowCurrencyInEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(DisallowEvolutionModeInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setDisallowEvolutionModeInEntitySchemaMutation((GrpcDisallowEvolutionModeInEntitySchemaMutation) m), new DisallowEvolutionModeInEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(DisallowLocaleInEntitySchemaMutation.class, new ToGrpc((b, m) -> b.setDisallowLocaleInEntitySchemaMutation((GrpcDisallowLocaleInEntitySchemaMutation) m), new DisallowLocaleInEntitySchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaDeprecationNoticeMutation((GrpcModifyEntitySchemaDeprecationNoticeMutation) m), new ModifyEntitySchemaDeprecationNoticeMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyEntitySchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyEntitySchemaDescriptionMutation((GrpcModifyEntitySchemaDescriptionMutation) m), new ModifyEntitySchemaDescriptionMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetEntitySchemaWithGeneratedPrimaryKeyMutation.class, new ToGrpc((b, m) -> b.setSetEntitySchemaWithGeneratedPrimaryKeyMutation((GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation) m), new SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetEntitySchemaWithHierarchyMutation.class, new ToGrpc((b, m) -> b.setSetEntitySchemaWithHierarchyMutation((GrpcSetEntitySchemaWithHierarchyMutation) m), new SetEntitySchemaWithHierarchyMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetEntitySchemaWithPriceMutation.class, new ToGrpc((b, m) -> b.setSetEntitySchemaWithPriceMutation((GrpcSetEntitySchemaWithPriceMutation) m), new SetEntitySchemaWithPriceMutationConverter()));
		// reference schema mutations
		TO_GRPC_CONVERTERS.put(CreateReferenceSchemaMutation.class, new ToGrpc((b, m) -> b.setCreateReferenceSchemaMutation((GrpcCreateReferenceSchemaMutation) m), new CreateReferenceSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyReferenceAttributeSchemaMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceAttributeSchemaMutation((GrpcModifyReferenceAttributeSchemaMutation) m), new ModifyReferenceAttributeSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaCardinalityMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaCardinalityMutation((GrpcModifyReferenceSchemaCardinalityMutation) m), new ModifyReferenceSchemaCardinalityMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaDeprecationNoticeMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaDeprecationNoticeMutation((GrpcModifyReferenceSchemaDeprecationNoticeMutation) m), new ModifyReferenceSchemaDeprecationNoticeMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaDescriptionMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaDescriptionMutation((GrpcModifyReferenceSchemaDescriptionMutation) m), new ModifyReferenceSchemaDescriptionMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaNameMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaNameMutation((GrpcModifyReferenceSchemaNameMutation) m), new ModifyReferenceSchemaNameMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaRelatedEntityGroupMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaRelatedEntityGroupMutation((GrpcModifyReferenceSchemaRelatedEntityGroupMutation) m), new ModifyReferenceSchemaRelatedEntityGroupMutationConverter()));
		TO_GRPC_CONVERTERS.put(ModifyReferenceSchemaRelatedEntityMutation.class, new ToGrpc((b, m) -> b.setModifyReferenceSchemaRelatedEntityMutation((GrpcModifyReferenceSchemaRelatedEntityMutation) m), new ModifyReferenceSchemaRelatedEntityMutationConverter()));
		TO_GRPC_CONVERTERS.put(RemoveReferenceSchemaMutation.class, new ToGrpc((b, m) -> b.setRemoveReferenceSchemaMutation((GrpcRemoveReferenceSchemaMutation) m), new RemoveReferenceSchemaMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetReferenceSchemaFacetedMutation.class, new ToGrpc((b, m) -> b.setSetReferenceSchemaFacetedMutation((GrpcSetReferenceSchemaFacetedMutation) m), new SetReferenceSchemaFacetedMutationConverter()));
		TO_GRPC_CONVERTERS.put(SetReferenceSchemaIndexedMutation.class, new ToGrpc((b, m) -> b.setSetReferenceSchemaIndexedMutation((GrpcSetReferenceSchemaFilterableMutation) m), new SetReferenceSchemaFilterableMutationConverter()));

		TO_JAVA_CONVERTERS = createHashMap(50);
		// associated data schema mutations
		TO_JAVA_CONVERTERS.put(CREATEASSOCIATEDDATASCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateAssociatedDataSchemaMutation, new CreateAssociatedDataSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaDeprecationNoticeMutation, new ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaDescriptionMutation, new ModifyAssociatedDataSchemaDescriptionMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaNameMutation, new ModifyAssociatedDataSchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYASSOCIATEDDATASCHEMATYPEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAssociatedDataSchemaTypeMutation, new ModifyAssociatedDataSchemaTypeMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVEASSOCIATEDDATASCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveAssociatedDataSchemaMutation, new RemoveAssociatedDataSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETASSOCIATEDDATASCHEMALOCALIZEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAssociatedDataSchemaLocalizedMutation, new SetAssociatedDataSchemaLocalizedMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETASSOCIATEDDATASCHEMANULLABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAssociatedDataSchemaNullableMutation, new SetAssociatedDataSchemaNullableMutationConverter()));
		// attribute schema mutations
		TO_JAVA_CONVERTERS.put(CREATEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateAttributeSchemaMutation, new CreateAttributeSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEFAULTVALUEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaDefaultValueMutation, new ModifyAttributeSchemaDefaultValueMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaDeprecationNoticeMutation, new ModifyAttributeSchemaDeprecationNoticeMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaDescriptionMutation, new ModifyAttributeSchemaDescriptionMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaNameMutation, new ModifyAttributeSchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYATTRIBUTESCHEMATYPEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyAttributeSchemaTypeMutation, new ModifyAttributeSchemaTypeMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveAttributeSchemaMutation, new RemoveAttributeSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAFILTERABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaFilterableMutation, new SetAttributeSchemaFilterableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMALOCALIZEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaLocalizedMutation, new SetAttributeSchemaLocalizedMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMANULLABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaNullableMutation, new SetAttributeSchemaNullableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAREPRESENTATIVEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaRepresentativeMutation, new SetAttributeSchemaRepresentativeMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMASORTABLEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaSortableMutation, new SetAttributeSchemaSortableMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETATTRIBUTESCHEMAUNIQUEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetAttributeSchemaUniqueMutation, new SetAttributeSchemaUniqueMutationConverter()));
		TO_JAVA_CONVERTERS.put(USEGLOBALATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getUseGlobalAttributeSchemaMutation, new UseGlobalAttributeSchemaMutationConverter()));
		// entity schema mutations
		TO_JAVA_CONVERTERS.put(ALLOWCURRENCYINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getAllowCurrencyInEntitySchemaMutation, new AllowCurrencyInEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(ALLOWEVOLUTIONMODEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getAllowEvolutionModeInEntitySchemaMutation, new AllowEvolutionModeInEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(ALLOWLOCALEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getAllowLocaleInEntitySchemaMutation, new AllowLocaleInEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(DISALLOWCURRENCYINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getDisallowCurrencyInEntitySchemaMutation, new DisallowCurrencyInEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(DISALLOWEVOLUTIONMODEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getDisallowEvolutionModeInEntitySchemaMutation, new DisallowEvolutionModeInEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(DISALLOWLOCALEINENTITYSCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getDisallowLocaleInEntitySchemaMutation, new DisallowLocaleInEntitySchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyEntitySchemaDeprecationNoticeMutation, new ModifyEntitySchemaDeprecationNoticeMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYENTITYSCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyEntitySchemaDescriptionMutation, new ModifyEntitySchemaDescriptionMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETENTITYSCHEMAWITHGENERATEDPRIMARYKEYMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetEntitySchemaWithGeneratedPrimaryKeyMutation, new SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETENTITYSCHEMAWITHHIERARCHYMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetEntitySchemaWithHierarchyMutation, new SetEntitySchemaWithHierarchyMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETENTITYSCHEMAWITHPRICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetEntitySchemaWithPriceMutation, new SetEntitySchemaWithPriceMutationConverter()));
		// reference schema mutations
		TO_JAVA_CONVERTERS.put(CREATEREFERENCESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getCreateReferenceSchemaMutation, new CreateReferenceSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCEATTRIBUTESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceAttributeSchemaMutation, new ModifyReferenceAttributeSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMACARDINALITYMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaCardinalityMutation, new ModifyReferenceSchemaCardinalityMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMADEPRECATIONNOTICEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaDeprecationNoticeMutation, new ModifyReferenceSchemaDeprecationNoticeMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMADESCRIPTIONMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaDescriptionMutation, new ModifyReferenceSchemaDescriptionMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMANAMEMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaNameMutation, new ModifyReferenceSchemaNameMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMARELATEDENTITYGROUPMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaRelatedEntityGroupMutation, new ModifyReferenceSchemaRelatedEntityGroupMutationConverter()));
		TO_JAVA_CONVERTERS.put(MODIFYREFERENCESCHEMARELATEDENTITYMUTATION, new ToJava(GrpcEntitySchemaMutation::getModifyReferenceSchemaRelatedEntityMutation, new ModifyReferenceSchemaRelatedEntityMutationConverter()));
		TO_JAVA_CONVERTERS.put(REMOVEREFERENCESCHEMAMUTATION, new ToJava(GrpcEntitySchemaMutation::getRemoveReferenceSchemaMutation, new RemoveReferenceSchemaMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETREFERENCESCHEMAFACETEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetReferenceSchemaFacetedMutation, new SetReferenceSchemaFacetedMutationConverter()));
		TO_JAVA_CONVERTERS.put(SETREFERENCESCHEMAINDEXEDMUTATION, new ToJava(GrpcEntitySchemaMutation::getSetReferenceSchemaIndexedMutation, new SetReferenceSchemaFilterableMutationConverter()));
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
