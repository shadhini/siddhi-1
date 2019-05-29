/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.core.util.cache;

import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventFactory;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.executor.VariableExpressionExecutor;
import io.siddhi.core.table.InMemoryTable;
import io.siddhi.core.table.Table;
import io.siddhi.core.table.record.AbstractQueryableRecordTable;
import io.siddhi.core.util.collection.operator.CompiledCondition;
import io.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import io.siddhi.core.util.parser.MatcherParser;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.TableDefinition;
import io.siddhi.query.api.expression.Expression;
import io.siddhi.query.api.expression.Variable;
import io.siddhi.query.api.expression.condition.Compare;
import io.siddhi.query.api.expression.constant.LongConstant;
import io.siddhi.query.api.expression.math.Subtract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.siddhi.core.util.cache.CacheUtils.findEventChunkSize;

/**
 * this class is a runnable which runs on a separate thread called by AbstractQueryableRecordTable and handles cache
 * expiry
 */
public class CacheExpiryHandlerRunnable {
    private InMemoryTable cacheTable;
    private StreamEventFactory streamEventFactory;
    private MatchingMetaInfoHolder matchingMetaInfoHolder;
    private Expression rightExpressionForSubtract;
    private Expression rightExpressionForCompare;
    private Compare.Operator greaterThanOperator;
    private List<VariableExpressionExecutor> variableExpressionExecutors;
    private Map<String, Table> tableMap;
    private AbstractQueryableRecordTable storeTable;
    private SiddhiAppContext siddhiAppContext;
    private long expiryTime;

    public CacheExpiryHandlerRunnable(long expiryTime, InMemoryTable cacheTable, Map<String, Table> tableMap,
                                      AbstractQueryableRecordTable storeTable, SiddhiAppContext siddhiAppContext) {
        this.cacheTable = cacheTable;
        this.tableMap = tableMap;
        this.storeTable = storeTable;
        this.siddhiAppContext = siddhiAppContext;
        this.expiryTime = expiryTime;

        MetaStreamEvent tableMetaStreamEvent = new MetaStreamEvent();
        tableMetaStreamEvent.setEventType(MetaStreamEvent.EventType.TABLE);
        TableDefinition matchingTableDefinition = TableDefinition.id("");
        for (Attribute attribute : cacheTable.getTableDefinition().getAttributeList()) {
            tableMetaStreamEvent.addOutputData(attribute);
            matchingTableDefinition.attribute(attribute.getName(), attribute.getType());
        }
        tableMetaStreamEvent.addInputDefinition(matchingTableDefinition);
        streamEventFactory = new StreamEventFactory(tableMetaStreamEvent);

        rightExpressionForSubtract = new Variable("timestamp");
        ((Variable) rightExpressionForSubtract).setStreamId(cacheTable.getTableDefinition().getId());
        rightExpressionForCompare = new LongConstant(expiryTime);
        greaterThanOperator = Compare.Operator.GREATER_THAN;

        matchingMetaInfoHolder =
                MatcherParser.constructMatchingMetaStateHolder(tableMetaStreamEvent, 0,
                        cacheTable.getTableDefinition(), 0);
        variableExpressionExecutors = new ArrayList<>();
    }

    private ComplexEventChunk<StateEvent> generateDeleteEventChunk() {
        ComplexEventChunk<StateEvent> deleteEventChunk = new ComplexEventChunk<>();
        StateEvent stateEvent = new StateEvent(2, 0);
        stateEvent.addEvent(0, streamEventFactory.newInstance());
        deleteEventChunk.add(stateEvent);
        return deleteEventChunk;
    }

    private CompiledCondition generateExpiryCompiledCondition(long currentTime) {
        Expression leftExpressionForSubtract = new LongConstant(currentTime);
        Expression leftExpressionForCompare = new Subtract(leftExpressionForSubtract, rightExpressionForSubtract);
        Expression deleteCondition = new Compare(leftExpressionForCompare, greaterThanOperator,
                rightExpressionForCompare);
        SiddhiQueryContext siddhiQueryContext = new SiddhiQueryContext(siddhiAppContext, "expiryDeleteQuery");
        return cacheTable.compileCondition(deleteCondition, matchingMetaInfoHolder, variableExpressionExecutors,
                tableMap, siddhiQueryContext);
    }

