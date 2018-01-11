/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.documentation;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.sampler.Sampler;
import org.assertj.core.api.BDDAssertions;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.sleuth.DefaultSpanNamer;
import org.springframework.cloud.sleuth.ErrorParser;
import org.springframework.cloud.sleuth.ExceptionMessageErrorParser;
import org.springframework.cloud.sleuth.SpanName;
import org.springframework.cloud.sleuth.SpanNamer;
import org.springframework.cloud.sleuth.instrument.async.TraceCallable;
import org.springframework.cloud.sleuth.instrument.async.TraceRunnable;
import org.springframework.cloud.sleuth.util.ArrayListSpanReporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * Test class to be embedded in the
 * {@code docs/src/main/asciidoc/spring-cloud-sleuth.adoc} file
 *
 * @author Marcin Grzejszczak
 */
public class SpringCloudSleuthDocTests {

	ArrayListSpanReporter reporter = new ArrayListSpanReporter();
	Tracing tracing = Tracing.newBuilder()
			.currentTraceContext(CurrentTraceContext.Default.create())
			.sampler(Sampler.ALWAYS_SAMPLE)
			.spanReporter(this.reporter)
			.build();
	
	@Before
	public void setup() {
		this.reporter.clear();
	}

	@Configuration
	public class SamplingConfiguration {
		// tag::always_sampler[]
		@Bean
		public Sampler defaultSampler() {
			return Sampler.ALWAYS_SAMPLE;
		}
		// end::always_sampler[]
	}

	// tag::span_name_annotation[]
	@SpanName("calculateTax")
	class TaxCountingRunnable implements Runnable {

		@Override public void run() {
			// perform logic
		}
	}
	// end::span_name_annotation[]

	@Test
	public void should_set_runnable_name_to_annotated_value()
			throws ExecutionException, InterruptedException {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		SpanNamer spanNamer = new DefaultSpanNamer();
		ErrorParser errorParser = new ExceptionMessageErrorParser();

		// tag::span_name_annotated_runnable_execution[]
		Runnable runnable = new TraceRunnable(tracing, spanNamer, errorParser, 
				new TaxCountingRunnable());
		Future<?> future = executorService.submit(runnable);
		// ... some additional logic ...
		future.get();
		// end::span_name_annotated_runnable_execution[]

		List<zipkin2.Span> spans = this.reporter.getSpans();
		then(spans).hasSize(1);
		then(spans.get(0).name())
				.isEqualTo("calculatetax");
	}

	@Test
	public void should_set_runnable_name_to_to_string_value()
			throws ExecutionException, InterruptedException {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		SpanNamer spanNamer = new DefaultSpanNamer();
		ErrorParser errorParser = new ExceptionMessageErrorParser();

		// tag::span_name_to_string_runnable_execution[]
		Runnable runnable = new TraceRunnable(tracing, spanNamer, errorParser, new Runnable() {
			@Override public void run() {
				// perform logic
			}

			@Override public String toString() {
				return "calculateTax";
			}
		});
		Future<?> future = executorService.submit(runnable);
		// ... some additional logic ...
		future.get();
		// end::span_name_to_string_runnable_execution[]

		List<zipkin2.Span> spans = this.reporter.getSpans();
		then(spans).hasSize(1);
		then(spans.get(0).name())
				.isEqualTo("calculatetax");
		executorService.shutdown();
	}

	@Test
	public void should_create_a_span_with_tracer() {
		String taxValue = "10";

		// tag::manual_span_creation[]
		// Start a span. If there was a span present in this thread it will become
		// the `newSpan`'s parent.
		Span newSpan = this.tracing.tracer().nextSpan().name("calculateTax");
		try (Tracer.SpanInScope ws = this.tracing.tracer().withSpanInScope(newSpan.start())) {
			// ...
			// You can tag a span
			newSpan.tag("taxValue", taxValue);
			// ...
			// You can log an event on a span
			newSpan.annotate("taxCalculated");
		} finally {
			// Once done remember to close the span. This will allow collecting
			// the span to send it to Zipkin
			newSpan.finish();
		}
		// end::manual_span_creation[]

		List<zipkin2.Span> spans = this.reporter.getSpans();
		then(spans).hasSize(1);
		then(spans.get(0).name())
				.isEqualTo("calculatetax");
		then(spans.get(0).tags())
				.containsEntry("taxValue", "10");
		then(spans.get(0).annotations()).hasSize(1);
	}

