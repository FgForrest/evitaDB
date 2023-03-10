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
// source: GrpcExtraResults.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcHierarchyParentEntityOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcHierarchyParentEntity)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReferences = 1;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntityReference> 
      getEntityReferencesList();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReferences = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReference getEntityReferences(int index);
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReferences = 1;</code>
   */
  int getEntityReferencesCount();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReferences = 1;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder> 
      getEntityReferencesOrBuilderList();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReferences = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder getEntityReferencesOrBuilder(
      int index);

  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entities = 2;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcSealedEntity> 
      getEntitiesList();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entities = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getEntities(int index);
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entities = 2;</code>
   */
  int getEntitiesCount();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entities = 2;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder> 
      getEntitiesOrBuilderList();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entities = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getEntitiesOrBuilder(
      int index);
}
