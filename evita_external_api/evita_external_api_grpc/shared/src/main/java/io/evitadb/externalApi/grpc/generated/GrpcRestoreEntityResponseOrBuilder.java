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
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcRestoreEntityResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The restored entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   * @return Whether the entityReference field is set.
   */
  boolean hasEntityReference();
  /**
   * <pre>
   * The restored entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   * @return The entityReference.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReference getEntityReference();
  /**
   * <pre>
   * The restored entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder getEntityReferenceOrBuilder();

  /**
   * <pre>
   * The restored entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   * @return Whether the entity field is set.
   */
  boolean hasEntity();
  /**
   * <pre>
   * The restored entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   * @return The entity.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getEntity();
  /**
   * <pre>
   * The restored entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getEntityOrBuilder();

  public io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.ResponseCase getResponseCase();
}
