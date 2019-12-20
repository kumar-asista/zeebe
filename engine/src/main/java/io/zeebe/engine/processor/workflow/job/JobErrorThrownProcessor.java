/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.job;

import io.zeebe.engine.processor.KeyGenerator;
import io.zeebe.engine.processor.TypedRecord;
import io.zeebe.engine.processor.TypedRecordProcessor;
import io.zeebe.engine.processor.TypedResponseWriter;
import io.zeebe.engine.processor.TypedStreamWriter;
import io.zeebe.engine.processor.workflow.EventHandle;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableActivity;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableCatchEvent;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableWorkflow;
import io.zeebe.engine.state.deployment.WorkflowState;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.instance.ElementInstanceState;
import io.zeebe.engine.state.instance.EventScopeInstanceState;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class JobErrorThrownProcessor implements TypedRecordProcessor<JobRecord> {

  private static final DirectBuffer NO_VARIABLES = new UnsafeBuffer();

  private final CatchEventTuple catchEventTuple = new CatchEventTuple();

  private final WorkflowState workflowState;
  private final ElementInstanceState elementInstanceState;
  private final EventScopeInstanceState eventScopeInstanceState;
  private final EventHandle eventHandle;

  public JobErrorThrownProcessor(
      final WorkflowState workflowState, final KeyGenerator keyGenerator) {
    this.workflowState = workflowState;
    elementInstanceState = workflowState.getElementInstanceState();
    eventScopeInstanceState = workflowState.getEventScopeInstanceState();

    eventHandle = new EventHandle(keyGenerator, eventScopeInstanceState);
  }

  @Override
  public void processRecord(
      final TypedRecord<JobRecord> record,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter) {

    final var job = record.getValue();
    final var serviceTaskInstanceKey = job.getElementInstanceKey();
    final var serviceTaskInstance = elementInstanceState.getInstance(serviceTaskInstanceKey);

    if (serviceTaskInstance != null && serviceTaskInstance.isActive()) {

      final var errorCode = job.getErrorCodeBuffer();
      final var workflow = getWorkflow(job.getWorkflowKey());

      final var foundCatchEvent = findCatchEvent(workflow, serviceTaskInstance, errorCode);
      if (foundCatchEvent != null) {

        eventHandle.triggerEvent(
            streamWriter, foundCatchEvent.instance, foundCatchEvent.catchEvent, NO_VARIABLES);
      }

      // remove job reference to not cancel it while terminating the task
      serviceTaskInstance.setJobKey(-1L);
      elementInstanceState.updateInstance(serviceTaskInstance);
    }
  }

  private ExecutableWorkflow getWorkflow(final long workflowKey) {

    final var deployedWorkflow = workflowState.getWorkflowByKey(workflowKey);
    if (deployedWorkflow == null) {
      throw new IllegalStateException(
          String.format(
              "Expected workflow with key '%d' to be deployed but not found", workflowKey));
    }

    return deployedWorkflow.getWorkflow();
  }

  private CatchEventTuple findCatchEvent(
      final ExecutableWorkflow workflow,
      final ElementInstance instance,
      final DirectBuffer errorCode) {

    // assuming that error events are used rarely
    // - just walk through the scope hierarchy and look for a matching boundary event
    final var elementId = instance.getValue().getElementIdBuffer();
    final var activity = workflow.getElementById(elementId, ExecutableActivity.class);

    for (final ExecutableCatchEvent catchEvent : activity.getEvents()) {
      if (hasErrorCode(catchEvent, errorCode)) {

        catchEventTuple.instance = instance;
        catchEventTuple.catchEvent = catchEvent;
        return catchEventTuple;
      }
    }

    // find catch event in parent scopes
    final var instanceParentKey = instance.getParentKey();
    if (instanceParentKey > 0) {
      final var parentInstance = elementInstanceState.getInstance(instanceParentKey);

      if (parentInstance != null && parentInstance.isActive()) {
        return findCatchEvent(workflow, parentInstance, errorCode);
      }
    }

    // no matching catch event found
    return null;
  }

  private boolean hasErrorCode(
      final ExecutableCatchEvent catchEvent, final DirectBuffer errorCode) {
    return catchEvent.isError() && catchEvent.getError().getErrorCode().equals(errorCode);
  }

  private static class CatchEventTuple {
    private ExecutableCatchEvent catchEvent;
    private ElementInstance instance;
  }
}
