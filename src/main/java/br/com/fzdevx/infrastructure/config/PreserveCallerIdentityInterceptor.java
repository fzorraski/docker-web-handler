package br.com.fzdevx.infrastructure.config;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/** Binds a detached copy of the caller to the thread for the whole invocation. */
@PreserveCallerIdentity
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class PreserveCallerIdentityInterceptor {

    @Inject
    CallerIdentity callerIdentity;

    @AroundInvoke
    Object preserve(InvocationContext context) throws Exception {
        return callerIdentity.callAs(callerIdentity.capture(), context::proceed);
    }
}
