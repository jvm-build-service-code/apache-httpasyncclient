/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.nio.client;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthState;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.Lookup;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.nio.conn.ClientAsyncConnectionManager;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutionHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;

@SuppressWarnings("deprecation")
class InternalHttpAsyncClient extends CloseableHttpAsyncClient {

    private final Log log = LogFactory.getLog(getClass());

    private final NHttpClientConnectionManager connmgr;
    private final HttpProcessor httpProcessor;
    private final ConnectionReuseStrategy reuseStrategy;
    private final InternalClientExec exec;
    private final Lookup<CookieSpecProvider> cookieSpecRegistry;
    private final Lookup<AuthSchemeProvider> authSchemeRegistry;
    private final CookieStore cookieStore;
    private final CredentialsProvider credentialsProvider;
    private final RequestConfig defaultConfig;
    private final Queue<HttpAsyncRequestExecutionHandler<?>> queue;
    private final Thread reactorThread;

    private volatile IOReactorStatus status;

    public InternalHttpAsyncClient(
            final NHttpClientConnectionManager connmgr,
            final HttpProcessor httpProcessor,
            final ConnectionReuseStrategy reuseStrategy,
            final InternalClientExec exec,
            final Lookup<CookieSpecProvider> cookieSpecRegistry,
            final Lookup<AuthSchemeProvider> authSchemeRegistry,
            final CookieStore cookieStore,
            final CredentialsProvider credentialsProvider,
            final RequestConfig defaultConfig) {
        super();
        this.connmgr = connmgr;
        this.httpProcessor = httpProcessor;
        this.reuseStrategy = reuseStrategy;
        this.exec = exec;
        this.cookieSpecRegistry = cookieSpecRegistry;
        this.authSchemeRegistry = authSchemeRegistry;
        this.cookieStore = cookieStore;
        this.credentialsProvider = credentialsProvider;
        this.defaultConfig = defaultConfig;
        this.queue = new ConcurrentLinkedQueue<HttpAsyncRequestExecutionHandler<?>>();
        this.reactorThread = new Thread() {

            @Override
            public void run() {
                doExecute();
            }

        };
        this.status = IOReactorStatus.INACTIVE;
    }

    private void doExecute() {
        try {
            final IOEventDispatch ioEventDispatch = new InternalIODispatch();
            this.connmgr.execute(ioEventDispatch);
        } catch (final Exception ex) {
            this.log.error("I/O reactor terminated abnormally", ex);
        } finally {
            this.status = IOReactorStatus.SHUT_DOWN;
            while (!this.queue.isEmpty()) {
                final HttpAsyncRequestExecutionHandler<?> exchangeHandler = this.queue.remove();
                exchangeHandler.cancel();
            }
        }
    }

    public IOReactorStatus getStatus() {
        return this.status;
    }

    public void start() {
        this.status = IOReactorStatus.ACTIVE;
        this.reactorThread.start();
    }

    public void shutdown() {
        if (this.status.compareTo(IOReactorStatus.ACTIVE) > 0) {
            return;
        }
        this.status = IOReactorStatus.SHUTDOWN_REQUEST;
        try {
            this.connmgr.shutdown();
        } catch (final IOException ex) {
            this.log.error("I/O error shutting down connection manager", ex);
        }
        if (this.reactorThread != null) {
            try {
                this.reactorThread.join();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void close() {
        shutdown();
    }

    private void setupContext(final HttpClientContext context) {
        if (context.getAttribute(ClientContext.TARGET_AUTH_STATE) == null) {
            context.setAttribute(ClientContext.TARGET_AUTH_STATE, new AuthState());
        }
        if (context.getAttribute(ClientContext.PROXY_AUTH_STATE) == null) {
            context.setAttribute(ClientContext.PROXY_AUTH_STATE, new AuthState());
        }
        if (context.getAttribute(ClientContext.AUTHSCHEME_REGISTRY) == null) {
            context.setAttribute(ClientContext.AUTHSCHEME_REGISTRY, this.authSchemeRegistry);
        }
        if (context.getAttribute(ClientContext.COOKIESPEC_REGISTRY) == null) {
            context.setAttribute(ClientContext.COOKIESPEC_REGISTRY, this.cookieSpecRegistry);
        }
        if (context.getAttribute(ClientContext.COOKIE_STORE) == null) {
            context.setAttribute(ClientContext.COOKIE_STORE, this.cookieStore);
        }
        if (context.getAttribute(ClientContext.CREDS_PROVIDER) == null) {
            context.setAttribute(ClientContext.CREDS_PROVIDER, this.credentialsProvider);
        }
        if (context.getAttribute(ClientContext.REQUEST_CONFIG) == null) {
            context.setAttribute(ClientContext.REQUEST_CONFIG, this.defaultConfig);
        }
    }

    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
        if (this.status != IOReactorStatus.ACTIVE) {
            throw new IllegalStateException("Request cannot be executed; " +
                    "I/O reactor status: " + this.status);
        }
        final BasicFuture<T> future = new BasicFuture<T>(callback);
        final ResultCallback<T> resultCallback = new DefaultResultCallback<T>(future, this.queue);
        final HttpClientContext localcontext = HttpClientContext.adapt(
            context != null ? context : new BasicHttpContext());
        setupContext(localcontext);

        final DefaultRequestExectionHandlerImpl<T> handler = new DefaultRequestExectionHandlerImpl<T>(
            this.log,
            requestProducer,
            responseConsumer,
            localcontext,
            resultCallback,
            this.connmgr,
            this.httpProcessor,
            this.reuseStrategy,
            this.exec);
        this.queue.add(handler);
        try {
            handler.start();
        } catch (final Exception ex) {
            handler.failed(ex);
        }
        return future;
    }

    @Deprecated
    public ClientAsyncConnectionManager getConnectionManager() {
        return null;
    }

    @Deprecated
    public HttpParams getParams() {
        return null;
    }

}
