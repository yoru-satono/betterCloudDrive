package com.betterclouddrive.service.inspection;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** A real UTC clock in production; controlled time is injectable for observation-window tests. */
@Component
public class InspectionClock {
    private final Clock clock;
    @Autowired public InspectionClock() { this(Clock.systemUTC()); }
    public InspectionClock(Clock clock) { this.clock = clock; }
    public Instant now() { return clock.instant(); }
}
