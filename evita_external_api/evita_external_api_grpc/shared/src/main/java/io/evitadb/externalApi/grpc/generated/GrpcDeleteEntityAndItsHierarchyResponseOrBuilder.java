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
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcDeleteEntityAndItsHierarchyResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Count of deleted entities.
   * </pre>
   *
   * <code>int32 deletedEntities = 1;</code>
   * @return The deletedEntities.
   */
  int getDeletedEntities();

  /**
   * <pre>
   * The deleted root entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference deletedRootEntityReference = 2;</code>
   * @return Whether the deletedRootEntityReference field is set.
   */
  boolean hasDeletedRootEntityReference();
  /**
   * <pre>
   * The deleted root entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference deletedRootEntityReference = 2;</code>
   * @return The deletedRootEntityReference.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReference getDeletedRootEntityReference();
  /**
   * <pre>
   * The deleted root entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference deletedRootEntityReference = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder getDeletedRootEntityReferenceOrBuilder();

  /**
   * <pre>
   * The deleted root entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity deletedRootEntity = 3;</code>
   * @return Whether the deletedRootEntity field is set.
   */
  boolean hasDeletedRootEntity();
  /**
   * <pre>
   * The deleted root entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity deletedRootEntity = 3;</code>
   * @return The deletedRootEntity.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getDeletedRootEntity();
  /**
   * <pre>
   * The deleted root entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity deletedRootEntity = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getDeletedRootEntityOrBuilder();

  public io.evitadb.externalApi.grpc.generated.GrpcDeleteEntityAndItsHierarchyResponse.ResponseCase getResponseCase();
}
