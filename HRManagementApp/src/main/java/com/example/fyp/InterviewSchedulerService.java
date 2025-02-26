package com.example.fyp;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.TimeZone;

@Service
public class InterviewSchedulerService {

    private final Calendar calendar;

    public InterviewSchedulerService(Calendar calendar) {
        this.calendar = calendar;
    }

    public String scheduleInterview(String hrEmail, String candidateEmail, String jobTitle) throws IOException {
        Event event = new Event()
                .setSummary(jobTitle + " Interview")
                .setDescription("Automatically scheduled interview for " + jobTitle);

        // Schedule for 2 days from now at 10:00 AM in the HR's timezone
        Instant startTime = Instant.now().plus(2, ChronoUnit.DAYS)
                .atZone(TimeZone.getDefault().toZoneId())
                .withHour(10)
                .withMinute(0)
                .toInstant();

        EventDateTime start = new EventDateTime()
                .setDateTime(new DateTime(startTime.toEpochMilli()))
                .setTimeZone(TimeZone.getDefault().getID());
        event.setStart(start);

        EventDateTime end = new EventDateTime()
                .setDateTime(new DateTime(startTime.plus(1, ChronoUnit.HOURS).toEpochMilli()))
                .setTimeZone(TimeZone.getDefault().getID());
        event.setEnd(end);

        // Fix: Use Arrays.asList instead of Collections.singletonList
        event.setAttendees(Arrays.asList(
                new EventAttendee().setEmail(candidateEmail),
                new EventAttendee().setEmail(hrEmail)
        ));

        Event createdEvent = calendar.events()
                .insert("primary", event)
                .setSendNotifications(true)
                .execute();

        return createdEvent.getId();
    }
}