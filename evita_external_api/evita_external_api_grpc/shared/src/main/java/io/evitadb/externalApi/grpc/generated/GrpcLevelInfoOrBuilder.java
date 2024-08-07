/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcExtraResults.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcLevelInfoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcLevelInfo)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Hierarchical entity reference at position in tree represented by this object.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   * @return Whether the entityReference field is set.
   */
  boolean hasEntityReference();
  /**
   * <pre>
   * Hierarchical entity reference at position in tree represented by this object.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   * @return The entityReference.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReference getEntityReference();
  /**
   * <pre>
   * Hierarchical entity reference at position in tree represented by this object.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder getEntityReferenceOrBuilder();

  /**
   * <pre>
   * Hierarchical entity at position in tree represented by this object.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   * @return Whether the entity field is set.
   */
  boolean hasEntity();
  /**
   * <pre>
   * Hierarchical entity at position in tree represented by this object.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   * @return The entity.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getEntity();
  /**
   * <pre>
   * Hierarchical entity at position in tree represented by this object.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getEntityOrBuilder();

  /**
   * <pre>
   * Contains the number of queried entities that refer directly to this `entity` or to any of its children
   * entities.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value queriedEntityCount = 3;</code>
   * @return Whether the queriedEntityCount field is set.
   */
  boolean hasQueriedEntityCount();
  /**
   * <pre>
   * Contains the number of queried entities that refer directly to this `entity` or to any of its children
   * entities.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value queriedEntityCount = 3;</code>
   * @return The queriedEntityCount.
   */
  com.google.protobuf.Int32Value getQueriedEntityCount();
  /**
   * <pre>
   * Contains the number of queried entities that refer directly to this `entity` or to any of its children
   * entities.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value queriedEntityCount = 3;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getQueriedEntityCountOrBuilder();

  /**
   * <pre>
   * Contains number of hierarchical entities that are referring to this `entity` as its parent.
   * The count will respect behaviour settings and will not count empty children in case `REMOVE_EMPTY` is
   * used for computation.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value childrenCount = 4;</code>
   * @return Whether the childrenCount field is set.
   */
  boolean hasChildrenCount();
  /**
   * <pre>
   * Contains number of hierarchical entities that are referring to this `entity` as its parent.
   * The count will respect behaviour settings and will not count empty children in case `REMOVE_EMPTY` is
   * used for computation.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value childrenCount = 4;</code>
   * @return The childrenCount.
   */
  com.google.protobuf.Int32Value getChildrenCount();
  /**
   * <pre>
   * Contains number of hierarchical entities that are referring to this `entity` as its parent.
   * The count will respect behaviour settings and will not count empty children in case `REMOVE_EMPTY` is
   * used for computation.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value childrenCount = 4;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getChildrenCountOrBuilder();

  /**
   * <pre>
   * Contains hierarchy info of the entities that are subordinate (children) of this `entity`.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo items = 5;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo>
      getItemsList();
  /**
   * <pre>
   * Contains hierarchy info of the entities that are subordinate (children) of this `entity`.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo items = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLevelInfo getItems(int index);
  /**
   * <pre>
   * Contains hierarchy info of the entities that are subordinate (children) of this `entity`.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo items = 5;</code>
   */
  int getItemsCount();
  /**
   * <pre>
   * Contains hierarchy info of the entities that are subordinate (children) of this `entity`.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo items = 5;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder>
      getItemsOrBuilderList();
  /**
   * <pre>
   * Contains hierarchy info of the entities that are subordinate (children) of this `entity`.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo items = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder getItemsOrBuilder(
      int index);

  /**
   * <pre>
   * Contains true if the `entity` was filtered by hierarchy within constraint
   * </pre>
   *
   * <code>bool requested = 6;</code>
   * @return The requested.
   */
  boolean getRequested();
}
