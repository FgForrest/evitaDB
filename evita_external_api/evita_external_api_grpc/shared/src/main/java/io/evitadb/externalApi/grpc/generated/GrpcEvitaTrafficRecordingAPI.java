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
// source: GrpcEvitaTrafficRecordingAPI.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcEvitaTrafficRecordingAPI {
  private GrpcEvitaTrafficRecordingAPI() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListRequest_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryRequest_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryResponse_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesResponse_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesRequest_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesResponse_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesResponse_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\"GrpcEvitaTrafficRecordingAPI.proto\022%io" +
      ".evitadb.externalApi.grpc.generated\032\033goo" +
      "gle/protobuf/empty.proto\032\017GrpcEnums.prot" +
      "o\032\030GrpcEvitaDataTypes.proto\032\036google/prot" +
      "obuf/wrappers.proto\032\032GrpcTrafficRecordin" +
      "g.proto\"\340\001\n\034GetTrafficHistoryListRequest" +
      "\022\r\n\005limit\030\001 \001(\005\022\\\n\010criteria\030\002 \001(\0132J.io.e" +
      "vitadb.externalApi.grpc.generated.GrpcTr" +
      "afficRecordingCaptureCriteria\022S\n\007content" +
      "\030\003 \001(\0162B.io.evitadb.externalApi.grpc.gen" +
      "erated.GrpcTrafficRecordingContent\"p\n\035Ge" +
      "tTrafficHistoryListResponse\022O\n\rtrafficRe" +
      "cord\030\001 \003(\01328.io.evitadb.externalApi.grpc" +
      ".generated.GrpcTrafficRecord\"\315\001\n\030GetTraf" +
      "ficHistoryRequest\022\\\n\010criteria\030\001 \001(\0132J.io" +
      ".evitadb.externalApi.grpc.generated.Grpc" +
      "TrafficRecordingCaptureCriteria\022S\n\007conte" +
      "nt\030\002 \001(\0162B.io.evitadb.externalApi.grpc.g" +
      "enerated.GrpcTrafficRecordingContent\"l\n\031" +
      "GetTrafficHistoryResponse\022O\n\rtrafficReco" +
      "rd\030\001 \003(\01328.io.evitadb.externalApi.grpc.g" +
      "enerated.GrpcTrafficRecord\"k\n$GetTraffic" +
      "RecordingLabelNamesRequest\022\r\n\005limit\030\001 \001(" +
      "\005\0224\n\016nameStartsWith\030\002 \001(\0132\034.google.proto" +
      "buf.StringValue\":\n%GetTrafficRecordingLa" +
      "belNamesResponse\022\021\n\tlabelName\030\001 \003(\t\"\200\001\n%" +
      "GetTrafficRecordingValuesNamesRequest\022\r\n" +
      "\005limit\030\001 \001(\005\022\021\n\tlabelName\030\002 \001(\t\0225\n\017value" +
      "StartsWith\030\003 \001(\0132\034.google.protobuf.Strin" +
      "gValue\"<\n&GetTrafficRecordingValuesNames" +
      "Response\022\022\n\nlabelValue\030\001 \003(\t2\237\006\n GrpcEvi" +
      "taTrafficRecordingService\022\253\001\n\036GetTraffic" +
      "RecordingHistoryList\022C.io.evitadb.extern" +
      "alApi.grpc.generated.GetTrafficHistoryLi" +
      "stRequest\032D.io.evitadb.externalApi.grpc." +
      "generated.GetTrafficHistoryListResponse\022" +
      "\241\001\n\032GetTrafficRecordingHistory\022?.io.evit" +
      "adb.externalApi.grpc.generated.GetTraffi" +
      "cHistoryRequest\032@.io.evitadb.externalApi" +
      ".grpc.generated.GetTrafficHistoryRespons" +
      "e0\001\022\321\001\n2GetTrafficRecordingLabelsNamesOr" +
      "deredByCardinality\022K.io.evitadb.external" +
      "Api.grpc.generated.GetTrafficRecordingLa" +
      "belNamesRequest\032L.io.evitadb.externalApi" +
      ".grpc.generated.GetTrafficRecordingLabel" +
      "NamesResponse0\001\022\324\001\n3GetTrafficRecordingL" +
      "abelsValuesOrderedByCardinality\022L.io.evi" +
      "tadb.externalApi.grpc.generated.GetTraff" +
      "icRecordingValuesNamesRequest\032M.io.evita" +
      "db.externalApi.grpc.generated.GetTraffic" +
      "RecordingValuesNamesResponse0\001B\014P\001\252\002\007Evi" +
      "taDBb\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.google.protobuf.EmptyProto.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor(),
          com.google.protobuf.WrappersProto.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListRequest_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListRequest_descriptor,
        new java.lang.String[] { "Limit", "Criteria", "Content", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_descriptor,
        new java.lang.String[] { "TrafficRecord", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryRequest_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryRequest_descriptor,
        new java.lang.String[] { "Criteria", "Content", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryResponse_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryResponse_descriptor,
        new java.lang.String[] { "TrafficRecord", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_descriptor,
        new java.lang.String[] { "Limit", "NameStartsWith", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesResponse_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesResponse_descriptor,
        new java.lang.String[] { "LabelName", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesRequest_descriptor =
      getDescriptor().getMessageTypes().get(6);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesRequest_descriptor,
        new java.lang.String[] { "Limit", "LabelName", "ValueStartsWith", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesResponse_descriptor =
      getDescriptor().getMessageTypes().get(7);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingValuesNamesResponse_descriptor,
        new java.lang.String[] { "LabelValue", });
    com.google.protobuf.EmptyProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor();
    com.google.protobuf.WrappersProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
