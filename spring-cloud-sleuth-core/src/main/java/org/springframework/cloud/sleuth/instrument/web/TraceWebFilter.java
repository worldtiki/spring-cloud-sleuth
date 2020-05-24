/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.web;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import brave.Span;
import brave.Tracer;
import brave.http.HttpServerHandler;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;
import brave.http.HttpTracing;
import brave.propagation.TraceContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * A {@link WebFilter} that creates / continues / closes and detaches spans for a reactive
 * web application.
 *
 * @author Marcin Grzejszczak
 * @since 2.0.0
 */
final class TraceWebFilter implements WebFilter, Ordered {

	/**
	 * If you register your filter before the {@link TraceWebFilter} then you will not
	 * have the tracing context passed for you out of the box. That means that e.g. your
	 * logs will not get correlated.
	 */
	public static final int ORDER = TraceHttpAutoConfiguration.TRACING_FILTER_ORDER;

	protected static final String TRACE_REQUEST_ATTR = TraceWebFilter.class.getName()
			+ ".TRACE";
	static final String MVC_CONTROLLER_CLASS_KEY = "mvc.controller.class";
	static final String MVC_CONTROLLER_METHOD_KEY = "mvc.controller.method";

	private static final Log log = LogFactory.getLog(TraceWebFilter.class);

	private static final String STATUS_CODE_KEY = "http.status_code";

	private static final String TRACE_SPAN_WITHOUT_PARENT = TraceWebFilter.class.getName()
			+ ".SPAN_WITH_NO_PARENT";

	private final BeanFactory beanFactory;

	Tracer tracer;

	HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;

	SleuthWebProperties webProperties;

