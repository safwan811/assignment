package com.example.shortener.web;

import com.example.shortener.dto.CreateLinkRequest;
import com.example.shortener.dto.LinkResponse;
import com.example.shortener.dto.StatsResponse;
import com.example.shortener.service.AnalyticsService;
import com.example.shortener.service.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService links;
    private final AnalyticsService analytics;

    public LinkController(LinkService links, AnalyticsService analytics) {
        this.links = links;
        this.analytics = analytics;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
                                               HttpServletRequest httpRequest) {
        LinkResponse created = links.create(request, ClientIdentity.of(httpRequest));
        return ResponseEntity
                .created(URI.create(created.shortUrl()))
                .body(created);
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code) {
        return links.get(code);
    }

    @GetMapping("/{code}/stats")
    public StatsResponse stats(@PathVariable String code) {
        return analytics.statsFor(code);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable String code) {
        links.disable(code);
    }
}
