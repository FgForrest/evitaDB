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

public interface GrpcTrafficRecordingCaptureCriteriaOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingCaptureCriteria)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * content determines whether only basic information about the traffic recording is returned or the actual content
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingContent content = 1;</code>
   * @return The enum numeric value on the wire for content.
   */
  int getContentValue();
  /**
   * <pre>
   * content determines whether only basic information about the traffic recording is returned or the actual content
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingContent content = 1;</code>
   * @return The content.
   */
  io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingContent getContent();

  /**
   * <pre>
   * since specifies the time from which the traffic recording should be returned
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime since = 2;</code>
   * @return Whether the since field is set.
   */
  boolean hasSince();
  /**
   * <pre>
   * since specifies the time from which the traffic recording should be returned
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime since = 2;</code>
   * @return The since.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getSince();
  /**
   * <pre>
   * since specifies the time from which the traffic recording should be returned
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime since = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getSinceOrBuilder();

  /**
   * <pre>
   * sinceSessionSequenceId specifies the session sequence ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>.google.protobuf.Int64Value sinceSessionSequenceId = 3;</code>
   * @return Whether the sinceSessionSequenceId field is set.
   */
  boolean hasSinceSessionSequenceId();
  /**
   * <pre>
   * sinceSessionSequenceId specifies the session sequence ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>.google.protobuf.Int64Value sinceSessionSequenceId = 3;</code>
   * @return The sinceSessionSequenceId.
   */
  com.google.protobuf.Int64Value getSinceSessionSequenceId();
  /**
   * <pre>
   * sinceSessionSequenceId specifies the session sequence ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>.google.protobuf.Int64Value sinceSessionSequenceId = 3;</code>
   */
  com.google.protobuf.Int64ValueOrBuilder getSinceSessionSequenceIdOrBuilder();

  /**
   * <pre>
   * sinceRecordSessionOffset specifies the record session offset from which the traffic recording should be returned
   *                          (the offset is relative to the session sequence ID and starts from 0), offset allows
   *                          to continue fetching the traffic recording from the last fetched record when session
   *                          was not fully fetched
   * </pre>
   *
   * <code>.google.protobuf.Int32Value sinceRecordSessionOffset = 4;</code>
   * @return Whether the sinceRecordSessionOffset field is set.
   */
  boolean hasSinceRecordSessionOffset();
  /**
   * <pre>
   * sinceRecordSessionOffset specifies the record session offset from which the traffic recording should be returned
   *                          (the offset is relative to the session sequence ID and starts from 0), offset allows
   *                          to continue fetching the traffic recording from the last fetched record when session
   *                          was not fully fetched
   * </pre>
   *
   * <code>.google.protobuf.Int32Value sinceRecordSessionOffset = 4;</code>
   * @return The sinceRecordSessionOffset.
   */
  com.google.protobuf.Int32Value getSinceRecordSessionOffset();
  /**
   * <pre>
   * sinceRecordSessionOffset specifies the record session offset from which the traffic recording should be returned
   *                          (the offset is relative to the session sequence ID and starts from 0), offset allows
   *                          to continue fetching the traffic recording from the last fetched record when session
   *                          was not fully fetched
   * </pre>
   *
   * <code>.google.protobuf.Int32Value sinceRecordSessionOffset = 4;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getSinceRecordSessionOffsetOrBuilder();

  /**
   * <pre>
   * type specifies the types of traffic recording to be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingType type = 5;</code>
   * @return A list containing the type.
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingType> getTypeList();
  /**
   * <pre>
   * type specifies the types of traffic recording to be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingType type = 5;</code>
   * @return The count of type.
   */
  int getTypeCount();
  /**
   * <pre>
   * type specifies the types of traffic recording to be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingType type = 5;</code>
   * @param index The index of the element to return.
   * @return The type at the given index.
   */
  io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingType getType(int index);
  /**
   * <pre>
   * type specifies the types of traffic recording to be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingType type = 5;</code>
   * @return A list containing the enum numeric values on the wire for type.
   */
  java.util.List<java.lang.Integer>
  getTypeValueList();
  /**
   * <pre>
   * type specifies the types of traffic recording to be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingType type = 5;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of type at the given index.
   */
  int getTypeValue(int index);

  /**
   * <pre>
   * sessionId specifies the session ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 6;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcUuid> 
      getSessionIdList();
  /**
   * <pre>
   * sessionId specifies the session ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 6;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuid getSessionId(int index);
  /**
   * <pre>
   * sessionId specifies the session ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 6;</code>
   */
  int getSessionIdCount();
  /**
   * <pre>
   * sessionId specifies the session ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 6;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder> 
      getSessionIdOrBuilderList();
  /**
   * <pre>
   * sessionId specifies the session ID from which the traffic recording should be returned
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcUuid sessionId = 6;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSessionIdOrBuilder(
      int index);

  /**
   * <pre>
   * longerThan specifies the minimum duration in milliseconds of the traffic recording to be returned
   * </pre>
   *
   * <code>.google.protobuf.Int32Value longerThanMilliseconds = 7;</code>
   * @return Whether the longerThanMilliseconds field is set.
   */
  boolean hasLongerThanMilliseconds();
  /**
   * <pre>
   * longerThan specifies the minimum duration in milliseconds of the traffic recording to be returned
   * </pre>
   *
   * <code>.google.protobuf.Int32Value longerThanMilliseconds = 7;</code>
   * @return The longerThanMilliseconds.
   */
  com.google.protobuf.Int32Value getLongerThanMilliseconds();
  /**
   * <pre>
   * longerThan specifies the minimum duration in milliseconds of the traffic recording to be returned
   * </pre>
   *
   * <code>.google.protobuf.Int32Value longerThanMilliseconds = 7;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getLongerThanMillisecondsOrBuilder();

  /**
   * <pre>
   * fetchingMoreBytesThan specifies the minimum number of bytes that record should have fetched from the disk
   * </pre>
   *
   * <code>.google.protobuf.Int32Value fetchingMoreBytesThan = 8;</code>
   * @return Whether the fetchingMoreBytesThan field is set.
   */
  boolean hasFetchingMoreBytesThan();
  /**
   * <pre>
   * fetchingMoreBytesThan specifies the minimum number of bytes that record should have fetched from the disk
   * </pre>
   *
   * <code>.google.protobuf.Int32Value fetchingMoreBytesThan = 8;</code>
   * @return The fetchingMoreBytesThan.
   */
  com.google.protobuf.Int32Value getFetchingMoreBytesThan();
  /**
   * <pre>
   * fetchingMoreBytesThan specifies the minimum number of bytes that record should have fetched from the disk
   * </pre>
   *
   * <code>.google.protobuf.Int32Value fetchingMoreBytesThan = 8;</code>
   */
  com.google.protobuf.Int32ValueOrBuilder getFetchingMoreBytesThanOrBuilder();

  /**
   * <pre>
   * labels specifies the client labels that the traffic recording must have (both name and value must match)
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryLabel labels = 9;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcQueryLabel> 
      getLabelsList();
  /**
   * <pre>
   * labels specifies the client labels that the traffic recording must have (both name and value must match)
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryLabel labels = 9;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryLabel getLabels(int index);
  /**
   * <pre>
   * labels specifies the client labels that the traffic recording must have (both name and value must match)
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryLabel labels = 9;</code>
   */
  int getLabelsCount();
  /**
   * <pre>
   * labels specifies the client labels that the traffic recording must have (both name and value must match)
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryLabel labels = 9;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcQueryLabelOrBuilder> 
      getLabelsOrBuilderList();
  /**
   * <pre>
   * labels specifies the client labels that the traffic recording must have (both name and value must match)
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcQueryLabel labels = 9;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcQueryLabelOrBuilder getLabelsOrBuilder(
      int index);
}
