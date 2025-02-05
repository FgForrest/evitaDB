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
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcStartTrafficRecordingRequest_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcStartTrafficRecordingRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcStopTrafficRecordingRequest_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcStopTrafficRecordingRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingStatusResponse_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingStatusResponse_fieldAccessorTable;

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
      "g.proto\"\213\001\n\034GetTrafficHistoryListRequest" +
      "\022\r\n\005limit\030\001 \001(\005\022\\\n\010criteria\030\002 \001(\0132J.io.e" +
      "vitadb.externalApi.grpc.generated.GrpcTr" +
      "afficRecordingCaptureCriteria\"p\n\035GetTraf" +
      "ficHistoryListResponse\022O\n\rtrafficRecord\030" +
      "\001 \003(\01328.io.evitadb.externalApi.grpc.gene" +
      "rated.GrpcTrafficRecord\"x\n\030GetTrafficHis" +
      "toryRequest\022\\\n\010criteria\030\001 \001(\0132J.io.evita" +
      "db.externalApi.grpc.generated.GrpcTraffi" +
      "cRecordingCaptureCriteria\"l\n\031GetTrafficH" +
      "istoryResponse\022O\n\rtrafficRecord\030\001 \003(\01328." +
      "io.evitadb.externalApi.grpc.generated.Gr" +
      "pcTrafficRecord\"k\n$GetTrafficRecordingLa" +
      "belNamesRequest\022\r\n\005limit\030\001 \001(\005\0224\n\016nameSt" +
      "artsWith\030\002 \001(\0132\034.google.protobuf.StringV" +
      "alue\":\n%GetTrafficRecordingLabelNamesRes" +
      "ponse\022\021\n\tlabelName\030\001 \003(\t\"\200\001\n%GetTrafficR" +
      "ecordingValuesNamesRequest\022\r\n\005limit\030\001 \001(" +
      "\005\022\021\n\tlabelName\030\002 \001(\t\0225\n\017valueStartsWith\030" +
      "\003 \001(\0132\034.google.protobuf.StringValue\"<\n&G" +
      "etTrafficRecordingValuesNamesResponse\022\022\n" +
      "\nlabelValue\030\001 \003(\t\"\200\002\n GrpcStartTrafficRe" +
      "cordingRequest\022\024\n\014samplingRate\030\001 \001(\005\022\022\n\n" +
      "exportFile\030\002 \001(\010\022>\n\031maxDurationInMillise" +
      "conds\030\003 \001(\0132\033.google.protobuf.Int64Value" +
      "\0227\n\022maxFileSizeInBytes\030\004 \001(\0132\033.google.pr" +
      "otobuf.Int64Value\0229\n\024chunkFileSizeInByte" +
      "s\030\005 \001(\0132\033.google.protobuf.Int64Value\"h\n\037" +
      "GrpcStopTrafficRecordingRequest\022E\n\014taskS" +
      "tatusId\030\001 \001(\0132/.io.evitadb.externalApi.g" +
      "rpc.generated.GrpcUuid\"n\n!GetTrafficReco" +
      "rdingStatusResponse\022I\n\ntaskStatus\030\001 \001(\0132" +
      "5.io.evitadb.externalApi.grpc.generated." +
      "GrpcTaskStatus2\250\n\n GrpcEvitaTrafficRecor" +
      "dingService\022\253\001\n\036GetTrafficRecordingHisto" +
      "ryList\022C.io.evitadb.externalApi.grpc.gen" +
      "erated.GetTrafficHistoryListRequest\032D.io" +
      ".evitadb.externalApi.grpc.generated.GetT" +
      "rafficHistoryListResponse\022\263\001\n&GetTraffic" +
      "RecordingHistoryListReversed\022C.io.evitad" +
      "b.externalApi.grpc.generated.GetTrafficH" +
      "istoryListRequest\032D.io.evitadb.externalA" +
      "pi.grpc.generated.GetTrafficHistoryListR" +
      "esponse\022\241\001\n\032GetTrafficRecordingHistory\022?" +
      ".io.evitadb.externalApi.grpc.generated.G" +
      "etTrafficHistoryRequest\032@.io.evitadb.ext" +
      "ernalApi.grpc.generated.GetTrafficHistor" +
      "yResponse0\001\022\317\001\n2GetTrafficRecordingLabel" +
      "sNamesOrderedByCardinality\022K.io.evitadb." +
      "externalApi.grpc.generated.GetTrafficRec" +
      "ordingLabelNamesRequest\032L.io.evitadb.ext" +
      "ernalApi.grpc.generated.GetTrafficRecord" +
      "ingLabelNamesResponse\022\321\001\n2GetTrafficReco" +
      "rdingLabelValuesOrderedByCardinality\022L.i" +
      "o.evitadb.externalApi.grpc.generated.Get" +
      "TrafficRecordingValuesNamesRequest\032M.io." +
      "evitadb.externalApi.grpc.generated.GetTr" +
      "afficRecordingValuesNamesResponse\022\252\001\n\025St" +
      "artTrafficRecording\022G.io.evitadb.externa" +
      "lApi.grpc.generated.GrpcStartTrafficReco" +
      "rdingRequest\032H.io.evitadb.externalApi.gr" +
      "pc.generated.GetTrafficRecordingStatusRe" +
      "sponse\022\250\001\n\024StopTrafficRecording\022F.io.evi" +
      "tadb.externalApi.grpc.generated.GrpcStop" +
      "TrafficRecordingRequest\032H.io.evitadb.ext" +
      "ernalApi.grpc.generated.GetTrafficRecord" +
      "ingStatusResponseB\014P\001\252\002\007EvitaDBb\006proto3"
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
        new java.lang.String[] { "Limit", "Criteria", });
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
        new java.lang.String[] { "Criteria", });
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
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcStartTrafficRecordingRequest_descriptor =
      getDescriptor().getMessageTypes().get(8);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcStartTrafficRecordingRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcStartTrafficRecordingRequest_descriptor,
        new java.lang.String[] { "SamplingRate", "ExportFile", "MaxDurationInMilliseconds", "MaxFileSizeInBytes", "ChunkFileSizeInBytes", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcStopTrafficRecordingRequest_descriptor =
      getDescriptor().getMessageTypes().get(9);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcStopTrafficRecordingRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcStopTrafficRecordingRequest_descriptor,
        new java.lang.String[] { "TaskStatusId", });
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingStatusResponse_descriptor =
      getDescriptor().getMessageTypes().get(10);
    internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingStatusResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingStatusResponse_descriptor,
        new java.lang.String[] { "TaskStatus", });
    com.google.protobuf.EmptyProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor();
    com.google.protobuf.WrappersProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
