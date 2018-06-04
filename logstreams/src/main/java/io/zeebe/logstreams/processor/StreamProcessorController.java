/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.logstreams.processor;

import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.log.LogStreamReader;
import io.zeebe.logstreams.log.LogStreamWriter;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.spi.ReadableSnapshot;
import io.zeebe.logstreams.spi.SnapshotStorage;
import io.zeebe.logstreams.spi.SnapshotWriter;
import io.zeebe.util.LangUtil;
import io.zeebe.util.metrics.MetricsManager;
import io.zeebe.util.sched.*;
import io.zeebe.util.sched.ActorTask.ActorLifecyclePhase;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamProcessorController extends Actor
{
    private static final Logger LOG = Loggers.LOGSTREAMS_LOGGER;

    private static final String ERROR_MESSAGE_RECOVER_FROM_SNAPSHOT_FAILED = "Stream processor '%s' failed to recover. Cannot find event with the snapshot position in target log stream.";
    private static final String ERROR_MESSAGE_REPROCESSING_NO_SOURCE_EVENT = "Stream processor '%s' failed to reprocess. Cannot find source event position: %d";
    private static final String ERROR_MESSAGE_REPROCESSING_FAILED = "Stream processor '%s' failed to reprocess event: %s";
    private static final String ERROR_MESSAGE_PROCESSING_FAILED = "Stream processor '{}' failed to process event. It stop processing further events.";

    private final StreamProcessor streamProcessor;
    private final StreamProcessorContext streamProcessorContext;

    private final LogStreamReader logStreamReader;
    private final LogStreamWriter logStreamWriter;

    private final SnapshotStorage snapshotStorage;
    private final Duration snapshotPeriod;

    private final ActorScheduler actorScheduler;
    private final AtomicBoolean isOpened = new AtomicBoolean(false);
    private final AtomicBoolean isFailed = new AtomicBoolean(false);

    private final EventFilter eventFilter;
    private final boolean isReadOnlyProcessor;

    private final EventLifecycleContext eventLifecycleContext = new EventLifecycleContext();

    private final Runnable readNextEvent = this::readNextEvent;

    private long snapshotPosition = -1L;
    private long lastSourceEventPosition = -1L;
    private long eventPosition = -1L;
    private long lastSuccessfulProcessedEventPosition = -1L;
    private long lastWrittenEventPosition = -1L;

    private LoggedEvent currentEvent;
    private EventProcessor eventProcessor;
    private ActorCondition onCommitPositionUpdatedCondition;

    private boolean suspended = false;

    private StreamProcessorMetrics metrics;

    public StreamProcessorController(StreamProcessorContext context)
    {
        this.streamProcessorContext = context;
        this.streamProcessorContext.setActorControl(actor);

        this.streamProcessorContext.setSuspendRunnable(this::suspend);
        this.streamProcessorContext.setResumeRunnable(this::resume);

        this.actorScheduler = context.getActorScheduler();
        this.streamProcessor = context.getStreamProcessor();
        this.logStreamReader = context.getLogStreamReader();
        this.logStreamWriter = context.getLogStreamWriter();
        this.snapshotStorage = context.getSnapshotStorage();
        this.snapshotPeriod = context.getSnapshotPeriod();
        this.eventFilter = context.getEventFilter();
        this.isReadOnlyProcessor = context.isReadOnlyProcessor();
    }

    @Override
    public String getName()
    {
        return streamProcessorContext.getName();
    }

    public ActorFuture<Void> openAsync()
    {
        if (isOpened.compareAndSet(false, true))
        {
            return actorScheduler.submitActor(this, true);
        }
        else
        {
            return CompletableActorFuture.completed(null);
        }
    }

    @Override
    protected void onActorStarting()
    {
        streamProcessor.getStateResource().reset();

        final LogStream logStream = streamProcessorContext.getLogStream();

        final MetricsManager metricsManager = actorScheduler.getMetricsManager();
        final String topicName = logStream.getTopicName().getStringWithoutLengthUtf8(0, logStream.getTopicName().capacity());
        final String partitionId = String.valueOf(logStream.getPartitionId());
        final String processorName = getName();

        metrics = new StreamProcessorMetrics(metricsManager, processorName, topicName, partitionId);

        logStreamReader.wrap(logStream);
        logStreamWriter.wrap(logStream);

        try
        {
            snapshotPosition = recoverFromSnapshot();
            lastSourceEventPosition = seekToLastSourceEvent();

            streamProcessor.onOpen(streamProcessorContext);
        }
        catch (Exception e)
        {
            onFailure();
            LangUtil.rethrowUnchecked(e);
        }
    }

    @Override
    protected void onActorStarted()
    {
        try
        {
            if (lastSourceEventPosition > snapshotPosition)
            {
                reprocessNextEvent();
            }
            else
            {
                onRecovered();
            }
        }
        catch (Exception e)
        {
            onFailure();
            LangUtil.rethrowUnchecked(e);
        }
    }

    private long recoverFromSnapshot() throws Exception
    {
        long snapshotPosition = -1L;

        final ReadableSnapshot lastSnapshot = snapshotStorage.getLastSnapshot(streamProcessorContext.getName());
        if (lastSnapshot != null)
        {
            // recover last snapshot
            lastSnapshot.recoverFromSnapshot(streamProcessor.getStateResource());

            // read the last event from snapshot
            snapshotPosition = lastSnapshot.getPosition();
            final boolean found = logStreamReader.seek(snapshotPosition);

            if (found && logStreamReader.hasNext())
            {
                // resume the next position on source log stream to continue from
                final long sourceEventPosition = snapshotPosition;
                logStreamReader.seek(sourceEventPosition + 1);
            }
            else
            {
                throw new IllegalStateException(String.format(ERROR_MESSAGE_RECOVER_FROM_SNAPSHOT_FAILED, getName()));
            }
        }
        return snapshotPosition;
    }

    private long seekToLastSourceEvent()
    {
        long lastSourceEventPosition = -1L;

        if (!isReadOnlyProcessor && logStreamReader.hasNext())
        {
            lastSourceEventPosition = snapshotPosition;
            while (logStreamReader.hasNext())
            {
                final LoggedEvent newEvent = logStreamReader.next();

                // ignore events from other producers
                if (newEvent.getProducerId() == streamProcessorContext.getId())
                {
                    final long sourceEventPosition = newEvent.getSourceEventPosition();
                    if (sourceEventPosition > 0 && sourceEventPosition > lastSourceEventPosition)
                    {
                        lastSourceEventPosition = sourceEventPosition;
                    }
                }
            }

            // reset reader
            logStreamReader.seek(snapshotPosition + 1);
        }
        return lastSourceEventPosition;
    }

    private void reprocessNextEvent()
    {
        try
        {
            if (logStreamReader.hasNext())
            {
                currentEvent = logStreamReader.next();
                if (currentEvent.getPosition() > lastSourceEventPosition)
                {
                    throw new IllegalStateException(String.format(ERROR_MESSAGE_REPROCESSING_NO_SOURCE_EVENT, getName(), lastSourceEventPosition));
                }

                reprocessEvent(currentEvent);
            }
            else
            {
                throw new IllegalStateException(String.format(ERROR_MESSAGE_REPROCESSING_NO_SOURCE_EVENT, getName(), lastSourceEventPosition));
            }
        }
        catch (RuntimeException e)
        {
            onFailure();
            throw e;
        }
    }

    private void reprocessEvent(LoggedEvent currentEvent)
    {
        if (eventFilter == null || eventFilter.applies(currentEvent))
        {
            try
            {
                final EventProcessor eventProcessor = streamProcessor.onEvent(currentEvent);

                if (eventProcessor != null)
                {
                    // don't execute side effects or write events
                    eventLifecycleContext.reset();
                    eventProcessor.processEvent(eventLifecycleContext);

                    if (eventLifecycleContext.hasFuture())
                    {
                        actor.runOnCompletion(eventLifecycleContext.getFuture(), (res, err) ->
                        {
                            if (err == null)
                            {
                                eventProcessor.updateState();
                                onRecordReprocessed(currentEvent);
                            }
                            else
                            {
                                LOG.error("Exception during async reprocessing", err);
                                onFailure();
                            }
                        });
                    }
                    else
                    {
                        eventProcessor.updateState();
                        onRecordReprocessed(currentEvent);
                    }
                }
                else
                {
                    onRecordReprocessed(currentEvent);
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException(String.format(ERROR_MESSAGE_REPROCESSING_FAILED, getName(), currentEvent), e);
            }
        }
        else
        {
            onRecordReprocessed(currentEvent);
        }
    }

    private void onRecordReprocessed(LoggedEvent currentEvent)
    {
        if (currentEvent.getPosition() == lastSourceEventPosition)
        {
            onRecovered();
        }
        else
        {
            actor.submit(this::reprocessNextEvent);
        }
    }

    private void onRecovered()
    {
        onCommitPositionUpdatedCondition = actor.onCondition(getName() + "-on-commit-position-updated", readNextEvent);
        streamProcessorContext.logStream.registerOnCommitPositionUpdatedCondition(onCommitPositionUpdatedCondition);

        actor.runAtFixedRate(snapshotPeriod, this::createSnapshot);

        // start reading
        streamProcessor.onRecovered();
        actor.submit(readNextEvent);
    }

    private void readNextEvent()
    {
        if (isOpened() && !isSuspended() && logStreamReader.hasNext() && eventProcessor == null)
        {
            currentEvent = logStreamReader.next();

            if (eventFilter == null || eventFilter.applies(currentEvent))
            {
                processEvent(currentEvent);
            }
            else
            {
                // continue with the next event
                actor.submit(readNextEvent);

                metrics.incrementEventsSkippedCount();
            }
        }
    }

    private void processEvent(LoggedEvent event)
    {
        eventProcessor = streamProcessor.onEvent(event);

        if (eventProcessor != null)
        {
            try
            {
                metrics.incrementEventsProcessedCount();

                eventLifecycleContext.reset();
                eventProcessor.processEvent(eventLifecycleContext);

                if (eventLifecycleContext.hasFuture())
                {
                    actor.runOnCompletion(eventLifecycleContext.getFuture(), (res, err) ->
                    {
                        if (err != null)
                        {
                            LOG.error(ERROR_MESSAGE_PROCESSING_FAILED, getName(), err);
                            onFailure();
                        }
                        else
                        {
                            actor.runUntilDone(this::executeSideEffects);
                        }
                    });
                }
                else
                {
                    actor.runUntilDone(this::executeSideEffects);
                }
            }
            catch (Exception e)
            {
                LOG.error(ERROR_MESSAGE_PROCESSING_FAILED, getName(), e);
                onFailure();
            }
        }
        else
        {
            // continue with the next event
            actor.submit(readNextEvent);

            metrics.incrementEventsSkippedCount();
        }
    }

    private void executeSideEffects()
    {
        try
        {
            final boolean success = eventProcessor.executeSideEffects();
            if (success)
            {
                actor.done();

                actor.runUntilDone(this::writeEvent);
            }
            else if (isOpened())
            {
                // try again
                actor.yield();
            }
            else
            {
                actor.done();
            }
        }
        catch (Exception e)
        {
            actor.done();

            LOG.error(ERROR_MESSAGE_PROCESSING_FAILED, getName(), e);
            onFailure();
        }
    }

    private void writeEvent()
    {
        try
        {
            logStreamWriter
                .producerId(streamProcessorContext.getId())
                .sourceRecordPosition(currentEvent.getPosition());

            eventPosition = eventProcessor.writeEvent(logStreamWriter);

            if (eventPosition >= 0)
            {
                actor.done();

                metrics.incrementEventsWrittenCount();

                updateState();
            }
            else if (isOpened())
            {
                // try again
                actor.yield();
            }
            else
            {
                actor.done();
            }
        }
        catch (Exception e)
        {
            actor.done();

            LOG.error(ERROR_MESSAGE_PROCESSING_FAILED, getName(), e);
            onFailure();
        }
    }

    private void updateState()
    {
        try
        {
            eventProcessor.updateState();

            lastSuccessfulProcessedEventPosition = currentEvent.getPosition();

            final boolean hasWrittenEvent = eventPosition > 0;
            if (hasWrittenEvent)
            {
                lastWrittenEventPosition = eventPosition;
            }

            // continue with next event
            eventProcessor = null;
            actor.submit(readNextEvent);
        }
        catch (Exception e)
        {
            LOG.error(ERROR_MESSAGE_PROCESSING_FAILED, getName(), e);
            onFailure();
        }
    }

    private void createSnapshot()
    {
        if (actor.getLifecyclePhase() == ActorLifecyclePhase.STARTED)
        {
            // run as io-bound actor while writing snapshot
            actor.setSchedulingHints(SchedulingHints.ioBound((short) 0));
            actor.submit(this::doCreateSnapshot);
        }
        else
        {
            doCreateSnapshot();
        }
    }

    private void doCreateSnapshot()
    {
        if (currentEvent != null)
        {
            final long commitPosition = streamProcessorContext.getLogStream().getCommitPosition();

            final boolean snapshotAlreadyPresent = lastSuccessfulProcessedEventPosition <= snapshotPosition;

            if (!snapshotAlreadyPresent)
            {
                // ensure that the last written event was committed
                if (commitPosition >= lastWrittenEventPosition)
                {
                    writeSnapshot(lastSuccessfulProcessedEventPosition);
                }
            }
        }

        // re-rest to cpu bound
        actor.setSchedulingHints(SchedulingHints.cpuBound(ActorPriority.REGULAR));
    }

    private void writeSnapshot(final long eventPosition)
    {
        SnapshotWriter snapshotWriter = null;
        try
        {
            final long start = System.currentTimeMillis();
            final String name = streamProcessorContext.getName();
            LOG.info("Write snapshot for stream processor {} at event position {}.", name, eventPosition);

            snapshotWriter = snapshotStorage.createSnapshot(name, eventPosition);

            final long snapshotSize = snapshotWriter.writeSnapshot(streamProcessor.getStateResource());
            snapshotWriter.commit();

            final long snapshotCreationTime = System.currentTimeMillis() - start;
            LOG.info("Creation of snapshot {} took {} ms.", name, snapshotCreationTime);

            metrics.recordSnapshotSize(snapshotSize);
            metrics.recordSnapshotCreationTime(snapshotCreationTime);

            snapshotPosition = eventPosition;
        }
        catch (Exception e)
        {
            LOG.error("Stream processor '{}' failed. Can not write snapshot.", getName(), e);

            if (snapshotWriter != null)
            {
                snapshotWriter.abort();
            }
        }
    }

    public ActorFuture<Void> closeAsync()
    {
        if (isOpened.compareAndSet(true, false))
        {
            return actor.close();
        }
        else
        {
            return CompletableActorFuture.completed(null);
        }
    }

    @Override
    protected void onActorClosing()
    {
        metrics.close();

        if (!isFailed())
        {
            createSnapshot();
            streamProcessor.onClose();
        }

        streamProcessorContext.getLogStreamReader().close();

        streamProcessorContext.logStream.removeOnCommitPositionUpdatedCondition(onCommitPositionUpdatedCondition);
        onCommitPositionUpdatedCondition = null;
    }

    private void onFailure()
    {
        if (isFailed.compareAndSet(false, true))
        {
            isOpened.set(false);

            actor.close();
        }
    }

    public boolean isOpened()
    {
        return isOpened.get();
    }

    public boolean isFailed()
    {
        return isFailed.get();
    }

    public boolean isSuspended()
    {
        return suspended;
    }

    private void suspend()
    {
        suspended = true;
    }

    private void resume()
    {
        suspended = false;
        actor.submit(readNextEvent);
    }
}
