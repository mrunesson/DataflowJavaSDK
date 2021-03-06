/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.junit.Assert.assertTrue;

import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.MergeResult;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.TriggerResult;
import com.google.cloud.dataflow.sdk.transforms.windowing.TriggerBuilder;
import com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn;
import com.google.cloud.dataflow.sdk.util.ActiveWindowSet.MergeCallback;
import com.google.cloud.dataflow.sdk.util.TimerInternals.TimerData;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy.AccumulationMode;
import com.google.cloud.dataflow.sdk.util.state.InMemoryStateInternals;
import com.google.cloud.dataflow.sdk.util.state.State;
import com.google.cloud.dataflow.sdk.util.state.StateInternals;
import com.google.cloud.dataflow.sdk.util.state.StateNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces.WindowAndTriggerNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces.WindowNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateTag;
import com.google.cloud.dataflow.sdk.util.state.WatermarkStateInternal;
import com.google.cloud.dataflow.sdk.values.TimestampedValue;
import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.joda.time.Instant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Test utility that runs a {@link Trigger}, using in-memory stub implementation to provide
 * the {@link StateInternals}.
 *
 * @param <W> The type of windows being used.
 */
public class TriggerTester<InputT, W extends BoundedWindow> {

  /**
   * A {@link TriggerTester} specialized to {@link Integer} values, so elements and timestamps
   * can be conflated. Today, triggers should not observed the element type, so this is the
   * only trigger tester that needs to be used.
   */
  public static class SimpleTriggerTester<W extends BoundedWindow>
      extends TriggerTester<Integer, W> {

    private SimpleTriggerTester(WindowingStrategy<?, W> wildcardStrategy) throws Exception {
      super(wildcardStrategy);
    }

    public void injectElements(int... values) throws Exception {
      List<TimestampedValue<Integer>> timestampedValues =
          Lists.newArrayListWithCapacity(values.length);
      for (int value : values) {
        timestampedValues.add(TimestampedValue.of(value, new Instant(value)));
      }
      injectElements(timestampedValues);
    }
  }

  private final TestInMemoryStateInternals stateInternals = new TestInMemoryStateInternals();
  private final TestTimerInternals timerInternals = new TestTimerInternals();
  private final TriggerContextFactory<W> contextFactory;

  private final WindowFn<Object, W> windowFn;
  private final ActiveWindowSet<W> activeWindows;
  private final List<Trigger.TriggerResult> resultSequence;
  private Trigger.TriggerResult latestResult;
  private Trigger.MergeResult latestMergeResult;

  /**
   * An {@link ExecutableTrigger} built from the {@link Trigger} or {@link TriggerBuilder}
   * under test.
   */
  private final ExecutableTrigger<W> executableTrigger;

  /**
   * A map from a window and trigger to whether that trigger is finished for the window.
   */
  private final Map<W, BitSet> finishedSets;

  public static <W extends BoundedWindow> SimpleTriggerTester<W> forTrigger(
      TriggerBuilder<W> trigger, WindowFn<?, W> windowFn) throws Exception {
    WindowingStrategy<?, W> strategy =
        WindowingStrategy.of(windowFn).withTrigger(trigger.buildTrigger())
        // Merging requires accumulation mode or early firings can break up a session.
        // Not currently an issue with the tester (because we never GC) but we don't want
        // mystery failures due to violating this need.
        .withMode(windowFn.isNonMerging()
            ? AccumulationMode.DISCARDING_FIRED_PANES
            : AccumulationMode.ACCUMULATING_FIRED_PANES);

    return new SimpleTriggerTester<>(strategy);
  }

  public static <InputT, W extends BoundedWindow> TriggerTester<Integer, W> forAdvancedTrigger(
      TriggerBuilder<W> trigger, WindowFn<InputT, W> windowFn) throws Exception {
    WindowingStrategy<?, W> strategy =
        WindowingStrategy.of(windowFn).withTrigger(trigger.buildTrigger())
        // Merging requires accumulation mode or early firings can break up a session.
        // Not currently an issue with the tester (because we never GC) but we don't want
        // mystery failures due to violating this need.
        .withMode(windowFn.isNonMerging()
            ? AccumulationMode.DISCARDING_FIRED_PANES
            : AccumulationMode.ACCUMULATING_FIRED_PANES);

    return new TriggerTester<>(strategy);
  }

