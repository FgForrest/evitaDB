// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEntitySchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Mutation is responsible for setting a `EntitySchema.withHierarchy` in `EntitySchema`.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation}
 */
public final class GrpcSetEntitySchemaWithHierarchyMutation extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation)
    GrpcSetEntitySchemaWithHierarchyMutationOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcSetEntitySchemaWithHierarchyMutation.newBuilder() to construct.
  private GrpcSetEntitySchemaWithHierarchyMutation(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcSetEntitySchemaWithHierarchyMutation() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcSetEntitySchemaWithHierarchyMutation();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcSetEntitySchemaWithHierarchyMutation(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 8: {

            withHierarchy_ = input.readBool();
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetEntitySchemaWithHierarchyMutation_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetEntitySchemaWithHierarchyMutation_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation.class, io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation.Builder.class);
  }

  public static final int WITHHIERARCHY_FIELD_NUMBER = 1;
  private boolean withHierarchy_;
  /**
   * <pre>
   * Whether entities of this type are organized in a tree like structure (hierarchy) where certain entities
   * are subordinate of other entities.
   * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
   * referred by multiple child entities. Hierarchy is always composed of entities of same type.
   * Each entity must be part of at most single hierarchy (tree).
   * Hierarchy can limit returned entities by using filtering constraints `hierarchy_{reference name}_within`. It's also used for
   * computation of extra data - such as `hierarchyParents`.
   * </pre>
   *
   * <code>bool withHierarchy = 1;</code>
   * @return The withHierarchy.
   */
  @java.lang.Override
  public boolean getWithHierarchy() {
    return withHierarchy_;
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (withHierarchy_ != false) {
      output.writeBool(1, withHierarchy_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (withHierarchy_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(1, withHierarchy_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation other = (io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation) obj;

    if (getWithHierarchy()
        != other.getWithHierarchy()) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + WITHHIERARCHY_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getWithHierarchy());
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Mutation is responsible for setting a `EntitySchema.withHierarchy` in `EntitySchema`.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation)
      io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutationOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetEntitySchemaWithHierarchyMutation_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetEntitySchemaWithHierarchyMutation_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation.class, io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      withHierarchy_ = false;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetEntitySchemaWithHierarchyMutation_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation build() {
      io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation result = new io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation(this);
      result.withHierarchy_ = withHierarchy_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation.getDefaultInstance()) return this;
      if (other.getWithHierarchy() != false) {
        setWithHierarchy(other.getWithHierarchy());
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private boolean withHierarchy_ ;
    /**
     * <pre>
     * Whether entities of this type are organized in a tree like structure (hierarchy) where certain entities
     * are subordinate of other entities.
     * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
     * referred by multiple child entities. Hierarchy is always composed of entities of same type.
     * Each entity must be part of at most single hierarchy (tree).
     * Hierarchy can limit returned entities by using filtering constraints `hierarchy_{reference name}_within`. It's also used for
     * computation of extra data - such as `hierarchyParents`.
     * </pre>
     *
     * <code>bool withHierarchy = 1;</code>
     * @return The withHierarchy.
     */
    @java.lang.Override
    public boolean getWithHierarchy() {
      return withHierarchy_;
    }
    /**
     * <pre>
     * Whether entities of this type are organized in a tree like structure (hierarchy) where certain entities
     * are subordinate of other entities.
     * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
     * referred by multiple child entities. Hierarchy is always composed of entities of same type.
     * Each entity must be part of at most single hierarchy (tree).
     * Hierarchy can limit returned entities by using filtering constraints `hierarchy_{reference name}_within`. It's also used for
     * computation of extra data - such as `hierarchyParents`.
     * </pre>
     *
     * <code>bool withHierarchy = 1;</code>
     * @param value The withHierarchy to set.
     * @return This builder for chaining.
     */
    public Builder setWithHierarchy(boolean value) {
      
      withHierarchy_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Whether entities of this type are organized in a tree like structure (hierarchy) where certain entities
     * are subordinate of other entities.
     * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
     * referred by multiple child entities. Hierarchy is always composed of entities of same type.
     * Each entity must be part of at most single hierarchy (tree).
     * Hierarchy can limit returned entities by using filtering constraints `hierarchy_{reference name}_within`. It's also used for
     * computation of extra data - such as `hierarchyParents`.
     * </pre>
     *
     * <code>bool withHierarchy = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearWithHierarchy() {
      
      withHierarchy_ = false;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation)
  private static final io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcSetEntitySchemaWithHierarchyMutation>
      PARSER = new com.google.protobuf.AbstractParser<GrpcSetEntitySchemaWithHierarchyMutation>() {
    @java.lang.Override
    public GrpcSetEntitySchemaWithHierarchyMutation parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcSetEntitySchemaWithHierarchyMutation(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcSetEntitySchemaWithHierarchyMutation> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcSetEntitySchemaWithHierarchyMutation> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