	TraceWebFilter(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public static WebFilter create(BeanFactory beanFactory) {
		return new TraceWebFilter(beanFactory);
	}

	@SuppressWarnings("unchecked")
	HttpServerHandler<HttpServerRequest, HttpServerResponse> handler() {
		if (this.handler == null) {
			this.handler = HttpServerHandler
					.create(this.beanFactory.getBean(HttpTracing.class));
		}
		return this.handler;
	}

	Tracer tracer() {
		if (this.tracer == null) {
			this.tracer = this.beanFactory.getBean(HttpTracing.class).tracing().tracer();
		}
		return this.tracer;
	}

	SleuthWebProperties sleuthWebProperties() {
		if (this.webProperties == null) {
			this.webProperties = this.beanFactory.getBean(SleuthWebProperties.class);
		}
		return this.webProperties;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String uri = exchange.getRequest().getPath().pathWithinApplication().value();
		if (log.isDebugEnabled()) {
			log.debug("Received a request to uri [" + uri + "]");
		}
		Mono<Void> source = chain.filter(exchange);
		boolean tracePresent = tracer().currentSpan() != null;
		if (tracePresent) {
			// clear any previous trace
			tracer().withSpanInScope(null); // TODO: dangerous and also allocates stuff
		}
		return new MonoWebFilterTrace(source, exchange, tracePresent, this);
	}

	@Override
	public int getOrder() {
		return sleuthWebProperties().getFilterOrder();
	}

	private static class MonoWebFilterTrace extends MonoOperator<Void, Void> {

		final ServerWebExchange exchange;

		final Tracer tracer;

		final Span attrSpan;

		final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;

		final AtomicBoolean initialSpanAlreadyRemoved = new AtomicBoolean();

		final boolean initialTracePresent;

		MonoWebFilterTrace(Mono<? extends Void> source, ServerWebExchange exchange,
				boolean initialTracePresent, TraceWebFilter parent) {
			super(source);
			this.tracer = parent.tracer();
			this.handler = parent.handler();
			this.exchange = exchange;
			this.attrSpan = exchange.getAttribute(TRACE_REQUEST_ATTR);
			this.initialTracePresent = initialTracePresent;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Void> subscriber) {
			Context context = contextWithoutInitialSpan(subscriber.currentContext());
			this.source.subscribe(new WebFilterTraceSubscriber(subscriber, context,
					findOrCreateSpan(context), this));
		}

		private Context contextWithoutInitialSpan(Context context) {
			if (this.initialTracePresent && !this.initialSpanAlreadyRemoved.get()) {
				context = context.delete(TraceContext.class);
				this.initialSpanAlreadyRemoved.set(true);
			}
			return context;
		}

		private Span findOrCreateSpan(Context c) {
			Span span;
			if (c.hasKey(TraceContext.class)) {
				TraceContext parent = c.get(TraceContext.class);
				span = this.tracer.newChild(parent).start();
				if (log.isDebugEnabled()) {
					log.debug("Found span in reactor context" + span);
				}
			}
			else {
				if (this.attrSpan != null) {
					span = this.attrSpan;
					if (log.isDebugEnabled()) {
						log.debug("Found span in attribute " + span);
					}
				}
				else {
					span = this.handler.handleReceive(
							new WrappedRequest(this.exchange.getRequest()));
					if (log.isDebugEnabled()) {
						log.debug("Handled receive of span " + span);
					}
				}
				this.exchange.getAttributes().put(TRACE_REQUEST_ATTR, span);
			}
			return span;
		}

		static final class WebFilterTraceSubscriber implements CoreSubscriber<Void> {

			final CoreSubscriber<? super Void> actual;

			final Context context;

			final Span span;

			final ServerWebExchange exchange;

			final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;

			WebFilterTraceSubscriber(CoreSubscriber<? super Void> actual, Context context,
					Span span, MonoWebFilterTrace parent) {
				this.actual = actual;
				this.span = span;
				this.context = context.put(TraceContext.class, span.context());
				this.exchange = parent.exchange;
				this.handler = parent.handler;
			}

			@Override
			public void onSubscribe(Subscription subscription) {
				this.actual.onSubscribe(subscription);
			}

			@Override
			public void onNext(Void aVoid) {
				// IGNORE
			}

			@Override
			public void onError(Throwable t) {
				terminateSpan(t);
				this.actual.onError(t);
			}

			@Override
			public void onComplete() {
				terminateSpan(null);
				this.actual.onComplete();
			}

			@Override
			public Context currentContext() {
				return this.context;
			}

			private void terminateSpan(@Nullable Throwable t) {
				Object attribute = this.exchange
						.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
				addClassMethodTag(attribute, this.span);
				addClassNameTag(attribute, this.span);
				Object pattern = this.exchange
						.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
				String httpRoute = pattern != null ? pattern.toString() : "";
				addResponseTagsForSpanWithoutParent(this.exchange,
						this.exchange.getResponse(), this.span);
				WrappedResponse response = new WrappedResponse(
						this.exchange.getResponse(),
						this.exchange.getRequest().getMethodValue(), httpRoute);
				this.handler.handleSend(response, t, this.span);
				if (log.isDebugEnabled()) {
					log.debug("Handled send of " + this.span);
				}
			}

			private void addClassMethodTag(Object handler, Span span) {
				if (handler instanceof HandlerMethod) {
					String methodName = ((HandlerMethod) handler).getMethod().getName();
					span.tag(MVC_CONTROLLER_METHOD_KEY, methodName);
					if (log.isDebugEnabled()) {
						log.debug("Adding a method tag with value [" + methodName
								+ "] to a span " + span);
					}
				}
			}

			private void addClassNameTag(Object handler, Span span) {
				if (handler == null) {
					return;
				}
				String className;
				if (handler instanceof HandlerMethod) {
					className = ((HandlerMethod) handler).getBeanType().getSimpleName();
				}
				else {
					className = handler.getClass().getSimpleName();
				}
				if (log.isDebugEnabled()) {
					log.debug("Adding a class tag with value [" + className
							+ "] to a span " + span);
				}
				span.tag(MVC_CONTROLLER_CLASS_KEY, className);
			}

			private void addResponseTagsForSpanWithoutParent(ServerWebExchange exchange,
					ServerHttpResponse response, Span span) {
				if (spanWithoutParent(exchange) && response.getStatusCode() != null
						&& span != null) {
					span.tag(STATUS_CODE_KEY,
							String.valueOf(response.getStatusCode().value()));
				}
			}

			private boolean spanWithoutParent(ServerWebExchange exchange) {
				return exchange.getAttribute(TRACE_SPAN_WITHOUT_PARENT) != null;
			}

		}

	}

	static final class WrappedRequest extends HttpServerRequest {

		final ServerHttpRequest delegate;

		WrappedRequest(ServerHttpRequest delegate) {
			this.delegate = delegate;
		}

		@Override
		public ServerHttpRequest unwrap() {
			return delegate;
		}

		@Override
		public boolean parseClientIpAndPort(Span span) {
			InetSocketAddress addr = delegate.getRemoteAddress();
			if (addr == null) {
				return false;
			}
			return span.remoteIpAndPort(addr.getAddress().getHostAddress(),
					addr.getPort());
		}

		@Override
		public String method() {
			return delegate.getMethodValue();
		}

		@Override
		public String path() {
			return delegate.getPath().toString();
		}

		@Override
		public String url() {
			return delegate.getURI().toString();
		}

		@Override
		public String header(String name) {
			return delegate.getHeaders().getFirst(name);
		}

	}

	static final class WrappedResponse extends HttpServerResponse {

		final ServerHttpResponse delegate;

		final String method;

		final String httpRoute;

		WrappedResponse(ServerHttpResponse resp, String method, String httpRoute) {
			this.delegate = resp;
			this.method = method;
			this.httpRoute = httpRoute;
		}

		@Override
		public String method() {
			return method;
		}

		@Override
		public String route() {
			return httpRoute;
		}

		@Override
		public ServerHttpResponse unwrap() {
			return delegate;
		}

		@Override
		public int statusCode() {
			return delegate.getStatusCode() != null ? delegate.getStatusCode().value()
					: 0;
		}

	}

}