  protected TriggerTester(WindowingStrategy<?, W> wildcardStrategy) throws Exception {
    @SuppressWarnings("unchecked")
    WindowingStrategy<Object, W> objectStrategy = (WindowingStrategy<Object, W>) wildcardStrategy;

    this.windowFn = objectStrategy.getWindowFn();
    this.executableTrigger = wildcardStrategy.getTrigger();
    this.resultSequence = new ArrayList<>();
    this.finishedSets = new HashMap<>();

    this.activeWindows =
        windowFn.isNonMerging()
            ? new NonMergingActiveWindowSet<W>()
            : new MergingActiveWindowSet<W>(windowFn, stateInternals);

    this.contextFactory =
        new TriggerContextFactory<>(objectStrategy, stateInternals, activeWindows);
  }

  /**
   * Returns the most recent {@link TriggerResult} from any invocation of the
   * {@link Trigger#onElement} or {@link Trigger#onTimer} methods
   * of the trigger under test.
   *
   * <p>Note that this is not window-aware, but will return the most recent
   * for any window. Tests should mostly be able to check
   * the latest result at an opportune moment.
   */
  public TriggerResult getLatestResult() {
    return latestResult;
  }

  /**
   * Returns the most recent {@link MergeResult} from any invocation of the
   * {@link Trigger#onMerge} of the trigger under test.
   *
   * <p>Note that this is not window-aware, but will return the most recent
   * of any merge result, not for any particular result window. Tests should generally
   * be able to check the latest merge result at an opportune moment.
   */
  public MergeResult getLatestMergeResult() {
    return latestMergeResult;
  }

  public void clearLatestMergeResult() {
    latestResult = null;
  }

  /**
   * Returns the full sequence of returned {@link TriggerResult TriggerResults} from
   * invocations of {@link Trigger#onElement} or {@link Trigger#onTimer} methods
   * of the trigger under test.
   */
  public List<Trigger.TriggerResult> getResultSequence() {
    return ImmutableList.copyOf(resultSequence);
  }

  /**
   * Clears the result sequence returned by {@link #getResultSequence}.
   */
  public void clearResultSequence() {
    resultSequence.clear();
  }

  /**
   * Instructs the trigger to clear its state for the given window.
   */
  public void clearState(W window) throws Exception {
    executableTrigger.invokeClear(contextFactory.base(window,
        new TestTimers(windowNamespace(window)), executableTrigger, getFinishedSet(window)));
  }

  /**
   * Asserts that the trigger has actually cleared all of its state for the given window. Since
   * the trigger under test is the root, this makes the assert for all triggers regardless
   * of their position in the trigger tree.
   */
  public void assertCleared(W window) {
    for (StateNamespace untypedNamespace : stateInternals.getNamespacesInUse()) {
      if (untypedNamespace instanceof WindowAndTriggerNamespace) {
        @SuppressWarnings("unchecked")
        WindowAndTriggerNamespace<W> namespace = (WindowAndTriggerNamespace<W>) untypedNamespace;
        if (namespace.getWindow().equals(window)) {
          Set<StateTag<?>> tagsInUse = stateInternals.getTagsInUse(namespace);
          assertTrue("Trigger has not cleared tags: " + tagsInUse, tagsInUse.isEmpty());
        }
      }
    }
  }

  /**
   * Returns {@code true} if the {@link Trigger} under test is finished for the given window.
   */
  public boolean isMarkedFinished(W window) {
    BitSet finishedSet = finishedSets.get(window);
    if (finishedSet == null) {
      return false;
    }

    return finishedSet.get(executableTrigger.getTriggerIndex());
  }

  private StateNamespace windowNamespace(W window) {
    return StateNamespaces.window(windowFn.windowCoder(), checkNotNull(window));
  }

  /**
   * Advance the input watermark to the specified time, firing any timers that should
   * fire. Then advance the output watermark as far as possible.
   */
  public void advanceInputWatermark(Instant newInputWatermark) throws Exception {
    timerInternals.advanceInputWatermark(newInputWatermark);
  }

