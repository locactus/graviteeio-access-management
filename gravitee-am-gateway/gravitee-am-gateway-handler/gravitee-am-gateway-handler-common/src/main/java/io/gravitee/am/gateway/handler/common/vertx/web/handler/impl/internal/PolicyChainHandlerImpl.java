/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.am.gateway.handler.common.vertx.web.handler.impl.internal;

import io.gravitee.am.common.policy.ExtensionPoint;
import io.gravitee.am.gateway.handler.common.policy.PolicyManager;
import io.gravitee.am.gateway.handler.common.vertx.core.http.VertxHttpServerRequest;
import io.gravitee.am.gateway.handler.common.vertx.core.http.VertxHttpServerResponse;
import io.gravitee.am.gateway.handler.context.ExecutionContextFactory;
import io.gravitee.am.gateway.policy.Policy;
import io.gravitee.am.gateway.policy.PolicyChainProcessorFactory;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.context.SimpleExecutionContext;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.reactivex.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public class PolicyChainHandlerImpl implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(PolicyChainHandlerImpl.class);
    private static final String CLIENT_CONTEXT_KEY = "client";
    private static final String USER_CONTEXT_KEY = "user";
    private PolicyManager policyManager;
    private PolicyChainProcessorFactory policyChainProcessorFactory;
    private ExecutionContextFactory executionContextFactory;
    private ExtensionPoint extensionPoint;

    public PolicyChainHandlerImpl(PolicyManager policyManager,
                                  PolicyChainProcessorFactory policyChainProcessorFactory,
                                  ExecutionContextFactory executionContextFactory,
                                  ExtensionPoint extensionPoint) {
        this.policyManager = policyManager;
        this.policyChainProcessorFactory = policyChainProcessorFactory;
        this.executionContextFactory = executionContextFactory;
        this.extensionPoint = extensionPoint;
    }

    @Override
    public void handle(RoutingContext context) {
        // resolve policies
        resolve(extensionPoint, handler -> {
            if (handler.failed()) {
                logger.error("An error occurs while resolving policies", handler.cause());
                context.fail(handler.cause());
                return;
            }

            List<Policy> policies = handler.result();
            // if no policies continue
            if (policies.isEmpty()) {
                context.next();
                return;
            }

            // prepare execution context
            prepareContext(context, contextHandler -> {
                if (contextHandler.failed()) {
                    logger.error("An error occurs while preparing execution context", handler.cause());
                    context.fail(contextHandler.cause());
                    return;
                }

                // call the policy chain
                ExecutionContext executionContext = contextHandler.result();
                policyChainProcessorFactory
                        .create(policies, executionContext)
                        .handler(executionContext1 -> context.next())
                        .errorHandler(processorFailure -> context.fail(processorFailure.statusCode()))
                        .handle(executionContext);
            });
        });
    }

    private void resolve(ExtensionPoint extensionPoint, Handler<AsyncResult<List<Policy>>> handler) {
        policyManager.findByExtensionPoint(extensionPoint)
                .subscribe(
                        policies -> handler.handle(Future.succeededFuture(policies)),
                        error -> handler.handle(Future.failedFuture(error)));
    }

    private void prepareContext(RoutingContext routingContext, Handler<AsyncResult<ExecutionContext>> handler) {
        try {
            HttpServerRequest request = routingContext.request().getDelegate();
            Request serverRequest = new VertxHttpServerRequest(request);
            Response serverResponse = new VertxHttpServerResponse(request, serverRequest.metrics());
            ExecutionContext simpleExecutionContext = new SimpleExecutionContext(serverRequest, serverResponse);
            ExecutionContext executionContext = executionContextFactory.create(simpleExecutionContext);

            // add client if exists
            if (routingContext.get(CLIENT_CONTEXT_KEY) != null) {
                executionContext.setAttribute(CLIENT_CONTEXT_KEY, routingContext.get(CLIENT_CONTEXT_KEY));
            }
            // add user if exists
            io.vertx.reactivex.ext.auth.User authenticatedUser = routingContext.user();
            if (authenticatedUser != null && (authenticatedUser.getDelegate() instanceof io.gravitee.am.gateway.handler.common.vertx.web.auth.user.User)) {
                executionContext.setAttribute(USER_CONTEXT_KEY, ((io.gravitee.am.gateway.handler.common.vertx.web.auth.user.User) authenticatedUser.getDelegate()).getUser());
            }
            handler.handle(Future.succeededFuture(executionContext));
        } catch (Exception ex) {
            handler.handle(Future.failedFuture(ex));
        }
    }
}
