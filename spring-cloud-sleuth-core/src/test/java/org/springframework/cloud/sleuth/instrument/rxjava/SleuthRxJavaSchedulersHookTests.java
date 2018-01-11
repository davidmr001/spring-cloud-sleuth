package org.springframework.cloud.sleuth.instrument.rxjava;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import rx.functions.Action0;
import rx.plugins.RxJavaErrorHandler;
import rx.plugins.RxJavaObservableExecutionHook;
import rx.plugins.RxJavaPlugins;
import rx.plugins.RxJavaSchedulersHook;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.sleuth.TraceKeys;
import org.springframework.cloud.sleuth.util.ArrayListSpanReporter;

import static org.assertj.core.api.BDDAssertions.then;

/**
 *
 * @author Shivang Shah
 */
public class SleuthRxJavaSchedulersHookTests {

	List<String> threadsToIgnore = new ArrayList<>();
	TraceKeys traceKeys = new TraceKeys();
	ArrayListSpanReporter reporter = new ArrayListSpanReporter();
	Tracing tracing = Tracing.newBuilder()
			.currentTraceContext(CurrentTraceContext.Default.create())
			.spanReporter(this.reporter)
			.build();

	@After
	public void clean() {
		this.tracing.close();
		this.reporter.clear();
	}
	private static StringBuilder caller;

	@Before
	@After
	public void setup() {
		RxJavaPlugins.getInstance().reset();
		caller = new StringBuilder();
	}

	@Test
	public void should_not_override_existing_custom_hooks() {
		RxJavaPlugins.getInstance().registerErrorHandler(new MyRxJavaErrorHandler());
		RxJavaPlugins.getInstance().registerObservableExecutionHook(new MyRxJavaObservableExecutionHook());

		new SleuthRxJavaSchedulersHook(this.tracing, this.traceKeys, threadsToIgnore);

		then(RxJavaPlugins.getInstance().getErrorHandler()).isExactlyInstanceOf(MyRxJavaErrorHandler.class);
		then(RxJavaPlugins.getInstance().getObservableExecutionHook()).isExactlyInstanceOf(MyRxJavaObservableExecutionHook.class);
	}

	@Test
	public void should_wrap_delegates_action_in_wrapped_action_when_delegate_is_present_on_schedule() {
		RxJavaPlugins.getInstance().registerSchedulersHook(new MyRxJavaSchedulersHook());
		SleuthRxJavaSchedulersHook schedulersHook = new SleuthRxJavaSchedulersHook(
			this.tracing, this.traceKeys, threadsToIgnore);
		Action0 action = schedulersHook.onSchedule(() -> {
			caller = new StringBuilder("hello");
		});

		action.call();

		then(action).isInstanceOf(SleuthRxJavaSchedulersHook.TraceAction.class);
		then(caller.toString()).isEqualTo("called_from_schedulers_hook");
		then(this.reporter.getSpans()).isNotEmpty();
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void should_not_create_a_span_when_current_thread_should_be_ignored()
			throws ExecutionException, InterruptedException {
		String threadNameToIgnore = "^MyCustomThread.*$";
		RxJavaPlugins.getInstance().registerSchedulersHook(new MyRxJavaSchedulersHook());
		SleuthRxJavaSchedulersHook schedulersHook = new SleuthRxJavaSchedulersHook(
			this.tracing, this.traceKeys, Collections.singletonList(threadNameToIgnore));
		Future<Void> hello = executorService().submit((Callable<Void>) () -> {
			Action0 action = schedulersHook.onSchedule(() -> {
				caller = new StringBuilder("hello");
			});
			action.call();
			return null;
		});

		hello.get();

		then(this.reporter.getSpans()).isEmpty();
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	private ExecutorService executorService() {
		ThreadFactory threadFactory = r -> {
			Thread thread = new Thread(r);
			thread.setName("MyCustomThread10");
			return thread;
		};
		return Executors
				.newSingleThreadExecutor(threadFactory);
	}

	static class MyRxJavaObservableExecutionHook extends RxJavaObservableExecutionHook {
	}

	static class MyRxJavaSchedulersHook extends RxJavaSchedulersHook {

		@Override
		public Action0 onSchedule(Action0 action) {
			return () -> {
				caller = new StringBuilder("called_from_schedulers_hook");
			};
		}
	}

	static class MyRxJavaErrorHandler extends RxJavaErrorHandler {
	}
}
