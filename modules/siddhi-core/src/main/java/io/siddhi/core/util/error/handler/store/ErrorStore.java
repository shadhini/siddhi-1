/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.core.util.error.handler.store;

import io.siddhi.core.util.error.handler.exception.ErrorStoreException;
import io.siddhi.core.util.error.handler.model.ErroneousEvent;
import io.siddhi.core.util.error.handler.model.ErrorEntry;
import io.siddhi.core.util.error.handler.util.ErroneousEventType;
import io.siddhi.core.util.error.handler.util.ErrorOccurrence;
import io.siddhi.core.util.error.handler.util.ErrorStoreUtils;
import io.siddhi.core.util.error.handler.util.ErrorType;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Denotes the interface for an error store in which, error event entries will be stored.
 */
public abstract class ErrorStore {
    private static final Logger log = Logger.getLogger(ErrorStore.class);

    public abstract void setProperties(Map properties);

    public void saveBeforeSourceMappingError(String siddhiAppName, List<ErroneousEvent> erroneousEvents,
                                             String streamName) {
        for (ErroneousEvent erroneousEvent : erroneousEvents) {
            try {
                save(siddhiAppName, streamName, erroneousEvent, ErroneousEventType.PAYLOAD_STRING,
                        ErrorOccurrence.BEFORE_SOURCE_MAPPING, ErrorType.MAPPING);
            } catch (ErrorStoreException e) {
                log.error("Failed to save erroneous event.", e);
            }
        }
    }

    public void saveOnSinkError(String siddhiAppName, ErroneousEvent erroneousEvent, ErroneousEventType eventType,
                                String streamName) {
        try {
            save(siddhiAppName, streamName, erroneousEvent, eventType, ErrorOccurrence.STORE_ON_SINK_ERROR,
                    ErrorType.TRANSPORT);
        } catch (ErrorStoreException e) {
            log.error("Failed to save erroneous event.", e);
        }
    }

    public void saveOnStreamError(String siddhiAppName, ErroneousEvent erroneousEvent, ErroneousEventType eventType,
                                  String streamName) {
        try {
            save(siddhiAppName, streamName, erroneousEvent, eventType, ErrorOccurrence.STORE_ON_STREAM_ERROR,
                    ErrorType.TRANSPORT);
        } catch (ErrorStoreException e) {
            log.error("Failed to save erroneous event.", e);
        }
    }

    protected void save(String siddhiAppName, String streamName, ErroneousEvent erroneousEvent,
                        ErroneousEventType eventType, ErrorOccurrence errorOccurrence, ErrorType errorType)
            throws ErrorStoreException {
        long timestamp = System.currentTimeMillis();
        Object event = erroneousEvent.getEvent();
        Throwable throwable = erroneousEvent.getThrowable();
        String cause;
        if (throwable != null) {
            if (throwable.getCause() != null) {
                cause = throwable.getCause().getMessage();
            } else {
                cause = throwable.getMessage();
            }
        } else {
            cause = "Unknown";
        }

        try {
            Object originalPayload = erroneousEvent.getOriginalPayload();
            byte[] eventAsBytes = (event != null && eventType == ErroneousEventType.PAYLOAD_STRING) ?
                    ErrorStoreUtils.getAsBytes(event.toString()) : ErrorStoreUtils.getAsBytes(event);
            byte[] stackTraceAsBytes = (throwable != null) ? ErrorStoreUtils.getThrowableStackTraceAsBytes(throwable) :
                    ErrorStoreUtils.getAsBytes(null);
            byte[] originalPayloadAsBytes = ErrorStoreUtils.getAsBytes(originalPayload);

            saveEntry(timestamp, siddhiAppName, streamName, eventAsBytes, cause, stackTraceAsBytes,
                    originalPayloadAsBytes, errorOccurrence.toString(), eventType.toString(), errorType.toString());
        } catch (IOException e) {
            throw new ErrorStoreException("Failure occurred during byte array conversion.", e);
        }
    }

    protected abstract void saveEntry(long timestamp, String siddhiAppName, String streamName,
                                      byte[] eventAsBytes, String cause, byte[] stackTraceAsBytes,
                                      byte[] originalPayloadAsBytes, String errorOccurrence, String eventType,
                                      String errorType) throws ErrorStoreException;

    public abstract List<ErrorEntry> loadErrorEntries(String siddhiAppName, Map<String, String> queryParams);

    protected ErrorEntry constructErrorEntry(int id, long timestamp, String siddhiAppName, String streamName,
                                             byte[] eventAsBytes, String cause, byte[] stackTraceAsBytes,
                                             byte[] originalPayloadAsBytes, ErrorOccurrence errorOccurrence,
                                             ErroneousEventType erroneousEventType, ErrorType errorType)
            throws IOException, ClassNotFoundException {
        String originalPayloadString =
                ErrorStoreUtils.getOriginalPayloadString(ErrorStoreUtils.getAsObject(originalPayloadAsBytes));
        Object eventObject = ErrorStoreUtils.getAsObject(eventAsBytes);
        return new ErrorEntry<>(id, timestamp, siddhiAppName, streamName, eventObject, cause,
                (String) ErrorStoreUtils.getAsObject(stackTraceAsBytes), originalPayloadString, errorOccurrence,
                erroneousEventType, errorType);
    }

    public abstract void discardErroneousEvent(int id);

    public abstract Map getStatus();
}
