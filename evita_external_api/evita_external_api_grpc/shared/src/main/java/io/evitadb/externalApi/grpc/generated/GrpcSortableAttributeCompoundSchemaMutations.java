// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcSortableAttributeCompoundSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcSortableAttributeCompoundSchemaMutations {
  private GrpcSortableAttributeCompoundSchemaMutations() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n2GrpcSortableAttributeCompoundSchemaMut" +
      "ations.proto\022%io.evitadb.externalApi.grp" +
      "c.generated\032\030GrpcEvitaDataTypes.proto\032\017G" +
      "rpcEnums.proto\032\026GrpcEntitySchema.proto\032\036" +
      "google/protobuf/wrappers.proto\"\205\002\n1GrpcC" +
      "reateSortableAttributeCompoundSchemaMuta" +
      "tion\022\014\n\004name\030\001 \001(\t\0221\n\013description\030\002 \001(\0132" +
      "\034.google.protobuf.StringValue\0227\n\021depreca" +
      "tionNotice\030\003 \001(\0132\034.google.protobuf.Strin" +
      "gValue\022V\n\021attributeElements\030\004 \003(\0132;.io.e" +
      "vitadb.externalApi.grpc.generated.GrpcAt" +
      "tributeElement\"\213\001\nBGrpcModifySortableAtt" +
      "ributeCompoundSchemaDeprecationNoticeMut" +
      "ation\022\014\n\004name\030\001 \001(\t\0227\n\021deprecationNotice" +
      "\030\002 \001(\0132\034.google.protobuf.StringValue\"\177\n<" +
      "GrpcModifySortableAttributeCompoundSchem" +
      "aDescriptionMutation\022\014\n\004name\030\001 \001(\t\0221\n\013de" +
      "scription\030\002 \001(\0132\034.google.protobuf.String" +
      "Value\"V\n5GrpcModifySortableAttributeComp" +
      "oundSchemaNameMutation\022\014\n\004name\030\001 \001(\t\022\017\n\007" +
      "newName\030\002 \001(\t\"A\n1GrpcRemoveSortableAttri" +
      "buteCompoundSchemaMutation\022\014\n\004name\030\001 \001(\t" +
      "\"\335\006\n+GrpcSortableAttributeCompoundSchema" +
      "Mutation\022\221\001\n-createSortableAttributeComp" +
      "oundSchemaMutation\030\001 \001(\0132X.io.evitadb.ex" +
      "ternalApi.grpc.generated.GrpcCreateSorta" +
      "bleAttributeCompoundSchemaMutationH\000\022\263\001\n" +
      ">modifySortableAttributeCompoundSchemaDe" +
      "precationNoticeMutation\030\002 \001(\0132i.io.evita" +
      "db.externalApi.grpc.generated.GrpcModify" +
      "SortableAttributeCompoundSchemaDeprecati" +
      "onNoticeMutationH\000\022\247\001\n8modifySortableAtt" +
      "ributeCompoundSchemaDescriptionMutation\030" +
      "\003 \001(\0132c.io.evitadb.externalApi.grpc.gene" +
      "rated.GrpcModifySortableAttributeCompoun" +
      "dSchemaDescriptionMutationH\000\022\231\001\n1modifyS" +
      "ortableAttributeCompoundSchemaNameMutati" +
      "on\030\004 \001(\0132\\.io.evitadb.externalApi.grpc.g" +
      "enerated.GrpcModifySortableAttributeComp" +
      "oundSchemaNameMutationH\000\022\221\001\n-removeSorta" +
      "bleAttributeCompoundSchemaMutation\030\005 \001(\013" +
      "2X.io.evitadb.externalApi.grpc.generated" +
      ".GrpcRemoveSortableAttributeCompoundSche" +
      "maMutationH\000B\n\n\010mutationB\014P\001\252\002\007EvitaDBb\006" +
      "proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaOuterClass.getDescriptor(),
          com.google.protobuf.WrappersProto.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_descriptor,
        new java.lang.String[] { "Name", "Description", "DeprecationNotice", "AttributeElements", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_descriptor,
        new java.lang.String[] { "Name", "DeprecationNotice", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_descriptor,
        new java.lang.String[] { "Name", "Description", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_descriptor,
        new java.lang.String[] { "Name", "NewName", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_descriptor,
        new java.lang.String[] { "Name", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_descriptor,
        new java.lang.String[] { "CreateSortableAttributeCompoundSchemaMutation", "ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation", "ModifySortableAttributeCompoundSchemaDescriptionMutation", "ModifySortableAttributeCompoundSchemaNameMutation", "RemoveSortableAttributeCompoundSchemaMutation", "Mutation", });
    io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaOuterClass.getDescriptor();
    com.google.protobuf.WrappersProto.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
