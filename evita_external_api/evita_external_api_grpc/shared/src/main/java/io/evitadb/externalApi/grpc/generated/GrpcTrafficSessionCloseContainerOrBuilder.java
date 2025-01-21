/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

public interface GrpcTrafficSessionCloseContainerOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcTrafficSessionCloseContainer)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The sequence order of the session (analogous to sessionId, but monotonic sequence based on location in the log).
   * </pre>
   *
   * <code>int64 sessionSequenceOrder = 1;</code>
   * @return The sessionSequenceOrder.
   */
  long getSessionSequenceOrder();

  /**
   * <pre>
   * The associated session id.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 2;</code>
   * @return Whether the sessionId field is set.
   */
  boolean hasSessionId();
  /**
   * <pre>
   * The associated session id.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 2;</code>
   * @return The sessionId.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuid getSessionId();
  /**
   * <pre>
   * The associated session id.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSessionIdOrBuilder();

  /**
   * <pre>
   * The relative order (offset) of the traffic recording within the session.
   * </pre>
   *
   * <code>int32 recordSessionOffset = 3;</code>
   * @return The recordSessionOffset.
   */
  int getRecordSessionOffset();

  /**
   * <pre>
   * The version of the catalog
   * </pre>
   *
   * <code>int64 catalogVersion = 4;</code>
   * @return The catalogVersion.
   */
  long getCatalogVersion();

  /**
   * <pre>
   * The time when the session was closed (the traffic record created).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime created = 5;</code>
   * @return Whether the created field is set.
   */
  boolean hasCreated();
  /**
   * <pre>
   * The time when the session was closed (the traffic record created).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime created = 5;</code>
   * @return The created.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getCreated();
  /**
   * <pre>
   * The time when the session was closed (the traffic record created).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime created = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getCreatedOrBuilder();

  /**
   * <pre>
   * The duration of the session in milliseconds.
   * </pre>
   *
   * <code>int64 durationInMilliseconds = 6;</code>
   * @return The durationInMilliseconds.
   */
  long getDurationInMilliseconds();

  /**
   * <pre>
   * The total number of disk fetch attempts made in this session.
   * </pre>
   *
   * <code>int32 ioFetchCount = 7;</code>
   * @return The ioFetchCount.
   */
  int getIoFetchCount();

  /**
   * <pre>
   * The total number of Bytes fetched from the disk in this session.
   * </pre>
   *
   * <code>int32 ioFetchedSizeBytes = 8;</code>
   * @return The ioFetchedSizeBytes.
   */
  int getIoFetchedSizeBytes();

  /**
   * <pre>
   * The overall number of traffic records recorded for this session.
   * </pre>
   *
   * <code>int32 trafficRecordCount = 9;</code>
   * @return The trafficRecordCount.
   */
  int getTrafficRecordCount();

  /**
   * <pre>
   * The number of records missed out in this session due to memory shortage (not sampling, sampling affects entire sessions).
   * </pre>
   *
   * <code>int32 trafficRecordsMissedOut = 10;</code>
   * @return The trafficRecordsMissedOut.
   */
  int getTrafficRecordsMissedOut();

  /**
   * <pre>
   * The overall number of queries executed in this session.
   * </pre>
   *
   * <code>int32 queryCount = 11;</code>
   * @return The queryCount.
   */
  int getQueryCount();

  /**
   * <pre>
   * The overall number of entities fetched in this session (excluding the entities fetched by queries).
   * </pre>
   *
   * <code>int32 entityFetchCount = 12;</code>
   * @return The entityFetchCount.
   */
  int getEntityFetchCount();

  /**
   * <pre>
   * The overall number of mutations executed in this session.
   * </pre>
   *
   * <code>int32 mutationCount = 13;</code>
   * @return The mutationCount.
   */
  int getMutationCount();
}
