package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.auth.Permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which RBAC permission(s) a REST resource requires. A method-level
 * annotation overrides the class-level default. Multiple values mean
 * "any of"; an empty array means any authenticated user (used for feature-flag
 * probes the UI calls regardless of role). Only enforced when RBAC is active
 * ({@code app.auth.enabled=true} and {@code app.auth.mode=rbac}); otherwise inert.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    Permission[] value();
}
