/**************************************************************************************
 * Copyright (C) 2006-2015 EsperTech Inc. All rights reserved.                        *
 * http://www.espertech.com/esper                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.named;

import com.espertech.esper.client.EventType;
import com.espertech.esper.core.context.util.AgentInstanceContext;
import com.espertech.esper.core.context.util.ContextDescriptor;
import com.espertech.esper.core.service.StatementResultService;
import com.espertech.esper.epl.expression.core.ExprValidationException;
import com.espertech.esper.epl.lookup.EventTableIndexMetadata;
import com.espertech.esper.epl.lookup.IndexMultiKey;
import com.espertech.esper.epl.lookup.IndexedPropDesc;
import com.espertech.esper.epl.metric.MetricReportingService;
import com.espertech.esper.epl.metric.StatementMetricHandle;
import com.espertech.esper.epl.table.mgmt.TableMetadata;
import com.espertech.esper.event.vaevent.ValueAddEventProcessor;

import java.util.*;

/**
 * An instance of this class is associated with a specific named window. The processor
 * provides the views to create-window, on-delete statements and statements selecting from a named window.
 */
public class NamedWindowProcessor
{
    private final String namedWindowName;
    private final NamedWindowTailView tailView;
    private final NamedWindowRootView rootView;
    private final String contextName;
    private final boolean singleInstanceContext;
    private final EventType eventType;
    private final String eplExpression;
    private final String statementName;
    private final boolean isEnableSubqueryIndexShare;
    private final boolean isVirtualDataWindow;
    private final StatementMetricHandle statementMetricHandle;
    private final Set<String> optionalUniqueKeyProps;
    private final String eventTypeAsName;
    private final EventTableIndexMetadata eventTableIndexMetadataRepo = new EventTableIndexMetadata();

    private final Map<Integer, NamedWindowProcessorInstance> instances = new HashMap<Integer, NamedWindowProcessorInstance>();
    private NamedWindowProcessorInstance instanceNoContext;

    /**
     * Ctor.
     * @param namedWindowService service for dispatching results
     * @param eventType the type of event held by the named window
     * @param statementResultService for coordinating on whether insert and remove stream events should be posted
     * @param revisionProcessor for revision processing
     * @param eplExpression epl expression
     * @param statementName statement name
     * @param isPrioritized if the engine is running with prioritized execution
     */
    public NamedWindowProcessor(String namedWindowName, NamedWindowService namedWindowService, String contextName, boolean singleInstanceContext, EventType eventType, StatementResultService statementResultService, ValueAddEventProcessor revisionProcessor, String eplExpression, String statementName, boolean isPrioritized, boolean isEnableSubqueryIndexShare, boolean enableQueryPlanLog, MetricReportingService metricReportingService, boolean isBatchingDataWindow, boolean isVirtualDataWindow, StatementMetricHandle statementMetricHandle, Set<String> optionalUniqueKeyProps, String eventTypeAsName)
    {
        this.namedWindowName = namedWindowName;
        this.contextName = contextName;
        this.singleInstanceContext = singleInstanceContext;
        this.eventType = eventType;
        this.eplExpression = eplExpression;
        this.statementName = statementName;
        this.isEnableSubqueryIndexShare = isEnableSubqueryIndexShare;
        this.isVirtualDataWindow = isVirtualDataWindow;
        this.statementMetricHandle = statementMetricHandle;
        this.optionalUniqueKeyProps = optionalUniqueKeyProps;
        this.eventTypeAsName = eventTypeAsName;

        rootView = new NamedWindowRootView(revisionProcessor, enableQueryPlanLog, metricReportingService, eventType, isBatchingDataWindow, isEnableSubqueryIndexShare, optionalUniqueKeyProps);
        tailView = new NamedWindowTailView(eventType, namedWindowService, statementResultService, revisionProcessor, isPrioritized, isBatchingDataWindow);
    }

    public String getEventTypeAsName() {
        return eventTypeAsName;
    }

    public synchronized NamedWindowProcessorInstance addInstance(AgentInstanceContext agentInstanceContext) {

        if (contextName == null) {
            if (instanceNoContext != null) {
                throw new RuntimeException("Failed to allocated processor instance: already allocated and not released");
            }
            instanceNoContext = new NamedWindowProcessorInstance(null, this, agentInstanceContext);
            return instanceNoContext;
        }

        int instanceId = agentInstanceContext.getAgentInstanceId();
        NamedWindowProcessorInstance instance = new NamedWindowProcessorInstance(instanceId, this, agentInstanceContext);
        instances.put(instanceId, instance);
        return instance;
    }

    public synchronized void removeProcessorInstance(NamedWindowProcessorInstance instance) {
        if (contextName == null) {
            instanceNoContext = null;
            return;
        }
        instances.remove(instance.getAgentInstanceId());
    }

    public NamedWindowProcessorInstance getProcessorInstanceNoContext() {
        return instanceNoContext;
    }

    public synchronized Collection<Integer> getProcessorInstancesAll() {
        Set<Integer> keyset = instances.keySet();
        return new ArrayDeque<Integer>(keyset);
    }

    public NamedWindowProcessorInstance getProcessorInstance(int agentInstanceId) {
        return instances.get(agentInstanceId);
    }

