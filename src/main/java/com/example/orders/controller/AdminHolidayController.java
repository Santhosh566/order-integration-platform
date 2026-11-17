package com.example.orders.controller;

import com.example.orders.delivery.HolidayCalendar;
import com.example.orders.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Delivery holidays, which delivery estimates and bookable delivery days skip. Admins only. */
@RestController
@RequestMapping("/api/admin/holidays")
public class AdminHolidayController {

    private final HolidayCalendar calendar;
    private final CurrentUser currentUser;

    public AdminHolidayController(HolidayCalendar calendar, CurrentUser currentUser) {
        this.calendar = calendar;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<HolidayCalendar.Holiday> year(@RequestParam(required = false) Integer year) {
        return calendar.year(year == null ? LocalDate.now().getYear() : year);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayCalendar.Holiday add(@RequestBody HolidayCalendar.NewHoliday request) {
        return calendar.add(request, currentUser.require().userId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long id) {
        calendar.remove(id);
    }
}
