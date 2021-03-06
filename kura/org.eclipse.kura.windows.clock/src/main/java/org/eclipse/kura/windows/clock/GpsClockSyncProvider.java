/*******************************************************************************
 * Copyright (c) 2011, 2016 Eurotech and/or its affiliates
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech
 *******************************************************************************/
package org.eclipse.kura.windows.clock;

import java.util.Date;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.position.PositionLockedEvent;
import org.eclipse.kura.position.PositionService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GpsClockSyncProvider implements ClockSyncProvider, EventHandler {

    private static final Logger s_logger = LoggerFactory.getLogger(GpsClockSyncProvider.class);

    private PositionService m_positionService;
    protected Map<String, Object> m_properties;
    protected ClockSyncListener m_listener;
    protected int m_refreshInterval;
    protected Date m_lastSync;
    protected boolean m_waitForLocked;
    protected ScheduledExecutorService m_scheduler;

    // ----------------------------------------------------------------
    //
    // Wait for GPS locked event if single clock update
    //
    // ----------------------------------------------------------------

    @Override
    public void handleEvent(Event event) {
        if (PositionLockedEvent.POSITION_LOCKED_EVENT_TOPIC.contains(event.getTopic())) {
            if (this.m_waitForLocked && this.m_refreshInterval == 0) {
                s_logger.info("Received Position Locked event");
                try {
                    synchClock();
                } catch (KuraException e) {
                    s_logger.error("Error Synchronizing Clock", e);
                }
            }
        }
    }

    public GpsClockSyncProvider() {
    }

    @Override
    public void init(Map<String, Object> properties, ClockSyncListener listener) throws KuraException {
        s_logger.debug("initiing the GPS clock sync provider");
        this.m_properties = properties;
        this.m_listener = listener;

        this.m_waitForLocked = false;

        this.m_refreshInterval = 0;
        if (this.m_properties.containsKey("clock.ntp.refresh-interval")) {
            this.m_refreshInterval = (Integer) this.m_properties.get("clock.ntp.refresh-interval");
        }

        try {
            // looking for a valid PositionService from SCR
            BundleContext bundleContext = FrameworkUtil.getBundle(this.getClass()).getBundleContext();
            ServiceReference<PositionService> scrServiceRef = bundleContext.getServiceReference(PositionService.class);
            this.m_positionService = bundleContext.getService(scrServiceRef);

            // install event listener for GPS locked event
            Dictionary props = new Hashtable<String, String>();
            String[] topic = { PositionLockedEvent.POSITION_LOCKED_EVENT_TOPIC };
            props.put(EventConstants.EVENT_TOPIC, topic);
            bundleContext.registerService(EventHandler.class.getName(), this, props);
        } catch (Exception e) {
            throw new KuraException(KuraErrorCode.INTERNAL_ERROR, "Failed to initialize the GpsClockSyncProvider", e);
        }

        s_logger.debug("done initiing the GPS clock sync provider");
    }

    @Override
    public void start() throws KuraException {

        if (this.m_refreshInterval < 0) {
            // Never do any update. So Nothing to do.
            s_logger.info("No clock update required");
        } else if (this.m_refreshInterval == 0) {
            // Perform a single clock update.
            s_logger.info("Perform single clock update.");
            try {
                synchClock();
            } catch (KuraException e) {
                s_logger.error("Error Synchronizing Clock", e);
            }
        } else {
            // Perform periodic clock updates.
            s_logger.info("Perform periodic clock updates every {} sec", this.m_refreshInterval);
            if (this.m_scheduler != null) {
                this.m_scheduler.shutdown();
                this.m_scheduler = null;
            }
            this.m_scheduler = Executors.newSingleThreadScheduledExecutor();
            this.m_scheduler.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    Thread.currentThread().setName("GpsClockSyncProvider");
                    try {
                        synchClock();
                    } catch (KuraException e) {
                        s_logger.error("Error Synchronizing Clock", e);
                    }
                }
            }, 0, this.m_refreshInterval, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() throws KuraException {
        if (this.m_scheduler != null) {
            this.m_scheduler.shutdown();
            this.m_scheduler = null;
        }
        this.m_positionService = null;
    }

    @Override
    public Date getLastSync() {
        return this.m_lastSync;
    }

    // ----------------------------------------------------------------
    //
    // The actual time sync method
    // The GPS can give time but not date
    //
    // ----------------------------------------------------------------

    protected void synchClock() throws KuraException {
        try {
            if (this.m_positionService != null) {
                if (this.m_positionService.isLocked()) {
                    String gpsTime = this.m_positionService.getNmeaTime();
                    String gpsDate = this.m_positionService.getNmeaDate();
                    // Execute a native Windows command to perform the set time and date.
                    if (!gpsDate.isEmpty() && !gpsTime.isEmpty()) {
                        String YY = gpsDate.substring(4, 6);
                        String MM = gpsDate.substring(2, 4);
                        String DD = gpsDate.substring(0, 2);

                        String hh = gpsTime.substring(0, 2);
                        String mm = gpsTime.substring(2, 4);
                        String ss = gpsTime.substring(4, 6);

                        WindowsSetSystemTime winTime = new WindowsSetSystemTime();
                        winTime.SetLocalTime(Integer.parseInt(YY), Integer.parseInt(MM), Integer.parseInt(DD),
                                Integer.parseInt(hh), Integer.parseInt(mm), Integer.parseInt(ss));

                        this.m_lastSync = new Date();
                        this.m_waitForLocked = false;
                        // Call update method with 0 offset to ensure the clock event gets fired and the HW clock
                        // is updated if desired.
                        this.m_listener.onClockUpdate(0);
                    }
                } else {
                    this.m_waitForLocked = true;
                }
            }
        } catch (Exception e) {
            throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
        } finally {
        }
    }
}
