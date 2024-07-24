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
// source: GrpcChangeCatalogCapture.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcChangeCatalogCaptureOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>int64 version = 1;</code>
   * @return The version.
   */
  long getVersion();

  /**
   * <code>int32 index = 2;</code>
   * @return The index.
   */
  int getIndex();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureArea area = 3;</code>
   * @return The enum numeric value on the wire for area.
   */
  int getAreaValue();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureArea area = 3;</code>
   * @return The area.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCaptureArea getArea();

  /**
   * <code>string entityType = 4;</code>
   * @return The entityType.
   */
  java.lang.String getEntityType();
  /**
   * <code>string entityType = 4;</code>
   * @return The bytes for entityType.
   */
  com.google.protobuf.ByteString
      getEntityTypeBytes();

  /**
   * <code>.google.protobuf.Int32Value entityVersion = 5;</code>
   * @return Whether the entityVersion field is set.
   */
  boolean hasEntityVersion();
  /**
   * <code>.google.protobuf.Int32Value entityVersion = 5;</code>
   * @return The entityVersion.
   */
  com.google.protobuf.Int32Value getEntityVersion();
  /**
   * <code>.google.protobuf.Int32Value entityVersion = 5;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getEntityVersionOrBuilder();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOperation operation = 6;</code>
   * @return The enum numeric value on the wire for operation.
   */
  int getOperationValue();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOperation operation = 6;</code>
   * @return The operation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOperation getOperation();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityMutation entityMutation = 7;</code>
   * @return Whether the entityMutation field is set.
   */
  boolean hasEntityMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityMutation entityMutation = 7;</code>
   * @return The entityMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityMutation getEntityMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityMutation entityMutation = 7;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityMutationOrBuilder getEntityMutationOrBuilder();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocalMutation localMutation = 8;</code>
   * @return Whether the localMutation field is set.
   */
  boolean hasLocalMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocalMutation localMutation = 8;</code>
   * @return The localMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocalMutation getLocalMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocalMutation localMutation = 8;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocalMutationOrBuilder getLocalMutationOrBuilder();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutation = 9;</code>
   * @return Whether the entitySchemaMutation field is set.
   */
  boolean hasEntitySchemaMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutation = 9;</code>
   * @return The entitySchemaMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation getEntitySchemaMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation entitySchemaMutation = 9;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutationOrBuilder getEntitySchemaMutationOrBuilder();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation catalogSchemaMutation = 10;</code>
   * @return Whether the catalogSchemaMutation field is set.
   */
  boolean hasCatalogSchemaMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation catalogSchemaMutation = 10;</code>
   * @return The catalogSchemaMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation getCatalogSchemaMutation();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation catalogSchemaMutation = 10;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder getCatalogSchemaMutationOrBuilder();

  public io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture.BodyCase getBodyCase();
}
