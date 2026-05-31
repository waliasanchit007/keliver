/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.http.api

/**
 * Annotates a method on a [KeliverApi]-marked interface as an
 * HTTP GET request. [path] is the URL path template; `{name}`
 * placeholders are substituted from parameters annotated with
 * [Path].
 *
 * Adopters can mix [Path] and [Query] parameters; the generated
 * implementation builds the final URL by substituting `{}` segments
 * with the corresponding `@Path` value and appending the remaining
 * `@Query` values as query string parameters (skipping `null`
 * values).
 *
 * ```
 * @GET("/users/{userId}/quotes")
 * suspend fun listForUser(
 *     @Path("userId") userId: String,
 *     @Query("limit") limit: Int,
 * ): List<Quote>
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class GET(public val path: String)

/**
 * HTTP POST. The request body comes from the parameter annotated with
 * [Body]; serialization uses the adopter-supplied
 * [dev.keliver.http.KeliverHttp.json] instance.
 *
 * ```
 * @POST("/quotes")
 * suspend fun create(@Body body: NewQuote): Quote
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class POST(public val path: String)

/**
 * HTTP PUT. Same body / parameter semantics as [POST].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class PUT(public val path: String)

/**
 * HTTP DELETE. Typically returns `Unit` or a small confirmation
 * envelope; the generated impl routes through
 * [dev.keliver.http.KeliverHttp.deleteUnit] for `Unit` returns and
 * [dev.keliver.http.KeliverHttp.delete] for typed returns.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class DELETE(public val path: String)
