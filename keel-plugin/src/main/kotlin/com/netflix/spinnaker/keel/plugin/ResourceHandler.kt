package com.netflix.spinnaker.keel.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.netflix.spinnaker.keel.api.ApiVersion
import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.SubmittedResource
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.randomUID
import com.netflix.spinnaker.keel.diff.ResourceDiff
import com.netflix.spinnaker.keel.events.Task
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @param S the spec type.
 * @param R the resolved model type.
 *
 * If those two are the same, use [SimpleResourceHandler] instead.
 */
abstract class ResourceHandler<S : ResourceSpec, R : Any>(
  private val objectMapper: ObjectMapper,
  private val resolvers: List<Resolver<*>>
) : KeelPlugin {

  protected val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

  abstract val apiVersion: ApiVersion

  /**
   * Maps the kind to the implementation type.
   */
  abstract val supportedKind: Pair<String, Class<S>>

  /**
   * Validates the resource spec and generates a metadata header.
   *
   * Implementors should not generally need to override this method.
   *
   * @return a hydrated `Resource` with a name generated by convention, an `id`,  `uid`, etc.
   */
  fun normalize(resource: SubmittedResource<S>): Resource<S> {
    val metadata = resource.metadata + mapOf(
      "id" to resource.id.toString(),
      "uid" to randomUID().toString(),
      "application" to resource.spec.application
    )
    return Resource(
      apiVersion = resource.apiVersion,
      kind = resource.kind,
      metadata = metadata,
      spec = resource.spec
    )
  }

  /**
   * Applies any defaults / opinions to the resource as it is resolved into its [desired] state.
   *
   * @return [resource] or a copy of [resource] that may have been changed in order to set default
   * values or apply opinions.
   */
  private fun applyResolvers(resource: Resource<S>): Resource<S> =
    resolvers
      .supporting(resource)
      .fold(resource) { r, resolver ->
        log.debug("Applying ${resolver.javaClass.simpleName} to ${r.id}")
        resolver(r)
      }

  /**
   * Resolve and convert the resource spec into the type that represents the diff-able desired
   * state.
   *
   * The value returned by this method is used as the basis of the diff (with the result of
   * [current] in order to decide whether to call [create]/[update]/[upsert].
   *
   * @param resource the resource as persisted in the Keel database.
   */
  suspend fun desired(resource: Resource<S>): R = toResolvedType(applyResolvers(resource))

  /**
   * Convert the resource spec into the type that represents the diff-able desired state. This may
   * involve looking up referenced resources, splitting a multi-region resource into discrete
   * objects for each region, etc.
   *
   * Implementations of this method should not actuate any changes.
   *
   * @param resource a fully-resolved version of the persisted resource spec. You can assume that
   * [applyResolvers] has already been called on this object.
   */
  protected abstract suspend fun toResolvedType(resource: Resource<S>): R

  /**
   * Return the current _actual_ representation of what [resource] looks like in the cloud.
   * The entire desired state is passed so that implementations can use whatever identifying
   * information they need to look up the resource.
   *
   * The value returned by this method is used as the basis of the diff (with the result of
   * [desired] in order to decide whether to call [create]/[update]/[upsert].
   *
   * Implementations of this method should not actuate any changes.
   */
  abstract suspend fun current(resource: Resource<S>): R?

  /**
   * Create a resource so that it matches the desired state represented by [resource].
   *
   * By default this just delegates to [upsert].
   *
   * Implement this method and [update] if you need to handle create and update in different ways.
   * Otherwise just implement [upsert].
   *
   * @return a list of tasks launched to actuate the resource.
   */
  open suspend fun create(
    resource: Resource<S>,
    resourceDiff: ResourceDiff<R>
  ): List<Task> =
    upsert(resource, resourceDiff)

  /**
   * Update a resource so that it matches the desired state represented by [resource].
   *
   * By default this just delegates to [upsert].
   *
   * Implement this method and [create] if you need to handle create and update in different ways.
   * Otherwise just implement [upsert].
   *
   * @return a list of tasks launched to actuate the resource.
   */
  open suspend fun update(
    resource: Resource<S>,
    resourceDiff: ResourceDiff<R>
  ): List<Task> =
    upsert(resource, resourceDiff)

  /**
   * Create or update a resource so that it matches the desired state represented by [resource].
   *
   * You don't need to implement this method if you are implementing [create] and [update]
   * individually.
   *
   * @return a list of tasks launched to actuate the resource.
   */
  open suspend fun upsert(
    resource: Resource<S>,
    resourceDiff: ResourceDiff<R>
  ): List<Task> {
    TODO("Not implemented")
  }

  /**
   * Delete a resource as the desired state is that it should no longer exist.
   */
  open suspend fun delete(resource: Resource<S>): List<Task> = TODO("Not implemented")

  /**
   * Generate a Keel SubmittedResource definition from currently existing resources.
   */
  open suspend fun export(
    exportable: Exportable
  ): SubmittedResource<*> {
    TODO("Not implemented")
  }

  /**
   * @return `true` if this plugin is still busy running a previous actuation for the resource
   * associated with [id], `false` otherwise.
   */
  open suspend fun actuationInProgress(id: ResourceId): Boolean = false

  /**
   * Used to register the [ResourceSpec] sub-type supported by this handler with Jackson so we can
   * serialize and deserialize it. Do not override this or call it, or even look at it. You never
   * saw this method, alright?
   */
  fun registerResourceKind(objectMappers: Iterable<ObjectMapper>) {
    val (kind, specClass) = supportedKind
    val typeId = "$apiVersion/$kind"
    val namedType = NamedType(specClass, typeId)
    log.info("Registering ResourceSpec sub-type {}: {}", typeId, specClass.simpleName)
    objectMappers.forEach { it.registerSubtypes(namedType) }
  }

  /**
   * Convenient version of `registerResourceKind(Iterable<ObjectMapper>)` for tests, etc.
   */
  fun registerResourceKind(vararg objectMappers: ObjectMapper) {
    registerResourceKind(objectMappers.toList())
  }
}

/**
 * Searches a list of `ResourceHandler`s and returns the first that supports [apiVersion] and
 * [kind].
 *
 * @throws UnsupportedKind if no appropriate handlers are found in the list.
 */
fun Collection<ResourceHandler<*, *>>.supporting(
  apiVersion: ApiVersion,
  kind: String
): ResourceHandler<*, *> =
  find {
    it.apiVersion == apiVersion && it.supportedKind.first == kind
  }
    ?: throw UnsupportedKind(apiVersion, kind)

class UnsupportedKind(apiVersion: ApiVersion, kind: String) :
  IllegalStateException("No resource handler supporting \"$kind\" in \"$apiVersion\" is available")
