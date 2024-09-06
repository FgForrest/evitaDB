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
// source: GrpcReferenceSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Mutation is responsible for setting value to a `ReferenceSchema.faceted in `EntitySchema`.
 * Mutation can be used for altering also the existing `ReferenceSchema` alone.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation}
 */
public final class GrpcSetReferenceSchemaFacetedMutation extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation)
    GrpcSetReferenceSchemaFacetedMutationOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcSetReferenceSchemaFacetedMutation.newBuilder() to construct.
  private GrpcSetReferenceSchemaFacetedMutation(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcSetReferenceSchemaFacetedMutation() {
    name_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcSetReferenceSchemaFacetedMutation();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcSetReferenceSchemaFacetedMutation(
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
          case 10: {
            java.lang.String s = input.readStringRequireUtf8();

            name_ = s;
            break;
          }
          case 16: {

            faceted_ = input.readBool();
            break;
          }
          case 24: {

            inherited_ = input.readBool();
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
    return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetReferenceSchemaFacetedMutation_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetReferenceSchemaFacetedMutation_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.class, io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.Builder.class);
  }

  public static final int NAME_FIELD_NUMBER = 1;
  private volatile java.lang.Object name_;
  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  @java.lang.Override
  public java.lang.String getName() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      name_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getNameBytes() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      name_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int FACETED_FIELD_NUMBER = 2;
  private boolean faceted_;
  /**
   * <pre>
   * Whether the statistics data for this reference should be maintained and this allowing to get
   * `facetSummary` for this reference or use `facet_{reference name}_inSet`
   * filtering query.
   * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * </pre>
   *
   * <code>bool faceted = 2;</code>
   * @return The faceted.
   */
  @java.lang.Override
  public boolean getFaceted() {
    return faceted_;
  }

  public static final int INHERITED_FIELD_NUMBER = 3;
  private boolean inherited_;
  /**
   * <pre>
   * Set to true when the faceted property should be inherited from the original.
   * This property makes sense only for inherited reference attributes on reflected reference. For all other cases it
   * must be left as false. When set to TRUE the value of `faceted` field is ignored.
   * </pre>
   *
   * <code>bool inherited = 3;</code>
   * @return The inherited.
   */
  @java.lang.Override
  public boolean getInherited() {
    return inherited_;
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
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(name_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, name_);
    }
    if (faceted_ != false) {
      output.writeBool(2, faceted_);
    }
    if (inherited_ != false) {
      output.writeBool(3, inherited_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(name_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, name_);
    }
    if (faceted_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(2, faceted_);
    }
    if (inherited_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(3, inherited_);
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation other = (io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation) obj;

    if (!getName()
        .equals(other.getName())) return false;
    if (getFaceted()
        != other.getFaceted()) return false;
    if (getInherited()
        != other.getInherited()) return false;
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
    hash = (37 * hash) + NAME_FIELD_NUMBER;
    hash = (53 * hash) + getName().hashCode();
    hash = (37 * hash) + FACETED_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getFaceted());
    hash = (37 * hash) + INHERITED_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getInherited());
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation prototype) {
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
   * Mutation is responsible for setting value to a `ReferenceSchema.faceted in `EntitySchema`.
   * Mutation can be used for altering also the existing `ReferenceSchema` alone.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation)
      io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutationOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetReferenceSchemaFacetedMutation_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetReferenceSchemaFacetedMutation_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.class, io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.newBuilder()
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
      name_ = "";

      faceted_ = false;

      inherited_ = false;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetReferenceSchemaFacetedMutation_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation build() {
      io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation result = new io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation(this);
      result.name_ = name_;
      result.faceted_ = faceted_;
      result.inherited_ = inherited_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation.getDefaultInstance()) return this;
      if (!other.getName().isEmpty()) {
        name_ = other.name_;
        onChanged();
      }
      if (other.getFaceted() != false) {
        setFaceted(other.getFaceted());
      }
      if (other.getInherited() != false) {
        setInherited(other.getInherited());
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
      io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object name_ = "";
    /**
     * <pre>
     * Name of the reference the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @return The name.
     */
    public java.lang.String getName() {
      java.lang.Object ref = name_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        name_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * Name of the reference the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @return The bytes for name.
     */
    public com.google.protobuf.ByteString
        getNameBytes() {
      java.lang.Object ref = name_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        name_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * Name of the reference the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @param value The name to set.
     * @return This builder for chaining.
     */
    public Builder setName(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      name_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the reference the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearName() {

      name_ = getDefaultInstance().getName();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the reference the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @param value The bytes for name to set.
     * @return This builder for chaining.
     */
    public Builder setNameBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      name_ = value;
      onChanged();
      return this;
    }

    private boolean faceted_ ;
    /**
     * <pre>
     * Whether the statistics data for this reference should be maintained and this allowing to get
     * `facetSummary` for this reference or use `facet_{reference name}_inSet`
     * filtering query.
     * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
     * occupies (memory/disk) space in the form of index.
     * Reference that was marked as faceted is called Facet.
     * </pre>
     *
     * <code>bool faceted = 2;</code>
     * @return The faceted.
     */
    @java.lang.Override
    public boolean getFaceted() {
      return faceted_;
    }
    /**
     * <pre>
     * Whether the statistics data for this reference should be maintained and this allowing to get
     * `facetSummary` for this reference or use `facet_{reference name}_inSet`
     * filtering query.
     * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
     * occupies (memory/disk) space in the form of index.
     * Reference that was marked as faceted is called Facet.
     * </pre>
     *
     * <code>bool faceted = 2;</code>
     * @param value The faceted to set.
     * @return This builder for chaining.
     */
    public Builder setFaceted(boolean value) {

      faceted_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Whether the statistics data for this reference should be maintained and this allowing to get
     * `facetSummary` for this reference or use `facet_{reference name}_inSet`
     * filtering query.
     * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
     * occupies (memory/disk) space in the form of index.
     * Reference that was marked as faceted is called Facet.
     * </pre>
     *
     * <code>bool faceted = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearFaceted() {

      faceted_ = false;
      onChanged();
      return this;
    }

    private boolean inherited_ ;
    /**
     * <pre>
     * Set to true when the faceted property should be inherited from the original.
     * This property makes sense only for inherited reference attributes on reflected reference. For all other cases it
     * must be left as false. When set to TRUE the value of `faceted` field is ignored.
     * </pre>
     *
     * <code>bool inherited = 3;</code>
     * @return The inherited.
     */
    @java.lang.Override
    public boolean getInherited() {
      return inherited_;
    }
    /**
     * <pre>
     * Set to true when the faceted property should be inherited from the original.
     * This property makes sense only for inherited reference attributes on reflected reference. For all other cases it
     * must be left as false. When set to TRUE the value of `faceted` field is ignored.
     * </pre>
     *
     * <code>bool inherited = 3;</code>
     * @param value The inherited to set.
     * @return This builder for chaining.
     */
    public Builder setInherited(boolean value) {

      inherited_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Set to true when the faceted property should be inherited from the original.
     * This property makes sense only for inherited reference attributes on reflected reference. For all other cases it
     * must be left as false. When set to TRUE the value of `faceted` field is ignored.
     * </pre>
     *
     * <code>bool inherited = 3;</code>
     * @return This builder for chaining.
     */
    public Builder clearInherited() {

      inherited_ = false;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation)
  private static final io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcSetReferenceSchemaFacetedMutation>
      PARSER = new com.google.protobuf.AbstractParser<GrpcSetReferenceSchemaFacetedMutation>() {
    @java.lang.Override
    public GrpcSetReferenceSchemaFacetedMutation parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcSetReferenceSchemaFacetedMutation(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcSetReferenceSchemaFacetedMutation> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcSetReferenceSchemaFacetedMutation> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