    public void deleteExpiredEvents() {
        StateEvent stateEventForCaching = new StateEvent(1, 0);
        StreamEvent loadedDataFromStore = null;

        if (storeTable.getStoreTableSize() != -1 &&
                storeTable.getStoreSizeLastCheckedTime() >
                        siddhiAppContext.getTimestampGenerator().currentTime() - 30000) {
            if (storeTable.getStoreTableSize() <= storeTable.maxCacheSize) {
                try {
                    loadedDataFromStore = storeTable.queryFromStore(stateEventForCaching,
                            storeTable.compiledConditionForCaching,
                            storeTable.compiledSelectionForCaching, storeTable.outputAttributesForCaching);
                } catch (ConnectionUnavailableException e) {

                }

                CompiledCondition cc = generateExpiryCompiledCondition(
                        siddhiAppContext.getTimestampGenerator().currentTime() + expiryTime + 100);
                cacheTable.delete(generateDeleteEventChunk(), cc);
                ComplexEventChunk<StreamEvent> addingEventChunkWithTimestamp = new ComplexEventChunk<>();

                while (true) {
                    assert loadedDataFromStore != null;
                    Object[] outputData = loadedDataFromStore.getOutputData();
                    Object[] outputDataWithTimeStamp = new Object[outputData.length + 1];
                    System.arraycopy(outputData, 0 , outputDataWithTimeStamp, 0, outputData.length);
                    outputDataWithTimeStamp[outputDataWithTimeStamp.length - 1] =
                            siddhiAppContext.getTimestampGenerator().currentTime();
                    StreamEvent eventWithTimeStamp = new StreamEvent(0, 0, outputDataWithTimeStamp.length);
                    eventWithTimeStamp.setOutputData(outputDataWithTimeStamp);
                    addingEventChunkWithTimestamp.add(eventWithTimeStamp);
                    if (loadedDataFromStore.getNext() == null) {
                        break;
                    }
                    loadedDataFromStore = loadedDataFromStore.getNext();
                }
                cacheTable.add(addingEventChunkWithTimestamp);
            } else {
                CompiledCondition cc = generateExpiryCompiledCondition(siddhiAppContext.getTimestampGenerator().
                        currentTime());
                cacheTable.delete(generateDeleteEventChunk(),
                        cc);
            }
        } else {
            try {
                loadedDataFromStore = storeTable.queryFromStore(stateEventForCaching,
                        storeTable.compiledConditionForCaching,
                        storeTable.compiledSelectionForCaching, storeTable.outputAttributesForCaching);
            } catch (ConnectionUnavailableException e) {

            }
            storeTable.setStoreTableSize(findEventChunkSize(loadedDataFromStore));
            storeTable.setStoreSizeLastCheckedTime(siddhiAppContext.getTimestampGenerator().currentTime());
            if (storeTable.getStoreTableSize() <= storeTable.maxCacheSize) {
                CompiledCondition cc = generateExpiryCompiledCondition(
                        siddhiAppContext.getTimestampGenerator().currentTime() + expiryTime + 100);
                cacheTable.delete(generateDeleteEventChunk(),
                        cc);
                ComplexEventChunk<StreamEvent> addingEventChunkWithTimestamp = new ComplexEventChunk<>();

                while (true) {
                    Object[] outputData = loadedDataFromStore.getOutputData();
                    Object[] outputDataWithTimeStamp = new Object[outputData.length + 1];
                    System.arraycopy(outputData, 0 , outputDataWithTimeStamp, 0, outputData.length);
                    outputDataWithTimeStamp[outputDataWithTimeStamp.length - 1] =
                            siddhiAppContext.getTimestampGenerator().currentTime();
                    StreamEvent eventWithTimeStamp = new StreamEvent(0, 0, outputDataWithTimeStamp.length);
                    eventWithTimeStamp.setOutputData(outputDataWithTimeStamp);
                    addingEventChunkWithTimestamp.add(eventWithTimeStamp);
                    if (loadedDataFromStore.getNext() == null) {
                        break;
                    }
                    loadedDataFromStore = loadedDataFromStore.getNext();
                }
                cacheTable.add(addingEventChunkWithTimestamp);
            } else {
                CompiledCondition cc = generateExpiryCompiledCondition(
                        siddhiAppContext.getTimestampGenerator().currentTime());
                cacheTable.delete(generateDeleteEventChunk(), cc);
            }
        }
    }

    public Runnable checkAndExpireCache(int cacheMode) {
        class CheckAndExpireCache implements Runnable {
//            int cacheMode;

            public CheckAndExpireCache(int cacheMode) {
//                this.cacheMode = cacheMode;
            }

            @Override
            public void run() {
                deleteExpiredEvents();
            }
        }
        return new CheckAndExpireCache(cacheMode);
    }
}