  /** Advance the processing time to the specified time, firing any timers that should fire. */
  public void advanceProcessingTime(Instant newProcessingTime) throws Exception {
    timerInternals.advanceProcessingTime(newProcessingTime);
  }

  /**
   * Inject all the timestamped values (after passing through the window function) as if they
   * arrived in a single chunk of a bundle (or work-unit).
   */
  @SafeVarargs
  public final void injectElements(TimestampedValue<InputT>... values) throws Exception {
    injectElements(Arrays.asList(values));
  }

  public final void injectElements(Collection<TimestampedValue<InputT>> values) throws Exception {
    for (TimestampedValue<InputT> value : values) {
      WindowTracing.trace("TriggerTester.injectElements: {}", value);
    }

    List<WindowedValue<InputT>> windowedValues = Lists.newArrayListWithCapacity(values.size());

    for (TimestampedValue<InputT> input : values) {
      try {
        InputT value = input.getValue();
        Instant timestamp = input.getTimestamp();
        Collection<W> assignedWindows = windowFn.assignWindows(new TestAssignContext<W>(
            windowFn, value, timestamp, Arrays.asList(GlobalWindow.INSTANCE)));

        for (W window : assignedWindows) {
          activeWindows.addActive(window);

          // Today, triggers assume onTimer firing at the watermark time, whether or not they
          // explicitly set the timer themselves. So this tester must set it.
          timerInternals.setTimer(
              TimerData.of(windowNamespace(window), window.maxTimestamp(), TimeDomain.EVENT_TIME));
        }

        windowedValues.add(WindowedValue.of(value, timestamp, assignedWindows, PaneInfo.NO_FIRING));
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }

    for (WindowedValue<InputT> windowedValue : windowedValues) {
      for (BoundedWindow untypedWindow : windowedValue.getWindows()) {
        // SDK is responsible for type safety
        @SuppressWarnings("unchecked")
        W window = activeWindows.representative((W) untypedWindow);

        Trigger<W>.OnElementContext context = contextFactory.createOnElementContext(window,
            new TestTimers(windowNamespace(window)), windowedValue.getTimestamp(),
            executableTrigger, getFinishedSet(window));

        if (!context.trigger().isFinished()) {
          latestResult = executableTrigger.invokeElement(context);
          resultSequence.add(latestResult);
          if (latestResult.isFinish()) {
            context.trigger().setFinished(true);
          }
        }
      }
    }
  }

  /**
   * Invokes merge from the {@link WindowFn} a single time and passes the resulting merge
   * events on to the trigger under test. Does not persist the fact that merging happened,
   * since it is just to test the trigger's {@code OnMerge} method.
   */
  public final void mergeWindows() throws Exception {
    final Map<W, Collection<W>> windowToComponents = new HashMap<>();

    activeWindows.merge(new MergeCallback<W>() {
      @Override
      public void onMerge(Collection<W> toBeMerged, Collection<W> activeToBeMerged, W mergeResult)
          throws Exception {
        windowToComponents.put(mergeResult, toBeMerged);
        timerInternals.setTimer(TimerData.of(
            windowNamespace(mergeResult), mergeResult.maxTimestamp(), TimeDomain.EVENT_TIME));
      }
    });

    for (Map.Entry<W, Collection<W>> merged : windowToComponents.entrySet()) {
      W window = merged.getKey();
      Collection<W> oldWindows = merged.getValue();
      latestMergeResult = executableTrigger.invokeMerge(
          contextFactory.createOnMergeContext(window, new TestTimers(windowNamespace(window)),
              oldWindows, executableTrigger, getFinishedSet(window), finishedSets));
    }
  }

  private BitSet getFinishedSet(W window) {
    BitSet finishedSet = finishedSets.get(window);
    if (finishedSet == null) {
      finishedSet = new BitSet();
      finishedSets.put(window, finishedSet);
    }
    return finishedSet;
  }

  public void fireTimer(W window, Instant timestamp, TimeDomain domain) throws Exception {
    Trigger<W>.OnTimerContext context =
        contextFactory.createOnTimerContext(window, new TestTimers(windowNamespace(window)),
            executableTrigger, getFinishedSet(window), timestamp, domain);
    latestResult = executableTrigger.invokeTimer(context);
    resultSequence.add(latestResult);
    if (latestResult.isFinish()) {
      context.trigger().setFinished(true);
    }
  }

  /**
   * Simulate state.
   */
  private static class TestInMemoryStateInternals extends InMemoryStateInternals {

    public Set<StateTag<?>> getTagsInUse(StateNamespace namespace) {
      Set<StateTag<?>> inUse = new HashSet<>();
      for (Map.Entry<StateTag<?>, State> entry : inMemoryState.getTagsInUse(namespace).entrySet()) {
        if (!isEmptyForTesting(entry.getValue())) {
          inUse.add(entry.getKey());
        }
      }
      return inUse;
    }

    public Set<StateNamespace> getNamespacesInUse() {
      return inMemoryState.getNamespacesInUse();
    }

    /** Return the earliest output watermark hold in state, or null if none. */
    public Instant earliestWatermarkHold() {
      Instant minimum = null;
      for (State storage : inMemoryState.values()) {
        if (storage instanceof WatermarkStateInternal) {
          Instant hold = ((WatermarkStateInternal) storage).get().read();
          if (minimum == null || (hold != null && hold.isBefore(minimum))) {
            minimum = hold;
          }
        }
      }
      return minimum;
    }
  }

  private static class TestAssignContext<W extends BoundedWindow>
      extends WindowFn<Object, W>.AssignContext {
    private Object element;
    private Instant timestamp;
    private Collection<? extends BoundedWindow> windows;

    public TestAssignContext(WindowFn<Object, W> windowFn, Object element, Instant timestamp,
        Collection<? extends BoundedWindow> windows) {
      windowFn.super();
      this.element = element;
      this.timestamp = timestamp;
      this.windows = windows;
    }

    @Override
    public Object element() {
      return element;
    }

    @Override
    public Instant timestamp() {
      return timestamp;
    }

    @Override
    public Collection<? extends BoundedWindow> windows() {
      return windows;
    }
  }

  /**
   * Simulate the firing of timers and progression of input and output watermarks for a
   * single computation and key in a Windmill-like streaming environment. Similar to
   * {@link BatchTimerInternals}, but also tracks the output watermark.
   */
  private class TestTimerInternals implements TimerInternals {
    /** At most one timer per timestamp is kept. */
    private Set<TimerData> existingTimers = new HashSet<>();

    /** Pending input watermark timers, in timestamp order. */
    private PriorityQueue<TimerData> watermarkTimers = new PriorityQueue<>(11);

    /** Pending processing time timers, in timestamp order. */
    private PriorityQueue<TimerData> processingTimers = new PriorityQueue<>(11);

    /** Current input watermark. */
    @Nullable
    private Instant inputWatermarkTime = null;

    /** Current output watermark. */
    @Nullable
    private Instant outputWatermarkTime = null;

    /** Current processing time. */
    private Instant processingTime = BoundedWindow.TIMESTAMP_MIN_VALUE;

    private PriorityQueue<TimerData> queue(TimeDomain domain) {
      return TimeDomain.EVENT_TIME.equals(domain) ? watermarkTimers : processingTimers;
    }

    @Override
    public void setTimer(TimerData timer) {
      WindowTracing.trace("TestTimerInternals.setTimer: {}", timer);
      if (existingTimers.add(timer)) {
        queue(timer.getDomain()).add(timer);
      }
    }

    @Override
    public void deleteTimer(TimerData timer) {
      WindowTracing.trace("TestTimerInternals.deleteTimer: {}", timer);
      existingTimers.remove(timer);
      queue(timer.getDomain()).remove(timer);
    }

    @Override
    public Instant currentProcessingTime() {
      return processingTime;
    }

    @Override
    @Nullable
    public Instant currentInputWatermarkTime() {
      return inputWatermarkTime;
    }

    @Override
    @Nullable
    public Instant currentOutputWatermarkTime() {
      return outputWatermarkTime;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("watermarkTimers", watermarkTimers)
          .add("processingTimers", processingTime)
          .add("inputWatermarkTime", inputWatermarkTime)
          .add("outputWatermarkTime", outputWatermarkTime)
          .add("processingTime", processingTime)
          .toString();
    }

    public void advanceInputWatermark(Instant newInputWatermark) throws Exception {
      checkNotNull(newInputWatermark);
      checkState(inputWatermarkTime == null || !newInputWatermark.isBefore(inputWatermarkTime),
          "Cannot move input watermark time backwards from %s to %s", inputWatermarkTime,
          newInputWatermark);
      WindowTracing.trace("TestTimerInternals.advanceInputWatermark: from {} to {}",
          inputWatermarkTime, newInputWatermark);
      inputWatermarkTime = newInputWatermark;
      advanceAndFire(newInputWatermark, TimeDomain.EVENT_TIME);

      Instant hold = stateInternals.earliestWatermarkHold();
      if (hold == null) {
        WindowTracing.trace("TestTimerInternals.advanceInputWatermark: no holds, "
            + "so output watermark = input watermark");
        hold = inputWatermarkTime;
      }
      advanceOutputWatermark(hold);
    }

    private void advanceOutputWatermark(Instant newOutputWatermark) throws Exception {
      checkNotNull(newOutputWatermark);
      checkNotNull(inputWatermarkTime);
      if (newOutputWatermark.isAfter(inputWatermarkTime)) {
        WindowTracing.trace(
            "TestTimerInternals.advanceOutputWatermark: clipping output watermark from {} to {}",
            newOutputWatermark, inputWatermarkTime);
        newOutputWatermark = inputWatermarkTime;
      }
      checkState(outputWatermarkTime == null || !newOutputWatermark.isBefore(outputWatermarkTime),
          "Cannot move output watermark time backwards from %s to %s", outputWatermarkTime,
          newOutputWatermark);
      WindowTracing.trace("TestTimerInternals.advanceOutputWatermark: from {} to {}",
          outputWatermarkTime, newOutputWatermark);
      outputWatermarkTime = newOutputWatermark;
    }

    public void advanceProcessingTime(Instant newProcessingTime) throws Exception {
      checkState(!newProcessingTime.isBefore(processingTime),
          "Cannot move processing time backwards from %s to %s", processingTime, newProcessingTime);
      WindowTracing.trace("TestTimerInternals.advanceProcessingTime: from {} to {}", processingTime,
          newProcessingTime);
      processingTime = newProcessingTime;
      advanceAndFire(newProcessingTime, TimeDomain.PROCESSING_TIME);
    }

    private void advanceAndFire(Instant currentTime, TimeDomain domain) throws Exception {
      PriorityQueue<TimerData> queue = queue(domain);

      TimerData nextTimer = queue.peek();
      while (nextTimer != null && currentTime.isAfter(nextTimer.getTimestamp())) {
        // Timers fire when the current time progresses past the timer time.
        WindowTracing.trace(
            "TestTimerInternals.advanceAndFire: firing {} at {}", nextTimer, currentTime);
        // Remove before firing, so that if the trigger adds another identical
        // timer we don't remove it.
        queue.remove();

        @SuppressWarnings("unchecked")
        WindowNamespace<W> windowNamespace = (WindowNamespace<W>) nextTimer.getNamespace();
        W window = windowNamespace.getWindow();

        if (activeWindows.isActive(window)) {
          fireTimer(window, nextTimer.getTimestamp(), nextTimer.getDomain());
        }

        nextTimer = queue.peek();
      }
    }
  }

  private class TestTimers implements ReduceFn.Timers {
    private final StateNamespace namespace;

    public TestTimers(StateNamespace namespace) {
      checkArgument(namespace instanceof WindowNamespace);
      this.namespace = namespace;
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain timeDomain) {
      timerInternals.setTimer(TimerData.of(namespace, timestamp, timeDomain));
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain timeDomain) {
      timerInternals.deleteTimer(TimerData.of(namespace, timestamp, timeDomain));
    }

    @Override
    public Instant currentProcessingTime() {
      return timerInternals.currentProcessingTime();
    }
  }
}
