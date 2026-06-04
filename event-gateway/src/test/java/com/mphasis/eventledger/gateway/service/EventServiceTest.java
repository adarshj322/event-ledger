package com.mphasis.eventledger.gateway.service;

import com.mphasis.eventledger.gateway.exception.EventNotFoundException;
import com.mphasis.eventledger.gateway.model.Event;
import com.mphasis.eventledger.gateway.model.EventType;
import com.mphasis.eventledger.gateway.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EventService}.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @InjectMocks
    private EventService eventService;

    private Event sampleEvent;

    @BeforeEach
    void setUp() {
        sampleEvent = new Event(
                "evt-001",
                "acct-123",
                EventType.CREDIT,
                new BigDecimal("500.00"),
                "USD",
                Instant.parse("2026-05-15T10:00:00Z"),
                null
        );
    }

    // ─── Ingest Event Tests ────────────────────────────────────────

    @Test
    @DisplayName("ingestEvent — saves new event, forwards to Account Service, returns duplicate=false")
    void ingestEvent_newEvent_savedAndForwarded() {
        when(eventRepository.findById("evt-001")).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenReturn(sampleEvent);

        EventService.IngestResult result = eventService.ingestEvent(sampleEvent);

        assertFalse(result.duplicate());
        assertEquals("evt-001", result.event().getEventId());
        verify(eventRepository).save(sampleEvent);
        verify(accountServiceClient).applyTransaction(
                "acct-123", "evt-001", "CREDIT",
                new BigDecimal("500.00"), "USD",
                Instant.parse("2026-05-15T10:00:00Z")
        );
    }

    @Test
    @DisplayName("ingestEvent — returns existing event with duplicate=true, does NOT forward to Account Service")
    void ingestEvent_duplicateEvent_returnsExisting() {
        when(eventRepository.findById("evt-001")).thenReturn(Optional.of(sampleEvent));

        EventService.IngestResult result = eventService.ingestEvent(sampleEvent);

        assertTrue(result.duplicate());
        assertEquals("evt-001", result.event().getEventId());
        verify(eventRepository, never()).save(any());
        verify(accountServiceClient, never()).applyTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("ingestEvent — handles race condition via DataIntegrityViolationException")
    void ingestEvent_raceCondition_catchesConstraintViolation() {
        when(eventRepository.findById("evt-001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(sampleEvent));
        when(eventRepository.save(any(Event.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        EventService.IngestResult result = eventService.ingestEvent(sampleEvent);

        assertTrue(result.duplicate());
        assertEquals("evt-001", result.event().getEventId());
        verify(eventRepository, times(2)).findById("evt-001");
    }

    // ─── Get Event Tests ───────────────────────────────────────────

    @Test
    @DisplayName("getEvent — returns event when found")
    void getEvent_existingId_returnsEvent() {
        when(eventRepository.findById("evt-001")).thenReturn(Optional.of(sampleEvent));

        Event result = eventService.getEvent("evt-001");

        assertEquals("evt-001", result.getEventId());
        assertEquals("acct-123", result.getAccountId());
    }

    @Test
    @DisplayName("getEvent — throws EventNotFoundException when ID does not exist")
    void getEvent_nonExistingId_throwsEventNotFoundException() {
        when(eventRepository.findById("evt-999")).thenReturn(Optional.empty());

        EventNotFoundException ex = assertThrows(EventNotFoundException.class,
                () -> eventService.getEvent("evt-999"));

        assertTrue(ex.getMessage().contains("evt-999"));
    }

    // ─── Get Events For Account Tests ──────────────────────────────

    @Test
    @DisplayName("getEventsForAccount — returns paginated results from repository")
    void getEventsForAccount_returnsPagedEvents() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Event> expectedPage = new PageImpl<>(List.of(sampleEvent), pageable, 1);
        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-123", pageable))
                .thenReturn(expectedPage);

        Page<Event> result = eventService.getEventsForAccount("acct-123", pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("evt-001", result.getContent().get(0).getEventId());
    }

    @Test
    @DisplayName("getEventsForAccount — returns empty page for unknown account")
    void getEventsForAccount_unknownAccount_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Event> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-unknown", pageable))
                .thenReturn(emptyPage);

        Page<Event> result = eventService.getEventsForAccount("acct-unknown", pageable);

        assertEquals(0, result.getTotalElements());
        assertTrue(result.getContent().isEmpty());
    }
}
