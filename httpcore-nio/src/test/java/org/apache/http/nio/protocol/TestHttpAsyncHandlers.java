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

package org.apache.http.nio.protocol;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.http.HttpCoreNIOTestBase;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.DefaultNHttpClientConnectionFactory;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.NHttpClientIOTarget;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.NHttpServerIOTarget;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.nio.reactor.SessionRequest;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.testserver.SimpleEventListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * HttpCore NIO integration tests for async handlers.
 */
public class TestHttpAsyncHandlers extends HttpCoreNIOTestBase {

    @Before
    @Override
    public void initServer() throws Exception {
        super.initServer();
    }

    @Before
    @Override
    public void initClient() throws Exception {
        super.initClient();
    }

    @After
    @Override
    public void shutDownClient() throws Exception {
        super.shutDownClient();
    }

    @After
    @Override
    public void shutDownServer() throws Exception {
        super.shutDownServer();
    }

    @Override
    protected NHttpConnectionFactory<NHttpServerIOTarget> createServerConnectionFactory(
            final HttpParams params) {
        return new DefaultNHttpServerConnectionFactory(params);
    }

    @Override
    protected NHttpConnectionFactory<NHttpClientIOTarget> createClientConnectionFactory(
            final HttpParams params) {
        return new DefaultNHttpClientConnectionFactory(params);
    }

    static class BasicHttpAsyncRequestHandlerResolver implements HttpAsyncRequestHandlerResolver {

        private final HttpAsyncRequestHandler<?> handler;

        public BasicHttpAsyncRequestHandlerResolver(final HttpAsyncRequestHandler<?> handler) {
            super();
            this.handler = handler;
        }

        public HttpAsyncRequestHandler<?> lookup(String requestURI) {
            return this.handler;
        }

    }

    private void executeStandardTest(
            final HttpAsyncRequestHandler<?> requestHandler,
            final NHttpRequestExecutionHandler requestExecutionHandler) throws Exception {
        int connNo = 3;
        int reqNo = 20;
        Job[] jobs = new Job[connNo * reqNo];
        for (int i = 0; i < jobs.length; i++) {
            jobs[i] = new Job();
        }
        Queue<Job> queue = new ConcurrentLinkedQueue<Job>();
        for (int i = 0; i < jobs.length; i++) {
            queue.add(jobs[i]);
        }

        HttpAsyncServiceHandler serviceHandler = new HttpAsyncServiceHandler(
                new BasicHttpAsyncRequestHandlerResolver(requestHandler),
                this.serverHttpProc,
                new DefaultConnectionReuseStrategy(),
                this.serverParams);

        AsyncNHttpClientHandler clientHandler = new AsyncNHttpClientHandler(
                this.clientHttpProc,
                requestExecutionHandler,
                new DefaultConnectionReuseStrategy(),
                this.clientParams);

        clientHandler.setEventListener(
                new SimpleEventListener());

        this.server.start(serviceHandler);
        this.client.start(clientHandler);

        ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();
        InetSocketAddress serverAddress = (InetSocketAddress) endpoint.getAddress();

        Assert.assertEquals("Test server status", IOReactorStatus.ACTIVE, this.server.getStatus());

        Queue<SessionRequest> connRequests = new LinkedList<SessionRequest>();
        for (int i = 0; i < connNo; i++) {
            SessionRequest sessionRequest = this.client.openConnection(
                    new InetSocketAddress("localhost", serverAddress.getPort()),
                    queue);
            connRequests.add(sessionRequest);
        }

        while (!connRequests.isEmpty()) {
            SessionRequest sessionRequest = connRequests.remove();
            sessionRequest.waitFor();
            if (sessionRequest.getException() != null) {
                throw sessionRequest.getException();
            }
            Assert.assertNotNull(sessionRequest.getSession());
        }

        Assert.assertEquals("Test client status", IOReactorStatus.ACTIVE, this.client.getStatus());

        for (int i = 0; i < jobs.length; i++) {
            Job testjob = jobs[i];
            testjob.waitFor();
            if (testjob.isSuccessful()) {
                Assert.assertEquals(HttpStatus.SC_OK, testjob.getStatusCode());
                Assert.assertEquals(testjob.getExpected(), testjob.getResult());
            } else {
                Assert.fail(testjob.getFailureMessage());
            }
        }
    }

    /**
     * This test case executes a series of simple (non-pipelined) GET requests
     * over multiple connections. This uses non-blocking output entities.
     */
    @Test
    public void testHttpGets() throws Exception {
        NHttpRequestExecutionHandler requestExecutionHandler = new RequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(Job testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount();
                return new BasicHttpRequest("GET", s);
            }

        };
        executeStandardTest(new BufferingAsyncRequestHandler(new RequestHandler()), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) POST requests
     * with content length delimited content over multiple connections.
     * It uses purely asynchronous handlers.
     */
    @Test
    public void testHttpPostsWithContentLength() throws Exception {
        NHttpRequestExecutionHandler requestExecutionHandler = new RequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(Job testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount();
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                    entity.setChunked(false);
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                return r;
            }

        };
        executeStandardTest(new BufferingAsyncRequestHandler(new RequestHandler()), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) POST requests
     * with chunk coded content content over multiple connections.  This tests
     * with nonblocking handlers & nonblocking entities.
     */
    @Test
    public void testHttpPostsChunked() throws Exception {
        NHttpRequestExecutionHandler requestExecutionHandler = new RequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(Job testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount();
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                    entity.setChunked(true);
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                return r;
            }

        };
        executeStandardTest(new BufferingAsyncRequestHandler(new RequestHandler()), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) HTTP/1.0
     * POST requests over multiple persistent connections. This tests with nonblocking
     * handlers & entities.
     */
    @Test
    public void testHttpPostsHTTP10() throws Exception {
        NHttpRequestExecutionHandler requestExecutionHandler = new RequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(Job testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount();
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s,
                        HttpVersion.HTTP_1_0);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                return r;
            }

        };
        executeStandardTest(new BufferingAsyncRequestHandler(new RequestHandler()), requestExecutionHandler);
    }

    /**
     * This test case executes a series of simple (non-pipelined) POST requests
     * over multiple connections using the 'expect: continue' handshake.  This test
     * uses nonblocking handlers & entities.
     */
    @Test
    public void testHttpPostsWithExpectContinue() throws Exception {
        NHttpRequestExecutionHandler requestExecutionHandler = new RequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(Job testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount();
                HttpEntityEnclosingRequest r = new BasicHttpEntityEnclosingRequest("POST", s);
                NStringEntity entity = null;
                try {
                    entity = new NStringEntity(testjob.getExpected(), "US-ASCII");
                } catch (UnsupportedEncodingException ignore) {
                }
                r.setEntity(entity);
                r.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, true);
                return r;
            }

        };
        executeStandardTest(new BufferingAsyncRequestHandler(new RequestHandler()), requestExecutionHandler);
    }

}