	@Test
	public void should_continue_a_span_with_tracer() throws Exception {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		String taxValue = "10";
		Span newSpan = this.tracing.tracer().nextSpan().name("calculateTax");
		try (Tracer.SpanInScope ws = this.tracing.tracer().withSpanInScope(newSpan.start())) {
			executorService.submit(() -> {
						// tag::manual_span_continuation[]
						// let's assume that we're in a thread Y and we've received
						// the `initialSpan` from thread X
						Span continuedSpan = this.tracing.tracer().joinSpan(newSpan.context());
						try {
							// ...
							// You can tag a span
							continuedSpan.tag("taxValue", taxValue);
							// ...
							// You can log an event on a span
							continuedSpan.annotate("taxCalculated");
						} finally {
							// Once done remember to detach the span. That way you'll
							// safely remove it from the current thread without closing it
							continuedSpan.flush();
						}
						// end::manual_span_continuation[]
					}
			).get();
		} finally {
			newSpan.finish();
		}

		List<zipkin2.Span> spans = this.reporter.getSpans();
		BDDAssertions.then(spans).hasSize(1);
		BDDAssertions.then(spans.get(0).name())
				.isEqualTo("calculatetax");
		BDDAssertions.then(spans.get(0).tags())
				.containsEntry("taxValue", "10");
		BDDAssertions.then(spans.get(0).annotations()).hasSize(1);
		executorService.shutdown();
	}

	@Test
	public void should_start_a_span_with_explicit_parent() throws Exception {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		String commissionValue = "10";
		Span initialSpan = this.tracing.tracer().nextSpan().name("calculateTax").start();

		executorService.submit(() -> {
					// tag::manual_span_joining[]
					// let's assume that we're in a thread Y and we've received
					// the `initialSpan` from thread X. `initialSpan` will be the parent
					// of the `newSpan`
					Span newSpan = null;
					try (Tracer.SpanInScope ws = this.tracing.tracer().withSpanInScope(initialSpan)) {
						newSpan = this.tracing.tracer().nextSpan().name("calculateCommission");
						// ...
						// You can tag a span
						newSpan.tag("commissionValue", commissionValue);
						// ...
						// You can log an event on a span
						newSpan.annotate("commissionCalculated");
					} finally {
						// Once done remember to close the span. This will allow collecting
						// the span to send it to Zipkin. The tags and events set on the
						// newSpan will not be present on the parent
						if (newSpan != null) {
							newSpan.finish();
						}
					}
					// end::manual_span_joining[]
				}
		).get();

		List<zipkin2.Span> spans = this.reporter.getSpans();
		Optional<zipkin2.Span> calculateTax = spans.stream()
				.filter(span -> span.name().equals("calculatecommission")).findFirst();
		BDDAssertions.then(calculateTax).isPresent();
		BDDAssertions.then(calculateTax.get().tags())
				.containsEntry("commissionValue", "10");
		BDDAssertions.then(calculateTax.get().annotations()).hasSize(1);
		executorService.shutdown();
	}

	@Test
	public void should_wrap_runnable_in_its_sleuth_representative() {
		SpanNamer spanNamer = new DefaultSpanNamer();
		ErrorParser errorParser = new ExceptionMessageErrorParser();
		// tag::trace_runnable[]
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				// do some work
			}

			@Override
			public String toString() {
				return "spanNameFromToStringMethod";
			}
		};
		// Manual `TraceRunnable` creation with explicit "calculateTax" Span name
		Runnable traceRunnable = new TraceRunnable(tracing, spanNamer, errorParser,
				runnable, "calculateTax");
		// Wrapping `Runnable` with `Tracing`. That way the current span will be available
		// in the thread of `Runnable`
		Runnable traceRunnableFromTracer = tracing.currentTraceContext().wrap(runnable);
		// end::trace_runnable[]

		then(traceRunnable).isExactlyInstanceOf(TraceRunnable.class);
	}

	@Test
	public void should_wrap_callable_in_its_sleuth_representative() {
		SpanNamer spanNamer = new DefaultSpanNamer();
		ErrorParser errorParser = new ExceptionMessageErrorParser();
		// tag::trace_callable[]
		Callable<String> callable = new Callable<String>() {
			@Override
			public String call() throws Exception {
				return someLogic();
			}

			@Override
			public String toString() {
				return "spanNameFromToStringMethod";
			}
		};
		// Manual `TraceCallable` creation with explicit "calculateTax" Span name
		Callable<String> traceCallable = new TraceCallable<>(tracing, spanNamer, errorParser,
				callable, "calculateTax");
		// Wrapping `Callable` with `Tracing`. That way the current span will be available
		// in the thread of `Callable`
		Callable<String> traceCallableFromTracer = tracing.currentTraceContext().wrap(callable);
		// end::trace_callable[]
	}

	private String someLogic() {
		return "some logic";
	}
}
