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
// source: GrpcTrafficRecording.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcTrafficSourceQueryStatisticsContainerOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The source query id
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return Whether the sourceQueryId field is set.
   */
  boolean hasSourceQueryId();
  /**
   * <pre>
   * The source query id
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return The sourceQueryId.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuid getSourceQueryId();
  /**
   * <pre>
   * The source query id
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSourceQueryIdOrBuilder();

  /**
   * <pre>
   * The total number of records returned by the query ({&#64;link EvitaResponse#getRecordData()} size)
   * </pre>
   *
   * <code>int32 returnedRecordCount = 2;</code>
   * @return The returnedRecordCount.
   */
  int getReturnedRecordCount();

  /**
   * <pre>
   * The total number of records calculated by the query ({&#64;link EvitaResponse#getTotalRecordCount()})
   * </pre>
   *
   * <code>int32 totalRecordCount = 3;</code>
   * @return The totalRecordCount.
   */
  int getTotalRecordCount();
}
