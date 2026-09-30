package br.com.fzdevx.infrastructure.config;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps the caller's identity available to the annotated method (or every
 * method of the annotated class) even after the request scope is gone.
 *
 * <p>Put it on controllers whose methods keep working after the HTTP response
 * is effectively over - the SSE streams. The identity is captured on entry,
 * while the scope is still active, and answers {@link CallerIdentity#user()}
 * for the rest of the call. See {@link CallerIdentity} for why this matters.</p>
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PreserveCallerIdentity {
}
