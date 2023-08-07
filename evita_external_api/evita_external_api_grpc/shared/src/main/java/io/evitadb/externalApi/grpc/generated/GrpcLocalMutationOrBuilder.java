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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcLocalMutation.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcLocalMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcLocalMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Increments or decrements existing numeric value by specified delta (negative number produces decremental of
   * existing number, positive one incrementation).
   * Allows to specify the number range that is tolerated for the value after delta application has been finished to
   * verify for example that number of items on stock doesn't go below zero.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcApplyDeltaAttributeMutation applyDeltaAttributeMutation = 1;</code>
   * @return Whether the applyDeltaAttributeMutation field is set.
   */
  boolean hasApplyDeltaAttributeMutation();
  /**
   * <pre>
   * Increments or decrements existing numeric value by specified delta (negative number produces decremental of
   * existing number, positive one incrementation).
   * Allows to specify the number range that is tolerated for the value after delta application has been finished to
   * verify for example that number of items on stock doesn't go below zero.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcApplyDeltaAttributeMutation applyDeltaAttributeMutation = 1;</code>
   * @return The applyDeltaAttributeMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcApplyDeltaAttributeMutation getApplyDeltaAttributeMutation();
  /**
   * <pre>
   * Increments or decrements existing numeric value by specified delta (negative number produces decremental of
   * existing number, positive one incrementation).
   * Allows to specify the number range that is tolerated for the value after delta application has been finished to
   * verify for example that number of items on stock doesn't go below zero.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcApplyDeltaAttributeMutation applyDeltaAttributeMutation = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcApplyDeltaAttributeMutationOrBuilder getApplyDeltaAttributeMutationOrBuilder();

  /**
   * <pre>
   * Upsert attribute mutation will either update existing attribute or create new one.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertAttributeMutation upsertAttributeMutation = 2;</code>
   * @return Whether the upsertAttributeMutation field is set.
   */
  boolean hasUpsertAttributeMutation();
  /**
   * <pre>
   * Upsert attribute mutation will either update existing attribute or create new one.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertAttributeMutation upsertAttributeMutation = 2;</code>
   * @return The upsertAttributeMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUpsertAttributeMutation getUpsertAttributeMutation();
  /**
   * <pre>
   * Upsert attribute mutation will either update existing attribute or create new one.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertAttributeMutation upsertAttributeMutation = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUpsertAttributeMutationOrBuilder getUpsertAttributeMutationOrBuilder();

  /**
   * <pre>
   * Remove attribute mutation will drop existing attribute - ie.generates new version of the attribute with tombstone on it.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation removeAttributeMutation = 3;</code>
   * @return Whether the removeAttributeMutation field is set.
   */
  boolean hasRemoveAttributeMutation();
  /**
   * <pre>
   * Remove attribute mutation will drop existing attribute - ie.generates new version of the attribute with tombstone on it.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation removeAttributeMutation = 3;</code>
   * @return The removeAttributeMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation getRemoveAttributeMutation();
  /**
   * <pre>
   * Remove attribute mutation will drop existing attribute - ie.generates new version of the attribute with tombstone on it.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation removeAttributeMutation = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutationOrBuilder getRemoveAttributeMutationOrBuilder();

  /**
   * <pre>
   * Upsert associatedData mutation will either update existing associatedData or create new one.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertAssociatedDataMutation upsertAssociatedDataMutation = 4;</code>
   * @return Whether the upsertAssociatedDataMutation field is set.
   */
  boolean hasUpsertAssociatedDataMutation();
  /**
   * <pre>
   * Upsert associatedData mutation will either update existing associatedData or create new one.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertAssociatedDataMutation upsertAssociatedDataMutation = 4;</code>
   * @return The upsertAssociatedDataMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUpsertAssociatedDataMutation getUpsertAssociatedDataMutation();
  /**
   * <pre>
   * Upsert associatedData mutation will either update existing associatedData or create new one.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertAssociatedDataMutation upsertAssociatedDataMutation = 4;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUpsertAssociatedDataMutationOrBuilder getUpsertAssociatedDataMutationOrBuilder();

  /**
   * <pre>
   * Remove associated data mutation will drop existing associatedData - ie.generates new version of the associated
   * data with tombstone on it.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveAssociatedDataMutation removeAssociatedDataMutation = 5;</code>
   * @return Whether the removeAssociatedDataMutation field is set.
   */
  boolean hasRemoveAssociatedDataMutation();
  /**
   * <pre>
   * Remove associated data mutation will drop existing associatedData - ie.generates new version of the associated
   * data with tombstone on it.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveAssociatedDataMutation removeAssociatedDataMutation = 5;</code>
   * @return The removeAssociatedDataMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveAssociatedDataMutation getRemoveAssociatedDataMutation();
  /**
   * <pre>
   * Remove associated data mutation will drop existing associatedData - ie.generates new version of the associated
   * data with tombstone on it.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveAssociatedDataMutation removeAssociatedDataMutation = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveAssociatedDataMutationOrBuilder getRemoveAssociatedDataMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to create / update `price` of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertPriceMutation upsertPriceMutation = 6;</code>
   * @return Whether the upsertPriceMutation field is set.
   */
  boolean hasUpsertPriceMutation();
  /**
   * <pre>
   * This mutation allows to create / update `price` of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertPriceMutation upsertPriceMutation = 6;</code>
   * @return The upsertPriceMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUpsertPriceMutation getUpsertPriceMutation();
  /**
   * <pre>
   * This mutation allows to create / update `price` of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUpsertPriceMutation upsertPriceMutation = 6;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUpsertPriceMutationOrBuilder getUpsertPriceMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to remove existing `price` of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemovePriceMutation removePriceMutation = 7;</code>
   * @return Whether the removePriceMutation field is set.
   */
  boolean hasRemovePriceMutation();
  /**
   * <pre>
   * This mutation allows to remove existing `price` of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemovePriceMutation removePriceMutation = 7;</code>
   * @return The removePriceMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemovePriceMutation getRemovePriceMutation();
  /**
   * <pre>
   * This mutation allows to remove existing `price` of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemovePriceMutation removePriceMutation = 7;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemovePriceMutationOrBuilder getRemovePriceMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to set / remove `priceInnerRecordHandling` behaviour of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetPriceInnerRecordHandlingMutation setPriceInnerRecordHandlingMutation = 8;</code>
   * @return Whether the setPriceInnerRecordHandlingMutation field is set.
   */
  boolean hasSetPriceInnerRecordHandlingMutation();
  /**
   * <pre>
   * This mutation allows to set / remove `priceInnerRecordHandling` behaviour of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetPriceInnerRecordHandlingMutation setPriceInnerRecordHandlingMutation = 8;</code>
   * @return The setPriceInnerRecordHandlingMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSetPriceInnerRecordHandlingMutation getSetPriceInnerRecordHandlingMutation();
  /**
   * <pre>
   * This mutation allows to set / remove `priceInnerRecordHandling` behaviour of the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetPriceInnerRecordHandlingMutation setPriceInnerRecordHandlingMutation = 8;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSetPriceInnerRecordHandlingMutationOrBuilder getSetPriceInnerRecordHandlingMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to set `parent` in the `entity`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetParentMutation setParentMutation = 9;</code>
   * @return Whether the setParentMutation field is set.
   */
  boolean hasSetParentMutation();
  /**
   * <pre>
   * This mutation allows to set `parent` in the `entity`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetParentMutation setParentMutation = 9;</code>
   * @return The setParentMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSetParentMutation getSetParentMutation();
  /**
   * <pre>
   * This mutation allows to set `parent` in the `entity`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetParentMutation setParentMutation = 9;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSetParentMutationOrBuilder getSetParentMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to remove `parent` from the `entity`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveParentMutation removeParentMutation = 10;</code>
   * @return Whether the removeParentMutation field is set.
   */
  boolean hasRemoveParentMutation();
  /**
   * <pre>
   * This mutation allows to remove `parent` from the `entity`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveParentMutation removeParentMutation = 10;</code>
   * @return The removeParentMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveParentMutation getRemoveParentMutation();
  /**
   * <pre>
   * This mutation allows to remove `parent` from the `entity`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveParentMutation removeParentMutation = 10;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveParentMutationOrBuilder getRemoveParentMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to create a reference in the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcInsertReferenceMutation insertReferenceMutation = 11;</code>
   * @return Whether the insertReferenceMutation field is set.
   */
  boolean hasInsertReferenceMutation();
  /**
   * <pre>
   * This mutation allows to create a reference in the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcInsertReferenceMutation insertReferenceMutation = 11;</code>
   * @return The insertReferenceMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcInsertReferenceMutation getInsertReferenceMutation();
  /**
   * <pre>
   * This mutation allows to create a reference in the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcInsertReferenceMutation insertReferenceMutation = 11;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcInsertReferenceMutationOrBuilder getInsertReferenceMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to remove a reference from the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceMutation removeReferenceMutation = 12;</code>
   * @return Whether the removeReferenceMutation field is set.
   */
  boolean hasRemoveReferenceMutation();
  /**
   * <pre>
   * This mutation allows to remove a reference from the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceMutation removeReferenceMutation = 12;</code>
   * @return The removeReferenceMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceMutation getRemoveReferenceMutation();
  /**
   * <pre>
   * This mutation allows to remove a reference from the entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceMutation removeReferenceMutation = 12;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceMutationOrBuilder getRemoveReferenceMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to create / update group of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetReferenceGroupMutation setReferenceGroupMutation = 13;</code>
   * @return Whether the setReferenceGroupMutation field is set.
   */
  boolean hasSetReferenceGroupMutation();
  /**
   * <pre>
   * This mutation allows to create / update group of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetReferenceGroupMutation setReferenceGroupMutation = 13;</code>
   * @return The setReferenceGroupMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSetReferenceGroupMutation getSetReferenceGroupMutation();
  /**
   * <pre>
   * This mutation allows to create / update group of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSetReferenceGroupMutation setReferenceGroupMutation = 13;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSetReferenceGroupMutationOrBuilder getSetReferenceGroupMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to remove group in the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceGroupMutation removeReferenceGroupMutation = 14;</code>
   * @return Whether the removeReferenceGroupMutation field is set.
   */
  boolean hasRemoveReferenceGroupMutation();
  /**
   * <pre>
   * This mutation allows to remove group in the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceGroupMutation removeReferenceGroupMutation = 14;</code>
   * @return The removeReferenceGroupMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceGroupMutation getRemoveReferenceGroupMutation();
  /**
   * <pre>
   * This mutation allows to remove group in the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceGroupMutation removeReferenceGroupMutation = 14;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcRemoveReferenceGroupMutationOrBuilder getRemoveReferenceGroupMutationOrBuilder();

  /**
   * <pre>
   * This mutation allows to create / update / remove attribute of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcReferenceAttributeMutation referenceAttributeMutation = 15;</code>
   * @return Whether the referenceAttributeMutation field is set.
   */
  boolean hasReferenceAttributeMutation();
  /**
   * <pre>
   * This mutation allows to create / update / remove attribute of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcReferenceAttributeMutation referenceAttributeMutation = 15;</code>
   * @return The referenceAttributeMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcReferenceAttributeMutation getReferenceAttributeMutation();
  /**
   * <pre>
   * This mutation allows to create / update / remove attribute of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcReferenceAttributeMutation referenceAttributeMutation = 15;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcReferenceAttributeMutationOrBuilder getReferenceAttributeMutationOrBuilder();

  public io.evitadb.externalApi.grpc.generated.GrpcLocalMutation.MutationCase getMutationCase();
}
