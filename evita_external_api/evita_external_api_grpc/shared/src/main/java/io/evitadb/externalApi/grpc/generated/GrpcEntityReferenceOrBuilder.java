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
// source: GrpcEntity.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcEntityReferenceOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcEntityReference)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Type of entity.
   * Entity type is main sharding key - all data of entities with same type are stored in separated collections. Within the
   * entity type entity is uniquely represented by primary key.
   * </pre>
   *
   * <code>string entityType = 1;</code>
   * @return The entityType.
   */
  java.lang.String getEntityType();
  /**
   * <pre>
   * Type of entity.
   * Entity type is main sharding key - all data of entities with same type are stored in separated collections. Within the
   * entity type entity is uniquely represented by primary key.
   * </pre>
   *
   * <code>string entityType = 1;</code>
   * @return The bytes for entityType.
   */
  com.google.protobuf.ByteString
      getEntityTypeBytes();

  /**
   * <pre>
   * Unique Integer positive number representing the entity. Can be used for fast lookup for
   * entity (entities). Primary key must be unique within the same entity type.
   * </pre>
   *
   * <code>int32 primaryKey = 2;</code>
   * @return The primaryKey.
   */
  int getPrimaryKey();

  /**
   * <pre>
   * value is deprecated, it was available only for entity references used in entity body, in other use-cases it was left
   * as zero - which was a mistake in the design.
   * in order to get the entity version you need to fetch the entity itself (with entity body).
   * </pre>
   *
   * <code>int32 version = 3 [deprecated = true];</code>
   * @deprecated
   * @return The version.
   */
  @java.lang.Deprecated int getVersion();

  /**
   * <pre>
   * Contains version of this reference and gets increased with any entity type update. Allows to execute
   * optimistic locking i.e. avoiding parallel modifications.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value referenceVersion = 4;</code>
   * @return Whether the referenceVersion field is set.
   */
  boolean hasReferenceVersion();
  /**
   * <pre>
   * Contains version of this reference and gets increased with any entity type update. Allows to execute
   * optimistic locking i.e. avoiding parallel modifications.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value referenceVersion = 4;</code>
   * @return The referenceVersion.
   */
  com.google.protobuf.Int32Value getReferenceVersion();
  /**
   * <pre>
   * Contains version of this reference and gets increased with any entity type update. Allows to execute
   * optimistic locking i.e. avoiding parallel modifications.
   * </pre>
   *
   * <code>.google.protobuf.Int32Value referenceVersion = 4;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getReferenceVersionOrBuilder();
}
