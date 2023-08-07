/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcExtraResults.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcExtraResultsOuterClass {
  private GrpcExtraResultsOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_HierarchyEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_HierarchyEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\026GrpcExtraResults.proto\022%io.evitadb.ext" +
      "ernalApi.grpc.generated\032\036google/protobuf" +
      "/wrappers.proto\032\020GrpcEntity.proto\032\030GrpcE" +
      "vitaDataTypes.proto\032\017GrpcEnums.proto\"\373\002\n" +
      "\rGrpcHistogram\022B\n\003min\030\001 \001(\01325.io.evitadb" +
      ".externalApi.grpc.generated.GrpcBigDecim" +
      "al\022B\n\003max\030\002 \001(\01325.io.evitadb.externalApi" +
      ".grpc.generated.GrpcBigDecimal\022\024\n\014overal" +
      "lCount\030\003 \001(\005\022P\n\007buckets\030\004 \003(\0132?.io.evita" +
      "db.externalApi.grpc.generated.GrpcHistog" +
      "ram.GrpcBucket\032z\n\nGrpcBucket\022\r\n\005index\030\001 " +
      "\001(\005\022H\n\tthreshold\030\002 \001(\01325.io.evitadb.exte" +
      "rnalApi.grpc.generated.GrpcBigDecimal\022\023\n" +
      "\013occurrences\030\003 \001(\005\"\275\002\n\030GrpcFacetGroupSta" +
      "tistics\022\025\n\rreferenceName\030\001 \001(\t\022X\n\024groupE" +
      "ntityReference\030\002 \001(\0132:.io.evitadb.extern" +
      "alApi.grpc.generated.GrpcEntityReference" +
      "\022L\n\013groupEntity\030\003 \001(\01327.io.evitadb.exter" +
      "nalApi.grpc.generated.GrpcSealedEntity\022\r" +
      "\n\005count\030\004 \001(\005\022S\n\017facetStatistics\030\005 \003(\0132:" +
      ".io.evitadb.externalApi.grpc.generated.G" +
      "rpcFacetStatistics\"\275\002\n\023GrpcFacetStatisti" +
      "cs\022X\n\024facetEntityReference\030\001 \001(\0132:.io.ev" +
      "itadb.externalApi.grpc.generated.GrpcEnt" +
      "ityReference\022L\n\013facetEntity\030\002 \001(\01327.io.e" +
      "vitadb.externalApi.grpc.generated.GrpcSe" +
      "aledEntity\022\021\n\trequested\030\003 \001(\010\022\r\n\005count\030\004" +
      " \001(\005\022+\n\006impact\030\005 \001(\0132\033.google.protobuf.I" +
      "nt32Value\022/\n\nmatchCount\030\006 \001(\0132\033.google.p" +
      "rotobuf.Int32Value\"\320\001\n\rGrpcHierarchy\022V\n\t" +
      "hierarchy\030\001 \003(\0132C.io.evitadb.externalApi" +
      ".grpc.generated.GrpcHierarchy.HierarchyE" +
      "ntry\032g\n\016HierarchyEntry\022\013\n\003key\030\001 \001(\t\022D\n\005v" +
      "alue\030\002 \001(\01325.io.evitadb.externalApi.grpc" +
      ".generated.GrpcLevelInfos:\0028\001\"Z\n\016GrpcLev" +
      "elInfos\022H\n\nlevelInfos\030\001 \003(\01324.io.evitadb" +
      ".externalApi.grpc.generated.GrpcLevelInf" +
      "o\"\337\002\n\rGrpcLevelInfo\022S\n\017entityReference\030\001" +
      " \001(\0132:.io.evitadb.externalApi.grpc.gener" +
      "ated.GrpcEntityReference\022G\n\006entity\030\002 \001(\013" +
      "27.io.evitadb.externalApi.grpc.generated" +
      ".GrpcSealedEntity\0227\n\022queriedEntityCount\030" +
      "\003 \001(\0132\033.google.protobuf.Int32Value\0222\n\rch" +
      "ildrenCount\030\004 \001(\0132\033.google.protobuf.Int3" +
      "2Value\022C\n\005items\030\005 \003(\01324.io.evitadb.exter" +
      "nalApi.grpc.generated.GrpcLevelInfo\"\335\001\n\022" +
      "GrpcQueryTelemetry\022H\n\toperation\030\001 \001(\01625." +
      "io.evitadb.externalApi.grpc.generated.Gr" +
      "pcQueryPhase\022\r\n\005start\030\002 \001(\003\022H\n\005steps\030\003 \003" +
      "(\01329.io.evitadb.externalApi.grpc.generat" +
      "ed.GrpcQueryTelemetry\022\021\n\targuments\030\004 \003(\t" +
      "\022\021\n\tspentTime\030\005 \001(\003\"\200\006\n\020GrpcExtraResults" +
      "\022k\n\022attributeHistogram\030\001 \003(\0132O.io.evitad" +
      "b.externalApi.grpc.generated.GrpcExtraRe" +
      "sults.AttributeHistogramEntry\022L\n\016priceHi" +
      "stogram\030\002 \001(\01324.io.evitadb.externalApi.g" +
      "rpc.generated.GrpcHistogram\022]\n\024facetGrou" +
      "pStatistics\030\003 \003(\0132?.io.evitadb.externalA" +
      "pi.grpc.generated.GrpcFacetGroupStatisti" +
      "cs\022K\n\rselfHierarchy\030\004 \001(\01324.io.evitadb.e" +
      "xternalApi.grpc.generated.GrpcHierarchy\022" +
      "Y\n\thierarchy\030\005 \003(\0132F.io.evitadb.external" +
      "Api.grpc.generated.GrpcExtraResults.Hier" +
      "archyEntry\022Q\n\016queryTelemetry\030\006 \001(\01329.io." +
      "evitadb.externalApi.grpc.generated.GrpcQ" +
      "ueryTelemetry\032o\n\027AttributeHistogramEntry" +
      "\022\013\n\003key\030\001 \001(\t\022C\n\005value\030\002 \001(\01324.io.evitad" +
      "b.externalApi.grpc.generated.GrpcHistogr" +
      "am:\0028\001\032f\n\016HierarchyEntry\022\013\n\003key\030\001 \001(\t\022C\n" +
      "\005value\030\002 \001(\01324.io.evitadb.externalApi.gr" +
      "pc.generated.GrpcHierarchy:\0028\001B\014P\001\252\002\007Evi" +
      "taDBb\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.google.protobuf.WrappersProto.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEntity.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor,
        new java.lang.String[] { "Min", "Max", "OverallCount", "Buckets", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor.getNestedTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_descriptor,
        new java.lang.String[] { "Index", "Threshold", "Occurrences", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_descriptor,
        new java.lang.String[] { "ReferenceName", "GroupEntityReference", "GroupEntity", "Count", "FacetStatistics", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_descriptor,
        new java.lang.String[] { "FacetEntityReference", "FacetEntity", "Requested", "Count", "Impact", "MatchCount", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_descriptor,
        new java.lang.String[] { "Hierarchy", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_HierarchyEntry_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_descriptor.getNestedTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_HierarchyEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_HierarchyEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor,
        new java.lang.String[] { "LevelInfos", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_descriptor,
        new java.lang.String[] { "EntityReference", "Entity", "QueriedEntityCount", "ChildrenCount", "Items", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_descriptor =
      getDescriptor().getMessageTypes().get(6);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_descriptor,
        new java.lang.String[] { "Operation", "Start", "Steps", "Arguments", "SpentTime", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor =
      getDescriptor().getMessageTypes().get(7);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor,
        new java.lang.String[] { "AttributeHistogram", "PriceHistogram", "FacetGroupStatistics", "SelfHierarchy", "Hierarchy", "QueryTelemetry", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor.getNestedTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor.getNestedTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    com.google.protobuf.WrappersProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEntity.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