    public long getProcessorRowCountDefaultInstance() {
        if (instanceNoContext != null) {
            return instanceNoContext.getCountDataWindow();
        }
        return -1;
    }

    public NamedWindowProcessorInstance getProcessorInstance(AgentInstanceContext agentInstanceContext) {
        if (contextName == null) {
            return instanceNoContext;
        }

        if (singleInstanceContext) {
            if (instances.isEmpty()) {
                return null;
            }
            return instances.values().iterator().next();
        }

        if (agentInstanceContext.getStatementContext().getContextDescriptor() == null) {
            return null;
        }
        
        if (this.contextName.equals(agentInstanceContext.getStatementContext().getContextDescriptor().getContextName())) {
            return instances.get(agentInstanceContext.getAgentInstanceId());
        }
        return null;
    }

    public String getContextName() {
        return contextName;
    }

    public NamedWindowConsumerView addConsumer(NamedWindowConsumerDesc consumerDesc, boolean isSubselect) {
        // handle same-context consumer
        if (this.contextName != null) {
            ContextDescriptor contextDescriptor = consumerDesc.getAgentInstanceContext().getStatementContext().getContextDescriptor();
            if (contextDescriptor != null && contextName.equals(contextDescriptor.getContextName())) {
                NamedWindowProcessorInstance instance = instances.get(consumerDesc.getAgentInstanceContext().getAgentInstanceId());
                return instance.getTailViewInstance().addConsumer(consumerDesc, isSubselect);
            }
            else {
                // consumer is out-of-context
                return tailView.addConsumer(consumerDesc);  // non-context consumers
            }
        }

        // handle no context associated
        return instanceNoContext.getTailViewInstance().addConsumer(consumerDesc, isSubselect);
    }

    public boolean isVirtualDataWindow() {
        return isVirtualDataWindow;
    }

    /**
     * Returns the tail view of the named window, hooked into the view chain after the named window's data window views,
     * as the last view.
     * @return tail view
     */
    public NamedWindowTailView getTailView()
    {
        return tailView;    // hooked as the tail sview before any data windows
    }

    /**
     * Returns the root view of the named window, hooked into the view chain before the named window's data window views,
     * right after the filter stream that filters for insert-into events.
     * @return tail view
     */
    public NamedWindowRootView getRootView()
    {
        return rootView;    // hooked as the top view before any data windows
    }

    /**
     * Returns the event type of the named window.
     * @return event type
     */
    public EventType getNamedWindowType()
    {
        return eventType;
    }

    /**
     * Returns the EPL expression.
     * @return epl
     */
    public String getEplExpression()
    {
        return eplExpression;
    }

    /**
     * Returns the statement name.
     * @return name
     */
    public String getStatementName()
    {
        return statementName;
    }

    /**
     * Deletes a named window and removes any associated resources.
     */
    public void destroy()
    {
    }

    public boolean isEnableSubqueryIndexShare() {
        return isEnableSubqueryIndexShare;
    }

    public StatementMetricHandle getCreateNamedWindowMetricsHandle() {
        return statementMetricHandle;
    }

    public String getNamedWindowName() {
        return namedWindowName;
    }

    public String[][] getUniqueIndexes(NamedWindowProcessorInstance processorInstance) {
        List<String[]> unique = null;
        if (processorInstance != null) {
            IndexMultiKey[] indexDescriptors = processorInstance.getIndexDescriptors();
            for (IndexMultiKey index : indexDescriptors) {
                if (!index.isUnique()) {
                    continue;
                }
                String[] uniqueKeys = IndexedPropDesc.getIndexProperties(index.getHashIndexedProps());
                if (unique == null) {
                    unique = new ArrayList<String[]>();
                }
                unique.add(uniqueKeys);
            }
        }
        if (optionalUniqueKeyProps != null) {
            if (unique == null) {
                unique = new ArrayList<String[]>();
            }
            unique.add(optionalUniqueKeyProps.toArray(new String[optionalUniqueKeyProps.size()]));
        }
        if (unique == null) {
            return null;
        }
        return unique.toArray(new String[unique.size()][]);
    }

    public Set<String> getOptionalUniqueKeyProps() {
        return optionalUniqueKeyProps;
    }

    public EventTableIndexMetadata getEventTableIndexMetadataRepo() {
        return eventTableIndexMetadataRepo;
    }

    public void removeAllInstanceIndexes(IndexMultiKey index) {
        if (instanceNoContext != null) {
            instanceNoContext.removeIndex(index);
        }
        if (instances != null) {
            for (Map.Entry<Integer, NamedWindowProcessorInstance> entry : instances.entrySet()) {
                entry.getValue().removeIndex(index);
            }
        }
    }

    public void validateAddIndex(String statementName, String indexName, IndexMultiKey imk) throws ExprValidationException {
        eventTableIndexMetadataRepo.addIndex(false, imk, indexName, statementName, true);
    }

    public void removeIndexReferencesStmtMayRemoveIndex(IndexMultiKey imk, String finalStatementName) {
        boolean last = eventTableIndexMetadataRepo.removeIndexReference(imk, finalStatementName);
        if (last) {
            eventTableIndexMetadataRepo.removeIndex(imk);
            removeAllInstanceIndexes(imk);
        }
    }
}