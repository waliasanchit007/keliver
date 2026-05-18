/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.http.api

/**
 * Substitutes the `{name}` placeholder in the method's URL path
 * template with this parameter's value (URL-encoded by the
 * underlying [dev.konduit.http.HostHttpProvider] implementation).
 *
 * ```
 * @GET("/users/{userId}/quotes/{quoteId}")
 * suspend fun get(
 *     @Path("userId") userId: String,
 *     @Path("quoteId") quoteId: String,
 * ): Quote
 * ```
 *
 * The processor fails the build if a `{name}` placeholder in the
 * path has no matching `@Path("name")` parameter, or if a `@Path`
 * parameter references a placeholder not present in the path.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Path(public val name: String)

/**
 * Appends a query-string parameter to the request URL. `null` values
 * are omitted from the final URL, so adopters can model optional
 * filters by declaring nullable parameter types:
 *
 * ```
 * @GET("/quotes")
 * suspend fun list(@Query("filter") filter: String?): List<Quote>
 * ```
 *
 * Repeated values (a `List<String>` parameter, etc.) emit the
 * parameter multiple times in the URL — handled by the underlying
 * [dev.konduit.http.HttpRequest.query] envelope and the host adapter.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Query(public val name: String)

/**
 * Marks the parameter whose value is JSON-encoded as the request body.
 * Valid on `@POST` and `@PUT` methods only; the processor rejects it
 * on `@GET` or `@DELETE` shapes.
 *
 * ```
 * @POST("/quotes")
 * suspend fun create(@Body body: NewQuote): Quote
 * ```
 *
 * Serialization uses the adopter-supplied
 * [dev.konduit.http.KonduitHttp.json] instance, so contextual
 * serializers and lenient parsing settings are honored.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Body

/**
 * Adds a single header to the outgoing request. The annotation's
 * [name] is the header name; the parameter's value becomes the
 * header value (converted to `String` via `toString()` if not
 * already a `String`).
 *
 * ```
 * @GET("/secure/data")
 * suspend fun secure(
 *     @Header("Authorization") token: String,
 *     @Header("X-Request-Id") requestId: String,
 * ): SecureData
 * ```
 *
 * `null` values are omitted from the request.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Header(public val name: String)

/**
 * Spreads a `Map<String, String>` of headers onto the outgoing
 * request. Useful for forwarding a request-scoped header bundle
 * (e.g. tracing IDs) without enumerating each one as a separate
 * `@Header` parameter.
 *
 * ```
 * @GET("/data")
 * suspend fun data(@HeaderMap headers: Map<String, String>): Data
 * ```
 *
 * The processor accepts only `Map<String, String>` and
 * `Map<String, String?>` (null values are filtered). The parameter
 * type must be one of those shapes; anything else fails the build.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class HeaderMap
