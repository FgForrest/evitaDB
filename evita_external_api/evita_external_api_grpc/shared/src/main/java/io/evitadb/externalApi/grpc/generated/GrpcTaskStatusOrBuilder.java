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
// source: GrpcEvitaDataTypes.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcTaskStatusOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcTaskStatus)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Type of the task (shortName of the task)
   * </pre>
   *
   * <code>string taskType = 1;</code>
   * @return The taskType.
   */
  java.lang.String getTaskType();
  /**
   * <pre>
   * Type of the task (shortName of the task)
   * </pre>
   *
   * <code>string taskType = 1;</code>
   * @return The bytes for taskType.
   */
  com.google.protobuf.ByteString
      getTaskTypeBytes();

  /**
   * <pre>
   * Longer, human-readable name of the task
   * </pre>
   *
   * <code>string taskName = 2;</code>
   * @return The taskName.
   */
  java.lang.String getTaskName();
  /**
   * <pre>
   * Longer, human-readable name of the task
   * </pre>
   *
   * <code>string taskName = 2;</code>
   * @return The bytes for taskName.
   */
  com.google.protobuf.ByteString
      getTaskNameBytes();

  /**
   * <pre>
   * Identification of the task
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid taskId = 3;</code>
   * @return Whether the taskId field is set.
   */
  boolean hasTaskId();
  /**
   * <pre>
   * Identification of the task
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid taskId = 3;</code>
   * @return The taskId.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuid getTaskId();
  /**
   * <pre>
   * Identification of the task
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid taskId = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getTaskIdOrBuilder();

  /**
   * <pre>
   * Name of the catalog the task is related to (optional)
   * </pre>
   *
   * <code>.google.protobuf.StringValue catalogName = 4;</code>
   * @return Whether the catalogName field is set.
   */
  boolean hasCatalogName();
  /**
   * <pre>
   * Name of the catalog the task is related to (optional)
   * </pre>
   *
   * <code>.google.protobuf.StringValue catalogName = 4;</code>
   * @return The catalogName.
   */
  com.google.protobuf.StringValue getCatalogName();
  /**
   * <pre>
   * Name of the catalog the task is related to (optional)
   * </pre>
   *
   * <code>.google.protobuf.StringValue catalogName = 4;</code>
   */
  com.google.protobuf.StringValueOrBuilder getCatalogNameOrBuilder();

  /**
   * <pre>
   * Date and time when the task was issued
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime issued = 5;</code>
   * @return Whether the issued field is set.
   */
  boolean hasIssued();
  /**
   * <pre>
   * Date and time when the task was issued
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime issued = 5;</code>
   * @return The issued.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getIssued();
  /**
   * <pre>
   * Date and time when the task was issued
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime issued = 5;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getIssuedOrBuilder();

  /**
   * <pre>
   * Date and time when the task was started
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime started = 6;</code>
   * @return Whether the started field is set.
   */
  boolean hasStarted();
  /**
   * <pre>
   * Date and time when the task was started
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime started = 6;</code>
   * @return The started.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getStarted();
  /**
   * <pre>
   * Date and time when the task was started
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime started = 6;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getStartedOrBuilder();

  /**
   * <pre>
   * Date and time when the task was finished
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime finished = 7;</code>
   * @return Whether the finished field is set.
   */
  boolean hasFinished();
  /**
   * <pre>
   * Date and time when the task was finished
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime finished = 7;</code>
   * @return The finished.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getFinished();
  /**
   * <pre>
   * Date and time when the task was finished
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime finished = 7;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getFinishedOrBuilder();

  /**
   * <pre>
   * Progress of the task (0-100)
   * </pre>
   *
   * <code>int32 progress = 8;</code>
   * @return The progress.
   */
  int getProgress();

  /**
   * <pre>
   * Configuration settings of the task
   * </pre>
   *
   * <code>.google.protobuf.StringValue settings = 9;</code>
   * @return Whether the settings field is set.
   */
  boolean hasSettings();
  /**
   * <pre>
   * Configuration settings of the task
   * </pre>
   *
   * <code>.google.protobuf.StringValue settings = 9;</code>
   * @return The settings.
   */
  com.google.protobuf.StringValue getSettings();
  /**
   * <pre>
   * Configuration settings of the task
   * </pre>
   *
   * <code>.google.protobuf.StringValue settings = 9;</code>
   */
  com.google.protobuf.StringValueOrBuilder getSettingsOrBuilder();

  /**
   * <pre>
   * Textual result of the task
   * </pre>
   *
   * <code>.google.protobuf.StringValue text = 10;</code>
   * @return Whether the text field is set.
   */
  boolean hasText();
  /**
   * <pre>
   * Textual result of the task
   * </pre>
   *
   * <code>.google.protobuf.StringValue text = 10;</code>
   * @return The text.
   */
  com.google.protobuf.StringValue getText();
  /**
   * <pre>
   * Textual result of the task
   * </pre>
   *
   * <code>.google.protobuf.StringValue text = 10;</code>
   */
  com.google.protobuf.StringValueOrBuilder getTextOrBuilder();

  /**
   * <pre>
   * File that was created by the task and is available for fetching
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFile file = 11;</code>
   * @return Whether the file field is set.
   */
  boolean hasFile();
  /**
   * <pre>
   * File that was created by the task and is available for fetching
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFile file = 11;</code>
   * @return The file.
   */
  io.evitadb.externalApi.grpc.generated.GrpcFile getFile();
  /**
   * <pre>
   * File that was created by the task and is available for fetching
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcFile file = 11;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcFileOrBuilder getFileOrBuilder();

  /**
   * <pre>
   * Exception that occurred during the task execution
   * </pre>
   *
   * <code>.google.protobuf.StringValue exception = 12;</code>
   * @return Whether the exception field is set.
   */
  boolean hasException();
  /**
   * <pre>
   * Exception that occurred during the task execution
   * </pre>
   *
   * <code>.google.protobuf.StringValue exception = 12;</code>
   * @return The exception.
   */
  com.google.protobuf.StringValue getException();
  /**
   * <pre>
   * Exception that occurred during the task execution
   * </pre>
   *
   * <code>.google.protobuf.StringValue exception = 12;</code>
   */
  com.google.protobuf.StringValueOrBuilder getExceptionOrBuilder();

  public io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.ResultCase getResultCase();
}
