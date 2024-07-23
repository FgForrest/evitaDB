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

public interface GrpcRegisterChangeCatalogCaptureResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string uuid = 1;</code>
   * @return The uuid.
   */
  java.lang.String getUuid();
  /**
   * <code>string uuid = 1;</code>
   * @return The bytes for uuid.
   */
  com.google.protobuf.ByteString
      getUuidBytes();

  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture capture = 2;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture>
      getCaptureList();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture capture = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture getCapture(int index);
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture capture = 2;</code>
   */
  int getCaptureCount();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture capture = 2;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCaptureOrBuilder>
      getCaptureOrBuilderList();
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture capture = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCaptureOrBuilder getCaptureOrBuilder(
      int index);

  /**
   * <code>int64 transactionalId = 3;</code>
   * @return The transactionalId.
   */
  long getTransactionalId();

  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType responseType = 4;</code>
   * @return The enum numeric value on the wire for responseType.
   */
  int getResponseTypeValue();
  /**
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType responseType = 4;</code>
   * @return The responseType.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType getResponseType();
}
